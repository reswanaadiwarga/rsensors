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
 * Android 8–14 print bare layer names instead of the Android 15+
 * `RequestedLayerState{…}` wrapper, and older SurfaceFlinger versions call a
 * game's render target `SurfaceView - <component>#<id>`. Both shapes are
 * accepted; see docs/UNIVERSALITY.md for the portability matrix.
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
 * `mFocusedApp` wins; `mCurrentFocus` is the fallback. Older Android versions
 * wrap the focus in `AppWindowToken{…}` or print the record without the typed
 * wrapper, so a generic `mFocusedApp=… u<id> <pkg>/<class> …` shape is accepted
 * too; it stays anchored to `mFocusedApp=` so unrelated `mFocusedWindow=` and
 * `mCurrentFocus=` lines never match. A leading `.` in the class is expanded
 * against the package, because the layer names use the fully qualified form
 * (`com.android.settings/.MiuiSettings` becomes
 * `com.android.settings/com.android.settings.MiuiSettings`).
 *
 * @param windowDump full `dumpsys window` stdout
 * @return `<pkg>/<class>` or null when neither focus line is present
 */
fun parseFocusedComponent(windowDump: String): String? {
  val match = FOCUSED_APP.find(windowDump)
    ?: FOCUSED_APP_GENERIC.find(windowDump)
    ?: CURRENT_FOCUS.find(windowDump)
    ?: return null
  val pkg = match.groupValues[1]
  val cls = match.groupValues[2]
  return if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"
}

/**
 * Layer names that can carry the focused component's frames.
 *
 * Accepts both `--list` formats: the Android 15+ `RequestedLayerState{<name>
 * …}` wrapper and the bare name lines Android 8–14 print. A name may carry a
 * layer-id prefix (`<hex> <name>`); the prefix is ignored for matching but kept
 * in the returned name because `--latency` needs the exact layer name.
 *
 * Frame-carrying shapes, in preference order:
 * 1. `SurfaceView[<component>](BLAST)#<id>`, plus the `@0(BLAST)`/`(BLAST)@0`
 *    spellings some devices print. This is a game's render target.
 * 2. `SurfaceView - <component>#<id>`, the pre-BLAST render target.
 * 3. `<component>#<id>`, the activity's own HWUI layer.
 *
 * Exact names come before their hash-prefixed twins inside each group. A
 * hash-prefixed plain name is only a fallback: Android 16 prints the
 * component's plain layer twice, and the T14 probe (`t14-fps-probe.txt` in
 * `Rsensors-evidence`) shows the hash-prefixed twin is a container with
 * `valid=0` while the exact twin carries the frames. So when an exact plain
 * layer exists, hash-prefixed plain layers are dropped; when none exists they
 * are kept for Android 17, where all names gain the prefix. BLAST and legacy
 * SurfaceView layers keep their hash prefix either way because that *is* the
 * game's render layer.
 *
 * The resolver takes the first candidate with two fresh frames, so the render
 * layer must precede the activity layer: a game whose UI also draws must
 * report the render rate (ANDROID.md §8 T14). Other `--list` entries are
 * containers that return no frame rows: `ActivityRecord…`,
 * `ActivityRecordInputSink…`, `Background for …`, `Bounds for …` and the
 * non-BLAST `SurfaceView[…]` container.
 *
 * @param listOutput full `dumpsys SurfaceFlinger --list` stdout in either format
 * @param component `<pkg>/<class>` from [parseFocusedComponent]
 * @return distinct candidates ordered BLAST, legacy SurfaceView, exact plain,
 *   then hash-prefixed plain only when no exact plain exists; exact names
 *   before hash-prefixed ones inside the BLAST/legacy groups
 */
fun parseCandidateLayers(listOutput: String, component: String): List<String> {
  // Two buckets per kind: exact first, then hash-prefixed.
  val buckets = Array(LayerKind.values().size * 2) { mutableListOf<String>() }
  listOutput.lineSequence().forEach { line ->
    val name = layerNameFromLine(line) ?: return@forEach
    val layer = splitHashPrefix(name)
    if (NON_FRAME_PREFIXES.any { layer.displayName.startsWith(it) }) return@forEach
    val kind = classifyLayer(layer.displayName, component) ?: return@forEach
    buckets[kind.ordinal * 2 + if (layer.hashPrefixed) 1 else 0] += name
  }
  val ordered = buildList {
    LayerKind.values().forEach { kind ->
      val exact = buckets[kind.ordinal * 2]
      addAll(exact)
      val skipHashFallback = kind == LayerKind.PLAIN && exact.isNotEmpty()
      if (!skipHashFallback) addAll(buckets[kind.ordinal * 2 + 1])
    }
  }
  return ordered.distinct()
}

/**
 * Parses `dumpsys SurfaceFlinger --latency` output.
 *
 * Line 1 is the refresh period and is discarded. Each later row is `desired
 * actual ready` in nanoseconds, tab-separated on the target phone (spaces are
 * accepted too). A row is a real frame when `actualPresent` and `frameReady` are
 * in `(0, 1e18)`; `desiredPresent` may be `0` because some devices report no
 * scheduled deadline for frames that were still presented. The `0 0 0` ring
 * slots, the `INT64_MAX` sentinels and short rows are dropped.
 */
fun parseLatencyRows(latencyOutput: String): List<FrameRow> {
  val rows = ArrayList<FrameRow>()
  latencyOutput.lineSequence().drop(1).forEach { line ->
    val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
    if (parts.size < 3) return@forEach
    val desired = parts[0].toLongOrNull() ?: return@forEach
    val actual = parts[1].toLongOrNull() ?: return@forEach
    val ready = parts[2].toLongOrNull() ?: return@forEach
    if (isDesiredTimestamp(desired) && isFrameTimestamp(actual) && isFrameTimestamp(ready)) {
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

/** `mFocusedApp=ActivityRecord{<hash> u0 <pkg>/<class> t<n>}` or `AppWindowToken{…}`. */
private val FOCUSED_APP =
  Regex(
    """mFocusedApp=(?:ActivityRecord|AppWindowToken)\{[^}]*?\bu\d+\s+([^\s/{}]+)/([^\s{}]+)""",
  )

/**
 * Older `mFocusedApp=… u<id> <pkg>/<class> …` records with an untyped or
 * differently typed wrapper (`WindowToken`, or a bare record).
 *
 * Anchored to `mFocusedApp=` and to the `u<id> <pkg>/<class>` shape, so
 * `mFocusedWindow=` and unrelated lines in the dump never match.
 */
private val FOCUSED_APP_GENERIC =
  Regex("""mFocusedApp=[^\n]*?\bu\d+\s+([^\s/{}]+)/([^\s{}]+)""")

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

/** Frame-carrying layer categories, in preference order. */
private enum class LayerKind { MODERN_BLAST, LEGACY_SURFACE, PLAIN }

/** Suffixes that follow `SurfaceView[<component>]` on a modern render layer. */
private val BLAST_SUFFIXES = listOf("(BLAST)#", "@0(BLAST)#", "(BLAST)@0#")

/**
 * Reads one `--list` line as a layer name.
 *
 * Android 15+ lines are `RequestedLayerState{<name> <attrs>}`; a malformed
 * wrapper is skipped rather than mistaken for a name. Any other non-blank line
 * is an Android 8–14 bare name and is returned whole.
 */
private fun layerNameFromLine(line: String): String? {
  val trimmed = line.trim()
  if (trimmed.isEmpty()) return null
  if (!trimmed.startsWith(LAYER_PREFIX)) return trimmed
  if (!trimmed.endsWith("}")) return null
  return layerName(trimmed.substring(LAYER_PREFIX.length, trimmed.length - 1))
}

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

/**
 * One layer name split into the part used for matching and whether the line
 * carried a `<hex> ` layer-id prefix.
 *
 * The prefix is ignored for matching but stays in the returned name, because
 * `--latency` is addressed by the exact name SurfaceFlinger printed.
 */
private class ParsedLayerName(val displayName: String, val hashPrefixed: Boolean)

/** Splits an optional `<hex> ` layer-id prefix off [name]. */
private fun splitHashPrefix(name: String): ParsedLayerName {
  val space = name.indexOf(' ')
  if (space <= 0) return ParsedLayerName(name, false)
  val first = name.substring(0, space)
  val isHash = first.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
  return if (isHash) ParsedLayerName(name.substring(space + 1), true) else ParsedLayerName(name, false)
}

/** Classifies [displayName], or null when it is not a frame-carrying layer. */
private fun classifyLayer(displayName: String, component: String): LayerKind? = when {
  isBlastSurfaceView(displayName, component) -> LayerKind.MODERN_BLAST
  isLegacySurfaceView(displayName, component) -> LayerKind.LEGACY_SURFACE
  isPlainLayer(displayName, component) -> LayerKind.PLAIN
  else -> null
}

/** True for the exact `<component>#<digits>` layer. */
private fun isPlainLayer(name: String, component: String): Boolean {
  val prefix = "$component#"
  return name.startsWith(prefix) && isLayerId(name.substring(prefix.length))
}

/** True for the pre-BLAST `SurfaceView - <component>#<digits>` render layer. */
private fun isLegacySurfaceView(name: String, component: String): Boolean {
  val prefix = "SurfaceView - $component#"
  return name.startsWith(prefix) && isLayerId(name.substring(prefix.length))
}

/**
 * True for `SurfaceView[<component>](BLAST)#<digits>` and the `@0` spellings.
 * The non-BLAST `SurfaceView[<component>]#<id>` container does not match.
 */
private fun isBlastSurfaceView(name: String, component: String): Boolean {
  val base = "SurfaceView[$component]"
  if (!name.startsWith(base)) return false
  val rest = name.substring(base.length)
  return BLAST_SUFFIXES.any { suffix ->
    rest.startsWith(suffix) && isLayerId(rest.substring(suffix.length))
  }
}

private fun isLayerId(value: String): Boolean =
  value.isNotEmpty() && value.all { it in '0'..'9' }

/**
 * A present/ready timestamp must be inside the CLOCK_MONOTONIC range; `0` is an
 * unused ring slot and `INT64_MAX` is the end-of-ring sentinel.
 */
private fun isFrameTimestamp(value: Long): Boolean =
  value > 0L && value < TIMESTAMP_LIMIT

/**
 * `desiredPresentTime` may legitimately be `0` when the frame was presented
 * without a scheduled deadline; only the sentinel and negative values are not
 * frames.
 */
private fun isDesiredTimestamp(value: Long): Boolean =
  value >= 0L && value < TIMESTAMP_LIMIT

private const val TIMESTAMP_LIMIT = 1_000_000_000_000_000_000L
