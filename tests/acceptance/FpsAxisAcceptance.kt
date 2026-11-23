// Independent acceptance checks for T19b-5 dynamic FPS axis ceiling.
//
// Expected values are hand-derived from the task spec:
//   - FPS_AXIS_MAX is the baseline 120.
//   - fpsAxisMax(null | NaN | <= 0 | <= 120) = 120; a peak above 120 is
//     rounded UP to the next 30 fps step (121/144 -> 150, 150 -> 150,
//     151/165 -> 180, 180 -> 180, 240 -> 240), capped at 500, +Infinity -> 500.
//   - fpsFraction(fps, axis) = fps / axis clamped to 0..1; a non-positive or
//     NaN axis falls back to 120.
// They are never copied from the implementation's unit tests or from running
// the implementation.
//
// Compile against `core/SessionChart.kt` (and the compiled core classes) and
// run FpsAxisAcceptanceKt; it exits non-zero on any failure. No device needed.

import dev.rsensors.android.core.FPS_AXIS_MAX
import dev.rsensors.android.core.fpsAxisMax
import dev.rsensors.android.core.fpsFraction
import kotlin.math.abs
import kotlin.system.exitProcess

private var checks = 0
private var failures = 0

private fun expect(condition: Boolean, label: String) {
  checks++
  if (condition) {
    println("ok: $label")
  } else {
    failures++
    println("FAIL: $label")
  }
}

private fun expectAxis(peak: Double?, expected: Double, label: String) {
  val actual = fpsAxisMax(peak)
  expect(abs(actual - expected) <= 1e-9, "$label: expected <$expected>, got <$actual>")
}

private fun expectFraction(actual: Float, expected: Float, label: String) {
  expect(abs(actual - expected) <= 1e-4f, "$label: expected <$expected>, got <$actual>")
}

fun main() {
  // Baseline constant itself is part of the contract.
  expect(FPS_AXIS_MAX == 120.0, "FPS_AXIS_MAX is the 120 baseline, got $FPS_AXIS_MAX")

  // --- fpsAxisMax: no usable peak keeps 120 -------------------------------
  expectAxis(null, 120.0, "null peak -> 120")
  expectAxis(Double.NaN, 120.0, "NaN peak -> 120")
  expectAxis(0.0, 120.0, "zero peak -> 120")
  expectAxis(-5.0, 120.0, "negative peak -> 120")
  expectAxis(Double.NEGATIVE_INFINITY, 120.0, "-Infinity peak -> 120")
  expectAxis(1.0, 120.0, "1 fps peak -> 120")
  expectAxis(60.0, 120.0, "60 fps peak -> 120")
  expectAxis(119.0, 120.0, "119 fps peak -> 120")
  expectAxis(120.0, 120.0, "120 fps peak -> 120 (baseline is inclusive)")

  // --- fpsAxisMax: round up to the next 30 fps step ----------------------
  expectAxis(120.5, 150.0, "120.5 fps peak rounds up -> 150")
  expectAxis(121.0, 150.0, "121 fps peak -> 150")
  expectAxis(135.0, 150.0, "135 fps peak -> 150")
  expectAxis(144.0, 150.0, "144 fps peak -> 150")
  expectAxis(150.0, 150.0, "150 fps peak stays 150 (30 fps boundary)")
  expectAxis(150.5, 180.0, "150.5 fps peak rounds up -> 180")
  expectAxis(151.0, 180.0, "151 fps peak -> 180")
  expectAxis(165.0, 180.0, "165 fps peak -> 180")
  expectAxis(180.0, 180.0, "180 fps peak stays 180")
  expectAxis(181.0, 210.0, "181 fps peak -> 210")
  expectAxis(240.0, 240.0, "240 fps peak stays 240")
  expectAxis(241.0, 270.0, "241 fps peak -> 270")
  expectAxis(300.0, 300.0, "300 fps peak stays 300")
  expectAxis(301.0, 330.0, "301 fps peak -> 330")
  expectAxis(480.0, 480.0, "480 fps peak stays 480")

  // --- fpsAxisMax: cap at 500 --------------------------------------------
  expectAxis(500.0, 500.0, "500 fps peak stays 500 (cap)")
  expectAxis(501.0, 500.0, "501 fps peak -> 500 (cap)")
  expectAxis(1_000.0, 500.0, "1000 fps peak -> 500 (cap)")
  expectAxis(Double.POSITIVE_INFINITY, 500.0, "+Infinity peak -> 500")

  // --- fpsAxisMax: monotonic and on-grid ----------------------------------
  var previous = 120.0
  var monotonic = true
  var onGrid = true
  var value = 120.0
  while (value <= 500.0) {
    val axis = fpsAxisMax(value)
    if (axis < previous) monotonic = false
    if (axis > 500.0) onGrid = false
    // Every non-capped plateau top is a 30 fps multiple (or the cap).
    if (axis < 500.0 && abs(axis % 30.0) > 1e-9) onGrid = false
    previous = axis
    value += 0.5
  }
  expect(monotonic, "axis is monotonic non-decreasing over 120..500")
  expect(onGrid, "every returned axis is a 30 fps multiple or the 500 cap")

  // --- fpsFraction: default axis is 120 -----------------------------------
  expectFraction(fpsFraction(0.0), 0f, "fpsFraction(0) = 0")
  expectFraction(fpsFraction(30.0), 0.25f, "fpsFraction(30) = 0.25")
  expectFraction(fpsFraction(60.0), 0.5f, "fpsFraction(60) = 0.5")
  expectFraction(fpsFraction(120.0), 1f, "fpsFraction(120) = 1")
  expectFraction(fpsFraction(-10.0), 0f, "fpsFraction(-10) clamps to 0")
  expectFraction(fpsFraction(240.0), 1f, "fpsFraction(240) on default axis clamps to 1")

  // --- fpsFraction: supplied axes ----------------------------------------
  expectFraction(fpsFraction(0.0, 150.0), 0f, "fpsFraction(0, 150) = 0")
  expectFraction(fpsFraction(75.0, 150.0), 0.5f, "fpsFraction(75, 150) = 0.5")
  expectFraction(fpsFraction(144.0, 150.0), 0.96f, "fpsFraction(144, 150) = 0.96")
  expectFraction(fpsFraction(150.0, 150.0), 1f, "fpsFraction(150, 150) = 1")
  expectFraction(fpsFraction(151.0, 150.0), 1f, "fpsFraction(151, 150) clamps to 1")

  expectFraction(fpsFraction(90.0, 180.0), 0.5f, "fpsFraction(90, 180) = 0.5")
  expectFraction(fpsFraction(165.0, 180.0), 11f / 12f, "fpsFraction(165, 180) = 0.9167")
  expectFraction(fpsFraction(180.0, 180.0), 1f, "fpsFraction(180, 180) = 1")

  expectFraction(fpsFraction(120.0, 240.0), 0.5f, "fpsFraction(120, 240) = 0.5")
  expectFraction(fpsFraction(240.0, 240.0), 1f, "fpsFraction(240, 240) = 1")
  expectFraction(fpsFraction(250.0, 500.0), 0.5f, "fpsFraction(250, 500) = 0.5")
  expectFraction(fpsFraction(500.0, 500.0), 1f, "fpsFraction(500, 500) = 1")

  // --- fpsFraction: unusable axis falls back to the 120 baseline ----------
  expectFraction(fpsFraction(60.0, 0.0), 0.5f, "axis 0 falls back to 120: fpsFraction(60) = 0.5")
  expectFraction(fpsFraction(60.0, -30.0), 0.5f, "negative axis falls back to 120: fpsFraction(60) = 0.5")
  expectFraction(fpsFraction(60.0, Double.NaN), 0.5f, "NaN axis falls back to 120: fpsFraction(60) = 0.5")
  expectFraction(fpsFraction(240.0, 0.0), 1f, "axis 0 fallback clamps fpsFraction(240) to 1")

  println()
  println(if (failures == 0) "PASS: $checks checks" else "FAILED: $failures of $checks checks")
  if (failures != 0) exitProcess(1)
}
