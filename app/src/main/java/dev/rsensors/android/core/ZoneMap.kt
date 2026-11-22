package dev.rsensors.android.core

/** Broad meaning assigned to a thermal zone, used by the overlay fields. */
enum class ZoneCategory { CPU, GPU, BATTERY, OTHER }

/**
 * One thermal zone as read from sysfs.
 *
 * @property index numeric suffix of `thermal_zone<N>`; lower indices win ties
 * @property type raw contents of the zone's `type` file (trimmed by the reader)
 * @property tempC normalised degrees Celsius, or null when the value was
 *   missing, unparseable, zero, or out of range
 */
data class Zone(val index: Int, val type: String, val tempC: Double? = null)

/**
 * Scored mapping from a thermal-zone `type` string to [ZoneCategory].
 *
 * Android has no naming standard, so the mapping is a per-string heuristic
 * seeded from the Qualcomm, MediaTek, and Exynos layouts in ANDROID.md §5.
 * Every input is lowercased and trimmed before scoring; scores are ordered so
 * an exact alias always beats a prefix and a prefix always beats a substring.
 *
 * A manual override, keyed by the same normalised type string, replaces the
 * heuristic for that zone (ANDROID.md §5, §8 T7). Overrides are stored by type
 * and not by index, so the assignment survives a kernel that renumbers zones.
 *
 * Pure: no I/O, no Android imports.
 */
object ZoneMap {

  /** Returns the category for one zone `type`; unknown or ambiguous types are [ZoneCategory.OTHER]. */
  fun categorize(type: String): ZoneCategory {
    val normalised = type.trim().lowercase()
    return resolve(normalised, score(normalised))
  }

  /**
   * Returns the category for one zone `type`, honouring [overrides] first.
   *
   * @param overrides manual assignments keyed by zone type; keys are trimmed
   *   and lowercased here, so callers need not pre-normalise them
   * @return the override when one matches, otherwise the heuristic category;
   *   an override to [ZoneCategory.OTHER] is returned as-is and removes the
   *   zone from [pick]
   */
  fun categorize(type: String, overrides: Map<String, ZoneCategory>): ZoneCategory {
    val normalised = type.trim().lowercase()
    return normalisedOverrides(overrides)[normalised] ?: resolve(normalised, score(normalised))
  }

  /**
   * Picks the best-temperature zone per category.
   *
   * The highest score wins; equal scores go to the lowest [Zone.index] so the
   * result is stable regardless of directory-listing order. An overridden zone
   * outranks every heuristic match in its assigned category, because the user's
   * explicit choice is authoritative. Overridden zones tie with each other and
   * fall back to the lowest index. Zones with a null temperature and
   * [ZoneCategory.OTHER] zones never appear. Categories with no usable zone are
   * absent from the map.
   *
   * @param overrides manual assignments keyed by zone type; a zone assigned
   *   [ZoneCategory.OTHER] is removed even when the heuristic would match it
   */
  fun pick(
    zones: List<Zone>,
    overrides: Map<String, ZoneCategory> = emptyMap(),
  ): Map<ZoneCategory, Zone> {
    val overridesByType = normalisedOverrides(overrides)
    val best = LinkedHashMap<ZoneCategory, Zone>()
    val bestScores = HashMap<ZoneCategory, Int>()
    for (zone in zones) {
      if (zone.tempC == null) continue
      val normalised = zone.type.trim().lowercase()
      val scores = score(normalised)
      val override = overridesByType[normalised]
      val category = override ?: resolve(normalised, scores)
      if (category == ZoneCategory.OTHER) continue
      val score = if (override != null) OVERRIDE_SCORE else scores.forCategory(category)
      val current = best[category]
      if (current == null ||
        score > bestScores.getValue(category) ||
        (score == bestScores.getValue(category) && zone.index < current.index)
      ) {
        best[category] = zone
        bestScores[category] = score
      }
    }
    return best
  }

  /** Trimmed and lowercased copy, so lookups do not depend on caller hygiene. */
  private fun normalisedOverrides(
    overrides: Map<String, ZoneCategory>,
  ): Map<String, ZoneCategory> =
    if (overrides.isEmpty()) {
      emptyMap()
    } else {
      overrides.entries.associate { (type, category) ->
        type.trim().lowercase() to category
      }
    }

  private fun resolve(normalised: String, scores: Scores): ZoneCategory {
    val best = maxOf(scores.cpu, scores.gpu, scores.battery)
    if (best == 0) return ZoneCategory.OTHER
    // A type that also names a GPU or battery is too ambiguous to call CPU.
    if (scores.cpu == best && (normalised.contains("gpu") || normalised.contains("batt"))) {
      return ZoneCategory.OTHER
    }
    return when {
      scores.cpu == best -> ZoneCategory.CPU
      scores.gpu == best -> ZoneCategory.GPU
      else -> ZoneCategory.BATTERY
    }
  }

  private fun score(normalised: String): Scores =
    Scores(
      cpu = cpuScore(normalised),
      gpu = gpuScore(normalised),
      battery = batteryScore(normalised),
    )

  private fun cpuScore(t: String): Int =
    when {
      t == "cpu" || t == "cpu_thermal" || t == "mtktscpu" -> 100
      t.startsWith("cpu-") || t.startsWith("cpu_") || t.startsWith("cpuss") -> 90
      t == "soc" || t == "soc_thermal" -> 60
      t.contains("cpu") -> 50
      else -> 0
    }

  private fun gpuScore(t: String): Int =
    when {
      t == "gpu" || t == "gpu_thermal" || t == "mtktsgpu" -> 100
      t.startsWith("gpu-") || t.startsWith("gpu_") || t.startsWith("gpuss") || t == "gpu0" -> 90
      t.contains("gpu") || t.contains("mali") -> 50
      else -> 0
    }

  private fun batteryScore(t: String): Int =
    when {
      t == "battery" || t == "batt" || t == "mtktsbattery" -> 100
      t.startsWith("batt") || t.startsWith("battery") -> 90
      t.contains("battery") -> 50
      else -> 0
    }

  private data class Scores(val cpu: Int, val gpu: Int, val battery: Int) {
    fun forCategory(category: ZoneCategory): Int =
      when (category) {
        ZoneCategory.CPU -> cpu
        ZoneCategory.GPU -> gpu
        ZoneCategory.BATTERY -> battery
        ZoneCategory.OTHER -> 0
      }
  }

  /** Manual assignments outrank every heuristic score. */
  private const val OVERRIDE_SCORE = 10_000
}
