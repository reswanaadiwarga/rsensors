package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Drag-release geometry for the overlay (ANDROID.md §3 "Drag").
 *
 * Expected values are hand-derived from the spec's rule, never copied from the
 * helper: `maxX = screenWidth - width`, the left half lands on `edgeMargin`, the
 * right half on `maxX - edgeMargin`, and both states clamp into `0..maxX`.
 * A typical phone is 1080 px wide with a 220 px panel, so `maxX` is 860 and the
 * 8 dp margin is passed in as 8 px by these tests.
 */
class OverlayPlacementTest {

  @Test
  fun `off keeps the exact drop position`() {
    assertEquals(
      500,
      overlayReleaseX(x = 500, width = 220, screenWidth = 1080, snapToEdges = false, edgeMargin = 8),
    )
    assertEquals(
      700,
      overlayReleaseX(x = 700, width = 220, screenWidth = 1080, snapToEdges = false, edgeMargin = 8),
    )
  }

  @Test
  fun `off clamps an off-screen drop fully on screen`() {
    assertEquals(
      0,
      overlayReleaseX(x = -50, width = 220, screenWidth = 1080, snapToEdges = false, edgeMargin = 8),
    )
    assertEquals(
      860,
      overlayReleaseX(x = 2000, width = 220, screenWidth = 1080, snapToEdges = false, edgeMargin = 8),
    )
  }

  @Test
  fun `on snaps the left half to the edge margin`() {
    assertEquals(
      8,
      overlayReleaseX(x = 100, width = 220, screenWidth = 1080, snapToEdges = true, edgeMargin = 8),
    )
    // At the left edge the centre is still in the left half.
    assertEquals(
      8,
      overlayReleaseX(x = 0, width = 220, screenWidth = 1080, snapToEdges = true, edgeMargin = 8),
    )
  }

  @Test
  fun `on snaps the right half to maxX minus the edge margin`() {
    assertEquals(
      852,
      overlayReleaseX(x = 700, width = 220, screenWidth = 1080, snapToEdges = true, edgeMargin = 8),
    )
    // A release already at maxX still lands the same 8 px from the edge.
    assertEquals(
      852,
      overlayReleaseX(x = 860, width = 220, screenWidth = 1080, snapToEdges = true, edgeMargin = 8),
    )
  }

  @Test
  fun `on clamps a release outside the drag range into 0 to maxX`() {
    // Left of the screen is still the left half, so it snaps to the margin.
    assertEquals(
      8,
      overlayReleaseX(x = -50, width = 220, screenWidth = 1080, snapToEdges = true, edgeMargin = 8),
    )
    // Past the right edge is still the right half, so it snaps in from maxX.
    assertEquals(
      852,
      overlayReleaseX(x = 2000, width = 220, screenWidth = 1080, snapToEdges = true, edgeMargin = 8),
    )
  }

  @Test
  fun `a panel as wide as the screen has no travel`() {
    assertEquals(
      0,
      overlayReleaseX(x = 50, width = 100, screenWidth = 100, snapToEdges = false, edgeMargin = 8),
    )
    assertEquals(
      0,
      overlayReleaseX(x = 50, width = 100, screenWidth = 100, snapToEdges = true, edgeMargin = 8),
    )
    // A panel wider than the screen is the same degenerate case.
    assertEquals(
      0,
      overlayReleaseX(x = 50, width = 150, screenWidth = 100, snapToEdges = true, edgeMargin = 8),
    )
  }

  @Test
  fun `a margin wider than the travel falls back to the left edge`() {
    // screenWidth 120 - width 100 = maxX 20, so a 50 px margin cannot fit.
    assertEquals(
      0,
      overlayReleaseX(x = 0, width = 100, screenWidth = 120, snapToEdges = true, edgeMargin = 50),
    )
    assertEquals(
      0,
      overlayReleaseX(x = 100, width = 100, screenWidth = 120, snapToEdges = true, edgeMargin = 50),
    )
  }

  @Test
  fun `the switch changes the result for a centre drop`() {
    val off = overlayReleaseX(
      x = 500,
      width = 220,
      screenWidth = 1080,
      snapToEdges = false,
      edgeMargin = 8,
    )
    val on = overlayReleaseX(
      x = 500,
      width = 220,
      screenWidth = 1080,
      snapToEdges = true,
      edgeMargin = 8,
    )
    assertEquals(500, off)
    assertEquals(852, on)
    assertNotEquals(off, on)
  }
}
