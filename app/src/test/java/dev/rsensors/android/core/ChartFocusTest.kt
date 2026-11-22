package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-graph legend focus: which lines are dimmed and the order the
 * highlighted ones paint in (ANDROID.md §3, §9, §8 T15d).
 *
 * Expected values are hand-derived from the task spec: every series starts
 * highlighted in enum order, a toggle dims a highlighted series in place, a
 * second toggle re-highlights it at the end of the paint order (the most recent
 * highlight wins a direct overlap), dimmed lines always paint underneath the
 * highlighted ones, and a corrupted stored order can never drop or duplicate a
 * line because `drawOrder()` rebuilds the full set.
 */
class ChartFocusTest {

  private val all = ChartSeries.entries

  @Test
  fun `all series are highlighted by default`() {
    val focus = ChartFocus()

    all.forEach { series ->
      assertFalse(series.name, focus.isDimmed(series))
    }
    assertEquals(all, focus.drawOrder())
  }

  @Test
  fun `toggling a highlighted series dims it and keeps its place in the order`() {
    val focus = ChartFocus().toggle(ChartSeries.GPU)

    assertTrue(focus.isDimmed(ChartSeries.GPU))
    // The order is untouched, so the series would return to its old slot if the
    // order were all that mattered; only the dim flag changed.
    assertEquals(all, focus.order)
    // Dimmed lines paint first, in enum order, then the highlighted ones.
    assertEquals(
      listOf(
        ChartSeries.GPU,
        ChartSeries.CPU,
        ChartSeries.BATTERY,
        ChartSeries.SOC,
        ChartSeries.SKIN,
        ChartSeries.FPS,
      ),
      focus.drawOrder(),
    )
  }

  @Test
  fun `toggling a dimmed series re-highlights it on top`() {
    val focus = ChartFocus()
      .toggle(ChartSeries.CPU)
      .toggle(ChartSeries.CPU)

    assertFalse(focus.isDimmed(ChartSeries.CPU))
    assertEquals(
      listOf(
        ChartSeries.GPU,
        ChartSeries.BATTERY,
        ChartSeries.SOC,
        ChartSeries.SKIN,
        ChartSeries.FPS,
        ChartSeries.CPU,
      ),
      focus.order,
    )
    // Nothing is dimmed any more, so the paint order is the highlight order.
    assertEquals(focus.order, focus.drawOrder())
  }

  @Test
  fun `dimmed lines come before every highlighted line`() {
    val focus = ChartFocus()
      .toggle(ChartSeries.SKIN)
      .toggle(ChartSeries.CPU)

    val drawOrder = focus.drawOrder()

    assertEquals(listOf(ChartSeries.CPU, ChartSeries.SKIN), drawOrder.take(2))
    drawOrder.drop(2).forEach { series ->
      assertFalse(series.name, focus.isDimmed(series))
    }
    assertEquals(
      listOf(
        ChartSeries.CPU,
        ChartSeries.SKIN,
        ChartSeries.GPU,
        ChartSeries.BATTERY,
        ChartSeries.SOC,
        ChartSeries.FPS,
      ),
      drawOrder,
    )
  }

  @Test
  fun `re-highlighting two series leaves the second one last`() {
    val focus = ChartFocus()
      .toggle(ChartSeries.CPU)
      .toggle(ChartSeries.SOC)
      .toggle(ChartSeries.CPU)
      .toggle(ChartSeries.SOC)

    assertEquals(ChartSeries.SOC, focus.order.last())
    assertEquals(ChartSeries.CPU, focus.order[focus.order.size - 2])
    assertEquals(
      listOf(
        ChartSeries.GPU,
        ChartSeries.BATTERY,
        ChartSeries.SKIN,
        ChartSeries.FPS,
        ChartSeries.CPU,
        ChartSeries.SOC,
      ),
      focus.drawOrder(),
    )
  }

  @Test
  fun `a corrupted order still yields every series exactly once`() {
    // Missing entries and a duplicate, as a stored order could look after an
    // app update that added a series.
    val focus = ChartFocus(order = listOf(ChartSeries.CPU, ChartSeries.CPU, ChartSeries.GPU))

    assertEquals(
      listOf(
        ChartSeries.CPU,
        ChartSeries.GPU,
        ChartSeries.BATTERY,
        ChartSeries.SOC,
        ChartSeries.SKIN,
        ChartSeries.FPS,
      ),
      focus.drawOrder(),
    )
    assertEquals(all.toSet(), focus.drawOrder().toSet())
  }

  @Test
  fun `a corrupted order keeps the dimmed series once and first`() {
    val focus = ChartFocus(
      dimmed = setOf(ChartSeries.FPS),
      order = listOf(ChartSeries.FPS, ChartSeries.FPS, ChartSeries.CPU),
    )

    assertEquals(
      listOf(
        ChartSeries.FPS,
        ChartSeries.CPU,
        ChartSeries.GPU,
        ChartSeries.BATTERY,
        ChartSeries.SOC,
        ChartSeries.SKIN,
      ),
      focus.drawOrder(),
    )
  }

  @Test
  fun `toggling a series missing from the order repairs the order`() {
    val focus = ChartFocus(order = listOf(ChartSeries.CPU))
      .toggle(ChartSeries.SKIN)

    assertTrue(focus.isDimmed(ChartSeries.SKIN))
    assertEquals(all.toSet(), focus.drawOrder().toSet())
    assertEquals(ChartSeries.entries.size, focus.drawOrder().size)
  }

  @Test
  fun `an empty stored order still paints every series`() {
    val focus = ChartFocus(order = emptyList())

    assertEquals(all, focus.drawOrder())
  }
}
