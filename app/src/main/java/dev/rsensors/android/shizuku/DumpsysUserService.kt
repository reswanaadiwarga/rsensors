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
 * ([dev.rsensors.android.core.parseMeminfoPss]).
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
 * that call. Errors are left to propagate to the caller as binder/IO failures,
 * which [dev.rsensors.android.source.ShizukuDumpsysRunner] converts to a null
 * dump so a shell hiccup cannot crash the overlay.
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
   * Returns the raw `dumpsys meminfo --oom` dump for the T17 process page.
   *
   * Uses the shared [runDumpsys] helper so process execution and the
   * read-to-EOF-before-wait ordering live in one place. The dump takes ~2 s on
   * the target, which is why the app requests it on page open and on Refresh
   * rather than from the 2 s poll.
   *
   * @return stdout verbatim; the pure parser trims and splits it
   * @throws java.io.IOException when the process cannot start or be read, or
   *   exits non-zero
   */
  override fun runMeminfo(): String {
    Log.i(TAG, "runMeminfo start")
    return try {
      val output = runDumpsys("meminfo", "--oom")
      Log.i(TAG, "runMeminfo end chars=${output.length}")
      output
    } catch (e: Exception) {
      Log.w(TAG, "runMeminfo failed", e)
      throw e
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

  /** One line, bounded, so the app-side reply parser never sees a stack trace. */
  private fun shortReason(error: Exception): String =
    (error.message ?: error.javaClass.simpleName)
      .lineSequence()
      .firstOrNull { it.isNotBlank() }
      ?.take(80)
      ?: error.javaClass.simpleName

  private companion object {
    private const val TAG = "Rsensors"

    /** Absolute because the UserService process has no useful PATH. */
    private const val DUMPSYS = "/system/bin/dumpsys"

    /** Minimum time between focus/layer re-resolves while nothing is fresh. */
    private const val RESOLVE_INTERVAL_MS = 4_000L
    private const val RESOLVE_INTERVAL_NANOS = RESOLVE_INTERVAL_MS * 1_000_000L

    /** Zero measurement used when no layer carries fresh frames. */
    private val IDLE = FpsMeasurement(fps = 0.0, frames = 0, spanMillis = 0L)
  }
}
