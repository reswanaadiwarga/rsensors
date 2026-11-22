package dev.rsensors.android.core

/**
 * The series the expanded card's sparkline draws (ANDROID.md §3, §9 T15b, T15c).
 *
 * The graph has its own selector, independent of the pill's CPU / GPU / FPS
 * chips: the chips decide what the pill and notification show, while this enum
 * only decides which line the 2-minute graph draws. The history behind the
 * graph always records CPU, GPU, battery and FPS, so switching series never
 * loses data.
 *
 * @property label text shown on the card's selector button
 */
enum class GraphSeries(val label: String) {
  /** CPU and GPU on one shared padded temperature scale (the default). */
  CPU_GPU("CPU+GPU"),

  /** CPU only, scaled from the CPU values. */
  CPU("CPU"),

  /** GPU only, scaled from the GPU values. */
  GPU("GPU"),

  /**
   * Battery temperature only, scaled from the battery values (T15c).
   *
   * Its own padded scale, like the single temperature modes: the battery sits
   * near 30–45 °C, so sharing the CPU/GPU range would flatten the line.
   */
  BAT("BAT"),

  /** Foreground frame rate on the fixed 0–120 scale. */
  FPS("FPS");

  /**
   * The next series in the selector's fixed cycle:
   * CPU+GPU → CPU → GPU → BAT → FPS → CPU+GPU.
   */
  fun next(): GraphSeries = when (this) {
    CPU_GPU -> CPU
    CPU -> GPU
    GPU -> BAT
    BAT -> FPS
    FPS -> CPU_GPU
  }

  companion object {
    /**
     * Restores a persisted selector value.
     *
     * @param raw the stored enum name, or null when nothing was saved
     * @return the matching series, or [CPU_GPU] for null/unknown so an old or
     *   corrupted preference never yields an empty graph
     */
    fun fromStored(raw: String?): GraphSeries =
      entries.firstOrNull { it.name == raw } ?: CPU_GPU
  }
}
