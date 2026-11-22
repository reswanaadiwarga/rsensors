package dev.rsensors.android.source

import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.ThermalStatus

/**
 * One source that failed during a poll, kept for the diagnostics screen.
 *
 * @property source the [SensorSource.id] that threw
 * @property message the exception message, or its class name when blank
 */
data class SourceError(val source: String, val message: String)

/**
 * Merges several [SensorSource]s into a single [Reading] (ANDROID.md §5).
 *
 * The list order *is* the priority order and is never re-sorted: the first
 * source that supplies a field wins, later sources only fill gaps. Every source
 * is polled on every pass, because one failing backend must not blank values
 * the others can still read. Failures are collected in [lastErrors] for
 * Diagnostics rather than thrown.
 *
 * A reading counts as a contribution only when it carries at least one value;
 * a [Reading.sourceName] alone is not a measurement. When no source contributes,
 * [poll] returns null and the UI draws dashes.
 *
 * @param sources backends in decreasing priority; the chain does not copy the
 *   list, so the caller must not mutate it while polling
 */
class SourceChain(private val sources: List<SensorSource>) : SensorSource {
  override val id: String = ID

  /**
   * Failures from the most recent [poll], in source order.
   *
   * Replaced on every poll, including with an empty list, so a recovered source
   * never leaves a stale error on the diagnostics screen.
   */
  var lastErrors: List<SourceError> = emptyList()
    private set

  override fun poll(now: Long): Reading? {
    val errors = mutableListOf<SourceError>()
    val contributors = mutableListOf<Pair<SensorSource, Reading>>()

    for (source in sources) {
      try {
        val reading = source.poll(now)
        if (reading != null && reading.hasValue()) {
          contributors += source to reading
        }
      } catch (e: Exception) {
        errors += SourceError(source.id, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
      }
    }
    lastErrors = errors

    if (contributors.isEmpty()) return null

    val readings = contributors.map { it.second }
    return Reading(
      t = now,
      cpu = readings.firstNotNullOfOrNull { it.cpu },
      cpuMax = readings.firstNotNullOfOrNull { it.cpuMax },
      cores = readings.firstOrNull { it.cores.isNotEmpty() }?.cores ?: emptyList(),
      gpu = readings.firstNotNullOfOrNull { it.gpu },
      gpuHot = readings.firstNotNullOfOrNull { it.gpuHot },
      battery = readings.firstNotNullOfOrNull { it.battery },
      thermalStatus = readings.firstOrNull { it.thermalStatus != ThermalStatus.UNKNOWN }?.thermalStatus
        ?: ThermalStatus.UNKNOWN,
      board = readings.firstOrNull { it.board.isNotEmpty() }?.board ?: emptyList(),
      sourceName = contributors
        .map { (source, reading) -> reading.sourceName.ifBlank { source.id } }
        .distinct()
        .joinToString("+"),
    )
  }

  /**
   * Closes every source, including ones already closed, and continues past a
   * source that throws: a shutdown failure in one backend must not leak the
   * others' resources.
   */
  override fun close() {
    for (source in sources) {
      try {
        source.close()
      } catch (e: Exception) {
        // Best-effort shutdown; nothing useful to report from close().
      }
    }
  }

  /** True when this reading carries at least one real measurement. */
  private fun Reading.hasValue(): Boolean =
    cpu != null ||
      cpuMax != null ||
      gpu != null ||
      gpuHot != null ||
      battery != null ||
      cores.isNotEmpty() ||
      board.isNotEmpty() ||
      thermalStatus != ThermalStatus.UNKNOWN

  companion object {
    const val ID = "chain"
  }
}
