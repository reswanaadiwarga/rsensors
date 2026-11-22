package dev.rsensors.android.ui

/**
 * Overlay and session-graph palette (ANDROID.md §3).
 *
 * Plain ARGB [Int] values rather than colour resources: the overlay builds its
 * own `Paint`s, the badge rasteriser runs outside an activity, and keeping the
 * numbers here makes the palette testable and impossible to theme-drift.
 *
 * Levels map as [dev.rsensors.android.core.Level] OK → [green], WARN →
 * [amber], HOT → [red], UNKNOWN → [gray].
 */
object Palette {
  /** Overlay background and badge border. */
  val dark: Int = 0xFF090D13.toInt()

  /** Primary digits. */
  val white: Int = 0xFFF5F8FC.toInt()

  /** OK band. */
  val green: Int = 0xFF2EA043.toInt()

  /** WARN band; [amberText] keeps digits legible on it. */
  val amber: Int = 0xFFD29922.toInt()

  /** Text drawn on an [amber] badge. */
  val amberText: Int = 0xFF0A0E14.toInt()

  /** HOT band. */
  val red: Int = 0xFFDA3633.toInt()

  /** Battery temperature line in the session graph (T13). */
  val blue: Int = 0xFF2F81F7.toInt()

  /**
   * Foreground frame rate: the session graph's FPS line and right-hand axis
   * (T14), and the card sparkline's FPS series (T15b).
   */
  val fps: Int = 0xFFA371F7.toInt()

  /** UNKNOWN band: no trustworthy measurement. */
  val gray: Int = 0xFF465260.toInt()
}
