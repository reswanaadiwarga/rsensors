package dev.rsensors.android.core

import java.util.Locale

/**
 * Shell-side FPS parsing and estimation (ANDROID.md §3, §9, §8 T14).
 *
 * Pure Kotlin so the focus → layer → latency chain and the fresh-window
 * arithmetic are unit-testable on the JVM. The UserService in
 * `shizuku/DumpsysUserService.kt` only runs the processes and calls these
 * helpers; the app only parses the one-line reply with [parseFpsReply].
 *
 * The captured probe (`input/t14-fps-probe.txt`) establishes the shapes:
 *
 * ```
 * mFocusedApp=ActivityRecord{230081998 u0 com.android.settings/.MiuiSettings t5643}
 * RequestedLayerState{com.android.settings/com.android.settings.MiuiSettings#5643 parentId=129}
 * RequestedLayerState{89e2605 SurfaceView[<pkg>/<activity>](BLAST)#5734 parentId=129}
 * 16666667
 * 33002540928274 33002540928274 33002540928274
 * ```
 *
 * A layer's ring is not cleared when drawing stops, so freshness must be judged
 * against a caller-supplied `System.nanoTime()`: an idle app reports 0, never
 * the last burst's rate.
 */

/** Frames older than this are not part of the current estimate. */
const val FPS_FRESH_WINDOW_MS = 1_500L

/** Upper clamp for a plausible foreground frame rate. */
private const val FPS_MAX = 500.0

private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0

/** A row of `--latency` output, all CLOCK_MONOTONIC nanoseconds. */
data class FrameRow(
  val desiredPresent: Long,
  val actualPresent: Long,
  val frameReady: Long,
)

/**
 * One FPS estimate for one poll.
 *
 * @property fps frames per second, 0 when fewer than two fresh frames exist
 * @property frames number of fresh frames behind [fps]
 * @property spanMillis time between the oldest and newest fresh frame, 0 when
 *   fewer than two are fresh
 */
data class FpsMeasurement(
  val fps: Double,
  val frames: Int,
  val spanMillis: Long,
)

/**
 * Reads the focused component from a `dumpsys window` dump.
 *
 * `mFocusedApp` wins; `mCurrentFocus` is the fallback. A leading `.` in the
 * class is expanded against the package, because the layer names use the fully
 * qualified form (`com.android.settings/.MiuiSettings` becomes
 * `com.android.settings/com.android.settings.MiuiSettings`).
 *
 * @param windowDump full `dumpsys window` stdout
 * @return `<pkg>/<class>` or null when neither focus line is present
 */
fun parseFocusedComponent(windowDump: String): String? {
  val match = FOCUSED_APP.find(windowDump) ?: CURRENT_FOCUS.find(windowDump) ?: return null
  val pkg = match.groupValues[1]
  val cls = match.groupValues[2]
  return if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"
}

/**
 * Layer names that can carry the focused component's frames.
 *
 * The plain `<component>#<id>` layer draws normal activities; a game draws on a
 * hash-prefixed `SurfaceView[<component>](BLAST)#<id>` layer. Every other
 * `RequestedLayerState` name is a container that returns no frame rows:
 * `ActivityRecord…`, `ActivityRecordInputSink…`, `Background for …`,
 * `Bounds for …`, hash-prefixed containers, and the non-BLAST SurfaceView
 * container.
 *
 * A game can update both its HWUI activity layer and its SurfaceView render
 * layer. The BLAST layer therefore comes first: the resolver takes the first
 * candidate with two fresh frames, so the reported rate is the render rate,
 * and the activity layer is used only when no SurfaceView is fresh
 * (ANDROID.md §8 T14).
 *
 * @param listOutput full `dumpsys SurfaceFlinger --list` stdout
 * @param component `<pkg>/<class>` from [parseFocusedComponent]
 * @return candidates with the BLAST SurfaceView layer first, then the plain
 *   activity layer; empty when the component has no such layer
 */
fun parseCandidateLayers(listOutput: String, component: String): List<String> {
  val plain = mutableListOf<String>()
  val blast = mutableListOf<String>()
  listOutput.lineSequence().forEach { line ->
    // Sliced rather than regex-matched: layer names contain braces and spaces,
    // and Android's ICU regex rejects an unescaped `}`.
    val trimmed = line.trim()
    if (!trimmed.startsWith(LAYER_PREFIX) || !trimmed.endsWith("}")) return@forEach
    val body = trimmed.substring(LAYER_PREFIX.length, trimmed.length - 1)
    val name = layerName(body) ?: return@forEach
    if (NON_FRAME_PREFIXES.any { name.startsWith(it) }) return@forEach
    when {
      isPlainLayer(name, component) -> plain += name
      isBlastSurfaceView(name, component) -> blast += name
    }
  }
  return blast + plain
}

/**
 * Parses `dumpsys SurfaceFlinger --latency` output.
 *
 * Line 1 is the refresh period and is discarded. Each later row is `desired
 * actual ready` in nanoseconds, tab-separated on the target phone (spaces are
 * accepted too); a row is a real frame only when all three values are in
 * `(0, 1e18)`, which drops the ring's `0 0 0` empty slots and its `INT64_MAX`
 * sentinels.
 */
fun parseLatencyRows(latencyOutput: String): List<FrameRow> {
  val rows = ArrayList<FrameRow>()
  latencyOutput.lineSequence().drop(1).forEach { line ->
    val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
    if (parts.size < 3) return@forEach
    val desired = parts[0].toLongOrNull() ?: return@forEach
    val actual = parts[1].toLongOrNull() ?: return@forEach
    val ready = parts[2].toLongOrNull() ?: return@forEach
    if (isFrameTimestamp(desired) && isFrameTimestamp(actual) && isFrameTimestamp(ready)) {
      rows += FrameRow(desired, actual, ready)
    }
  }
  return rows
}

/**
 * Rows whose actual-present timestamp falls inside the fresh window ending at
 * [nowNanos]. A timestamp in the future is rejected: it cannot be a frame this
 * poll has seen.
 */
fun freshRows(
  rows: List<FrameRow>,
  nowNanos: Long,
  windowNanos: Long = FPS_FRESH_WINDOW_MS * NANOS_PER_MILLI,
): List<FrameRow> = rows.filter { row ->
  val age = nowNanos - row.actualPresent
  age in 0..windowNanos
}

/**
 * FPS over the fresh window.
 *
 * Fewer than two fresh frames (a frozen ring, a stopped app, a single stalled
 * frame) is 0. Otherwise the spacing between the oldest and newest fresh frame
 * gives `(frames - 1) / spanSeconds`, clamped to [FPS_MAX].
 *
 * @param nowNanos reference clock, the same `System.nanoTime()` domain as the
 *   latency rows
 */
fun measureFps(rows: List<FrameRow>, nowNanos: Long): FpsMeasurement {
  val fresh = freshRows(rows, nowNanos)
  if (fresh.size < 2) return FpsMeasurement(fps = 0.0, frames = fresh.size, spanMillis = 0L)

  val oldest = fresh.minOf { it.actualPresent }
  val newest = fresh.maxOf { it.actualPresent }
  val spanNanos = newest - oldest
  if (spanNanos <= 0L) return FpsMeasurement(fps = 0.0, frames = fresh.size, spanMillis = 0L)

  val fps = ((fresh.size - 1) * NANOS_PER_SECOND / spanNanos).coerceIn(0.0, FPS_MAX)
  return FpsMeasurement(fps = fps, frames = fresh.size, spanMillis = spanNanos / NANOS_PER_MILLI)
}

/**
 * Reads the FPS double out of the UserService reply line.
 *
 * @return the value from `OK fps=<double> …`, or null for `ERR …`, a missing
 *   `fps=` token, a malformed number or a negative value
 */
fun parseFpsReply(reply: String): Double? {
  val line = reply.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
  if (!line.startsWith("OK ")) return null
  val token = line.split(' ').firstOrNull { it.startsWith("fps=") } ?: return null
  val value = token.removePrefix("fps=").toDoubleOrNull() ?: return null
  return value.takeIf { it.isFinite() && it >= 0.0 }
}

/**
 * Formats the single reply line the app parses.
 *
 * @param measurement result for this poll
 * @param layer layer the measurement came from, or null when nothing carried
 *   frames; a zero measurement always reports `layer=-`
 */
fun formatFpsReply(measurement: FpsMeasurement, layer: String?): String {
  val frames = "frames=${measurement.frames} span_ms=${measurement.spanMillis}"
  if (measurement.fps <= 0.0) return "OK fps=0 $frames layer=-"
  val fps = String.format(Locale.US, "%.1f", measurement.fps)
  return "OK fps=$fps $frames layer=${layer ?: "-"}"
}

/** `mFocusedApp=ActivityRecord{<hash> u0 <pkg>/<class> t<n>}`. */
private val FOCUSED_APP =
  Regex("""mFocusedApp=ActivityRecord\{[^}]*?\bu\d+\s+([^\s/{}]+)/([^\s{}]+)""")

/** `mCurrentFocus=Window{<hash> u0 <pkg>/<class>}`. */
private val CURRENT_FOCUS =
  Regex("""mCurrentFocus=Window\{[^}]*?\bu\d+\s+([^\s/{}]+)/([^\s{}]+)""")

/** Prefix of one `RequestedLayerState{…}` line; the name may contain braces. */
private const val LAYER_PREFIX = "RequestedLayerState{"

/** Container name prefixes whose layers never carry frame timestamps. */
private val NON_FRAME_PREFIXES = listOf(
  "ActivityRecord",
  "ActivityRecordInputSink",
  "Background for ",
  "Bounds for ",
)

/**
 * Extracts the layer name from the braces' content.
 *
 * Attributes follow the name as `key=value` tokens, so the name is every leading
 * token without an `=`. This keeps `SurfaceView[...](BLAST)#5734` with its
 * hash prefix intact while dropping `parentId=…` and friends.
 */
private fun layerName(body: String): String? {
  val tokens = body.trim().split(' ').filter { it.isNotEmpty() }
  val name = tokens.takeWhile { '=' !in it }.joinToString(" ")
  return name.ifEmpty { null }
}

/** True for the exact `<component>#<digits>` layer. */
private fun isPlainLayer(name: String, component: String): Boolean {
  val prefix = "$component#"
  if (!name.startsWith(prefix)) return false
  val tail = name.substring(prefix.length)
  return tail.isNotEmpty() && tail.all { it.isDigit() }
}

/** True for `SurfaceView[<component>](BLAST)#<digits>`, with or without a hash. */
private fun isBlastSurfaceView(name: String, component: String): Boolean {
  val marker = "SurfaceView[$component](BLAST)#"
  val index = name.indexOf(marker)
  if (index < 0) return false
  val tail = name.substring(index + marker.length)
  if (tail.isEmpty() || !tail.all { it.isDigit() }) return false
  if (index == 0) return true
  val prefix = name.substring(0, index)
  return prefix.endsWith(' ') &&
    prefix.dropLast(1).isNotEmpty() &&
    prefix.dropLast(1).all { it in '0'..'9' || it in 'a'..'f' }
}

private fun isFrameTimestamp(value: Long): Boolean =
  value > 0L && value < 1_000_000_000_000_000_000L
