package dev.rsensors.android.overlay

import android.content.Context
import androidx.core.content.edit
import dev.rsensors.android.core.ZoneCategory

/**
 * Manual thermal-zone assignments, keyed by normalised zone `type` (ANDROID.md
 * §5, §8 T7).
 *
 * Stored in its own SharedPreferences file, so the override table cannot be
 * confused with the overlay's demo/position state. A zone is addressed by its
 * `type` string, not its index: the kernel may renumber zones across boots, and
 * the user's choice should follow the sensor, not the directory numbering.
 *
 * [set] removes the entry for null and for [ZoneCategory.OTHER], which is the
 * screen's "Auto" chip; keys are trimmed and lowercased so lookups agree with
 * [dev.rsensors.android.core.ZoneMap]. Unknown or corrupt stored values are
 * ignored on load instead of throwing.
 *
 * Threading: synchronous on the caller. The overlay's worker reads on every
 * poll and the activity writes from a chip tap, both of which are cheap cached
 * SharedPreferences operations.
 */
object ZoneOverrideStore {
  /** SharedPreferences file name; also the diagnostics screen's namespace. */
  const val FILE = "zone_overrides"

  /**
   * All manual assignments, keyed by trimmed lowercase zone type.
   *
   * @return only CPU/GPU/BATTERY entries; unknown names and invalid category
   *   strings are skipped, so a hand-edited file cannot crash the app
   */
  fun load(context: Context): Map<String, ZoneCategory> {
    val result = LinkedHashMap<String, ZoneCategory>()
    for ((rawKey, rawValue) in prefs(context).all) {
      val type = normalise(rawKey)
      if (type.isEmpty()) continue
      val category = (rawValue as? String)?.let(::categoryOrNull) ?: continue
      result[type] = category
    }
    return result
  }

  /**
   * Assigns [type] to [category], or removes it when [category] is null or
   * [ZoneCategory.OTHER] (the screen's Auto choice).
   */
  fun set(context: Context, type: String, category: ZoneCategory?) {
    val key = normalise(type)
    if (key.isEmpty()) return
    val stored = category?.takeUnless { it == ZoneCategory.OTHER }
    prefs(context).edit {
      if (stored == null) remove(key) else putString(key, stored.name)
    }
  }

  private fun normalise(type: String): String = type.trim().lowercase()

  /** Parses a stored category name, rejecting OTHER and garbage. */
  private fun categoryOrNull(name: String): ZoneCategory? =
    try {
      ZoneCategory.valueOf(name).takeUnless { it == ZoneCategory.OTHER }
    } catch (e: IllegalArgumentException) {
      null
    }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
