package dev.rsensors.android.source

import dev.rsensors.android.core.ZoneCategory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture-driven tests for the best-effort sysfs source (ANDROID.md §5, §8 T3). */
class SysfsSourceTest {

  @Test
  fun `qualcomm tree maps cpu gpu and battery`() {
    val source = source("qualcomm")
    assertEquals("sysfs", source.id)

    val now = 1_234_567L
    val reading = source.poll(now)!!

    assertEquals(now, reading.t)
    assertEquals("sysfs", reading.sourceName)
    assertEquals(62.0, reading.cpu!!, 0.0)
    assertEquals(48.0, reading.gpu!!, 0.0)
    assertEquals(34.0, reading.battery!!, 0.0)
  }

  @Test
  fun `mediatek tree maps cpu gpu and battery`() {
    val reading = source("mediatek").poll(42L)!!

    assertEquals(61.0, reading.cpu!!, 0.0)
    assertEquals(47.0, reading.gpu!!, 0.0)
    assertEquals(33.0, reading.battery!!, 0.0)
  }

  @Test
  fun `exynos tree maps cpu and gpu but has no battery zone`() {
    val reading = source("exynos").poll(42L)!!

    assertEquals(60.0, reading.cpu!!, 0.0)
    assertEquals(46.0, reading.gpu!!, 0.0)
    assertNull(reading.battery)
  }

  @Test
  fun `kgsl-only tree contributes gpu only`() {
    val reading = source("kgsl-only").poll(42L)!!

    assertNull(reading.cpu)
    assertEquals(47.0, reading.gpu!!, 0.0)
    assertNull(reading.battery)
  }

  @Test
  fun `malformed values yield no reading and record parse zero and range errors`() {
    val source = source("malformed")

    assertNull(source.poll(42L))
    val reasons = source.lastErrors.map { it.reason }
    assertTrue("expected a parse error in $reasons", reasons.contains("parse"))
    assertTrue("expected a zero error in $reasons", reasons.contains("zero"))
    assertTrue("expected a range error in $reasons", reasons.contains("range"))
  }

  @Test
  fun `denied reads yield no reading and EACCES errors`() {
    val source = SysfsSource(DeniedReader(), "/sys/class/thermal", "/sys/class/kgsl/kgsl-3d0/temp")

    assertNull(source.poll(42L))
    assertTrue(source.lastErrors.any { it.reason == "EACCES" })
  }

  @Test
  fun `kgsl 201 is out of range instead of being scaled to 0_201 C`() {
    val source = kgslOnlySource("201")

    assertNull(source.poll(42L))
    assertTrue(
      "expected a range error for the kgsl node in ${source.lastErrors}",
      source.lastErrors.any { it.path == kgslPath && it.reason == "range" },
    )
  }

  @Test
  fun `kgsl 47000 is out of range instead of being scaled to 47 C`() {
    val source = kgslOnlySource("47000")

    assertNull(source.poll(42L))
    assertTrue(
      "expected a range error for the kgsl node in ${source.lastErrors}",
      source.lastErrors.any { it.path == kgslPath && it.reason == "range" },
    )
  }

  @Test
  fun `thermal zones keep magnitude normalisation for whole and milli values`() {
    val root = SysfsSource.DEFAULT_THERMAL_ROOT
    val files =
      mapOf(
        "$root/thermal_zone0/type" to "cpu-0-0",
        "$root/thermal_zone0/temp" to "62",
        "$root/thermal_zone1/type" to "gpu0",
        "$root/thermal_zone1/temp" to "150000",
      )

    val reading = SysfsSource(PathMapReader(files)).poll(42L)!!

    assertEquals(62.0, reading.cpu!!, 0.0)
    assertEquals(150.0, reading.gpu!!, 0.0)
  }

  @Test
  fun `denied thermal tree still yields the kgsl gpu value and errors`() {
    val root = fixture("qualcomm")
    val source =
      SysfsSource(
        ThermalDeniedReader("$root/thermal"),
        "$root/thermal",
        "$root/kgsl/kgsl-3d0/temp",
      )

    val reading = source.poll(42L)!!

    assertNull(reading.cpu)
    assertEquals(47.0, reading.gpu!!, 0.0)
    assertNull(reading.battery)
    assertTrue(source.lastErrors.isNotEmpty())
  }

  @Test
  fun `overrides change the chosen cpu and gpu zone`() {
    val root = SysfsSource.DEFAULT_THERMAL_ROOT
    val files =
      mapOf(
        "$root/thermal_zone0/type" to "skin",
        "$root/thermal_zone0/temp" to "50000",
        "$root/thermal_zone1/type" to "cpu-0-0",
        "$root/thermal_zone1/temp" to "60000",
        "$root/thermal_zone2/type" to "gpu0",
        "$root/thermal_zone2/temp" to "47000",
      )

    val plain = SysfsSource(PathMapReader(files)).poll(42L)!!
    assertEquals(60.0, plain.cpu!!, 0.0)
    assertEquals(47.0, plain.gpu!!, 0.0)

    val skinAsGpu =
      SysfsSource(PathMapReader(files), overrides = { mapOf("skin" to ZoneCategory.GPU) })
        .poll(42L)!!
    assertEquals(60.0, skinAsGpu.cpu!!, 0.0)
    assertEquals(50.0, skinAsGpu.gpu!!, 0.0)

    val gpuAsCpu =
      SysfsSource(PathMapReader(files), overrides = { mapOf("gpu0" to ZoneCategory.CPU) })
        .poll(42L)!!
    assertEquals(47.0, gpuAsCpu.cpu!!, 0.0)
    assertNull(gpuAsCpu.gpu)
  }

  private fun source(name: String): SysfsSource {
    val root = fixture(name)
    return SysfsSource(DirectReader(), "$root/thermal", "$root/kgsl/kgsl-3d0/temp")
  }

  /** A source whose only readable node is the default kgsl temp path. */
  private fun kgslOnlySource(raw: String): SysfsSource =
    SysfsSource(PathMapReader(mapOf(kgslPath to raw)))

  private val kgslPath: String = SysfsSource.DEFAULT_KGSL_TEMP_PATH

  private fun fixture(name: String): String =
    File(javaClass.classLoader!!.getResource("fixtures/sysfs/$name")!!.toURI()).path
}

/**
 * In-memory [SysfsReader]: [files] maps absolute paths to raw contents, and
 * [listDir] derives child names from those keys. Unlisted paths record
 * `ENOENT`, mirroring a missing node.
 */
private class PathMapReader(private val files: Map<String, String>) : SysfsReader {
  private val log = mutableListOf<SysfsError>()

  override fun open(path: String): String? =
    files[path] ?: run {
      log += SysfsError(path, "ENOENT")
      null
    }

  override fun listDir(path: String): List<String> {
    val prefix = "$path/"
    val names =
      files.keys
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix).substringBefore('/') }
        .distinct()
    if (names.isEmpty()) log += SysfsError(path, "ENOENT")
    return names
  }

  override fun errors(): List<SysfsError> = log.toList()

  override fun clearErrors() {
    log.clear()
  }
}

/** Test reader that denies every path, including the thermal-zone directory. */
private class DeniedReader : SysfsReader {
  private val log = mutableListOf<SysfsError>()

  override fun open(path: String): String? {
    log += SysfsError(path, "EACCES")
    return null
  }

  override fun listDir(path: String): List<String> {
    log += SysfsError(path, "EACCES")
    return emptyList()
  }

  override fun errors(): List<SysfsError> = log.toList()

  override fun clearErrors() {
    log.clear()
  }
}

/** Denies the thermal tree only; every other path falls through to a [DirectReader]. */
private class ThermalDeniedReader(private val thermalRoot: String) : SysfsReader {
  private val delegate = DirectReader()
  private val log = mutableListOf<SysfsError>()

  override fun open(path: String): String? =
    if (path.startsWith(thermalRoot)) {
      log += SysfsError(path, "EACCES")
      null
    } else {
      delegate.open(path)
    }

  override fun listDir(path: String): List<String> =
    if (path.startsWith(thermalRoot)) {
      log += SysfsError(path, "EACCES")
      emptyList()
    } else {
      delegate.listDir(path)
    }

  override fun errors(): List<SysfsError> = log + delegate.errors()

  override fun clearErrors() {
    log.clear()
    delegate.clearErrors()
  }
}
