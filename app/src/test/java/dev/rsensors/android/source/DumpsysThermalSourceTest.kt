package dev.rsensors.android.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DumpsysThermalSource] contract (ANDROID.md §5, §8 T8): the runner supplies
 * the raw dump, the source stamps the caller's clock and its own id, and every
 * runner failure degrades to a null reading instead of reaching the poller.
 */
class DumpsysThermalSourceTest {

  /** Test-local runner with controllable output and failure. */
  private class FakeRunner(private val behavior: () -> String?) : DumpsysRunner {
    var runCount = 0
      private set
    var closeCount = 0
      private set

    override fun run(): String? {
      runCount += 1
      return behavior()
    }

    override fun close() {
      closeCount += 1
    }
  }

  @Test
  fun `dump becomes a reading stamped with now and the source id`() {
    val source = DumpsysThermalSource(FakeRunner { DUMP })

    val reading = source.poll(1_234_567L)!!

    assertEquals("dumpsys", source.id)
    assertEquals(1_234_567L, reading.t)
    assertEquals("dumpsys", reading.sourceName)
    assertEquals(38.351, reading.cpu!!, 1e-9)
    assertEquals(40.086, reading.gpu!!, 1e-9)
    assertEquals(33.4, reading.battery!!, 1e-9)
  }

  @Test
  fun `a null dump yields null`() {
    val source = DumpsysThermalSource(FakeRunner { null })

    assertNull(source.poll(42L))
  }

  @Test
  fun `a throwing runner yields null`() {
    val source = DumpsysThermalSource(FakeRunner { throw IllegalStateException("binder died") })

    assertNull(source.poll(42L))
  }

  @Test
  fun `an unparseable dump yields null`() {
    val source = DumpsysThermalSource(FakeRunner { "Thermal Status: 0" })

    assertNull(source.poll(42L))
  }

  @Test
  fun `close delegates to the runner exactly once`() {
    val runner = FakeRunner { null }
    val source = DumpsysThermalSource(runner)

    source.close()

    assertEquals(1, runner.closeCount)
  }

  companion object {
    private val DUMP = """
      |Cached temperatures:
      |	Temperature{mValue=44.776, mType=0, mName=CPU, mStatus=0}
      |Current temperatures from HAL:
      |	Temperature{mValue=38.351, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=40.086, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=33.4, mType=2, mName=BATTERY, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()
  }
}
