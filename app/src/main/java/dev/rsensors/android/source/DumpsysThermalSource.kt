package dev.rsensors.android.source

import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.parseDumpsysThermal

/**
 * CPU, GPU and battery temperatures from `dumpsys thermalservice` (ANDROID.md §5).
 *
 * This is the tier-1 source: on the target phone `dumpsys` is the only route to
 * real CPU/GPU values, and it runs through a [DumpsysRunner] (Shizuku in
 * production, a fake in tests). The runner's output goes through
 * [parseDumpsysThermal], and the source stamps the caller's clock and its own
 * id, because the dump carries no timestamp.
 *
 * Failure policy (§2.2, §8 T8): a missing runner, a denied permission, a dead
 * binder or a dump that parses to nothing all yield null, so [SourceChain]
 * falls through to the next source instead of the poller seeing an exception.
 *
 * Single owner thread, matching the poller; no resources of its own.
 *
 * @param runner produces the raw dump and owns the Shizuku binding
 */
class DumpsysThermalSource(private val runner: DumpsysRunner) : SensorSource {
  override val id: String = ID

  override fun poll(now: Long): Reading? =
    try {
      runner.run()?.let { parseDumpsysThermal(it)?.copy(t = now, sourceName = id) }
    } catch (e: Exception) {
      null
    }

  /** Releases the runner's binding; the chain calls this once on shutdown. */
  override fun close() {
    runner.close()
  }

  companion object {
    /** Matches the HAL block key and the green overlay dot (ANDROID.md §3). */
    const val ID = "dumpsys"
  }
}
