package dev.rsensors.android.core

import java.util.Locale
import kotlin.math.roundToInt

/** Placeholder drawn wherever a value is unavailable (ANDROID.md §2.2). */
const val DASH = "\u2014"

private const val BRAND = "Rsensors"
private const val SEPARATOR = " \u00b7 "

/** Notifications and tooltips cap their text at 127 characters. */
private const val TOOLTIP_LIMIT = 127

/** Degrees without the unit suffix, for the collapsed pill: `66.4` -> `66°`. */
fun fmtTemp(value: Double?): String =
  if (value == null) DASH else "${value.roundToInt()}\u00b0"

/**
 * Whole frames per second, without a unit suffix: `60.2` -> `60`, `0.0` -> `0`.
 *
 * The card and pill label the value `FPS` themselves, so repeating a unit here
 * would only make the row wider; a missing value is the usual em dash.
 */
fun fmtFps(value: Double?): String =
  if (value == null) DASH else value.roundToInt().toString()

/** Decimal gigabytes: Android reports memory in decimal units, not GiB. */
private const val BYTES_PER_GB = 1_000_000_000.0

/**
 * System RAM as `used / total` in decimal gigabytes, e.g. `4.5 / 7.8 GB`.
 *
 * @param usedBytes memory in use in bytes, or null when unavailable
 * @param totalBytes physical memory in bytes, or null when unavailable
 * @return one decimal per figure with a fixed `.` separator; [DASH] when either
 *   value is null or the total is not positive, because a ratio against no
 *   total would be invented
 */
fun fmtRam(usedBytes: Long?, totalBytes: Long?): String {
  if (usedBytes == null || totalBytes == null || totalBytes <= 0L) return DASH
  return String.format(
    Locale.ROOT,
    "%.1f / %.1f GB",
    usedBytes / BYTES_PER_GB,
    totalBytes / BYTES_PER_GB,
  )
}

/**
 * Used system RAM for the collapsed pill: decimal gigabytes, one decimal and a
 * `G` suffix, e.g. `8.6G`.
 *
 * The pill is width-constrained, so unlike [fmtRam] this omits the total and
 * the space before the unit. A null figure is [DASH].
 */
fun fmtRamPill(usedBytes: Long?): String =
  if (usedBytes == null) DASH else String.format(Locale.ROOT, "%.1fG", usedBytes / BYTES_PER_GB)

/**
 * Human-readable age for a tooltip.
 *
 * Bands: under 3 s "just now", under a minute "Xs ago", under an hour
 * "Xm SSs ago", then "Xh MMm ago". A negative input (clock skew) reads as
 * zero rather than producing a negative age.
 */
fun fmtAge(ageMillis: Long): String {
  val seconds = ageMillis.coerceAtLeast(0L) / 1000L
  return when {
    seconds < 3L -> "just now"
    seconds < 60L -> "${seconds}s ago"
    seconds < 3_600L -> {
      val minutes = seconds / 60L
      val rest = (seconds % 60L).toString().padStart(2, '0')
      "${minutes}m ${rest}s ago"
    }
    else -> {
      val hours = seconds / 3_600L
      val minutes = ((seconds % 3_600L) / 60L).toString().padStart(2, '0')
      "${hours}h ${minutes}m ago"
    }
  }
}

/**
 * Multi-part tooltip for the overlay and the notification.
 *
 * @param reading latest sample, or null before the first poll
 * @param now current epoch millis, the reference for the age and stale text
 * @param source source name appended to the brand; blank means brand only
 * @param error non-empty when the current source failed; reported instead of
 *   the temperatures
 * @param shizukuRunning false adds "(needs Shizuku)" to a missing CPU, because
 *   CPU temperatures on a stock phone need the shell tier
 * @param staleAfterMs age at which the text switches to "no reading for Ns"
 * @return at most 127 characters, parts joined with " · "
 */
fun tooltip(
  reading: Reading?,
  now: Long,
  source: String = "",
  error: String = "",
  shizukuRunning: Boolean = false,
  staleAfterMs: Long = STALE_AFTER_MS,
): String {
  val base = if (source.isBlank()) BRAND else "$BRAND$SEPARATOR$source"
  if (error.isNotEmpty()) return "$base$SEPARATOR$error".take(TOOLTIP_LIMIT)
  if (reading == null) {
    return "${base}${SEPARATOR}waiting for a reading".take(TOOLTIP_LIMIT)
  }

  val parts = mutableListOf(base)
  val cpu = reading.cpu
  parts += if (cpu == null) {
    "CPU n/a" + if (shizukuRunning) "" else " (needs Shizuku)"
  } else {
    "CPU ${cpu.roundToInt()}\u00b0C"
  }
  val gpu = reading.gpu
  parts += if (gpu == null) "GPU n/a" else "GPU ${gpu.roundToInt()}\u00b0C"
  parts += if (reading.stale(now, staleAfterMs)) {
    "no reading for ${reading.ageMillis(now) / 1000L}s"
  } else {
    fmtAge(reading.ageMillis(now))
  }
  return parts.joinToString(SEPARATOR).take(TOOLTIP_LIMIT)
}
