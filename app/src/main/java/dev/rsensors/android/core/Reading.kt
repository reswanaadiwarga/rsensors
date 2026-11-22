package dev.rsensors.android.core

/**
 * One sensor sample.
 *
 * All temperatures are degrees Celsius. Any field is null when the device does
 * not expose that sensor; callers render such fields as an em dash instead of
 * inventing a number (ANDROID.md §2.2, §3).
 *
 * @property t sample time, epoch millis
 * @property cores per-core temperatures, empty when unavailable
 * @property board named motherboard sensors, empty when unavailable
 * @property sourceName human-readable source that produced the sample
 * @property fps foreground app's frames per second at sample time, or null when
 *   the shell-side SurfaceFlinger route is unavailable (ANDROID.md §3, §9)
 */
data class Reading(
  val t: Long,
  val cpu: Double? = null,
  val cpuMax: Double? = null,
  val cores: List<Double> = emptyList(),
  val gpu: Double? = null,
  val gpuHot: Double? = null,
  val battery: Double? = null,
  val thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN,
  val board: List<Pair<String, Double>> = emptyList(),
  val sourceName: String = "",
  val fps: Double? = null,
) {
  /**
   * Milliseconds since [t].
   *
   * Negative deltas (a clock that jumped backwards) count as zero, so a bad
   * wall clock can never make a fresh reading look ancient.
   */
  fun ageMillis(now: Long): Long = (now - t).coerceAtLeast(0L)

  /**
   * True when this sample is older than [afterMillis].
   *
   * Strictly greater-than: a sample exactly at the threshold is still live,
   * and the strip dims one millisecond later.
   */
  fun stale(now: Long, afterMillis: Long = STALE_AFTER_MS): Boolean =
    ageMillis(now) > afterMillis
}

/**
 * Coarse thermal pressure, mirroring `PowerManager.getCurrentThermalStatus()`.
 * Values are ordered by severity, so ordinally-higher statuses are worse.
 */
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

/** Age at which the strip dims and the badge falls back to "?" (ANDROID.md §3). */
const val STALE_AFTER_MS = 15_000L
