package dev.rsensors.android.source

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.rsensors.android.shizuku.DumpsysUserService
import dev.rsensors.android.shizuku.IDumpsysService
import rikka.shizuku.Shizuku

/**
 * [DumpsysRunner] and [FpsRunner] backed by one Shizuku UserService
 * (ANDROID.md §5, §9, §8 T14).
 *
 * Shizuku 13 no longer exposes a process API, so the work runs inside
 * [DumpsysUserService] — a process Shizuku starts as the shell UID — and this
 * runner only binds to it and calls [IDumpsysService.runDumpsys] /
 * [IDumpsysService.runFps]. Both calls share the binding: `dumpsys window`,
 * `--list` and `--latency` must run in the same shell process as the thermal
 * dump. Binding is lazy and self-healing: the first call after a granted
 * permission starts a bind and yields null for that poll, later polls use the
 * live service, and any failure clears the reference so the next poll binds
 * again. That is what makes killing and restarting the Shizuku server
 * recoverable without a restart (ANDROID.md §8 T8).
 *
 * Every Shizuku call is wrapped: the poller must never see a RemoteException, a
 * dead binder, a missing provider or a denied permission as a crash. All of
 * them mean "no dump / no FPS this poll", and the chain falls back to
 * battery/status while the temperature poll continues.
 *
 * Threading: [run] and [runFps] are called on the poller's worker thread while
 * the connection callbacks arrive on the main thread. The binder calls
 * themselves (`pingBinder`, `checkSelfPermission`, `bindUserService`) are posted
 * to the main looper, matching the official demo and avoiding any binder-context
 * assumption; the worker never blocks waiting for them. [service], [binding]
 * and [bindStartedAt] are therefore volatile. [close] runs on the worker.
 *
 * A bind that never connects is abandoned after [BIND_TIMEOUT_MS] so a stale
 * Shizuku registration cannot wedge the source forever; the next poll retries.
 *
 * @param context application context; its package name addresses the
 *   UserService component and the provider authority
 */
class ShizukuDumpsysRunner(context: Context) : DumpsysRunner, FpsRunner {
  private val appContext = context.applicationContext

  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Binding descriptor for the UserService.
   *
   * `daemon(false)` because the OS may reclaim an idle service between polls;
   * `version(3)` forces a fresh registration after the T14 AIDL added `runFps`,
   * so a stale v2 shell process cannot answer the new transaction;
   * `tag("dumpsys")` keeps that record addressable; `debuggable(false)` keeps
   * the shell-side process un-debuggable in release and debug builds alike.
   */
  private val args = Shizuku.UserServiceArgs(
    ComponentName(appContext.packageName, DumpsysUserService::class.java.name),
  )
    .daemon(false)
    .processNameSuffix("dumpsys")
    .tag(TAG_NAME)
    .debuggable(false)
    .version(3)

  /** Live binding, or null while unbound; written on both threads. */
  @Volatile private var service: IDumpsysService? = null

  /** True between a posted bind request and its connection callback. */
  @Volatile private var binding = false

  /** Uptime of the current bind request, or 0 when no bind is in flight. */
  @Volatile private var bindStartedAt = 0L

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      Log.i(TAG, "onServiceConnected binder=${binder != null}")
      service = binder?.let { IDumpsysService.Stub.asInterface(it) }
      binding = false
      bindStartedAt = 0L
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      Log.i(TAG, "onServiceDisconnected")
      service = null
      binding = false
      bindStartedAt = 0L
    }
  }

  override fun run(): String? {
    val current = service
    if (current == null) {
      Log.d(TAG, "run: no UserService (binding=$binding)")
      abandonTimedOutBind()
      tryBind()
      return null
    }
    return try {
      Log.d(TAG, "runDumpsys call")
      val dump = current.runDumpsys()
      Log.d(TAG, "runDumpsys returned ${dump.length} chars")
      dump
    } catch (e: Exception) {
      // Dead binder, remote death or a shell failure: drop the reference and
      // let the next poll rebind if Shizuku is still alive.
      Log.w(TAG, "runDumpsys failed", e)
      forgetService()
      null
    }
  }

  /**
   * Runs one FPS measurement through the same shell process as [run].
   *
   * @return the UserService's single OK/ERR line, or null on any Shizuku or
   *   binder failure; the temperature poll must survive a failed FPS call
   */
  override fun runFps(): String? {
    val current = service
    if (current == null) {
      Log.d(TAG, "runFps: no UserService (binding=$binding)")
      abandonTimedOutBind()
      tryBind()
      return null
    }
    return try {
      Log.d(TAG, "runFps call")
      val reply = current.runFps()
      Log.d(TAG, "runFps returned ${reply.length} chars")
      reply
    } catch (e: Exception) {
      Log.w(TAG, "runFps failed", e)
      forgetService()
      null
    }
  }

  /** Drops the live reference so the next call rebinds; worker-side only. */
  private fun forgetService() {
    service = null
    binding = false
    bindStartedAt = 0L
  }

  /**
   * Requests a bind when Shizuku is reachable and this app is allowed.
   *
   * The request is only posted here; the binder calls run on the main thread in
   * [bindOnMain]. [binding] is set before posting so overlapping polls cannot
   * queue duplicates. Silent by construction: not running, not granted, or a
   * throwing binder call all just leave the runner unbound for this poll.
   */
  private fun tryBind() {
    if (binding) return
    binding = true
    bindStartedAt = SystemClock.elapsedRealtime()
    if (!mainHandler.post(::bindOnMain)) {
      Log.w(TAG, "main looper rejected bind; will retry next poll")
      binding = false
      bindStartedAt = 0L
    }
  }

  /**
   * Main-thread half of [tryBind]: pings Shizuku, checks the grant and asks for
   * the UserService. Logs every outcome; nothing here may reach the poller.
   */
  private fun bindOnMain() {
    // A timeout may have cancelled this request while it sat in the queue.
    if (!binding) {
      Log.d(TAG, "bind request cancelled before it ran")
      return
    }

    val running = try {
      Shizuku.pingBinder()
    } catch (e: Exception) {
      Log.w(TAG, "pingBinder threw", e)
      false
    }
    Log.i(TAG, "pingBinder=$running")
    if (!running) {
      binding = false
      bindStartedAt = 0L
      return
    }

    val granted = try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
      Log.w(TAG, "checkSelfPermission threw", e)
      false
    }
    Log.i(TAG, "checkSelfPermission granted=$granted")
    if (!granted) {
      binding = false
      bindStartedAt = 0L
      return
    }

    try {
      Log.i(TAG, "bindUserService version=3 tag=$TAG_NAME")
      Shizuku.bindUserService(args, connection)
      bindStartedAt = SystemClock.elapsedRealtime()
      Log.i(TAG, "bindUserService requested")
    } catch (e: Exception) {
      Log.w(TAG, "bindUserService threw", e)
      binding = false
      bindStartedAt = 0L
    }
  }

  /** Clears a bind that stayed in flight past [BIND_TIMEOUT_MS]. */
  private fun abandonTimedOutBind() {
    if (!binding) return
    val started = bindStartedAt
    if (started == 0L) return
    val waited = SystemClock.elapsedRealtime() - started
    if (waited <= BIND_TIMEOUT_MS) return
    Log.w(TAG, "bind not connected after $waited ms; resetting for retry")
    binding = false
    bindStartedAt = 0L
  }

  /** Unbinds and forgets the service; safe to call when nothing is bound. */
  override fun close() {
    Log.i(TAG, "unbindUserService (was bound=${service != null})")
    try {
      Shizuku.unbindUserService(args, connection, true)
    } catch (e: Exception) {
      // Server already gone or never bound; nothing left to release.
      Log.w(TAG, "unbindUserService threw", e)
    }
    service = null
    binding = false
    bindStartedAt = 0L
  }

  companion object {
    private const val TAG = "Rsensors"

    /** Stable server-side tag for this UserService registration. */
    private const val TAG_NAME = "dumpsys"

    /**
     * How long a bind may stay pending before the runner treats it as lost.
     * Shizuku itself gives a starting record 30 s; 10 s is long enough for a
     * normal start and short enough that a dead registration does not hide the
     * fallback for long.
     */
    private const val BIND_TIMEOUT_MS = 10_000L
  }
}
