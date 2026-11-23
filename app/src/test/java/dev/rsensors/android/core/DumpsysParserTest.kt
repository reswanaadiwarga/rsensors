package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing of `dumpsys thermalservice` (ANDROID.md §5, §8 T8).
 *
 * The fixture mirrors the target phone's dump order, which puts the Cached
 * block *before* the Current HAL block. That order is what makes the block gate
 * load-bearing: a whole-dump scan returns the stale 44.776 °C CPU value instead
 * of the live 38.351 °C one.
 */
class DumpsysParserTest {

  @Test
  fun `current hal block wins over the cached block`() {
    val reading = parseDumpsysThermal(T0_DUMP)!!

    assertEquals(0L, reading.t)
    assertEquals(38.351, reading.cpu!!, 1e-9)
    assertEquals(38.351, reading.gpu!!, 1e-9)
    assertEquals(33.5, reading.battery!!, 1e-9)
    assertEquals(ThermalStatus.UNKNOWN, reading.thermalStatus)
    assertEquals(
      listOf(
        "SOC" to 38.351,
        "SKIN" to 33.5,
        "NPU" to 38.351,
        "TPU" to 38.351,
        "POWER_AMPLIFIER" to 35.56,
      ),
      reading.board,
    )
  }

  @Test
  fun `block stops at the cooling devices header`() {
    // A CPU line after the stop header must not be read, even though it matches.
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=30.0, mType=0, mName=CPU, mStatus=0}
      |Current cooling devices from HAL:
      |	Temperature{mValue=99.0, mType=0, mName=CPU, mStatus=0}
    """.trimMargin()

    assertEquals(30.0, parseDumpsysThermal(text)!!.cpu!!, 1e-9)
  }

  @Test
  fun `missing gpu leaves gpu null`() {
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=38.351, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=33.5, mType=2, mName=BATTERY, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(38.351, reading.cpu!!, 1e-9)
    assertNull(reading.gpu)
    assertEquals(33.5, reading.battery!!, 1e-9)
    assertEquals(emptyList<Pair<String, Double>>(), reading.board)
  }

  @Test
  fun `a block without cpu gpu or battery returns null`() {
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=33.5, mType=3, mName=SKIN, mStatus=0}
      |	Temperature{mValue=35.56, mType=5, mName=POWER_AMPLIFIER, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    assertNull(parseDumpsysThermal(text))
  }

  @Test
  fun `empty text header only and garbage return null`() {
    assertNull(parseDumpsysThermal(""))
    assertNull(parseDumpsysThermal("   \n  \n"))
    assertNull(parseDumpsysThermal("Current temperatures from HAL:\n"))
    assertNull(parseDumpsysThermal("Temperature{mValue=38.351, mType=0, mName=CPU, mStatus=0}"))
    assertNull(parseDumpsysThermal("thermal status 0; nothing to see here"))
  }

  @Test
  fun `negative mValue parses`() {
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=-5.5, mType=0, mName=CPU, mStatus=0}
    """.trimMargin()

    assertEquals(-5.5, parseDumpsysThermal(text)!!.cpu!!, 1e-9)
  }

  @Test
  fun `an unknown name falls back to its mType`() {
    // ANDROID.md §5: prefer mName, fall back to mType (0 CPU, 1 GPU, 2 BATTERY).
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=41.0, mType=0, mName=CPU_CORE_G0, mStatus=0}
      |	Temperature{mValue=45.0, mType=1, mName=GPU_CORE, mStatus=0}
      |	Temperature{mValue=34.0, mType=2, mName=BATTERY_UNKNOWN, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(41.0, reading.cpu!!, 1e-9)
    assertEquals(45.0, reading.gpu!!, 1e-9)
    assertEquals(34.0, reading.battery!!, 1e-9)
  }

  @Test
  fun `duplicate cpu and gpu entries keep the hottest value`() {
    // The HAL can list several CPU/GPU sensors; the display shows the hottest.
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=61.3, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=38.351, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=38.351, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=56.0, mType=1, mName=GPU, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(61.3, reading.cpu!!, 1e-9)
    assertEquals(56.0, reading.gpu!!, 1e-9)
  }

  @Test
  fun `battery and board sensors keep the first occurrence`() {
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=33.5, mType=2, mName=BATTERY, mStatus=0}
      |	Temperature{mValue=34.5, mType=2, mName=BATTERY, mStatus=0}
      |	Temperature{mValue=30.0, mType=3, mName=SKIN, mStatus=0}
      |	Temperature{mValue=31.0, mType=3, mName=SKIN, mStatus=0}
      |	Temperature{mValue=35.0, mType=13, mName=SOC, mStatus=0}
      |	Temperature{mValue=36.0, mType=13, mName=SOC, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(33.5, reading.battery!!, 1e-9)
    assertEquals(listOf("SOC" to 35.0, "SKIN" to 30.0), reading.board)
  }

  @Test
  fun `samsung AP and BAT aliases map to cpu and battery`() {
    // Unrelated mType values prove the name alias, not the numeric fallback, wins.
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=55.2, mType=14, mName= AP , mStatus=0}
      |	Temperature{mValue=36.4, mType=14, mName= bat , mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(55.2, reading.cpu!!, 1e-9)
    assertEquals(36.4, reading.battery!!, 1e-9)
    assertNull(reading.gpu)
  }

  @Test
  fun `SUBBAT is ignored even when its mType says battery`() {
    // Samsung exposes SUBBAT (a secondary battery rail) with mType 2; it must not
    // be displayed as the main battery temperature.
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=38.0, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=36.0, mType=2, mName=SUBBAT, mStatus=0}
      |	Temperature{mValue=35.0, mType=2, mName=subbat, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(38.0, reading.cpu!!, 1e-9)
    assertNull(reading.battery)
  }

  @Test
  fun `out of range entries are rejected without masking a valid duplicate`() {
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=999.0, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=45.0, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=-273.15, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=40.0, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=33.5, mType=2, mName=BATTERY, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(45.0, reading.cpu!!, 1e-9)
    assertEquals(40.0, reading.gpu!!, 1e-9)
    assertEquals(33.5, reading.battery!!, 1e-9)
  }

  @Test
  fun `a block with only implausible values returns null`() {
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=999.0, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=200.0, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=-273.15, mType=2, mName=BATTERY, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    assertNull(parseDumpsysThermal(text))
  }

  @Test
  fun `the sanity range endpoints are inclusive`() {
    val text = """
      |Current temperatures from HAL:
      |	Temperature{mValue=-40.0, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=150.0, mType=1, mName=GPU, mStatus=0}
      |Current cooling devices from HAL:
    """.trimMargin()

    val reading = parseDumpsysThermal(text)!!

    assertEquals(-40.0, reading.cpu!!, 1e-9)
    assertEquals(150.0, reading.gpu!!, 1e-9)
  }

  @Test
  fun `a cached-only dump still returns null`() {
    val text = """
      |Cached temperatures:
      |	Temperature{mValue=44.776, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=44.761, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=31.2, mType=2, mName=BATTERY, mStatus=0}
      |HAL Ready: true
    """.trimMargin()

    assertNull(parseDumpsysThermal(text))
  }

  companion object {
    /** Real `dumpsys thermalservice` layout with the T0 HAL sample (§10). */
    val T0_DUMP = """
      |IsStatusOverride: false
      |Thermal Status: 0
      |Cached temperatures:
      |	Temperature{mValue=44.776, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=44.761, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=44.776, mType=9, mName=NPU, mStatus=0}
      |	Temperature{mValue=44.776, mType=13, mName=SOC, mStatus=0}
      |	Temperature{mValue=44.776, mType=10, mName=TPU, mStatus=0}
      |	Temperature{mValue=31.2, mType=3, mName=SKIN, mStatus=0}
      |	Temperature{mValue=31.2, mType=2, mName=BATTERY, mStatus=0}
      |	Temperature{mValue=41.784, mType=5, mName=POWER_AMPLIFIER, mStatus=0}
      |HAL Ready: true
      |HAL connection:
      |	ThermalHAL AIDL 3  connected: yes
      |Current temperatures from HAL:
      |	Temperature{mValue=38.351, mType=0, mName=CPU, mStatus=0}
      |	Temperature{mValue=38.351, mType=1, mName=GPU, mStatus=0}
      |	Temperature{mValue=33.5, mType=2, mName=BATTERY, mStatus=0}
      |	Temperature{mValue=33.5, mType=3, mName=SKIN, mStatus=0}
      |	Temperature{mValue=35.56, mType=5, mName=POWER_AMPLIFIER, mStatus=0}
      |	Temperature{mValue=38.351, mType=9, mName=NPU, mStatus=0}
      |	Temperature{mValue=38.351, mType=10, mName=TPU, mStatus=0}
      |	Temperature{mValue=38.351, mType=13, mName=SOC, mStatus=0}
      |Current cooling devices from HAL:
      |	CoolingDevice{mValue=21, mType=3, mName=devfreq-13000000.mali}
      |Temperature static thresholds from HAL:
      |	TemperatureThreshold{mType=0, mName=CPU, mHotThrottlingThresholds=[NaN, 85.0]}
    """.trimMargin()
  }
}
