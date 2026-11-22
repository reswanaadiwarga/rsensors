package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory 2-minute history feeding the expanded card's sparkline
 * (ANDROID.md §3, §9).
 *
 * Expected values are hand-written from the spec: a 120 s window, newest last,
 * at most 120 samples.
 */
class ReadingHistoryTest {

  private fun sample(t: Long, cpu: Double? = 40.0) = Reading(t = t, cpu = cpu, gpu = 40.0)

  @Test
  fun `empty history snapshots empty`() {
    val history = ReadingHistory()
    assertTrue(history.snapshot(10_000L).isEmpty())
  }

  @Test
  fun `snapshot keeps samples inside the window and drops older ones`() {
    val history = ReadingHistory(windowMs = 120_000L)
    val now = 200_000L
    history.add(sample(now - 120_000L))
    history.add(sample(now - 120_001L))
    history.add(sample(now - 60_000L))

    assertEquals(
      listOf(now - 120_000L, now - 60_000L),
      history.snapshot(now).map { it.t },
    )
  }

  @Test
  fun `snapshot keeps the newest samples when the buffer overflows`() {
    val history = ReadingHistory(maxSamples = 3)
    repeat(5) { index -> history.add(sample(index.toLong())) }

    assertEquals(listOf(2L, 3L, 4L), history.snapshot(4L).map { it.t })
  }

  @Test
  fun `snapshot returns samples oldest first and newest last`() {
    val history = ReadingHistory()
    history.add(sample(1_000L))
    history.add(sample(2_000L))
    history.add(sample(3_000L))

    assertEquals(listOf(1_000L, 2_000L, 3_000L), history.snapshot(3_000L).map { it.t })
  }

  @Test
  fun `clear empties the buffer`() {
    val history = ReadingHistory()
    history.add(sample(1_000L))
    history.clear()

    assertTrue(history.snapshot(1_000L).isEmpty())
  }

  @Test
  fun `a null cpu sample keeps its slot so the sparkline can break the line`() {
    val history = ReadingHistory()
    history.add(sample(1_000L))
    history.add(sample(2_000L, cpu = null))
    history.add(sample(3_000L))

    val snapshot = history.snapshot(3_000L)
    assertEquals(3, snapshot.size)
    assertEquals(listOf(40.0, null, 40.0), snapshot.map { it.cpu })
  }

  @Test
  fun `history records fps regardless of what the graph displays`() {
    val history = ReadingHistory()
    val now = 10_000L
    history.add(Reading(t = now, cpu = 41.0, gpu = 42.0, fps = 60.0))

    val snapshot = history.snapshot(now)
    assertEquals(1, snapshot.size)
    assertEquals(60.0, snapshot.single().fps!!, 0.0)
  }
}
