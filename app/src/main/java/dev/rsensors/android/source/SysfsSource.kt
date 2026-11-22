package dev.rsensors.android.source

import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.Zone
import dev.rsensors.android.core.ZoneCategory
import dev.rsensors.android.core.ZoneMap

/**
 * Best-effort CPU/GPU/battery temperatures from sysfs thermal nodes.
 *
 * Per poll it lists `$thermalRoot/thermal_zone*`, reads each zone's `type` and
 * `temp`, normalises the value, and lets [ZoneMap] pick one zone per category.
 * When no thermal zone maps to a GPU it falls back to the whole-degree kgsl
 * node. It never invents a value: a missing or unreadable node contributes
 * nothing and is recorded in [lastErrors] for Diagnostics.
 *
 * Normalisation (ANDROID.md §5) lives in [SysfsValues] and is shared with the
 * Diagnostics probe: thermal-zone values are millidegrees when `|v| > 200`,
 * while the kgsl node is always whole degrees and never passes through that
 * heuristic. Exactly zero is dropped, as is anything outside `-40..150 °C`
 * after scaling.
 *
 * Manual zone overrides (ANDROID.md §8 T7) are read through [overrides] on
 * every poll, so an assignment made in Diagnostics applies within one poll
 * without rebuilding the source or restarting the service.
 *
 * Not a real-time source: it is polled by the overlay, and every call resets
 * the error log. Single owner thread.
 *
 * @param reader file access, direct or Shizuku-backed
 * @param thermalRoot directory containing `thermal_zone*` entries
 * @param kgslTempPath Qualcomm GPU junction temperature (whole degrees)
 * @param overrides manual zone-type assignments; called once per poll
 */
class SysfsSource(
  private val reader: SysfsReader,
  private val thermalRoot: String = DEFAULT_THERMAL_ROOT,
  private val kgslTempPath: String = DEFAULT_KGSL_TEMP_PATH,
  private val overrides: () -> Map<String, ZoneCategory> = { emptyMap() },
) : SensorSource {
  override val id: String = ID

  /** Reader plus parse errors from the most recent [poll]; empty before it. */
  var lastErrors: List<SysfsError> = emptyList()
    private set

  override fun poll(now: Long): Reading? {
    reader.clearErrors()
    val errors = mutableListOf<SysfsError>()
    val overrideMap = overrides()

    val zones = readZones(errors)
    val picked = ZoneMap.pick(zones, overrideMap)
    val cpu = picked[ZoneCategory.CPU]?.tempC
    val battery = picked[ZoneCategory.BATTERY]?.tempC
    var gpu = picked[ZoneCategory.GPU]?.tempC
    if (gpu == null) {
      // kgsl is whole degrees, so it must not be scaled like a thermal zone.
      val raw = reader.open(kgslTempPath)
      if (raw != null) {
        val value = SysfsValues.normalise(raw, milliByMagnitude = false)
        value.error?.let { errors += SysfsError(kgslTempPath, it) }
        gpu = value.celsius
      }
    }

    lastErrors = reader.errors().toList() + errors
    if (cpu == null && gpu == null && battery == null) return null
    return Reading(t = now, cpu = cpu, gpu = gpu, battery = battery, sourceName = ID)
  }

  private fun readZones(errors: MutableList<SysfsError>): List<Zone> {
    val names = reader.listDir(thermalRoot)
    return names
      .mapNotNull { name ->
        val match = ZONE_NAME.matchEntire(name) ?: return@mapNotNull null
        val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val typePath = "$thermalRoot/$name/type"
        val type = reader.open(typePath) ?: return@mapNotNull null
        val tempPath = "$thermalRoot/$name/temp"
        val raw = reader.open(tempPath)
        val normalised = raw?.let { SysfsValues.normalise(it, milliByMagnitude = true) }
        normalised?.error?.let { errors += SysfsError(tempPath, it) }
        Zone(index, type, normalised?.celsius)
      }
      .sortedBy { it.index }
  }

  companion object {
    const val ID = "sysfs"
    const val DEFAULT_THERMAL_ROOT = "/sys/class/thermal"
    const val DEFAULT_KGSL_TEMP_PATH = "/sys/class/kgsl/kgsl-3d0/temp"

    private val ZONE_NAME = Regex("""thermal_zone(\d+)""")
  }
}
