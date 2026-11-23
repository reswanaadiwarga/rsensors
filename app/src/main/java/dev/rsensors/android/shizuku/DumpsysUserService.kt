package dev.rsensors.android.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import dev.rsensors.android.core.FpsMeasurement
import dev.rsensors.android.core.FrameRow
import dev.rsensors.android.core.formatFpsReply
import dev.rsensors.android.core.measureFps
import dev.rsensors.android.core.parseCandidateLayers
import dev.rsensors.android.core.parseFocusedComponent
import dev.rsensors.android.core.parseLatencyRows
import dev.rsensors.android.core.parseMeminfoPss
import java.io.IOException

/**
 * Shell-side half of the tier-1 source: runs `dumpsys` as the shell UID
 * (ANDROID.md §5, §9, §8 T14, §8 T17).
 *
 * Shizuku starts this class with `app_process` inside a process owned by the
 * shell user, so it has no app context and no UI. [runDumpsys] spawns one
 * `dumpsys thermalservice` and returns its complete stdout for
 * [dev.rsensors.android.core.parseDumpsysThermal]. [runFps] resolves the focused
 * activity's SurfaceFlinger layer and measures its frame rate with
 * `dumpsys SurfaceFlinger --list/--latency`, returning the one-line reply the
 * app parses with [dev.rsensors.android.core.parseFpsReply]. [runMeminfo] runs
 * `dumpsys meminfo --oom` and returns its raw stdout for the T17 process page
 * ([dev.rsensors.android.core.parseMeminfoPss]); when that dump yields no PSS
 * rows it falls back to `ps -A -o PID,RSS,NAME` so the page still has data
 * (T19b-3); [stopPackage] runs `am force-stop <package>` for the T18 stop
 * action.
 *
 * `dumpsys` is invoked by absolute path because a UserService process does not
 * inherit the app's PATH; relying on a bare `dumpsys` was a silent
 * "cannot run program" on this device.
 *
 * Constructors: Shizuku reflectively instantiates this class and prefers the
 * `(Context)` form from the official demo; the no-arg form is kept for older
 * servers that fall back to it. Both must survive R8 because they are only
 * referenced by name from another process, hence `@Keep`.
 *
 * Threading: each call on the binder thread; the process object is confined to
 * that call. [runDumpsys] and [runMeminfo] leave errors to propagate as
 * binder/IO failures, which [dev.rsensors.android.source.ShizukuDumpsysRunner]
 * converts to a null dump so a shell hiccup cannot crash the overlay;
 * [stopPackage] catches its own so a failed stop is an `ERR` reply.
 */
@Keep
class DumpsysUserService : IDumpsysService.Stub {
  /** No-arg form for servers that do not look up the Context constructor. */
  @Keep
  constructor() : super() {
    Log.i(TAG, "constructor")
  }

  /**
   * Form preferred by Shizuku 13 (official demo contract).
   *
   * @param context package context created by the Shizuku server; logged only,
   *   because this service needs nothing from it
   */
  @Keep
  constructor(context: Context) : super() {
    Log.i(TAG, "constructor with Context: ${context.packageName}")
  }

  /** Shizuku's shutdown hook: a daemon UserService must exit on its own. */
  override fun destroy() {
    Log.i(TAG, "destroy")
    System.exit(0)
  }

  /**
   * Runs the dump and returns its stdout verbatim.
   *
   * @return full output of `dumpsys thermalservice`, already trimmed by the
   *   consumer rather than here so the parser sees the real line breaks
   * @throws java.io.IOException when the process cannot be started or read
   */
  override fun runDumpsys(): String {
    Log.i(TAG, "runDumpsys start")
    return try {
      val process = Runtime.getRuntime().exec(arrayOf(DUMPSYS, "thermalservice"))
      // Read to EOF before waiting: dumpsys writes more than a pipe buffer, so
      // a waitFor-first order would deadlock on a full pipe.
      val output = process.inputStream.bufferedReader().use { it.readText() }
      val exit = process.waitFor()
      Log.i(TAG, "runDumpsys end exit=$exit chars=${output.length}")
      output
    } catch (e: Exception) {
      Log.w(TAG, "runDumpsys failed", e)
      throw e
    }
  }

  /**
   * Measures the foreground app's frame rate for this poll.
   *
   * While the cached layer keeps producing fresh frames only its `--latency`
   * call runs (~90 ms). When the ring is stale the component and candidate
   * layers are re-resolved — `dumpsys window` plus `--list` — at most once
   * every [RESOLVE_INTERVAL_MS], so an idle foreground app cannot turn the
   * overlay into a dumpsys loop (ANDROID.md §8 T14).
   *
   * Freshness is judged against `System.nanoTime()`, the same CLOCK_MONOTONIC
   * domain as the latency rows; a stopped app's frozen ring is 0 fps, not its
   * last burst.
   *
   * @return exactly one `OK fps=… frames=… span_ms=… layer=…` line (or
   *   `OK fps=0 … layer=-` when nothing carries fresh frames); any shell
   *   failure becomes `ERR <short reason>` and never escapes as an exception
   */
  @Synchronized
  override fun runFps(): String {
    return try {
      val resolved = resolveAndMeasure()
      formatFpsReply(resolved.measurement, resolved.layer)
    } catch (e: Exception) {
      Log.w(TAG, "runFps failed", e)
      "ERR " + shortReason(e)
    }
  }

  /**
   * Returns one raw process-memory dump for the T17 process page.
   *
   * The preferred route is `dumpsys meminfo --oom`, which carries PSS and OOM
   * groups. A dump that parses to no PSS rows means this device/vendor cannot
   * produce that section, so the method falls back to
   * `ps -A -o PID,RSS,NAME`; the app's parser labels the resulting snapshot RSS
   * instead of pretending it is PSS (T19b-3). A meminfo failure also falls
   * through to `ps`.
   *
   * The dump takes ~2 s on the target, which is why the app requests it on page
   * open and on Refresh rather than from the 2 s poll.
   *
   * @return the meminfo or `ps` stdout verbatim; the pure parser trims and
   *   splits it
   * @throws java.io.IOException when neither route produces usable output
   */
  override fun runMeminfo(): String {
    Log.i(TAG, "runMeminfo start")
    try {
      val output = runDumpsys("meminfo", "--oom")
      if (parseMeminfoPss(output).isNotEmpty()) {
        Log.i(TAG, "runMeminfo end route=meminfo chars=${output.length}")
        return output
      }
      Log.i(TAG, "runMeminfo meminfo had no PSS rows chars=${output.length}; trying ps")
    } catch (e: Exception) {
      Log.w(TAG, "runMeminfo meminfo failed; trying ps", e)
    }

    try {
      val output = runPs()
      if (output.isNotEmpty()) {
        Log.i(TAG, "runMeminfo end route=ps chars=${output.length}")
        return output
      }
      throw IOException("ps returned no output")
    } catch (e: Exception) {
      Log.w(TAG, "runMeminfo ps failed", e)
      throw IOException("No process memory from meminfo or ps", e)
    }
  }

  /**
   * Stops every process of [packageName] with `am force-stop` in this shell
   * process (ANDROID.md §3, §9, §8 T18).
   *
   * The name is checked against [PACKAGE_NAME] and [MAX_PACKAGE_LENGTH] before
   * any process exists, and the command is passed as a three-element argument
   * array so no shell parses it. A failed stop is a reply, never an exception,
   * because the process page must survive a stop that Android refuses.
   *
   * @param packageName package to force-stop; rejected without running
   *   anything when it is empty, longer than 255 characters or not a
   *   dot-separated package name
   * @return `OK` when `am` exited 0 without printing anything, otherwise
   *   `ERR exit=<code>` plus the first non-blank output line (≤80 chars), or
   *   `ERR <short reason>` when validation or process execution failed
   */
  override fun stopPackage(packageName: String): String {
    Log.i(TAG, "stopPackage start pkg=$packageName")
    if (packageName.length !in 1..MAX_PACKAGE_LENGTH || !PACKAGE_NAME.matches(packageName)) {
      Log.w(TAG, "stopPackage rejected pkg=$packageName")
      return "ERR invalid package"
    }
    return try {
      val process =
        ProcessBuilder(AM, "force-stop", packageName)
          .redirectErrorStream(true)
          .start()
      // Read to EOF before waiting: a failing `am` can write more than a pipe
      // buffer, so waitFor-first would deadlock on a full pipe.
      val output = process.inputStream.bufferedReader().use { it.readText() }
      val exit = process.waitFor()
      val error = firstUsefulLine(output)
      val reply = if (exit == 0 && error == null) "OK" else errorLine(exit, error)
      Log.i(TAG, "stopPackage end pkg=$packageName exit=$exit reply=$reply")
      reply
    } catch (e: Exception) {
      Log.w(TAG, "stopPackage failed pkg=$packageName", e)
      "ERR " + shortReason(e)
    }
  }

  /** One measurement plus the layer it came from, or null when idle. */
  private data class Resolved(val measurement: FpsMeasurement, val layer: String?)

  /**
   * Cached layer and the last full resolve, guarded by the synchronized
   * [runFps]. The layer stays cached through idle polls so the 4 s re-resolve
   * gate, not the poll cadence, decides when `dumpsys window` runs again.
   */
  private var cachedLayer: String? = null
  private var lastResolveNanos: Long? = null

  private fun resolveAndMeasure(): Resolved {
    cachedLayer?.let { layer ->
      val rows = latencyRows(layer)
      if (rows != null) {
        val measured = measureFps(rows, System.nanoTime())
        if (measured.frames >= 2) return Resolved(measured, layer)
      }
    }

    val now = System.nanoTime()
    val last = lastResolveNanos
    if (last == null || now - last >= RESOLVE_INTERVAL_NANOS) {
      lastResolveNanos = now
      val component = parseFocusedComponent(runDumpsys("window"))
        ?: return Resolved(IDLE, null)
      val candidates = parseCandidateLayers(runDumpsys("SurfaceFlinger", "--list"), component)
      if (candidates.isEmpty()) {
        cachedLayer = null
        return Resolved(IDLE, null)
      }
      // Cache the first candidate even when it is idle right now; the next
      // poll retries it before spending another window/list pair.
      cachedLayer = candidates.first()
      for (candidate in candidates) {
        val rows = latencyRows(candidate) ?: continue
        val measured = measureFps(rows, System.nanoTime())
        if (measured.frames >= 2) {
          cachedLayer = candidate
          return Resolved(measured, candidate)
        }
      }
    }
    return Resolved(IDLE, null)
  }

  /**
   * `--latency` for [layer], or null when the process cannot run or the layer
   * disappeared; a vanished layer is "no frames", not a poll failure.
   */
  private fun latencyRows(layer: String): List<FrameRow>? =
    try {
      parseLatencyRows(runDumpsys("SurfaceFlinger", "--latency", layer))
    } catch (e: Exception) {
      Log.w(TAG, "latency failed for $layer", e)
      null
    }

  /**
   * Runs `dumpsys <args>` and returns stdout.
   *
   * Reads to EOF before waiting, like [runDumpsys]: `dumpsys window` writes far
   * more than a pipe buffer, so waiting first would deadlock.
   *
   * @throws IOException when the process cannot start or exits non-zero
   */
  private fun runDumpsys(vararg args: String): String {
    val command = ArrayList<String>(args.size + 1)
    command += DUMPSYS
    command += args
    val process = ProcessBuilder(command).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    if (exit != 0) throw IOException("dumpsys ${args.joinToString(" ")} exit=$exit")
    return output
  }

  /**
   * Runs `ps -A -o PID,RSS,NAME` and returns stdout (T19b-3).
   *
   * Uses the absolute [PS] path for the same PATH reason as [DUMPSYS], and an
   * argv array so nothing parses a shell string. Reads to EOF before waiting:
   * the process table can exceed a pipe buffer, so waitFor-first would deadlock
   * on a full pipe.
   *
   * @throws IOException when the process cannot start or exits non-zero
   */
  private fun runPs(): String {
    val process = ProcessBuilder(PS, "-A", "-o", "PID,RSS,NAME").start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    if (exit != 0) throw IOException("ps exit=$exit")
    return output
  }

  /** One line, bounded, so the app-side reply parser never sees a stack trace. */
  private fun shortReason(error: Exception): String =
    (error.message ?: error.javaClass.simpleName)
      .lineSequence()
      .firstOrNull { it.isNotBlank() }
      ?.take(80)
      ?: error.javaClass.simpleName

  /** First non-blank line of [text], trimmed to the 80-char reply budget. */
  private fun firstUsefulLine(text: String): String? =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)

  /** `ERR exit=<code>` with the first useful line appended when there is one. */
  private fun errorLine(exit: Int, line: String?): String =
    if (line == null) "ERR exit=$exit" else "ERR exit=$exit $line"

  private companion object {
    private const val TAG = "Rsensors"

    /** Absolute because the UserService process has no useful PATH. */
    private const val DUMPSYS = "/system/bin/dumpsys"

    /** Absolute for the same reason as [DUMPSYS]; runs the stop command. */
    private const val AM = "/system/bin/am"

    /** Absolute for the same reason as [DUMPSYS]; the RSS fallback route. */
    private const val PS = "/system/bin/ps"

    /** Longest package name Android accepts; anything longer is rejected. */
    private const val MAX_PACKAGE_LENGTH = 255

    /**
     * A package name safe to hand to a privileged process: dot-separated
     * segments of letters, digits and underscores. Deliberately stricter than
     * `PackageManager` (no hyphens) because this is the only input check on the
     * stop route.
     */
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")

    /** Minimum time between focus/layer re-resolves while nothing is fresh. */
    private const val RESOLVE_INTERVAL_MS = 4_000L
    private const val RESOLVE_INTERVAL_NANOS = RESOLVE_INTERVAL_MS * 1_000_000L

    /** Zero measurement used when no layer carries fresh frames. */
    private val IDLE = FpsMeasurement(fps = 0.0, frames = 0, spanMillis = 0L)
  }
}
