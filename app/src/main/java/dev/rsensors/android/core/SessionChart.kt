package dev.rsensors.android.core

import kotlin.math.ceil

/**
 * Chart geometry for the full-session graph (ANDROID.md §3, §9, §8 T13).
 *
 * Pure Kotlin so the sample-to-series mapping, the axis bounds and the
 * nearest-sample pick can be unit-tested without Compose or a device. The
 * Compose renderer in `ui/SessionGraph.kt` owns pixel geometry and colours; this
 * file only answers "which value, which range, which sample".
 */

/**
 * One plotted line.
 *
 * @property label short name used by the legend and the table header; BATTERY
 *   is abbreviated because the graph's tooltip is width-constrained
 */
enum class ChartSeries(val label: String) {
  CPU("CPU"),
  GPU("GPU"),
  BATTERY("BAT"),
  SOC("SOC"),
  SKIN("SKIN"),

  /** Foreground frame rate; plotted against its own right-hand axis. */
  FPS("FPS"),
}

/**
 * Series drawn against the shared temperature axis. FPS is excluded because it
 * has its own right-hand axis (ANDROID.md §3, §9).
 */
val TEMPERATURE_SERIES: List<ChartSeries> = ChartSeries.entries.filter { it != ChartSeries.FPS }

/**
 * Baseline top of the right-hand FPS axis, in frames per second.
 *
 * Most phones cap at 60 or 120 Hz, so 120 keeps those sessions on a stable
 * scale. A faster panel raises the axis through [fpsAxisMax] instead of
 * clipping its line at the top of the plot.
 */
const val FPS_AXIS_MAX = 120.0

/** FPS axis step above the baseline: 120, 150, 180, … up to the shell cap. */
private const val FPS_AXIS_STEP = 30.0

/** Highest frame rate the shell can report; mirrors `FpsParse`'s clamp. */
private const val FPS_AXIS_CAP = 500.0

/**
 * Top of the FPS axis for a session whose highest recorded frame rate is
 * [peakFps].
 *
 * [FPS_AXIS_MAX] is the baseline: a missing peak (a v1 session), NaN, a
 * non-positive value, or anything at or below 120 keeps the familiar 0–120
 * scale. Only a genuinely faster peak raises the axis, rounded up to the next
 * [FPS_AXIS_STEP] (121 and 144 → 150, 165 → 180) and capped at the shell's
 * 500 fps maximum so an implausible value cannot stretch the plot.
 *
 * @param peakFps highest FPS in the plotted data, or null when none was
 *   recorded
 * @return the axis top in frames per second, never below [FPS_AXIS_MAX]
 */
fun fpsAxisMax(peakFps: Double?): Double {
  if (peakFps == null || peakFps.isNaN() || peakFps <= FPS_AXIS_MAX) return FPS_AXIS_MAX
  val rounded = ceil(peakFps / FPS_AXIS_STEP) * FPS_AXIS_STEP
  return rounded.coerceAtMost(FPS_AXIS_CAP)
}

/**
 * Position of [fps] on a 0–[axisMax] axis, as a 0..1 fraction from the bottom.
 *
 * Clamped so a value the shell could not have produced stays on-plot; the
 * graph's renderer turns this back into a y coordinate. A non-positive or NaN
 * axis falls back to the [FPS_AXIS_MAX] baseline so a caller with no usable
 * peak still gets a valid position.
 *
 * @param axisMax top of the axis; defaults to the baseline
 */
fun fpsFraction(fps: Double, axisMax: Double = FPS_AXIS_MAX): Float {
  val axis = if (axisMax.isNaN() || axisMax <= 0.0) FPS_AXIS_MAX else axisMax
  return (fps / axis).toFloat().coerceIn(0f, 1f)
}

/**
 * True when at least one [samples] entry recorded a frame rate.
 *
 * A v1 session has no `fps` column, so its samples all decode with a null FPS.
 * The session screens use this to hide the FPS legend chip, the right-hand axis
 * and the FPS table column together; a v2 row recording `0.0` (an idle app) is
 * still a value and keeps all three (ANDROID.md §9).
 */
fun hasFpsValues(samples: List<SessionSample>): Boolean =
  samples.any { it.fps != null }

/**
 * The value a stored sample contributes to [series].
 *
 * CPU, GPU and BATTERY come from their own CSV columns; SOC and SKIN come from
 * the board list, matched case-insensitively because board names are stored
 * verbatim from the HAL (`SOC`, `SKIN`, `POWER_AMPLIFIER`, …) while older or
 * hand-edited logs may differ in case. FPS is the v2 `fps` column.
 *
 * @return the temperature in °C (or frames per second for FPS), or null when
 *   the value was absent in that poll
 */
fun SessionSample.valueFor(series: ChartSeries): Double? = when (series) {
  ChartSeries.CPU -> cpu
  ChartSeries.GPU -> gpu
  ChartSeries.BATTERY -> battery
  ChartSeries.SOC -> boardValue("SOC")
  ChartSeries.SKIN -> boardValue("SKIN")
  ChartSeries.FPS -> fps
}

/** First board entry whose name matches [name] ignoring case. */
private fun SessionSample.boardValue(name: String): Double? =
  board.firstOrNull { (sensor, _) -> sensor.equals(name, ignoreCase = true) }?.second

/**
 * Plot range for one session and one series.
 *
 * @property startT first sample time on the x axis, epoch millis
 * @property endT last sample time; equal to [startT] for a single sample
 * @property minTemp bottom of the y axis, in °C, after padding
 * @property maxTemp top of the y axis, in °C, after padding
 */
data class ChartBounds(
  val startT: Long,
  val endT: Long,
  val minTemp: Double,
  val maxTemp: Double,
)

/**
 * Axis range covering [samples] and the non-null values of [series].
 *
 * The x range spans every sample, including polls where the series was null,
 * so all five lines share one time axis. The y range is padded by 10% of the
 * observed span, at least 1 °C, which keeps a flat line visible mid-plot and a
 * single sample from collapsing the axis to zero height.
 *
 * @return null when there are no samples, when every sample's [series] value is
 *   null, or for [ChartSeries.FPS], which uses its own [fpsAxisMax] axis; a
 *   caller may still plot the other series in those cases
 */
fun bounds(samples: List<SessionSample>, series: ChartSeries): ChartBounds? {
  if (samples.isEmpty() || series == ChartSeries.FPS) return null

  var min = Double.MAX_VALUE
  var max = -Double.MAX_VALUE
  samples.forEach { sample ->
    val value = sample.valueFor(series) ?: return@forEach
    if (value < min) min = value
    if (value > max) max = value
  }
  if (min > max) return null

  val pad = ((max - min) * 0.1).coerceAtLeast(1.0)
  return ChartBounds(
    startT = samples.minOf { it.t },
    endT = samples.maxOf { it.t },
    minTemp = min - pad,
    maxTemp = max + pad,
  )
}

/**
 * Index of the sample closest in time to [targetTime].
 *
 * Ties go to the lower list index, so a tap exactly between two samples always
 * resolves to the earlier row instead of flickering between them.
 *
 * @return the index into [samples], or null when the session has no samples
 */
fun nearestIndex(samples: List<SessionSample>, targetTime: Long): Int? {
  if (samples.isEmpty()) return null

  var bestIndex = 0
  var bestDistance = Long.MAX_VALUE
  samples.forEachIndexed { index, sample ->
    val distance = if (sample.t >= targetTime) {
      sample.t - targetTime
    } else {
      targetTime - sample.t
    }
    if (distance < bestDistance) {
      bestDistance = distance
      bestIndex = index
    }
  }
  return bestIndex
}
