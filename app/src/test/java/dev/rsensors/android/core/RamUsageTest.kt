package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** System RAM fraction used by the expanded card's usage bar. */
class RamUsageTest {

  @Test
  fun `fraction is used over total`() {
    assertEquals(0.5f, RamUsage(4, 8).fraction, 0.0001f)
    assertEquals(0.25f, RamUsage(2, 8).fraction, 0.0001f)
  }

  @Test
  fun `negative used clamps to zero`() {
    assertEquals(0f, RamUsage(-1, 8).fraction, 0.0001f)
  }

  @Test
  fun `used above total clamps to one`() {
    assertEquals(1f, RamUsage(10, 8).fraction, 0.0001f)
  }

  @Test
  fun `zero total has no fraction`() {
    assertEquals(0f, RamUsage(0, 0).fraction, 0.0001f)
    assertEquals(0f, RamUsage(5, 0).fraction, 0.0001f)
    assertEquals(0f, RamUsage(5, -8).fraction, 0.0001f)
  }

  @Test
  fun `fraction boundary values stay in range`() {
    assertEquals(0f, RamUsage(0, 8).fraction, 0.0001f)
    assertEquals(1f, RamUsage(8, 8).fraction, 0.0001f)
  }
}
