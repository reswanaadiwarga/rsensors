package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staleness rules for [Reading] (ANDROID.md §3). */
class ReadingTest {

  @Test
  fun `age clamps a backwards clock to zero`() {
    assertEquals(0L, Reading(t = 1_000L).ageMillis(500L))
    assertEquals(0L, Reading(t = 1_000L).ageMillis(1_000L))
    assertEquals(250L, Reading(t = 1_000L).ageMillis(1_250L))
  }

  @Test
  fun `exactly the stale threshold is not stale`() {
    val reading = Reading(t = 1_000L)
    assertFalse(reading.stale(1_000L + STALE_AFTER_MS))
    assertTrue(reading.stale(1_000L + STALE_AFTER_MS + 1))
  }

  @Test
  fun `stale honours a custom threshold`() {
    val reading = Reading(t = 1_000L)
    assertFalse(reading.stale(1_001L, afterMillis = 1L))
    assertTrue(reading.stale(1_002L, afterMillis = 1L))
  }

  @Test
  fun `defaults describe a missing sample`() {
    val reading = Reading(t = 0L)
    assertEquals(null, reading.cpu)
    assertEquals(null, reading.cpuMax)
    assertEquals(emptyList<Double>(), reading.cores)
    assertEquals(null, reading.gpu)
    assertEquals(null, reading.gpuHot)
    assertEquals(null, reading.battery)
    assertEquals(ThermalStatus.UNKNOWN, reading.thermalStatus)
    assertEquals(emptyList<Pair<String, Double>>(), reading.board)
    assertEquals("", reading.sourceName)
  }

  @Test
  fun `stale threshold is fifteen seconds in millis`() {
    assertEquals(15_000L, STALE_AFTER_MS)
  }
}
