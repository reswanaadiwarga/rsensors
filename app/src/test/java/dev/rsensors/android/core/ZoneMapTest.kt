package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zone heuristics from ANDROID.md §5. */
class ZoneMapTest {

  @Test
  fun `qualcomm zone names map to cpu gpu and battery`() {
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("cpu-0-0-usr"))
    assertEquals(ZoneCategory.GPU, ZoneMap.categorize("gpuss-0-usr"))
    assertEquals(ZoneCategory.BATTERY, ZoneMap.categorize("battery"))
  }

  @Test
  fun `mediatek zone names map to cpu gpu and battery`() {
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("mtktscpu"))
    assertEquals(ZoneCategory.GPU, ZoneMap.categorize("mtktsgpu"))
    assertEquals(ZoneCategory.BATTERY, ZoneMap.categorize("mtktsbattery"))
  }

  @Test
  fun `exynos zone names map to cpu and gpu`() {
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("cpu-0-0"))
    assertEquals(ZoneCategory.GPU, ZoneMap.categorize("gpu0"))
  }

  @Test
  fun `type is lowercased and trimmed before scoring`() {
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("  CPU-0-0-usr \n"))
    assertEquals(ZoneCategory.GPU, ZoneMap.categorize("GPUSS-0-USR"))
  }

  @Test
  fun `soc and exact aliases score as cpu`() {
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("soc"))
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("soc_thermal"))
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("cpu_thermal"))
    assertEquals(ZoneCategory.GPU, ZoneMap.categorize("gpu_thermal"))
  }

  @Test
  fun `kirin and samsung sysfs aliases map to cpu`() {
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("soc-thermal"))
    assertEquals(ZoneCategory.CPU, ZoneMap.categorize("ap"))
  }

  @Test
  fun `samsung and qualcomm gpu aliases map to gpu`() {
    assertEquals(ZoneCategory.GPU, ZoneMap.categorize("gpu_tmu"))
    assertEquals(ZoneCategory.GPU, ZoneMap.categorize("kgsl"))
  }

  @Test
  fun `subbat is never a battery zone`() {
    assertEquals(ZoneCategory.OTHER, ZoneMap.categorize("subbat"))
  }

  @Test
  fun `unrelated zones are other`() {
    assertEquals(ZoneCategory.OTHER, ZoneMap.categorize("skin"))
    assertEquals(ZoneCategory.OTHER, ZoneMap.categorize("quiet-therm"))
    assertEquals(ZoneCategory.OTHER, ZoneMap.categorize(""))
  }

  @Test
  fun `cpu type that also names gpu or battery is other`() {
    assertEquals(ZoneCategory.OTHER, ZoneMap.categorize("cpu_gpu"))
    assertEquals(ZoneCategory.OTHER, ZoneMap.categorize("cpu-batt"))
  }

  @Test
  fun `pick prefers the higher score over the lower index`() {
    val picked =
      ZoneMap.pick(
        listOf(
          Zone(0, "soc_thermal", 40.0),
          Zone(1, "cpu-0-0-usr", 62.0),
        ),
      )
    assertEquals(1, picked.getValue(ZoneCategory.CPU).index)
    assertEquals(62.0, picked.getValue(ZoneCategory.CPU).tempC!!, 0.0)
  }

  @Test
  fun `pick breaks equal scores by the lowest zone index`() {
    val picked =
      ZoneMap.pick(
        listOf(
          Zone(3, "gpu0", 46.0),
          Zone(1, "gpu0", 45.0),
        ),
      )
    assertEquals(1, picked.getValue(ZoneCategory.GPU).index)
    assertEquals(45.0, picked.getValue(ZoneCategory.GPU).tempC!!, 0.0)
  }

  @Test
  fun `pick skips zones without a temperature`() {
    val picked =
      ZoneMap.pick(
        listOf(
          Zone(0, "cpu-0-0", null),
          Zone(1, "mtktscpu", 50.0),
        ),
      )
    assertEquals(1, picked.getValue(ZoneCategory.CPU).index)
  }

  @Test
  fun `pick never returns other and omits missing categories`() {
    val picked = ZoneMap.pick(listOf(Zone(0, "skin", 35.0)))
    assertTrue(picked.isEmpty())
  }

  @Test
  fun `override wins over the heuristic for the same zone`() {
    val picked =
      ZoneMap.pick(
        listOf(
          Zone(0, "gpu0", 45.0),
          Zone(5, "skin", 50.0),
        ),
        mapOf("skin" to ZoneCategory.GPU),
      )
    assertEquals(5, picked.getValue(ZoneCategory.GPU).index)
    assertEquals(50.0, picked.getValue(ZoneCategory.GPU).tempC!!, 0.0)
  }

  @Test
  fun `override to other removes the zone and an absent override falls back`() {
    val zones = listOf(Zone(1, "cpu-0-0", 60.0))

    assertTrue(ZoneMap.pick(zones, mapOf("cpu-0-0" to ZoneCategory.OTHER)).isEmpty())
    assertEquals(1, ZoneMap.pick(zones, emptyMap()).getValue(ZoneCategory.CPU).index)
  }

  @Test
  fun `override keys are trimmed and lowercased`() {
    val picked =
      ZoneMap.pick(
        listOf(Zone(2, "mtktsgpu", 47.0)),
        mapOf("  MTKTSGPU " to ZoneCategory.BATTERY),
      )
    assertEquals(2, picked.getValue(ZoneCategory.BATTERY).index)
  }

  @Test
  fun `overridden zones tie-break on the lowest index`() {
    val picked =
      ZoneMap.pick(
        listOf(
          Zone(4, "skin", 51.0),
          Zone(2, "skin", 49.0),
          Zone(0, "gpu0", 45.0),
        ),
        mapOf("skin" to ZoneCategory.GPU),
      )
    assertEquals(2, picked.getValue(ZoneCategory.GPU).index)
    assertEquals(49.0, picked.getValue(ZoneCategory.GPU).tempC!!, 0.0)
  }
}
