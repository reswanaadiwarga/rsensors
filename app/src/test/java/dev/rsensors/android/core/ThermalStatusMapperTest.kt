package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping of the platform thermal-status integers to [ThermalStatus], mirroring
 * `PowerManager.THERMAL_STATUS_*` (ANDROID.md §8 T4).
 */
class ThermalStatusMapperTest {

  @Test
  fun `0 through 6 map to the seven severity levels`() {
    assertEquals(ThermalStatus.NONE, thermalStatusFromInt(0))
    assertEquals(ThermalStatus.LIGHT, thermalStatusFromInt(1))
    assertEquals(ThermalStatus.MODERATE, thermalStatusFromInt(2))
    assertEquals(ThermalStatus.SEVERE, thermalStatusFromInt(3))
    assertEquals(ThermalStatus.CRITICAL, thermalStatusFromInt(4))
    assertEquals(ThermalStatus.EMERGENCY, thermalStatusFromInt(5))
    assertEquals(ThermalStatus.SHUTDOWN, thermalStatusFromInt(6))
  }

  @Test
  fun `out-of-range values map to unknown`() {
    assertEquals(ThermalStatus.UNKNOWN, thermalStatusFromInt(-1))
    assertEquals(ThermalStatus.UNKNOWN, thermalStatusFromInt(7))
    assertEquals(ThermalStatus.UNKNOWN, thermalStatusFromInt(99))
  }
}
