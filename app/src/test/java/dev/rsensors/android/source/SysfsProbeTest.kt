package dev.rsensors.android.source

import dev.rsensors.android.core.ZoneCategory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-driven tests for the diagnostics zone probe (ANDROID.md §2.2, §5,
 * §8 T7).
 *
 * The probe is the whole-table view: unlike [SysfsSource] it keeps zones whose
 * type or temperature could not be read, so the screen can show the failure
 * instead of hiding the node.
 */
class SysfsProbeTest {

  @Test
  fun `qualcomm fixture lists every zone with raw values and categories`() {
    val reports = probeThermalZones(DirectReader(), fixture("qualcomm") + "/thermal", emptyMap())

    assertEquals(listOf(0, 1, 2), reports.map { it.index })
    assertEquals(listOf("cpu-0-0-usr", "gpuss-0-usr", "battery"), reports.map { it.type })
    assertEquals(listOf("62000", "48000", "34000"), reports.map { it.rawTemp })
    assertEquals(listOf(62.0, 48.0, 34.0), reports.map { it.celsius })
    assertEquals(
      listOf(ZoneCategory.CPU, ZoneCategory.GPU, ZoneCategory.BATTERY),
      reports.map { it.category },
    )
    assertTrue(reports.all { it.error == null })
  }

  @Test
  fun `mediatek fixture lists every zone with raw values and categories`() {
    val reports = probeThermalZones(DirectReader(), fixture("mediatek") + "/thermal", emptyMap())

    assertEquals(listOf("mtktscpu", "mtktsgpu", "mtktsbattery"), reports.map { it.type })
    assertEquals(listOf(61.0, 47.0, 33.0), reports.map { it.celsius })
    assertEquals(
      listOf(ZoneCategory.CPU, ZoneCategory.GPU, ZoneCategory.BATTERY),
      reports.map { it.category },
    )
  }

  @Test
  fun `exynos fixture lists only the zones it has`() {
    val reports = probeThermalZones(DirectReader(), fixture("exynos") + "/thermal", emptyMap())

    assertEquals(listOf(0, 1), reports.map { it.index })
    assertEquals(listOf("cpu-0-0", "gpu0"), reports.map { it.type })
    assertEquals(listOf(60.0, 46.0), reports.map { it.celsius })
    assertEquals(listOf(ZoneCategory.CPU, ZoneCategory.GPU), reports.map { it.category })
  }

  @Test
  fun `override re-categorises a zone in the probe`() {
    val reports =
      probeThermalZones(
        DirectReader(),
        fixture("qualcomm") + "/thermal",
        mapOf("  GPUSS-0-USR " to ZoneCategory.CPU),
      )

    assertEquals(ZoneCategory.CPU, reports.first { it.index == 1 }.category)
  }

  @Test
  fun `denied reads still list every zone with EACCES and no values`() {
    val reader =
      ListedDeniedReader(listOf("thermal_zone0", "cooling_device0", "thermal_zone3"))

    val reports = probeThermalZones(reader, "/sys/class/thermal", emptyMap())

    assertEquals(listOf(0, 3), reports.map { it.index })
    reports.forEach { report ->
      assertNull(report.type)
      assertNull(report.rawTemp)
      assertNull(report.celsius)
      assertEquals(ZoneCategory.OTHER, report.category)
      assertEquals("EACCES", report.error)
    }
  }

  @Test
  fun `per-path parse zero and range reasons are attached to their zone`() {
    val reports = probeThermalZones(DirectReader(), fixture("malformed") + "/thermal", emptyMap())

    assertEquals(listOf("parse", "zero", "range"), reports.map { it.error })
    assertEquals(listOf("garbage", "0", "999999"), reports.map { it.rawTemp })
    assertTrue(reports.all { it.celsius == null })
  }

  private fun fixture(name: String): String =
    File(javaClass.classLoader!!.getResource("fixtures/sysfs/$name")!!.toURI()).path
}

/**
 * Test reader with a working thermal directory listing but denied node reads,
 * matching the target phone (ANDROID.md §10): `ls` succeeds while `type` and
 * `temp` return EACCES.
 */
private class ListedDeniedReader(private val names: List<String>) : SysfsReader {
  private val log = mutableListOf<SysfsError>()

  override fun open(path: String): String? {
    log += SysfsError(path, "EACCES")
    return null
  }

  override fun listDir(path: String): List<String> = names

  override fun errors(): List<SysfsError> = log.toList()

  override fun clearErrors() {
    log.clear()
  }
}
