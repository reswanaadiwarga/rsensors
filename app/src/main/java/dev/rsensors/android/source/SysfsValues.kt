package dev.rsensors.android.source

import kotlin.math.abs

/**
 * Outcome of interpreting one raw sysfs temperature value.
 *
 * Exactly one side is meaningful: [celsius] when the value is plausible, or
 * [error] when it was rejected. A rejected value never produces a number, so
 * callers cannot accidentally display a guess (ANDROID.md §2.2).
 *
 * @property celsius temperature in degrees Celsius, or null when rejected
 * @property error short machine-readable reason (`parse`, `zero`, `range`), or
 *   null when [celsius] is set
 */
data class NormalisedValue(val celsius: Double?, val error: String?)

/**
 * The single normalisation rule set for raw sysfs temperatures (ANDROID.md §5).
 *
 * Both [SysfsSource] and [SysfsProbe] call this, so the pill and the
 * diagnostics table can never disagree about what a node means.
 *
 * Rules, in order:
 * 1. unparseable or non-finite text -> `parse`
 * 2. exactly zero -> `zero` (a kernel that reports 0 is not measuring)
 * 3. `milliByMagnitude` and `|v| > 200` -> treat as millidegrees (`v / 1000`)
 * 4. outside `-40..150 °C` after scaling -> `range`
 *
 * The magnitude heuristic is disabled for whole-degree nodes such as the kgsl
 * GPU junction temperature, so `47000` there is rejected as out of range
 * instead of being silently scaled to 47 °C.
 *
 * Pure: no I/O, no Android imports.
 */
object SysfsValues {
  /** Smallest plausible temperature; colder values are a sensor fault. */
  const val MIN_CELSIUS = -40.0

  /** Largest plausible temperature; hotter values are a sensor fault. */
  const val MAX_CELSIUS = 150.0

  /** Above this magnitude a thermal-zone value is millidegrees. */
  const val MILLI_THRESHOLD = 200.0

  /**
   * Converts [raw] to Celsius or a rejection reason.
   *
   * @param raw trimmed text read from the sysfs node
   * @param milliByMagnitude true for thermal zones, false for whole-degree
   *   vendor nodes such as `/sys/class/kgsl/kgsl-3d0/temp`
   */
  fun normalise(raw: String, milliByMagnitude: Boolean): NormalisedValue {
    val value = raw.toDoubleOrNull()
    if (value == null || !value.isFinite()) return NormalisedValue(null, "parse")
    if (value == 0.0) return NormalisedValue(null, "zero")
    val celsius =
      if (milliByMagnitude && abs(value) > MILLI_THRESHOLD) value / 1000.0 else value
    if (celsius < MIN_CELSIUS || celsius > MAX_CELSIUS) {
      return NormalisedValue(null, "range")
    }
    return NormalisedValue(celsius, null)
  }
}
