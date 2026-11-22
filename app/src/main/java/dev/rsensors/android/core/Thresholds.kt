package dev.rsensors.android.core

/**
 * Colour grade of a value.
 *
 * UNKNOWN means "no measurement", which is deliberately distinct from OK: the
 * UI must draw a dash, not green digits, when a sensor is missing.
 */
enum class Level { OK, WARN, HOT, UNKNOWN }

/** CPU warn/hot thresholds in °C (ANDROID.md §3). */
const val CPU_WARN = 75.0
const val CPU_HOT = 85.0

/** GPU warn/hot thresholds in °C. */
const val GPU_WARN = 70.0
const val GPU_HOT = 83.0

/** Battery warn/hot thresholds in °C. */
const val BATTERY_WARN = 42.0
const val BATTERY_HOT = 45.0

/**
 * FPS band edges (ANDROID.md §3, §9): below 30 is red, below 50 amber,
 * otherwise green. Low frame rate is the problem, so the names follow the
 * temperature table's meaning (warn/hot = degraded) and not its ordering.
 */
const val FPS_WARN = 50.0
const val FPS_HOT = 30.0

/**
 * Grades [value] against [warn] and [hot].
 *
 * Boundaries are inclusive at the bottom of each band: a value equal to [hot]
 * is HOT, a value equal to [warn] is WARN. A null value is UNKNOWN.
 */
fun level(value: Double?, warn: Double, hot: Double): Level = when {
  value == null -> Level.UNKNOWN
  value >= hot -> Level.HOT
  value >= warn -> Level.WARN
  else -> Level.OK
}

/**
 * Combines the grades of the two SoC sensors into the pill's single colour.
 *
 * UNKNOWN is "no measurement" rather than "fine", so it is only the result when
 * both inputs are UNKNOWN; when one sensor answered, the worse of the answered
 * levels (OK/WARN/HOT) wins (ANDROID.md §3, §9 T15i).
 */
fun worstLevel(a: Level, b: Level): Level = when {
  a == Level.UNKNOWN -> b
  b == Level.UNKNOWN -> a
  // Ordinals are OK < WARN < HOT < UNKNOWN, and UNKNOWN is handled above, so
  // the higher ordinal is the worse grade here.
  a.ordinal > b.ordinal -> a
  else -> b
}

/**
 * Grades a frame rate, where a *higher* number is the healthy one.
 *
 * Deliberately not `level(value, FPS_WARN, FPS_HOT)`: that helper treats the
 * larger constant as worse. A null value (no shell route, no FPS this poll) is
 * UNKNOWN, 0 is HOT — an idle screen is a frozen one, not a fine one.
 */
fun fpsLevel(fps: Double?): Level = when {
  fps == null -> Level.UNKNOWN
  fps >= FPS_WARN -> Level.OK
  fps >= FPS_HOT -> Level.WARN
  else -> Level.HOT
}

/**
 * Maps thermal pressure onto the same colour scale as the temperatures:
 * NONE/LIGHT are fine, MODERATE warns, SEVERE and worse are hot.
 */
fun thermalLevel(status: ThermalStatus): Level = when (status) {
  ThermalStatus.NONE, ThermalStatus.LIGHT -> Level.OK
  ThermalStatus.MODERATE -> Level.WARN
  ThermalStatus.SEVERE,
  ThermalStatus.CRITICAL,
  ThermalStatus.EMERGENCY,
  ThermalStatus.SHUTDOWN,
  -> Level.HOT
  ThermalStatus.UNKNOWN -> Level.UNKNOWN
}
