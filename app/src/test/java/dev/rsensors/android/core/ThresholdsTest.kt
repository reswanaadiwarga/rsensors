package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Threshold table from ANDROID.md §3, plus the combined CPU/GPU grade (T15i). */
class ThresholdsTest {

  @Test
  fun `level hits warn then hot`() {
    assertEquals(Level.UNKNOWN, level(null, CPU_WARN, CPU_HOT))
    assertEquals(Level.OK, level(74.9, CPU_WARN, CPU_HOT))
    assertEquals(Level.WARN, level(75.0, CPU_WARN, CPU_HOT))
    assertEquals(Level.WARN, level(84.9, CPU_WARN, CPU_HOT))
    assertEquals(Level.HOT, level(85.0, CPU_WARN, CPU_HOT))
  }

  @Test
  fun `level bands stay warn up to the hot boundary`() {
    assertEquals(Level.OK, level(74.999, CPU_WARN, CPU_HOT))
    assertEquals(Level.WARN, level(84.999, CPU_WARN, CPU_HOT))
    assertEquals(Level.HOT, level(85.0, CPU_WARN, CPU_HOT))
  }

  @Test
  fun `constants match the spec table`() {
    assertEquals(75.0, CPU_WARN, 0.0)
    assertEquals(85.0, CPU_HOT, 0.0)
    assertEquals(70.0, GPU_WARN, 0.0)
    assertEquals(83.0, GPU_HOT, 0.0)
    assertEquals(42.0, BATTERY_WARN, 0.0)
    assertEquals(45.0, BATTERY_HOT, 0.0)
  }

  @Test
  fun `fps constants match the spec bands`() {
    assertEquals(50.0, FPS_WARN, 0.0)
    assertEquals(30.0, FPS_HOT, 0.0)
  }

  @Test
  fun `fps grades hot below warn`() {
    // The FPS scale runs the other way round: low frames are the problem, and
    // the 30 / 50 boundaries belong to the better band (ANDROID.md §3, §9).
    assertEquals(Level.UNKNOWN, fpsLevel(null))
    assertEquals(Level.HOT, fpsLevel(29.9))
    assertEquals(Level.WARN, fpsLevel(30.0))
    assertEquals(Level.WARN, fpsLevel(49.9))
    assertEquals(Level.OK, fpsLevel(50.0))
    assertEquals(Level.HOT, fpsLevel(0.0))
    assertEquals(Level.OK, fpsLevel(120.0))
  }

  @Test
  fun `thermal status maps to the colour scale`() {
    assertEquals(Level.OK, thermalLevel(ThermalStatus.NONE))
    assertEquals(Level.OK, thermalLevel(ThermalStatus.LIGHT))
    assertEquals(Level.WARN, thermalLevel(ThermalStatus.MODERATE))
    assertEquals(Level.HOT, thermalLevel(ThermalStatus.SEVERE))
    assertEquals(Level.HOT, thermalLevel(ThermalStatus.CRITICAL))
    assertEquals(Level.HOT, thermalLevel(ThermalStatus.EMERGENCY))
    assertEquals(Level.HOT, thermalLevel(ThermalStatus.SHUTDOWN))
    assertEquals(Level.UNKNOWN, thermalLevel(ThermalStatus.UNKNOWN))
  }

  @Test
  fun `worstLevel is unknown only when both are unknown`() {
    assertEquals(Level.UNKNOWN, worstLevel(Level.UNKNOWN, Level.UNKNOWN))
    assertEquals(Level.OK, worstLevel(Level.OK, Level.UNKNOWN))
    assertEquals(Level.OK, worstLevel(Level.UNKNOWN, Level.OK))
    assertEquals(Level.WARN, worstLevel(Level.WARN, Level.UNKNOWN))
    assertEquals(Level.WARN, worstLevel(Level.UNKNOWN, Level.WARN))
    assertEquals(Level.HOT, worstLevel(Level.HOT, Level.UNKNOWN))
    assertEquals(Level.HOT, worstLevel(Level.UNKNOWN, Level.HOT))
  }

  @Test
  fun `worstLevel returns the worse of two measurements`() {
    assertEquals(Level.OK, worstLevel(Level.OK, Level.OK))
    assertEquals(Level.WARN, worstLevel(Level.OK, Level.WARN))
    assertEquals(Level.WARN, worstLevel(Level.WARN, Level.OK))
    assertEquals(Level.WARN, worstLevel(Level.WARN, Level.WARN))
    assertEquals(Level.HOT, worstLevel(Level.OK, Level.HOT))
    assertEquals(Level.HOT, worstLevel(Level.HOT, Level.OK))
    assertEquals(Level.HOT, worstLevel(Level.WARN, Level.HOT))
    assertEquals(Level.HOT, worstLevel(Level.HOT, Level.WARN))
    assertEquals(Level.HOT, worstLevel(Level.HOT, Level.HOT))
  }
}
