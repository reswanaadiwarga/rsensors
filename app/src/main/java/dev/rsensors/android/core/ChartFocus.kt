package dev.rsensors.android.core

/**
 * Highlight state and paint order for the full-session graph's six lines
 * (ANDROID.md §3, §9, §8 T15d).
 *
 * Pure data so the toggle and ordering rules are unit-testable without Compose
 * or a device; `ui/SessionGraph.kt` only maps this onto alpha and draw calls.
 * Every series starts highlighted. A dimmed series is never removed from the
 * graph: it keeps painting at low alpha underneath the highlighted lines,
 * because the tooltip still reports every value and dimming must not hide data.
 *
 * @property dimmed series drawn at low alpha; membership is all that matters,
 *   so its iteration order is meaningless
 * @property order highlighted series in paint order: the last element paints on
 *   top, so the most recently re-highlighted line wins a direct overlap
 */
data class ChartFocus(
  val dimmed: Set<ChartSeries> = emptySet(),
  val order: List<ChartSeries> = ChartSeries.entries,
) {
  /** True when [series] is drawn dim instead of fully highlighted. */
  fun isDimmed(series: ChartSeries): Boolean = series in dimmed

  /**
   * Flips [series] between dimmed and highlighted.
   *
   * A highlighted series is only added to [dimmed]; it keeps its slot in
   * [order] so un-dimming later still moves it, not its neighbours. A dimmed
   * series leaves [dimmed] and moves to the end of [order] so it paints over the
   * lines it overlaps.
   *
   * @return a new focus; this instance is never mutated
   */
  fun toggle(series: ChartSeries): ChartFocus =
    if (isDimmed(series)) {
      ChartFocus(dimmed = dimmed - series, order = order - series + series)
    } else {
      // The slot in `order` survives: a dimmed series does not jump when it is
      // switched off, only when it comes back.
      ChartFocus(dimmed = dimmed + series, order = order)
    }

  /**
   * The paint order: dimmed series first, then highlighted ones.
   *
   * The result is rebuilt from [ChartSeries.entries] rather than trusted from
   * [order], so a stored order that lost or duplicated entries (an older app
   * version, a hand-edited preference) still yields every series exactly once.
   * Among the dimmed lines the enum order is used; highlighted lines keep
   * [order]'s relative sequence except for any series that is dimmed, and any
   * series absent from [order] is appended in enum order.
   */
  fun drawOrder(): List<ChartSeries> {
    val dimmedLines = ChartSeries.entries.filter { it in dimmed }
    val highlighted = order.filter { it !in dimmed }.distinct()
    val leftover = ChartSeries.entries.filter { it !in dimmed && it !in highlighted }
    return dimmedLines + highlighted + leftover
  }
}
