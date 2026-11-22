package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Battery temperature conversion from ANDROID.md §8 T4.
 *
 * `BatteryManager.EXTRA_TEMPERATURE` is tenths of a degree Celsius and uses
 * [Int.MIN_VALUE] as the platform's "unknown" sentinel.
 */
class BatteryTemperatureTest {

  @Test
  fun `tenths are converted to degrees celsius`() {
    assertEquals(33.2, parseBatteryTemperature(332)!!, 1e-9)
  }

  @Test
  fun `zero is a real temperature and is not dropped`() {
    assertEquals(0.0, parseBatteryTemperature(0)!!, 0.0)
  }

  @Test
  fun `negative temperatures are passed through`() {
    assertEquals(-5.0, parseBatteryTemperature(-50)!!, 1e-9)
  }

  @Test
  fun `whole degrees stay exact`() {
    assertEquals(100.0, parseBatteryTemperature(1000)!!, 1e-9)
  }

  @Test
  fun `the missing sentinel becomes null`() {
    assertNull(parseBatteryTemperature(Int.MIN_VALUE))
  }

  @Test
  fun `an absent extra becomes null`() {
    assertNull(parseBatteryTemperature(null))
  }
}
