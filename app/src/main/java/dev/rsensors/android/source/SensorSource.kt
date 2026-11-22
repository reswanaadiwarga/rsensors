package dev.rsensors.android.source

import dev.rsensors.android.core.Reading

/**
 * One sensor backend.
 *
 * Implementations contribute only the fields they can actually read; callers
 * merge several sources into one [Reading] (ANDROID.md §5). A source must
 * never invent a value: a missing or unreadable sensor yields a null field, and
 * the UI draws a dash.
 *
 * Threading: polling and closing happen on the same owner thread; the interface
 * itself adds no synchronisation.
 */
interface SensorSource {
  /** Stable identifier used in diagnostics and logs, e.g. "demo". */
  val id: String

  /**
   * Reads the current values.
   *
   * @param now current epoch millis; implementations stamp it as [Reading.t]
   *   so the caller's clock, not the source's, defines sample age
   * @return a reading, or null when the source has nothing at all to report
   */
  fun poll(now: Long): Reading?

  /** Releases any held resources. Idempotent; the default has nothing to do. */
  fun close() {}
}
