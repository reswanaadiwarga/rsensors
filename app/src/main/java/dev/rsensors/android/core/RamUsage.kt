package dev.rsensors.android.core

/**
 * One poll's system RAM totals, for the expanded card's RAM row and bar and the
 * collapsed pill's RAM segment.
 *
 * Both values are bytes as reported by
 * [android.app.ActivityManager.MemoryInfo]: [totalBytes] is the device's
 * physical memory and [usedBytes] is `totalMem - availMem`, already floored at
 * zero by the caller. A total of zero or less means the platform gave no usable
 * figure; [fraction] then stays 0 so the bar renders empty rather than dividing
 * by zero.
 *
 * Immutable and free of Android types, so it can be built anywhere and tested
 * on the JVM.
 */
data class RamUsage(val usedBytes: Long, val totalBytes: Long) {
  /**
   * Share of memory in use, clamped to `0f..1f`.
   *
   * The clamp covers both a negative used value (defensive) and a used value
   * above total, which keeps a bar's geometry inside its track.
   */
  val fraction: Float
    get() = if (totalBytes <= 0L) {
      0f
    } else {
      (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
