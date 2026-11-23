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
 * [DumpsysRunner], [FpsRunner], [MeminfoRunner] and [StopRunner] backed by one
 * Shizuku UserService (ANDROID.md §5, §9, §8 T14, §8 T17, §8 T18).
 *
 * Shizuku 13 no longer exposes a process API, so the work runs inside
 * [DumpsysUserService] — a process Shizuku starts as the shell UID — and this
 * runner only binds to it and calls [IDumpsysService.runDumpsys] /
 * [IDumpsysService.runFps] / [IDumpsysService.runMeminfo] /
 * [IDumpsysService.runProcessUids] / [IDumpsysService.stopPackage]. All calls
 * share the binding: `dumpsys window`, `--list`, `--latency`, the meminfo dump,
 * the `ps` UID table and `am force-stop` must run in the same shell process as
 * the thermal dump. Binding is lazy and
 * self-healing: the first call after a granted permission starts a bind and
 * yields null for that poll, later polls use the live service, and any failure
 * clears the reference so the next poll binds again. That is what makes killing
 * and restarting the Shizuku server recoverable without a restart
 * (ANDROID.md §8 T8).
 *
 * Every Shizuku call is wrapped: the poller and the process-page scan must never
 * see a RemoteException, a dead binder, a missing provider or a denied
 * permission as a crash. All of them mean "no dump / no FPS / no meminfo / no
 * stop reply this call", and the chain falls back to battery/status while the
 * temperature poll continues.
 *
 * Threading: [run] and [runFps] are called on the poller's worker thread and
 * [runMeminfo], [runProcessUids] and [stopPackage] on the process page's own
 * worker thread, while
 * the connection callbacks arrive on the main thread. The binder calls
 * themselves (`pingBinder`, `checkSelfPermission`, `bindUserService`) are
 * posted to the main looper, matching the official demo and avoiding any
 * binder-context assumption; the workers never block waiting for them.
 * [service] stays volatile so calls can read the live reference without taking
 * the lock, while every bind-state transition ([binding], [bindStartedAt]) is
 * guarded by [bindLock] so two callers cannot post duplicate bind requests.
 * [close] runs on the poller's worker.
 *
 * A bind that never connects is abandoned after [BIND_TIMEOUT_MS] so a stale
 * Shizuku registration cannot wedge the source forever; the next poll retries.
 * A connection callback that arrives after a bind was abandoned, forgotten or
 * already handled is ignored rather than resurrecting a service the runner
 * gave up on (T17 verifier finding).
 *
 * @param context application context; its package name addresses the
 *   UserService component and the provider authority
 */
class ShizukuDumpsysRunner(context: Context) :
  DumpsysRunner, FpsRunner, MeminfoRunner, StopRunner {
  private val appContext = context.applicationContext

  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Binding descriptor for the UserService.
   *
   * `daemon(false)` because the OS may reclaim an idle service between polls;
   * `version(6)` forces a fresh registration after the T19c-3a AIDL added
   * `runProcessUids`, so a stale v5 shell process cannot answer the new
   * transaction;
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
    .version(6)

  /** Live binding, or null while unbound; written on both threads. */
  @Volatile private var service: IDumpsysService? = null

  /**
   * Guards the bind-state transitions among the poller worker, the process-page
   * scan thread and the main-thread callbacks. Never held across a Shizuku
   * binder call; [service] stays readable without it.
   */
  private val bindLock = Any()

  /** True between a posted bind request and its connection callback. */
  @Volatile private var binding = false

  /** Uptime of the current bind request, or 0 when no bind is in flight. */
  @Volatile private var bindStartedAt = 0L

  /**
   * Id of the newest bind request, guarded by [bindLock]. A queued or running
   * [bindOnMain] carries the id it was posted with, so a request abandoned by a
   * timeout or cleared by a failure cannot bind on behalf of a newer one.
   */
  private var bindGeneration = 0L

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      Log.i(TAG, "onServiceConnected binder=${binder != null}")
      val bound = binder?.let { IDumpsysService.Stub.asInterface(it) }
      synchronized(bindLock) {
        // A callback that arrives after close(), a failure or a bind timeout
        // must not resurrect a service the runner already gave up on.
        if (!binding) {
          Log.i(TAG, "late onServiceConnected ignored")
          return
        }
        service = bound
        binding = false
        bindStartedAt = 0L
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      Log.i(TAG, "onServiceDisconnected")
      synchronized(bindLock) {
        service = null
        // The disconnect belongs to the old service; a bind started after it is
        // still pending and keeps its flag and timestamp.
        if (!binding) {
          bindStartedAt = 0L
        }
      }
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

  /**
   * Runs one memory dump through the same shell process as [run].
   *
   * Called from the process page's scan thread, not the 2 s poll, so it may
   * take ~2 s.
   *
   * @return the raw `dumpsys meminfo --oom` stdout or, when that has no PSS
   *   rows or fails, the `ps -A -o PID,RSS,NAME` fallback; null on any Shizuku
   *   or binder failure. The temperature poll must survive a failed scan.
   */
  override fun runMeminfo(): String? {
    val current = service
    if (current == null) {
      Log.d(TAG, "runMeminfo: no UserService (binding=$binding)")
      abandonTimedOutBind()
      tryBind()
      return null
    }
    return try {
      Log.d(TAG, "runMeminfo call")
      val dump = current.runMeminfo()
      Log.d(TAG, "runMeminfo returned ${dump.length} chars")
      dump
    } catch (e: Exception) {
      Log.w(TAG, "runMeminfo failed", e)
      forgetService()
      null
    }
  }

  /**
   * Runs one `ps -A -o PID,UID,NAME` read through the same shell process as
   * [run] (T19c-3a).
   *
   * Called on the process page's scan thread after [runMeminfo]; the page
   * parses the table and attaches UIDs to the snapshot rows. A missing or
   * failed service reports null like the other routes, so the scan keeps its
   * null UIDs and continues.
   *
   * @return the raw `ps` UID table stdout, or null on any Shizuku or binder
   *   failure; the process page must survive a failed UID lookup
   */
  override fun runProcessUids(): String? {
    val current = service
    if (current == null) {
      Log.d(TAG, "runProcessUids: no UserService (binding=$binding)")
      abandonTimedOutBind()
      tryBind()
      return null
    }
    return try {
      Log.d(TAG, "runProcessUids call")
      val dump = current.runProcessUids()
      Log.d(TAG, "runProcessUids returned ${dump.length} chars")
      dump
    } catch (e: Exception) {
      Log.w(TAG, "runProcessUids failed", e)
      forgetService()
      null
    }
  }

  /**
   * Stops [packageName] through the same shell process as [run] (ANDROID.md §3,
   * §9, §8 T18).
   *
   * Called from the process page's stop action, not the 2 s poll. A missing
   * service reports null like the scan route; the page keeps its selection and
   * shows the failure instead of crashing.
   *
   * @return the UserService's `OK` / `ERR …` reply, or null on any Shizuku or
   *   binder failure; the UserService never throws for a bad package or a
   *   failed stop
   */
  override fun stopPackage(packageName: String): String? {
    val current = service
    if (current == null) {
      Log.d(TAG, "stopPackage: no UserService (binding=$binding)")
      abandonTimedOutBind()
      tryBind()
      return null
    }
    return try {
      Log.d(TAG, "stopPackage call pkg=$packageName")
      val reply = current.stopPackage(packageName)
      Log.d(TAG, "stopPackage returned $reply")
      reply
    } catch (e: Exception) {
      Log.w(TAG, "stopPackage failed", e)
      forgetService()
      null
    }
  }

  /** Drops the live reference so the next call rebinds. */
  private fun forgetService() {
    synchronized(bindLock) {
      service = null
      binding = false
      bindStartedAt = 0L
      bindGeneration += 1
    }
  }

  /**
   * Requests a bind when Shizuku is reachable and this app is allowed.
   *
   * The request is only posted here; the binder calls run on the main thread in
   * [bindOnMain]. [binding] is set under [bindLock] before posting so the
   * poller and the scan thread cannot queue duplicates. Silent by
   * construction: not running, not granted, or a throwing binder call all just
   * leave the runner unbound for this call.
   */
  private fun tryBind() {
    val generation =
      synchronized(bindLock) {
        if (binding) return
        binding = true
        bindStartedAt = SystemClock.elapsedRealtime()
        bindGeneration += 1
        bindGeneration
      }
    if (!mainHandler.post { bindOnMain(generation) }) {
      Log.w(TAG, "main looper rejected bind; will retry next poll")
      cancelBind(generation)
    }
  }

  /**
   * Main-thread half of [tryBind]: pings Shizuku, checks the grant and asks for
   * the UserService. Logs every outcome; nothing here may reach the poller.
   *
   * @param generation id captured by [tryBind]; anything else means this
   *   request was abandoned while it waited in the queue
   *
   * The binder calls run outside [bindLock] so a slow Shizuku cannot stall the
   * scan thread; only the flag transitions are synchronized.
   */
  private fun bindOnMain(generation: Long) {
    // A timeout may have cancelled this request while it sat in the queue.
    val requested = synchronized(bindLock) { binding && bindGeneration == generation }
    if (!requested) {
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
      cancelBind(generation)
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
      cancelBind(generation)
      return
    }

    // Another caller may have forgotten the service while the checks ran; a
    // cancelled request must not start a bind the new owner does not expect.
    val current = synchronized(bindLock) { binding && bindGeneration == generation }
    if (!current) {
      Log.d(TAG, "bind request cancelled before bindUserService")
      return
    }

    try {
      Log.i(TAG, "bindUserService version=6 tag=$TAG_NAME")
      Shizuku.bindUserService(args, connection)
      synchronized(bindLock) {
        // The connection may already have fired while bindUserService ran; do
        // not resurrect a cleared request with a timestamp a timeout would
        // count.
        if (binding && bindGeneration == generation) {
          bindStartedAt = SystemClock.elapsedRealtime()
        }
      }
      Log.i(TAG, "bindUserService requested")
    } catch (e: Exception) {
      Log.w(TAG, "bindUserService threw", e)
      cancelBind(generation)
    }
  }

  /** Cancels [generation]'s pending request, unless a newer one replaced it. */
  private fun cancelBind(generation: Long) {
    synchronized(bindLock) {
      if (bindGeneration != generation) return
      binding = false
      bindStartedAt = 0L
    }
  }

  /** Clears a bind that stayed in flight past [BIND_TIMEOUT_MS]. */
  private fun abandonTimedOutBind() {
    synchronized(bindLock) {
      if (!binding) return
      val started = bindStartedAt
      if (started == 0L) return
      val waited = SystemClock.elapsedRealtime() - started
      if (waited <= BIND_TIMEOUT_MS) return
      Log.w(TAG, "bind not connected after $waited ms; resetting for retry")
      binding = false
      bindStartedAt = 0L
      // Invalidate the abandoned request so it cannot bind for the retry.
      bindGeneration += 1
    }
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
    forgetService()
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
