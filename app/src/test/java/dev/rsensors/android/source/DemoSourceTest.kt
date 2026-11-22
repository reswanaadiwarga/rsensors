package dev.rsensors.android.source

import dev.rsensors.android.core.ThermalStatus
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic source used for previews. */
class DemoSourceTest {

  @Test
  fun `source id is demo`() {
    assertEquals("demo", DemoSource().id)
  }

  @Test
  fun `poll fills every field`() {
    val now = 1_234_567L
    val reading = DemoSource().poll(now)
    assertNotNull(reading.cpu)
    assertNotNull(reading.gpu)
    assertTrue(reading.cores.isNotEmpty())
    assertTrue(reading.board.isNotEmpty())
    assertEquals(now, reading.t)
    assertEquals("demo", reading.sourceName)
    assertEquals(ThermalStatus.NONE, reading.thermalStatus)
    assertEquals(34.0, reading.battery!!, 0.0)
  }

  @Test
  fun `first poll matches the ramp formula at tick one`() {
    val now = 1_000L
    val cpu = 55.0 + 22.0 * sin(1.0 / 20.0)
    val gpu = 48.0 + 18.0 * sin(1.0 / 40.0)
    val reading = DemoSource().poll(now)

    assertEquals(cpu, reading.cpu!!, 1e-9)
    assertEquals(gpu, reading.gpu!!, 1e-9)
    assertEquals(cpu + 3.0, reading.cpuMax!!, 1e-9)
    assertEquals(List(4) { cpu + 2.0 }, reading.cores)
    assertEquals(gpu + 12.0, reading.gpuHot!!, 1e-9)
    assertEquals(listOf("VRM MOS" to 51.0, "PCH" to 57.0), reading.board)
  }

  @Test
  fun `each poll advances the ramp`() {
    val source = DemoSource()
    val first = source.poll(1_000L).cpu!!
    val second = source.poll(2_000L).cpu!!
    assertEquals(55.0 + 22.0 * sin(2.0 / 20.0), second, 1e-9)
    assertTrue("tick should move the ramp", first != second)
  }
}

