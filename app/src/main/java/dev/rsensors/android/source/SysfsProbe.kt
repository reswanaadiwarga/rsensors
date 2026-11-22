package dev.rsensors.android.source

import dev.rsensors.android.core.ZoneCategory
import dev.rsensors.android.core.ZoneMap

/**
 * One thermal zone as Diagnostics sees it (ANDROID.md §2.2, §5, §8 T7).
 *
 * Unlike the overlay's `Zone`, every listed directory becomes a report, even
 * when its metadata or temperature could not be read: the screen exists to show
 * what the kernel denies, not to hide it.
 *
 * @property index numeric suffix of `thermal_zone<N>`
 * @property type raw `type` text, or null when the read failed
 * @property rawTemp raw `temp` text as read, or null when the read failed
 * @property celsius normalised temperature, or null when the value was
 *   unreadable, unparseable, zero, or outside the plausible range
 * @property category manual override first, then the [ZoneMap] heuristic, or
 *   [ZoneCategory.OTHER] when the type is unknown or unreadable
 * @property error per-path failure reason (`EACCES`, `ENOENT`, `parse`, `zero`,
 *   `range`), or null when both files were read and the value was accepted
 */
data class ZoneReport(
  val index: Int,
  val type: String?,
  val rawTemp: String?,
  val celsius: Double?,
  val category: ZoneCategory,
  val error: String?,
)

private val ZONE_NAME = Regex("""thermal_zone(\d+)""")

/**
 * Reads the whole thermal-zone table for the Diagnostics screen.
 *
 * Lists `$thermalRoot/thermal_zone*`, reads each zone's `type` and `temp`, and
 * applies the same [SysfsValues] rule set as [SysfsSource], so the table and the
 * pill cannot disagree. Every zone is kept, sorted by index, including ones
 * whose reads were denied.
 *
 * The error column prefers the temperature's failure over the type's: when the
 * value was rejected, that reason explains the missing number. A type failure
 * is attached only when the temperature itself was accepted. If the directory
 * listing is denied there are no zone names to report, so the result is empty;
 * the caller shows the source-level error instead.
 *
 * Side effects: clears the reader's error log and then reads through it. Single
 * owner thread, like the reader.
 *
 * @param reader file access, direct or Shizuku-backed
 * @param thermalRoot directory containing `thermal_zone*` entries
 * @param overrides manual assignments keyed by zone type; trimmed and
 *   lowercased by [ZoneMap.categorize]
 * @return one report per numeric zone, ascending by index; never null
 */
fun probeThermalZones(
  reader: SysfsReader,
  thermalRoot: String = SysfsSource.DEFAULT_THERMAL_ROOT,
  overrides: Map<String, ZoneCategory> = emptyMap(),
): List<ZoneReport> {
  reader.clearErrors()
  var errorCount = reader.errors().size

  /**
   * Opens one node and returns the error logged by that open, if it failed.
   * Errors from earlier paths stay consumed, so each report gets its own reason.
   */
  fun open(path: String): Pair<String?, String?> {
    val raw = reader.open(path)
    val errors = reader.errors()
    val reason = if (raw == null) errors.getOrNull(errorCount)?.reason else null
    errorCount = errors.size
    return raw to reason
  }

  val zones =
    reader.listDir(thermalRoot)
      .mapNotNull { name ->
        val match = ZONE_NAME.matchEntire(name) ?: return@mapNotNull null
        val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        index to name
      }
      .sortedBy { (index, _) -> index }
  errorCount = reader.errors().size

  return zones.map { (index, name) ->
    val (type, typeError) = open("$thermalRoot/$name/type")
    val (rawTemp, tempReadError) = open("$thermalRoot/$name/temp")
    val normalised = rawTemp?.let { SysfsValues.normalise(it, milliByMagnitude = true) }
    ZoneReport(
      index = index,
      type = type,
      rawTemp = rawTemp,
      celsius = normalised?.celsius,
      category = type?.let { ZoneMap.categorize(it, overrides) } ?: ZoneCategory.OTHER,
      error = tempReadError ?: normalised?.error ?: typeError,
    )
  }
}
