// Independent acceptance checks for the persisted "Snap to edges" drag-release
// rule (feat/snap-toggle).
//
// Expected values are hand-derived from the task spec, never copied from the
// implementer's unit test or from overlayReleaseX output. The spec's rule:
//   maxX = screenWidth - width (never below 0)
//   on  -> nearer side: left-half drop lands at edgeMargin, the rest at
//          maxX - edgeMargin (margin clamped to 0..maxX)
//   off -> the exact drop x, clamped so the whole panel stays on screen
//   a panel at least as wide as the display has no travel -> 0
//   a margin wider than the travel cannot be honoured -> 0
//
// Compile against the real app core sources and run OverlayPlacementAcceptanceKt;
// it exits non-zero on any failure.

import dev.rsensors.android.core.overlayReleaseX
import kotlin.system.exitProcess

private var failures = 0
private var checks = 0

private fun expect(condition: Boolean, message: String) {
  checks++
  if (!condition) {
    failures++
    println("FAIL: $message")
  } else {
    println("ok: $message")
  }
}

private fun expectEquals(actual: Int, expected: Int, label: String) {
  expect(actual == expected, "$label: expected <$expected>, got <$actual>")
}

/** Overlay geometry shared by most cases: 1080-wide screen, 220-wide panel. */
private const val SCREEN = 1080
private const val PANEL = 220
private const val MARGIN = 8
private const val MAX_X = SCREEN - PANEL // 860

private fun on(x: Int, width: Int = PANEL, screen: Int = SCREEN, margin: Int = MARGIN) =
  overlayReleaseX(x = x, width = width, screenWidth = screen, snapToEdges = true, edgeMargin = margin)

private fun off(x: Int, width: Int = PANEL, screen: Int = SCREEN, margin: Int = MARGIN) =
  overlayReleaseX(x = x, width = width, screenWidth = screen, snapToEdges = false, edgeMargin = margin)

fun main() {
  // --- Snap on: left half lands at the 8 px margin -------------------------
  expectEquals(on(100), MARGIN, "on, left half (x=100) -> margin")
  expectEquals(on(0), MARGIN, "on, at the left edge (x=0) -> margin")
  expectEquals(on(MAX_X / 2 - 200), MARGIN, "on, clearly left (x=230) -> margin")

  // --- Snap on: right half lands at maxX - margin --------------------------
  expectEquals(on(700), MAX_X - MARGIN, "on, right half (x=700) -> maxX - margin")
  expectEquals(on(MAX_X), MAX_X - MARGIN, "on, already at maxX (x=860) -> maxX - margin")
  expectEquals(on(SCREEN / 2), MAX_X - MARGIN, "on, screen-centre drop -> right side")

  // A drop whose panel centre is exactly the screen centre is a tie; the rule
  // as specified resolves to the right side (nearer is ambiguous, but the
  // release must still land on an edge target, never stay free).
  expectEquals(on(SCREEN / 2 - PANEL / 2), MAX_X - MARGIN, "on, exact centre tie -> right target")

  // --- Snap off: exact drop position, including centre/near-edge -----------
  expectEquals(off(500), 500, "off, centre drop (x=500) stays exact")
  expectEquals(off(0), 0, "off, at 0 stays 0")
  expectEquals(off(MARGIN), MARGIN, "off, near-left (x=8) stays exact")
  expectEquals(off(MAX_X - MARGIN), MAX_X - MARGIN, "off, near-right (x=852) stays exact")
  expectEquals(off(MAX_X), MAX_X, "off, at maxX stays exact")
  expectEquals(off(430), 430, "off, exact-centre (x=430) stays exact")

  // --- Clamp below 0 and above maxX, for both states ----------------------
  expectEquals(off(-50), 0, "off, below 0 clamps to 0")
  expectEquals(off(2000), MAX_X, "off, above maxX clamps to maxX")
  expectEquals(on(-50), MARGIN, "on, below 0 is left half -> margin")
  expectEquals(on(2000), MAX_X - MARGIN, "on, above maxX is right half -> maxX - margin")

  // --- Degenerate: panel at least as wide as the screen -> 0 --------------
  expectEquals(on(50, width = 100, screen = 100), 0, "on, width == screen -> 0")
  expectEquals(off(50, width = 100, screen = 100), 0, "off, width == screen -> 0")
  expectEquals(on(50, width = 150, screen = 100), 0, "on, width > screen -> 0")
  expectEquals(off(50, width = 150, screen = 100), 0, "off, width > screen -> 0")
  expectEquals(on(500, width = SCREEN), 0, "on, panel exactly screen-wide -> 0")
  expectEquals(off(500, width = SCREEN), 0, "off, panel exactly screen-wide -> 0")
  expectEquals(off(-5, width = 100, screen = 100), 0, "off, degenerate + below 0 -> 0")
  expectEquals(off(200, width = 100, screen = 100), 0, "off, degenerate + above -> 0")

  // --- Degenerate: margin wider than the travel -> 0 ----------------------
  // screenWidth 120 - width 100 = maxX 20, so a 50 px margin cannot fit.
  expectEquals(on(0, width = 100, screen = 120, margin = 50), 0, "on, margin > maxX (left) -> 0")
  expectEquals(on(100, width = 100, screen = 120, margin = 50), 0, "on, margin > maxX (right) -> 0")
  expectEquals(on(60, width = 100, screen = 120, margin = 50), 0, "on, margin > maxX (centre) -> 0")
  // Off ignores the margin entirely, so it still lands on the clamped drop.
  expectEquals(off(15, width = 100, screen = 120, margin = 50), 15, "off, margin > maxX ignored, x=15")
  expectEquals(off(50, width = 100, screen = 120, margin = 50), 20, "off, margin > maxX ignored, x=50 clamps to maxX 20")

  // --- Negative margin: never pushes the panel off-screen -----------------
  expectEquals(on(100, margin = -8), 0, "on, negative margin left half -> 0")
  expectEquals(on(700, margin = -8), MAX_X, "on, negative margin right half -> maxX")
  expectEquals(off(500, margin = -8), 500, "off, negative margin does not change free drop")

  // --- screenWidth / width boundaries -------------------------------------
  // width == screenWidth - 2*margin -> maxX 16, both halves land at 8.
  expectEquals(on(0, width = 1064), MARGIN, "on, width=screen-2margin left -> 8")
  expectEquals(on(100, width = 1064), MARGIN, "on, width=screen-2margin right -> 8")
  // width == screenWidth - margin -> maxX 8; left -> 8, right -> 0 (both put
  // the panel's near edge 8 px inside the screen).
  expectEquals(on(0, width = 1072), MARGIN, "on, width=screen-margin left -> 8")
  expectEquals(on(100, width = 1072), 0, "on, width=screen-margin right -> 0")
  // width a hair under the screen: maxX 1, margin 8 > travel -> 0.
  expectEquals(on(0, width = 1079), 0, "on, width=screen-1 (margin>travel) -> 0")
  expectEquals(off(0, width = 1079), 0, "off, width=screen-1 x=0 -> 0")
  expectEquals(off(1, width = 1079), 1, "off, width=screen-1 x=1 -> 1")
  expectEquals(off(5, width = 1079), 1, "off, width=screen-1 x=5 clamps to 1")

  // --- On and off differ for a centre drop --------------------------------
  expect(off(500) != on(500), "on and off differ for a centre drop (500)")
  expectEquals(off(500), 500, "centre drop off result")
  expectEquals(on(500), MAX_X - MARGIN, "centre drop on result")

  // --- Invariants over a sweep (independent oracle) -----------------------
  var sweepOk = true
  for (x in -200..1200 step 37) {
    val offValue = off(x)
    val onValue = on(x)
    if (offValue != x.coerceIn(0, MAX_X)) {
      sweepOk = false
      println("FAIL: sweep off x=$x expected ${x.coerceIn(0, MAX_X)} got $offValue")
    }
    if (onValue != MARGIN && onValue != MAX_X - MARGIN) {
      sweepOk = false
      println("FAIL: sweep on x=$x expected edge target got $onValue")
    }
    if (offValue !in 0..MAX_X || onValue !in 0..MAX_X) {
      sweepOk = false
      println("FAIL: sweep x=$x left the 0..$MAX_X range (off=$offValue on=$onValue)")
    }
  }
  expect(sweepOk, "sweep: off clamps exactly and on always lands on an edge target")

  // Negative margins and odd widths must never escape 0..maxX either.
  var boundsOk = true
  for (width in 0..1200 step 53) {
    val maxX = (SCREEN - width).coerceAtLeast(0)
    for (margin in -20..60 step 7) {
      for (x in -100..1200 step 131) {
        val onValue = on(x, width = width, margin = margin)
        val offValue = off(x, width = width, margin = margin)
        if (onValue !in 0..maxX || offValue != x.coerceIn(0, maxX)) {
          boundsOk = false
          println(
            "FAIL: bounds width=$width margin=$margin x=$x expected off=${x.coerceIn(0, maxX)} on in 0..$maxX got off=$offValue on=$onValue",
          )
        }
      }
    }
  }
  expect(boundsOk, "bounds: on/off stay inside 0..maxX for odd widths and negative margins")

  println("checks=$checks failures=$failures")
  if (failures != 0) {
    println("ACCEPTANCE FAILED")
    exitProcess(1)
  }
  println("ACCEPTANCE PASSED")
}