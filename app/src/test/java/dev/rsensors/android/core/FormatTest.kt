package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatting and tooltip text. */
class FormatTest {

  @Test
  fun `missing temperature is a dash`() {
    assertEquals(DASH, fmtTemp(null))
    assertEquals("66°", fmtTemp(66.4))
    assertEquals("67°", fmtTemp(66.6))
  }

  @Test
  fun `fmt fps is a plain whole number`() {
    assertEquals(DASH, fmtFps(null))
    assertEquals("60", fmtFps(60.0))
    assertEquals("60", fmtFps(59.6))
    assertEquals("0", fmtFps(0.0))
    assertEquals("121", fmtFps(120.6))
  }

  @Test
  fun `fmt ram shows one-decimal decimal gigabytes`() {
    assertEquals("4.5 / 7.8 GB", fmtRam(4_500_000_000L, 7_800_000_000L))
    assertEquals("0.0 / 8.0 GB", fmtRam(0L, 8_000_000_000L))
  }

  @Test
  fun `fmt ram is a dash when unavailable`() {
    assertEquals(DASH, fmtRam(null, 8_000_000_000L))
    assertEquals(DASH, fmtRam(4_500_000_000L, null))
    assertEquals(DASH, fmtRam(4_500_000_000L, 0L))
    assertEquals(DASH, fmtRam(4_500_000_000L, -1L))
  }

  @Test
  fun `fmt age handles every band boundary`() {
    assertEquals("just now", fmtAge(-1_000L))
    assertEquals("just now", fmtAge(0L))
    assertEquals("just now", fmtAge(2_999L))
    assertEquals("3s ago", fmtAge(3_000L))
    assertEquals("59s ago", fmtAge(59_999L))
    assertEquals("1m 00s ago", fmtAge(60_000L))
    assertEquals("1m 05s ago", fmtAge(65_000L))
    assertEquals("59m 59s ago", fmtAge(3_599_000L))
    assertEquals("1h 00m ago", fmtAge(3_600_000L))
    assertEquals("1h 05m ago", fmtAge(3_905_000L))
  }

  @Test
  fun `tooltip reports both temperatures and age`() {
    val now = 100_000L
    val text = tooltip(Reading(now - 5_000L, cpu = 66.2, gpu = 44.4), now, source = "dumpsys")
    assertTrue(text, text.startsWith("Rsensors · dumpsys"))
    assertTrue(text, text.contains("CPU 66°C"))
    assertTrue(text, text.contains("GPU 44°C"))
    assertTrue(text, text.contains("5s ago"))
    assertTrue(text.length <= 127)
  }

  @Test
  fun `tooltip says just now for a fresh reading`() {
    val now = 100_000L
    assertTrue(tooltip(Reading(now - 1_000L, cpu = 60.0), now).contains("just now"))
  }

  @Test
  fun `tooltip reports staleness in seconds`() {
    val now = 100_000L
    val text = tooltip(Reading(now - STALE_AFTER_MS - 500L, cpu = 60.0), now)
    assertTrue(text, text.contains("no reading for 15s"))
  }

  @Test
  fun `tooltip explains a missing cpu without shizuku`() {
    val text = tooltip(Reading(1_000L, gpu = 44.0), 1_000L, shizukuRunning = false)
    assertTrue(text, text.contains("CPU n/a"))
    assertTrue(text, text.contains("needs Shizuku"))
    assertFalse(text, text.contains("needs admin"))
  }

  @Test
  fun `tooltip omits the shizuku hint when it runs`() {
    val text = tooltip(Reading(1_000L, gpu = 44.0), 1_000L, shizukuRunning = true)
    assertTrue(text, text.contains("CPU n/a"))
    assertFalse(text, text.contains("needs Shizuku"))
  }

  @Test
  fun `tooltip says waiting before the first reading`() {
    assertTrue(tooltip(null, 1_000L).contains("waiting"))
    assertEquals("Rsensors · waiting for a reading", tooltip(null, 1_000L, source = ""))
  }

  @Test
  fun `tooltip prefers the error over the reading`() {
    val text = tooltip(Reading(1_000L, cpu = 60.0), 1_000L, source = "", error = "EACCES")
    assertEquals("Rsensors · EACCES", text)
  }

  @Test
  fun `blank source gives a single brand`() {
    val text = tooltip(Reading(1_000L, cpu = 60.0), 1_000L, source = "")
    assertTrue(text, text.startsWith("Rsensors · CPU 60°C"))
  }

  @Test
  fun `tooltip defaults keep a single brand`() {
    val now = 100_000L
    val text = tooltip(Reading(now, cpu = 60.0, gpu = 50.0), now)
    assertTrue(text, text.startsWith("Rsensors"))
    assertEquals(text, 1, Regex("Rsensors").findAll(text).count())
    assertFalse(text, text.contains("Rsensors · Rsensors"))
  }

  @Test
  fun `tooltip is truncated to 127 characters`() {
    val text = tooltip(null, 1_000L, error = "x".repeat(200))
    assertEquals(127, text.length)
  }
}
