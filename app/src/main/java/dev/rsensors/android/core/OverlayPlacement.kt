package dev.rsensors.android.core

/**
 * Position of the overlay's left edge when a drag is released (ANDROID.md §3).
 *
 * Pure geometry: no Android types, so the release rule is testable on the JVM.
 *
 * @param x the dragged window x in pixels
 * @param width the measured window width in pixels
 * @param screenWidth the display width in pixels
 * @param snapToEdges true to jump to the nearer side, false to stay put
 * @param edgeMargin the gap from the side edge used when snapping, in pixels
 * @return a left coordinate inside `0..maxX`, where
 *   `maxX = (screenWidth - width).coerceAtLeast(0)`
 *
 * With [snapToEdges] off, the exact dragged x is returned, clamped so the whole
 * window stays on screen. With it on, a window whose centre is left of the
 * screen centre lands at [edgeMargin]; the other half lands at
 * `maxX - edgeMargin`, matching the pre-setting snap.
 *
 * Degenerate screens collapse to 0: a window at least as wide as the display
 * has no travel, and a margin wider than the available travel cannot be
 * honoured without leaving the window partly off-screen.
 */
fun overlayReleaseX(
  x: Int,
  width: Int,
  screenWidth: Int,
  snapToEdges: Boolean,
  edgeMargin: Int,
): Int {
  val maxX = (screenWidth - width).coerceAtLeast(0)
  if (!snapToEdges) return x.coerceIn(0, maxX)
  if (edgeMargin > maxX) return 0
  val margin = edgeMargin.coerceIn(0, maxX)
  val target = if (x + width / 2 < screenWidth / 2) margin else maxX - margin
  return target.coerceIn(0, maxX)
}
