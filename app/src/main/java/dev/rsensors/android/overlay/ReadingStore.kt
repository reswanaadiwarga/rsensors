package dev.rsensors.android.overlay

import dev.rsensors.android.core.Reading
import dev.rsensors.android.source.SourceError
import dev.rsensors.android.source.SysfsError

/**
 * One poll's view of the overlay for the Diagnostics screen (ANDROID.md §8 T7).
 *
 * The overlay's worker builds this after every poll, including a poll that
 * produced no reading, so the screen can show the age, the failing source, and
 * the raw sysfs errors without owning a second poller.
 *
 * @property reading last merged reading, or null when every source was empty
 * @property now poll time, epoch millis, the reference for the reading's age
 * @property chainErrors exceptions a source threw during the poll
 * @property sysfsErrors per-path sysfs failures recorded by [SysfsSource]
 * @property shizukuRunning whether the Shizuku server answered at poll time
 * @property shizukuGranted whether this app held shell permission at poll time
 * @property overlayRunning false once the service is stopping
 */
data class SourceSnapshot(
  val reading: Reading?,
  val now: Long,
  val chainErrors: List<SourceError>,
  val sysfsErrors: List<SysfsError>,
  val shizukuRunning: Boolean,
  val shizukuGranted: Boolean,
  val overlayRunning: Boolean,
)

/**
 * Process-wide hand-off from the overlay worker to Diagnostics.
 *
 * The service is the only writer; the screen reads [snapshot] on its own
 * background executor. The value is immutable and replaced atomically, so a
 * reader never sees a half-updated poll. A null snapshot means no overlay has
 * polled since the process started (or the service cleared it on stop).
 */
object ReadingStore {
  @Volatile var snapshot: SourceSnapshot? = null
}
