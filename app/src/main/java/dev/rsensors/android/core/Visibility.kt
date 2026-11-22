package dev.rsensors.android.core

/** The four things the pill and notification can show (ANDROID.md §3, §9 T15i). */
enum class VisibilityKind { CPUGPU, BAT, FPS, RAM }

/**
 * Which kinds the collapsed pill (and therefore the notification) renders.
 *
 * This is the single source of truth for the card's CPU/GPU / BAT / FPS / RAM
 * chips: the view asks [isOn] to paint a chip, and the service persists the
 * state it gets back from [toggle]. Pure Kotlin so the invariant below is
 * unit-tested rather than trusted to the UI.
 *
 * @property cpuGpu render the combined CPU/GPU temperature segment: the hotter
 *   of the two sensors, coloured by the worse of their levels
 * @property bat render the battery temperature segment as a first-class metric
 * @property fps render the foreground frame-rate segment
 * @property ram render the system RAM segment: used decimal GB, independent of
 *   the sensor reading
 */
data class PillVisibility(
  val cpuGpu: Boolean = true,
  val bat: Boolean = true,
  val fps: Boolean = true,
  val ram: Boolean = true,
) {
  /** True when [kind] is enabled. */
  fun isOn(kind: VisibilityKind): Boolean = when (kind) {
    VisibilityKind.CPUGPU -> cpuGpu
    VisibilityKind.BAT -> bat
    VisibilityKind.FPS -> fps
    VisibilityKind.RAM -> ram
  }

  /**
   * Flips [kind].
   *
   * Turning off the last enabled kind is a no-op: an empty pill is never a
   * valid state, so the last chip stays on instead of clearing the panel.
   */
  fun toggle(kind: VisibilityKind): PillVisibility {
    val next = when (kind) {
      VisibilityKind.CPUGPU -> copy(cpuGpu = !cpuGpu)
      VisibilityKind.BAT -> copy(bat = !bat)
      VisibilityKind.FPS -> copy(fps = !fps)
      VisibilityKind.RAM -> copy(ram = !ram)
    }
    return if (!next.cpuGpu && !next.bat && !next.fps && !next.ram) this else next
  }

  /**
   * Repairs a stored state that has every kind off.
   *
   * SharedPreferences can be written by an older build or a partial edit; an
   * all-off value would draw a pill with only the source dot, so it is mapped
   * to CPU/GPU only (the same fallback the last-chip rule produces).
   */
  fun normalised(): PillVisibility =
    if (!cpuGpu && !bat && !fps && !ram) copy(cpuGpu = true) else this
}
