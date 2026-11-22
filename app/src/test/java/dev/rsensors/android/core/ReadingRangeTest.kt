package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two-minute min/max for the card's CPU, GPU and BAT rows (ANDROID.md §3,
 * §9 T15i). Missing samples never widen or shrink the range, and an empty
 * window is honestly two nulls.
 */
class ReadingRangeTest {

  @Test
  fun `empty history is two nulls`() {
    val range = emptyList<Reading>().valueRange { it.cpu }
    assertEquals(ValueRange(min = null, max = null), range)
  }

  @Test
  fun `null values are ignored`() {
    val range = listOf(
      Reading(1_000L, cpu = null),
      Reading(1_001L, cpu = 41.0),
      Reading(1_002L, cpu = null),
      Reading(1_003L, cpu = 39.0),
    ).valueRange { it.cpu }
    assertEquals(ValueRange(min = 39.0, max = 41.0), range)
  }

  @Test
  fun `a single value is both min and max`() {
    val range = listOf(Reading(1_000L, gpu = 50.0)).valueRange { it.gpu }
    assertEquals(ValueRange(min = 50.0, max = 50.0), range)
  }

  @Test
  fun `min and max span every sample`() {
    val range = listOf(
      Reading(1_000L, battery = 34.0),
      Reading(1_001L, battery = 36.5),
      Reading(1_002L, battery = 33.25),
      Reading(1_003L, battery = 35.0),
    ).valueRange { it.battery }
    assertEquals(ValueRange(min = 33.25, max = 36.5), range)
  }

  @Test
  fun `the selector chooses the field`() {
    val readings = listOf(
      Reading(1_000L, cpu = 60.0, gpu = 80.0),
      Reading(1_001L, cpu = 70.0, gpu = 50.0),
    )
    assertEquals(ValueRange(min = 60.0, max = 70.0), readings.valueRange { it.cpu })
    assertEquals(ValueRange(min = 50.0, max = 80.0), readings.valueRange { it.gpu })
  }
}
