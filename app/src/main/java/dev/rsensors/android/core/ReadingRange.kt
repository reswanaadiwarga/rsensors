package dev.rsensors.android.core

/**
 * Minimum and maximum of one reading field over a window (ANDROID.md §3, §9
 * T15i).
 *
 * Both are null for an empty window, so the card can draw `max — min —` rather
 * than inventing a value; a single sample is both ends of the range.
 */
data class ValueRange(val min: Double?, val max: Double?)

/**
 * Finds the minimum and maximum of [selector] across these readings.
 *
 * Null field values are ignored instead of widening the range, because a
 * missing sensor is not a measurement. The input order does not matter, and the
 * receiver is not modified.
 *
 * @param selector field to measure, for example `{ it.cpu }`
 * @return both ends null when no reading carries a value, otherwise the
 *   smallest and largest observed value (equal for a single sample)
 */
fun Iterable<Reading>.valueRange(selector: (Reading) -> Double?): ValueRange {
  var lo: Double? = null
  var hi: Double? = null
  for (reading in this) {
    val value = selector(reading) ?: continue
    if (lo == null || value < lo) lo = value
    if (hi == null || value > hi) hi = value
  }
  return ValueRange(min = lo, max = hi)
}
