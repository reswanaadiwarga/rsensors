package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expanded card's graph series selector (ANDROID.md §3, §9 T15b).
 *
 * Expected values are hand-written from the spec: the cycle order is
 * CPU+GPU → CPU → GPU → BAT → FPS → CPU+GPU (ANDROID.md §3, §8 T15c), the
 * labels are what the card's button shows, and a missing or unknown stored
 * value is the combined graph.
 */
class GraphSeriesTest {

  @Test
  fun `null or unknown stored value falls back to CPU+GPU`() {
    assertEquals(GraphSeries.CPU_GPU, GraphSeries.fromStored(null))
    assertEquals(GraphSeries.CPU_GPU, GraphSeries.fromStored("NOPE"))
    assertEquals(GraphSeries.CPU_GPU, GraphSeries.fromStored(""))
  }

  @Test
  fun `fromStored round-trips every series name`() {
    GraphSeries.entries.forEach { series ->
      assertEquals(series, GraphSeries.fromStored(series.name))
    }
    // BAT is stored by enum name, so T15c needs no preference migration.
    assertEquals(GraphSeries.BAT, GraphSeries.fromStored("BAT"))
  }

  @Test
  fun `next cycles CPU+GPU to CPU to GPU to BAT to FPS and back`() {
    assertEquals(GraphSeries.CPU, GraphSeries.CPU_GPU.next())
    assertEquals(GraphSeries.GPU, GraphSeries.CPU.next())
    assertEquals(GraphSeries.BAT, GraphSeries.GPU.next())
    assertEquals(GraphSeries.FPS, GraphSeries.BAT.next())
    assertEquals(GraphSeries.CPU_GPU, GraphSeries.FPS.next())
  }

  @Test
  fun `five taps walk every series and return to CPU+GPU`() {
    var series = GraphSeries.CPU_GPU
    val visited = List(5) {
      series = series.next()
      series
    }

    assertEquals(
      listOf(GraphSeries.CPU, GraphSeries.GPU, GraphSeries.BAT, GraphSeries.FPS, GraphSeries.CPU_GPU),
      visited,
    )
  }

  @Test
  fun `labels match the selector button`() {
    assertEquals("CPU+GPU", GraphSeries.CPU_GPU.label)
    assertEquals("CPU", GraphSeries.CPU.label)
    assertEquals("GPU", GraphSeries.GPU.label)
    assertEquals("BAT", GraphSeries.BAT.label)
    assertEquals("FPS", GraphSeries.FPS.label)
  }
}
