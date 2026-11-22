package dev.rsensors.android.core

/**
 * In-memory rolling history for the expanded card's sparkline
 * (ANDROID.md §3, §9).
 *
 * Holds at most [maxSamples] samples from the last [windowMs] milliseconds: 120
 * samples covers the 2-minute graph at the 2 s expanded cadence. Nothing is
 * persisted — the history dies with the process, which is exactly the v1 scope.
 *
 * Samples normally arrive in non-decreasing [Reading.t] order from a single
 * poller, but [snapshot] sorts by time so "newest last" holds even if a sample
 * lands out of order (for example after a clock adjustment).
 *
 * Threading: not synchronised. The owner (the overlay's poller) uses it from one
 * thread at a time.
 */
class ReadingHistory(
  private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
  private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
  private val samples = ArrayDeque<Reading>()

  /**
   * Appends one sample and drops what no longer fits.
   *
   * Pruning is relative to the new sample's own time, because [add] has no
   * separate clock and a poll's timestamp is the newest time the buffer knows.
   */
  fun add(reading: Reading) {
    samples.addLast(reading)
    prune(reading.t)
  }

  /**
   * Returns the samples still inside the window at [now], oldest first.
   *
   * @param now reference time in epoch millis; a sample exactly at
   *   `now - windowMs` is still included
   * @return a new list, at most [maxSamples] long; mutating it cannot affect
   *   the buffer
   */
  fun snapshot(now: Long): List<Reading> {
    prune(now)
    return samples.sortedBy { it.t }
  }

  /** Drops every sample; used when the source is rebuilt. */
  fun clear() {
    samples.clear()
  }

  /** Removes expired and excess samples. */
  private fun prune(now: Long) {
    val cutoff = now - windowMs
    samples.removeAll { it.t < cutoff }
    while (samples.size > maxSamples) {
      samples.removeFirst()
    }
  }

  companion object {
    /** 120 samples = 2 minutes at the 2 s expanded cadence. */
    const val DEFAULT_MAX_SAMPLES = 120

    /** Sparkline window in milliseconds (2 minutes). */
    const val DEFAULT_WINDOW_MS = 120_000L
  }
}
