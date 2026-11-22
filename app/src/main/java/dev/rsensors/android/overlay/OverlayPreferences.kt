package dev.rsensors.android.overlay

import android.content.Context
import androidx.core.content.edit
import dev.rsensors.android.core.GraphSeries
import dev.rsensors.android.core.PillVisibility

/**
 * Persisted overlay settings (ANDROID.md §4: SharedPreferences, no DataStore).
 *
 * Holds the demo switch, the window position in pixels, the collapsed /
 * expanded state, which kinds the pill shows (CPU/GPU / BAT / FPS / RAM chips),
 * the sparkline's graph series, and whether
 * [dev.rsensors.android.boot.BootReceiver] should start the overlay after a
 * reboot (ANDROID.md §8 T9, T10, T15, T15b, T15i). Position is only written when
 * a drag settles, not on every frame.
 *
 * All access is synchronous on the calling thread; values are tiny and reads
 * are cached by the framework, so the UI may read them during composition. The
 * receiver also reads the boot flag from a background broadcast, which is safe
 * because the write goes through `apply`.
 */
object OverlayPreferences {
  private const val FILE = "overlay"
  private const val KEY_DEMO = "demo"
  private const val KEY_X = "x"
  private const val KEY_Y = "y"
  private const val KEY_EXPANDED = "expanded"
  private const val KEY_BOOT = "boot"
  private const val KEY_SHOW_CPUGPU = "show_cpugpu"
  private const val KEY_SHOW_BAT = "show_bat"
  private const val KEY_SHOW_FPS = "show_fps"
  private const val KEY_SHOW_RAM = "show_ram"
  private const val KEY_GRAPH_SERIES = "graph_series"

  /** Sentinel for "no saved position"; the caller falls back to the defaults. */
  const val POSITION_UNSET = -1

  /** True when the poller should use synthetic sine ramps instead of sensors. */
  fun isDemo(context: Context): Boolean = prefs(context).getBoolean(KEY_DEMO, false)

  /**
   * Stores the demo switch and applies it asynchronously.
   *
   * The running service is not restarted here: the caller decides whether a
   * restart is needed, because this object knows nothing about service state.
   */
  fun setDemo(context: Context, enabled: Boolean) {
    prefs(context).edit { putBoolean(KEY_DEMO, enabled) }
  }

  /** Saved window x in pixels, or [POSITION_UNSET] when none was stored. */
  fun positionX(context: Context): Int = prefs(context).getInt(KEY_X, POSITION_UNSET)

  /** Saved window y in pixels, or [POSITION_UNSET] when none was stored. */
  fun positionY(context: Context): Int = prefs(context).getInt(KEY_Y, POSITION_UNSET)

  /** Stores both coordinates of the settled window, in pixels. */
  fun setPosition(context: Context, x: Int, y: Int) {
    prefs(context).edit {
      putInt(KEY_X, x)
      putInt(KEY_Y, y)
    }
  }

  /** True when the card was expanded when the overlay last ran. */
  fun isExpanded(context: Context): Boolean =
    prefs(context).getBoolean(KEY_EXPANDED, false)

  /** Stores the collapsed / expanded state for the next start. */
  fun setExpanded(context: Context, expanded: Boolean) {
    prefs(context).edit { putBoolean(KEY_EXPANDED, expanded) }
  }

  /**
   * True when the overlay should start by itself after a reboot (T10).
   *
   * Defaults to on: the overlay is the product, and a reboot that silently
   * leaves it off is the failure mode this task exists to fix. It only
   * expresses intent — the overlay grant and the OEM's autostart policy still
   * decide whether the start is allowed.
   */
  fun isBootEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BOOT, true)

  /** Stores the "Start overlay at boot" switch; the boot receiver reads it. */
  fun setBootEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit { putBoolean(KEY_BOOT, enabled) }
  }

  /**
   * Which kinds the pill and notification draw (ANDROID.md §3, §9 T15i).
   *
   * Defaults to all on and is always [PillVisibility.normalised], so a
   * corrupted or partially written preference can never produce an empty pill.
   * The pre-T15i `show_cpu` / `show_gpu` keys are deliberately ignored: the
   * pill now has one combined CPU/GPU chip, and reading a stale half-state
   * would resurrect the split controls.
   */
  fun visibility(context: Context): PillVisibility {
    val prefs = prefs(context)
    return PillVisibility(
      cpuGpu = prefs.getBoolean(KEY_SHOW_CPUGPU, true),
      bat = prefs.getBoolean(KEY_SHOW_BAT, true),
      fps = prefs.getBoolean(KEY_SHOW_FPS, true),
      ram = prefs.getBoolean(KEY_SHOW_RAM, true),
    ).normalised()
  }

  /**
   * Stores the chip state.
   *
   * Normalises before writing so the "at least one on" invariant survives a
   * restart even if a caller hands over an all-off value.
   */
  fun setVisibility(context: Context, value: PillVisibility) {
    val stored = value.normalised()
    prefs(context).edit {
      putBoolean(KEY_SHOW_CPUGPU, stored.cpuGpu)
      putBoolean(KEY_SHOW_BAT, stored.bat)
      putBoolean(KEY_SHOW_FPS, stored.fps)
      putBoolean(KEY_SHOW_RAM, stored.ram)
    }
  }

  /**
   * The sparkline's series (ANDROID.md §3, §9 T15b).
   *
   * Independent of [visibility]: the graph selector does not change the pill,
   * and the chips do not change the graph. A missing or unknown value restores
   * the combined CPU+GPU graph.
   */
  fun graphSeries(context: Context): GraphSeries =
    GraphSeries.fromStored(prefs(context).getString(KEY_GRAPH_SERIES, null))

  /** Stores the sparkline's series; the next start restores it. */
  fun setGraphSeries(context: Context, series: GraphSeries) {
    prefs(context).edit { putString(KEY_GRAPH_SERIES, series.name) }
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
