package dev.rsensors.android.source

import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge semantics of [SourceChain] (ANDROID.md §5): caller order is priority,
 * per-field first-non-null wins, and one failing source must never blank the
 * values the others produced.
 */
class SourceChainTest {

  /** Test-local backend with controllable polling and close behaviour. */
  private class FakeSource(
    override val id: String,
    private val behavior: (Long) -> Reading?,
  ) : SensorSource {
    var pollCount = 0
      private set
    var closeCount = 0
      private set
    var failOnClose = false

    override fun poll(now: Long): Reading? {
      pollCount += 1
      return behavior(now)
    }

    override fun close() {
      closeCount += 1
      if (failOnClose) throw IllegalStateException("cannot close $id")
    }
  }

  @Test
  fun `chain id is chain`() {
    assertEquals("chain", SourceChain(emptyList()).id)
  }

  @Test
  fun `first source wins per field and the second fills only its gaps`() {
    val primary = FakeSource("dumpsys") { now ->
      Reading(t = now, cpu = 60.0, sourceName = "dumpsys")
    }
    val secondary = FakeSource("sysfs") { now ->
      Reading(
        t = now,
        cpu = 99.0,
        cpuMax = 65.0,
        gpu = 50.0,
        battery = 33.0,
        sourceName = "sysfs",
      )
    }
    val chain = SourceChain(listOf(primary, secondary))

    val merged = chain.poll(5_000L)!!

    assertEquals(5_000L, merged.t)
    assertEquals(60.0, merged.cpu!!, 0.0)
    assertEquals(65.0, merged.cpuMax!!, 0.0)
    assertEquals(50.0, merged.gpu!!, 0.0)
    assertEquals(33.0, merged.battery!!, 0.0)
    assertEquals("dumpsys+sysfs", merged.sourceName)
  }

  @Test
  fun `a failing source is recorded and does not blank the others`() {
    val first = FakeSource("a") { now -> Reading(t = now, cpu = 60.0, sourceName = "a") }
    val failing = FakeSource("sysfs") { throw IllegalStateException("boom") }
    val silent = FakeSource("other") { throw RuntimeException() }
    val last = FakeSource("b") { now -> Reading(t = now, gpu = 50.0, sourceName = "b") }
    val chain = SourceChain(listOf(first, failing, silent, last))

    val merged = chain.poll(7_000L)!!

    assertEquals(60.0, merged.cpu!!, 0.0)
    assertEquals(50.0, merged.gpu!!, 0.0)
    assertEquals("a+b", merged.sourceName)
    assertEquals(
      listOf(
        SourceError("sysfs", "boom"),
        SourceError("other", "RuntimeException"),
      ),
      chain.lastErrors,
    )
  }

  @Test
  fun `all-null readings and an empty chain return null`() {
    val empty = SourceChain(emptyList())
    assertNull(empty.poll(1_000L))
    assertTrue(empty.lastErrors.isEmpty())

    val nothing = SourceChain(
      listOf(
        FakeSource("null") { null },
        // A source name alone is not a contribution.
        FakeSource("named") { now -> Reading(t = now, sourceName = "named") },
      ),
    )

    assertNull(nothing.poll(2_000L))
    assertTrue(nothing.lastErrors.isEmpty())
  }

  @Test
  fun `first non-empty cores and board and first non-UNKNOWN status win`() {
    val primary = FakeSource("a") { now ->
      Reading(t = now, cpu = 60.0, sourceName = "a")
    }
    val secondary = FakeSource("b") { now ->
      Reading(
        t = now,
        cores = listOf(40.0, 41.0),
        board = listOf("PCH" to 57.0),
        thermalStatus = ThermalStatus.MODERATE,
        sourceName = "b",
      )
    }
    val tertiary = FakeSource("c") { now ->
      Reading(
        t = now,
        cores = listOf(30.0),
        board = listOf("VRM MOS" to 70.0),
        thermalStatus = ThermalStatus.SEVERE,
        sourceName = "c",
      )
    }
    val chain = SourceChain(listOf(primary, secondary, tertiary))

    val merged = chain.poll(9_000L)!!

    assertEquals(listOf(40.0, 41.0), merged.cores)
    assertEquals(listOf("PCH" to 57.0), merged.board)
    assertEquals(ThermalStatus.MODERATE, merged.thermalStatus)
    assertEquals(9_000L, merged.t)
  }

  @Test
  fun `sourceName lists only contributing sources with id fallback and distinct`() {
    val chain = SourceChain(
      listOf(
        FakeSource("a") { now -> Reading(t = now, cpu = 60.0, sourceName = "") },
        FakeSource("b") { now -> Reading(t = now, gpu = 50.0, sourceName = "b") },
        // Nothing but a name: must not appear.
        FakeSource("c") { now -> Reading(t = now, sourceName = "c") },
        // Same display name as b: distinct, so it collapses.
        FakeSource("d") { now -> Reading(t = now, battery = 33.0, sourceName = "b") },
      ),
    )

    assertEquals("a+b", chain.poll(3_000L)!!.sourceName)
  }

  @Test
  fun `lastErrors resets on the next clean poll`() {
    var calls = 0
    val flaky = FakeSource("flaky") { now ->
      calls += 1
      if (calls == 1) throw IllegalStateException("boom")
      Reading(t = now, cpu = 40.0, sourceName = "flaky")
    }
    val good = FakeSource("good") { now ->
      Reading(t = now, gpu = 45.0, sourceName = "good")
    }
    val chain = SourceChain(listOf(flaky, good))

    val first = chain.poll(1_000L)!!
    assertEquals(45.0, first.gpu!!, 0.0)
    assertEquals(listOf(SourceError("flaky", "boom")), chain.lastErrors)

    chain.poll(2_000L)
    assertTrue(chain.lastErrors.isEmpty())
  }

  @Test
  fun `close closes every source exactly once and swallows failures`() {
    val sources = List(3) { index -> FakeSource("s$index") { null } }
    sources[1].failOnClose = true
    val chain = SourceChain(sources)

    chain.close()

    sources.forEach { source -> assertEquals(1, source.closeCount) }
  }
}
