package dev.rsensors.android.core

import kotlin.math.roundToInt

/**
 * Badge content for the notification large icon and the tray-style dot.
 *
 * @property label digits to draw, or "?" when there is nothing trustworthy
 * @property level colour grade; the palette itself belongs to the UI layer
 */
data class IconSpec(val label: String, val level: Level)

/**
 * Derives the badge from the CPU temperature.
 *
 * Returns `("?", UNKNOWN)` when there is no reading, the sample is stale, or
 * the device reports no CPU temperature — the badge never displays a GPU or
 * battery number as if it were the CPU.
 *
 * @param reading latest sample, or null before the first poll
 * @param now current epoch millis, the reference for staleness
 * @param staleAfterMs age at which the badge falls back to "?"
 */
fun iconSpec(
  reading: Reading?,
  now: Long,
  staleAfterMs: Long = STALE_AFTER_MS,
): IconSpec {
  if (reading == null || reading.stale(now, staleAfterMs)) {
    return IconSpec("?", Level.UNKNOWN)
  }
  val cpu = reading.cpu ?: return IconSpec("?", Level.UNKNOWN)
  return IconSpec(cpu.roundToInt().toString(), level(cpu, CPU_WARN, CPU_HOT))
}
