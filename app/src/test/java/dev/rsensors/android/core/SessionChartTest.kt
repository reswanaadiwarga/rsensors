package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chart geometry for the full-session graph (ANDROID.md §3, §9, §8 T13).
 *
 * Expected values are hand-derived from the task spec: series fields come from
 * their CSV column (SOC/SKIN from the board list), bounds pad the observed
 * temperature range, and the nearest sample is chosen by absolute time distance
 * with ties going to the lower list index.
 */
class SessionChartTest {

  private fun sample(
    t: Long,
    cpu: Double? = null,
    gpu: Double? = null,
    battery: Double? = null,
    board: List<Pair<String, Double>> = emptyList(),
    fps: Double? = null,
  ) = SessionSample(t = t, cpu = cpu, gpu = gpu, battery = battery, board = board, fps = fps)

  @Test
  fun `series labels match the table vocabulary`() {
    assertEquals(
      listOf("CPU", "GPU", "BAT", "SOC", "SKIN", "FPS"),
      ChartSeries.entries.map { it.label },
    )
  }

  @Test
  fun `valueFor reads the recorded fps`() {
    assertEquals(59.8, sample(1_000L, fps = 59.8).valueFor(ChartSeries.FPS)!!, 0.0)
    assertNull(sample(1_000L).valueFor(ChartSeries.FPS))
  }

  @Test
  fun `fps scale maps 0 60 and 120 onto the fixed axis`() {
    assertEquals(0f, fpsFraction(0.0), 0f)
    assertEquals(0.5f, fpsFraction(60.0), 0.0001f)
    assertEquals(1f, fpsFraction(120.0), 0f)
    // Values outside the axis clamp to its ends rather than drawing off-plot.
    assertEquals(0f, fpsFraction(-10.0), 0f)
    assertEquals(1f, fpsFraction(240.0), 0f)
  }

  @Test
  fun `fpsAxisMax stays at the 120 baseline without a usable peak`() {
    assertEquals(120.0, fpsAxisMax(null), 0.0)
    assertEquals(120.0, fpsAxisMax(Double.NaN), 0.0)
    assertEquals(120.0, fpsAxisMax(0.0), 0.0)
    assertEquals(120.0, fpsAxisMax(-5.0), 0.0)
    assertEquals(120.0, fpsAxisMax(60.0), 0.0)
    assertEquals(120.0, fpsAxisMax(120.0), 0.0)
  }

  @Test
  fun `fpsAxisMax rounds a higher peak up to the next 30 fps step`() {
    assertEquals(150.0, fpsAxisMax(121.0), 0.0)
    assertEquals(150.0, fpsAxisMax(144.0), 0.0)
    // A peak already sitting on a 30 fps boundary keeps that boundary.
    assertEquals(150.0, fpsAxisMax(150.0), 0.0)
    assertEquals(180.0, fpsAxisMax(151.0), 0.0)
    assertEquals(180.0, fpsAxisMax(165.0), 0.0)
    assertEquals(180.0, fpsAxisMax(180.0), 0.0)
    assertEquals(240.0, fpsAxisMax(240.0), 0.0)
  }

  @Test
  fun `fpsAxisMax caps at the shell maximum`() {
    assertEquals(500.0, fpsAxisMax(500.0), 0.0)
    assertEquals(500.0, fpsAxisMax(501.0), 0.0)
    assertEquals(500.0, fpsAxisMax(Double.POSITIVE_INFINITY), 0.0)
  }

  @Test
  fun `fpsFraction maps onto a supplied axis`() {
    assertEquals(0f, fpsFraction(0.0, 150.0), 0f)
    assertEquals(0.5f, fpsFraction(75.0, 150.0), 0.0001f)
    assertEquals(1f, fpsFraction(150.0, 150.0), 0f)

    assertEquals(0f, fpsFraction(0.0, 180.0), 0f)
    assertEquals(0.5f, fpsFraction(90.0, 180.0), 0.0001f)
    assertEquals(1f, fpsFraction(180.0, 180.0), 0f)

    // Values past the axis clamp to its ends rather than drawing off-plot.
    assertEquals(0f, fpsFraction(-10.0, 150.0), 0f)
    assertEquals(1f, fpsFraction(240.0, 180.0), 0f)
  }

  @Test
  fun `fpsFraction treats a non-positive axis as the 120 baseline`() {
    assertEquals(0.5f, fpsFraction(60.0, 0.0), 0.0001f)
    assertEquals(0.5f, fpsFraction(60.0, -30.0), 0.0001f)
    assertEquals(1f, fpsFraction(240.0, 0.0), 0f)
  }

  @Test
  fun `hasFpsValues is false for an empty session`() {
    assertFalse(hasFpsValues(emptyList()))
  }

  @Test
  fun `hasFpsValues is false for a v1 session whose fps is always null`() {
    assertFalse(
      hasFpsValues(listOf(sample(1_000L, cpu = 40.0), sample(2_000L, cpu = 41.0))),
    )
  }

  @Test
  fun `hasFpsValues is true when any sample recorded an fps`() {
    // A measured zero is still a recorded value and must keep the FPS chip.
    assertTrue(hasFpsValues(listOf(sample(1_000L), sample(2_000L, fps = 0.0))))
  }

  @Test
  fun `bounds refuses the fps series because it has its own fixed axis`() {
    assertNull(bounds(listOf(sample(1_000L, fps = 60.0)), ChartSeries.FPS))
  }

  @Test
  fun `valueFor reads direct fields and board sensors verbatim`() {
    val sample = sample(
      t = 1_000L,
      cpu = 42.1,
      gpu = 38.5,
      battery = 33.5,
      board = listOf("SOC" to 43.1, "SKIN" to 35.2, "POWER_AMPLIFIER" to 35.56),
    )

    assertEquals(42.1, sample.valueFor(ChartSeries.CPU)!!, 0.0)
    assertEquals(38.5, sample.valueFor(ChartSeries.GPU)!!, 0.0)
    assertEquals(33.5, sample.valueFor(ChartSeries.BATTERY)!!, 0.0)
    assertEquals(43.1, sample.valueFor(ChartSeries.SOC)!!, 0.0)
    assertEquals(35.2, sample.valueFor(ChartSeries.SKIN)!!, 0.0)
  }

  @Test
  fun `valueFor finds board sensors case-insensitively`() {
    val sample = sample(t = 1_000L, board = listOf("soc" to 41.0, "Skin" to 34.0))

    assertEquals(41.0, sample.valueFor(ChartSeries.SOC)!!, 0.0)
    assertEquals(34.0, sample.valueFor(ChartSeries.SKIN)!!, 0.0)
  }

  @Test
  fun `valueFor returns null when the sensor is absent`() {
    val sample = sample(t = 1_000L)

    ChartSeries.entries.forEach { series ->
      assertNull(series.name, sample.valueFor(series))
    }
  }

  @Test
  fun `bounds is null without samples or without values for that series`() {
    assertNull(bounds(emptyList(), ChartSeries.CPU))
    assertNull(bounds(listOf(sample(1_000L), sample(2_000L)), ChartSeries.CPU))

    val gpuOnly = listOf(sample(1_000L, gpu = 41.0))
    assertNull(bounds(gpuOnly, ChartSeries.CPU))
    assertEquals(ChartBounds(1_000L, 1_000L, 40.0, 42.0), bounds(gpuOnly, ChartSeries.GPU))
  }

  @Test
  fun `bounds pads the range and spans every sample time`() {
    val samples = listOf(
      sample(1_000L, cpu = 30.0),
      sample(2_000L, cpu = 50.0),
      sample(3_000L),
    )

    assertEquals(
      ChartBounds(startT = 1_000L, endT = 3_000L, minTemp = 28.0, maxTemp = 52.0),
      bounds(samples, ChartSeries.CPU),
    )
  }

  @Test
  fun `bounds reads SOC from the board`() {
    val samples = listOf(
      sample(1_000L, board = listOf("SOC" to 40.0)),
      sample(2_000L, board = listOf("SOC" to 44.0)),
    )

    assertEquals(
      ChartBounds(startT = 1_000L, endT = 2_000L, minTemp = 39.0, maxTemp = 45.0),
      bounds(samples, ChartSeries.SOC),
    )
  }

  @Test
  fun `bounds pads a single sample to one degree`() {
    val bounds = bounds(listOf(sample(5_000L, cpu = 42.0)), ChartSeries.CPU)

    assertEquals(ChartBounds(5_000L, 5_000L, 41.0, 43.0), bounds)
  }

  @Test
  fun `bounds pads an equal min and max to one degree`() {
    val samples = listOf(sample(1_000L, battery = 40.0), sample(2_000L, battery = 40.0))

    assertEquals(ChartBounds(1_000L, 2_000L, 39.0, 41.0), bounds(samples, ChartSeries.BATTERY))
  }

  @Test
  fun `nearest picks the closest sample before after and between`() {
    val samples = listOf(sample(1_000L), sample(2_000L), sample(3_000L))

    assertEquals(0, nearestIndex(samples, 0L))
    assertEquals(2, nearestIndex(samples, 5_000L))
    assertEquals(1, nearestIndex(samples, 2_100L))
    assertEquals(1, nearestIndex(samples, 2_000L))
  }

  @Test
  fun `nearest breaks a tie towards the lower index`() {
    val samples = listOf(sample(1_000L), sample(2_000L))

    assertEquals(0, nearestIndex(samples, 1_500L))
  }

  @Test
  fun `nearest is null for an empty session`() {
    assertNull(nearestIndex(emptyList(), 1_000L))
  }
}
