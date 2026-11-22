package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Notification badge label. */
class IconTest {

  private val now = 1_000L

  @Test
  fun `icon shows the cpu with its grade`() {
    assertEquals(IconSpec("60", Level.OK), iconSpec(Reading(now, cpu = 60.0), now))
    assertEquals(IconSpec("78", Level.WARN), iconSpec(Reading(now, cpu = 78.0), now))
    assertEquals(IconSpec("91", Level.HOT), iconSpec(Reading(now, cpu = 91.0), now))
  }

  @Test
  fun `icon falls back to a question mark`() {
    assertEquals(IconSpec("?", Level.UNKNOWN), iconSpec(null, now))
    assertEquals(IconSpec("?", Level.UNKNOWN), iconSpec(Reading(now, gpu = 44.0), now))
  }

  @Test
  fun `icon falls back when the reading is stale`() {
    val stale = Reading(now - STALE_AFTER_MS - 1, cpu = 60.0)
    assertEquals(IconSpec("?", Level.UNKNOWN), iconSpec(stale, now))
  }

  @Test
  fun `icon accepts a reading exactly at the stale threshold`() {
    val edge = Reading(now - STALE_AFTER_MS, cpu = 60.0)
    assertEquals(IconSpec("60", Level.OK), iconSpec(edge, now))
  }
}
