package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collapsed-pill segments (ANDROID.md §3, §9 T15i): one combined CPU/GPU
 * metric coloured by the worse of the two sensors, plus first-class BAT, FPS
 * and RAM segments in that order.
 */
class StripTest {

  @Test
  fun `strip pairs the combined label with the hotter sensor`() {
    val strip = stripSegments(Reading(1_000L, cpu = 80.0, gpu = 50.0), 1_002L)
    assertEquals(
      listOf(
        Segment("CPU/GPU", SegmentKind.LABEL),
        Segment("80°", SegmentKind.WARN),
      ),
      strip.segments,
    )
    assertFalse(strip.dim)
  }

  @Test
  fun `strip picks the gpu when it is the hotter sensor`() {
    val strip = stripSegments(Reading(1_000L, cpu = 60.0, gpu = 75.0), 1_000L)
    assertEquals("75°", strip.segments[1].text)
    assertEquals(SegmentKind.WARN, strip.segments[1].kind)
  }

  @Test
  fun `strip grades the combined value by the worse of the two levels`() {
    // CPU is only WARN at 80, GPU is HOT at 90; the hotter value also carries
    // the worse grade, and each sensor uses its own thresholds.
    val strip = stripSegments(Reading(1_000L, cpu = 80.0, gpu = 90.0), 1_000L)
    assertEquals("90°", strip.segments[1].text)
    assertEquals(SegmentKind.HOT, strip.segments[1].kind)

    val cpuWarnOnly = stripSegments(Reading(1_000L, cpu = 80.0, gpu = 50.0), 1_000L)
    assertEquals(SegmentKind.WARN, cpuWarnOnly.segments[1].kind)
  }

  @Test
  fun `strip keeps a cpu-only reading`() {
    val strip = stripSegments(Reading(1_000L, cpu = 80.0, gpu = null), 1_000L)
    assertEquals(listOf("CPU/GPU", "80°"), strip.segments.map { it.text })
    assertEquals(SegmentKind.WARN, strip.segments[1].kind)
  }

  @Test
  fun `strip keeps a gpu-only reading`() {
    val strip = stripSegments(Reading(1_000L, cpu = null, gpu = 90.0), 1_000L)
    assertEquals(listOf("CPU/GPU", "90°"), strip.segments.map { it.text })
    assertEquals(SegmentKind.HOT, strip.segments[1].kind)
  }

  @Test
  fun `strip dashes the combined value when both sensors are missing`() {
    val strip = stripSegments(
      Reading(1_000L, battery = 34.0),
      1_000L,
      PillVisibility(fps = false, ram = false),
    )
    assertEquals(
      listOf(
        Segment("CPU/GPU", SegmentKind.LABEL),
        Segment("—", SegmentKind.UNKNOWN),
        Segment("BAT", SegmentKind.LABEL),
        Segment("34°", SegmentKind.OK),
      ),
      strip.segments,
    )
  }

  @Test
  fun `strip shows battery as a first-class metric`() {
    val strip = stripSegments(
      Reading(1_000L, battery = 34.0),
      1_000L,
      PillVisibility(cpuGpu = false, fps = false, ram = false),
    )
    assertEquals(listOf("BAT", "34°"), strip.segments.map { it.text })
    assertEquals(SegmentKind.OK, strip.segments[1].kind)
  }

  @Test
  fun `strip grades battery warn and hot at their boundaries`() {
    fun batKind(value: Double): SegmentKind = stripSegments(
      Reading(1_000L, battery = value),
      1_000L,
      PillVisibility(cpuGpu = false, fps = false, ram = false),
    ).segments[1].kind

    assertEquals(SegmentKind.OK, batKind(41.9))
    assertEquals(SegmentKind.WARN, batKind(42.0))
    assertEquals(SegmentKind.WARN, batKind(44.9))
    assertEquals(SegmentKind.HOT, batKind(45.0))
  }

  @Test
  fun `strip shows fps as a first-class metric`() {
    val strip = stripSegments(
      Reading(1_000L),
      1_000L,
      PillVisibility(cpuGpu = false, bat = false, ram = false),
      fps = 60.2,
    )
    assertEquals(listOf("FPS", "60"), strip.segments.map { it.text })
    assertEquals(SegmentKind.LABEL, strip.segments[0].kind)
    assertEquals(SegmentKind.OK, strip.segments[1].kind)
  }

  @Test
  fun `strip dashes a missing fps`() {
    val strip = stripSegments(
      Reading(1_000L),
      1_000L,
      PillVisibility(cpuGpu = false, bat = false, ram = false),
    )
    assertEquals(listOf("FPS", "—"), strip.segments.map { it.text })
    assertEquals(SegmentKind.UNKNOWN, strip.segments[1].kind)
  }

  @Test
  fun `strip grades fps at its 30 and 50 boundaries`() {
    fun fpsKind(value: Double): SegmentKind = stripSegments(
      Reading(1_000L),
      1_000L,
      PillVisibility(cpuGpu = false, bat = false, ram = false),
      fps = value,
    ).segments[1].kind

    assertEquals(SegmentKind.HOT, fpsKind(29.9))
    assertEquals(SegmentKind.WARN, fpsKind(30.0))
    assertEquals(SegmentKind.WARN, fpsKind(49.9))
    assertEquals(SegmentKind.OK, fpsKind(50.0))
  }

  @Test
  fun `strip orders the enabled kinds cpu-gpu then bat then fps then ram`() {
    val strip = stripSegments(
      Reading(1_000L, cpu = 41.0, gpu = 39.0, battery = 34.0, fps = 0.0),
      1_000L,
      PillVisibility(),
      ram = RamUsage(usedBytes = 8_600_000_000L, totalBytes = 12_000_000_000L),
    )
    assertEquals(
      listOf("CPU/GPU", "41°", "BAT", "34°", "FPS", "0", "RAM", "8.6G"),
      strip.segments.map { it.text },
    )
    assertEquals(
      listOf(
        SegmentKind.LABEL,
        SegmentKind.OK,
        SegmentKind.LABEL,
        SegmentKind.OK,
        SegmentKind.LABEL,
        SegmentKind.HOT,
        SegmentKind.LABEL,
        SegmentKind.INFO,
      ),
      strip.segments.map { it.kind },
    )
  }

  @Test
  fun `each chip off removes only its own segment`() {
    val reading = Reading(1_000L, cpu = 80.0, gpu = 50.0, battery = 34.0, fps = 60.0)
    val ram = RamUsage(usedBytes = 8_600_000_000L, totalBytes = 12_000_000_000L)

    val noCpuGpu = stripSegments(
      reading,
      1_000L,
      PillVisibility(cpuGpu = false, ram = false),
      ram = ram,
    )
    assertEquals(listOf("BAT", "34°", "FPS", "60"), noCpuGpu.segments.map { it.text })

    val noBat = stripSegments(
      reading,
      1_000L,
      PillVisibility(bat = false, ram = false),
      ram = ram,
    )
    assertEquals(listOf("CPU/GPU", "80°", "FPS", "60"), noBat.segments.map { it.text })

    val noFps = stripSegments(
      reading,
      1_000L,
      PillVisibility(fps = false, ram = false),
      ram = ram,
    )
    assertEquals(listOf("CPU/GPU", "80°", "BAT", "34°"), noFps.segments.map { it.text })

    val noRam = stripSegments(reading, 1_000L, PillVisibility(ram = false), ram = ram)
    assertEquals(
      listOf("CPU/GPU", "80°", "BAT", "34°", "FPS", "60"),
      noRam.segments.map { it.text },
    )

    // RAM is independent of the other chips: CPU/GPU off must not drop it.
    val noCpuGpuButRam = stripSegments(
      reading,
      1_000L,
      PillVisibility(cpuGpu = false),
      ram = ram,
    )
    assertEquals(
      listOf("BAT", "34°", "FPS", "60", "RAM", "8.6G"),
      noCpuGpuButRam.segments.map { it.text },
    )
    assertEquals(SegmentKind.INFO, noCpuGpuButRam.segments.last().kind)
  }

  @Test
  fun `strip shows ram after fps and grades it as info`() {
    val strip = stripSegments(
      Reading(1_000L, cpu = 41.0, gpu = 39.0, battery = 34.0, fps = 60.0),
      1_000L,
      PillVisibility(),
      ram = RamUsage(usedBytes = 8_600_000_000L, totalBytes = 12_000_000_000L),
    )
    assertEquals(
      listOf("RAM", "8.6G"),
      strip.segments.takeLast(2).map { it.text },
    )
    assertEquals(SegmentKind.LABEL, strip.segments[strip.segments.lastIndex - 1].kind)
    assertEquals(SegmentKind.INFO, strip.segments.last().kind)
  }

  @Test
  fun `strip dashes a missing ram`() {
    val strip = stripSegments(Reading(1_000L, cpu = 41.0), 1_000L, PillVisibility())
    assertEquals(listOf("RAM", "—"), strip.segments.takeLast(2).map { it.text })
    assertEquals(SegmentKind.UNKNOWN, strip.segments.last().kind)
  }

  @Test
  fun `strip dashes every enabled label before the first reading`() {
    val strip = stripSegments(null, 1_000L, PillVisibility())
    assertEquals(
      listOf("CPU/GPU", "—", "BAT", "—", "FPS", "—", "RAM", "—"),
      strip.segments.map { it.text },
    )
    assertEquals(
      listOf(
        SegmentKind.LABEL,
        SegmentKind.UNKNOWN,
        SegmentKind.LABEL,
        SegmentKind.UNKNOWN,
        SegmentKind.LABEL,
        SegmentKind.UNKNOWN,
        SegmentKind.LABEL,
        SegmentKind.UNKNOWN,
      ),
      strip.segments.map { it.kind },
    )
    assertTrue(strip.dim)
  }

  @Test
  fun `null reading still shows ram and does not dim`() {
    val strip = stripSegments(
      null,
      1_000L,
      PillVisibility(),
      ram = RamUsage(usedBytes = 8_600_000_000L, totalBytes = 12_000_000_000L),
    )
    assertEquals(
      listOf("CPU/GPU", "—", "BAT", "—", "FPS", "—", "RAM", "8.6G"),
      strip.segments.map { it.text },
    )
    assertEquals(SegmentKind.UNKNOWN, strip.segments[1].kind)
    assertEquals(SegmentKind.INFO, strip.segments.last().kind)
    assertFalse(strip.dim)
  }

  @Test
  fun `null reading with the ram chip off stays dim even when ram is available`() {
    val strip = stripSegments(
      null,
      1_000L,
      PillVisibility(ram = false),
      ram = RamUsage(usedBytes = 8_600_000_000L, totalBytes = 12_000_000_000L),
    )
    assertEquals(
      listOf("CPU/GPU", "—", "BAT", "—", "FPS", "—"),
      strip.segments.map { it.text },
    )
    assertTrue(strip.dim)
  }

  @Test
  fun `ram dims with the strip on a stale reading`() {
    val strip = stripSegments(
      Reading(1_000L, cpu = 80.0),
      1_000L + STALE_AFTER_MS + 1,
      PillVisibility(bat = false, fps = false),
      ram = RamUsage(usedBytes = 8_600_000_000L, totalBytes = 12_000_000_000L),
    )
    assertTrue(strip.dim)
    assertEquals("8.6G", strip.segments.last().text)
  }

  @Test
  fun `strip dims when stale and keeps the values`() {
    val reading = Reading(1_000L, cpu = 80.0, gpu = 50.0)
    val strip = stripSegments(reading, 1_000L + STALE_AFTER_MS + 1)
    assertTrue(strip.dim)
    assertEquals("80°", strip.segments[1].text)
  }

  @Test
  fun `strip keeps its two-argument output to cpu-gpu only`() {
    // Pre-T15i callers pass only reading and now; BAT, FPS and RAM are opt-in
    // through the visibility argument, so a reading that carries them still
    // draws the combined row alone.
    val strip = stripSegments(
      Reading(1_000L, cpu = 80.0, gpu = 50.0, battery = 34.0, fps = 60.0),
      1_002L,
    )
    assertEquals(listOf("CPU/GPU", "80°"), strip.segments.map { it.text })
  }

  @Test
  fun `strip never falls back to battery for a missing combined value`() {
    // The old battery-only fallback is gone: a battery temperature is never
    // relabelled as a SoC sensor (ANDROID.md §2.2, T15i).
    val strip = stripSegments(Reading(1_000L, battery = 34.0), 1_000L)
    assertEquals(listOf("CPU/GPU", "—"), strip.segments.map { it.text })
    assertEquals(SegmentKind.UNKNOWN, strip.segments[1].kind)
  }

  @Test
  fun `strip never renders an empty pill for an all-off visibility`() {
    val strip = stripSegments(
      Reading(1_000L, cpu = 80.0),
      1_000L,
      PillVisibility(cpuGpu = false, bat = false, fps = false, ram = false),
    )
    assertEquals(listOf("CPU/GPU", "80°"), strip.segments.map { it.text })
  }
}
