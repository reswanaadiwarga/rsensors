package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Versioned CSV session log (ANDROID.md §3, §9, §8 T12/T14).
 *
 * Expected file contents are hand-written from the format in the task spec: a
 * magic line, the start time, a fixed column header, then one row per poll with
 * empty cells for nulls, semicolon lists for cores and board, and v2's trailing
 * fps cell. The v1 fixture keeps the column set of a stored pre-T14 file, which
 * the decoder must still open with a null fps.
 */
class SessionLogTest {

  private val started = 1_795_000_000_000L

  private val fullSample = SessionSample(
    t = started + 5_000L,
    cpu = 42.1,
    cpuMax = 45.2,
    cores = listOf(40.1, 41.2),
    gpu = 38.5,
    gpuHot = 40.0,
    battery = 33.5,
    thermal = ThermalStatus.LIGHT,
    board = listOf("SOC" to 43.1, "SKIN" to 35.2),
    source = "dumpsys+battery+thermal",
    fps = 59.8,
  )

  @Test
  fun `encode writes the v2 header and one row per sample`() {
    val log = SessionLog(started, listOf(fullSample))

    val expected = "# rsensors session v2\n" +
      "# started=1795000000000\n" +
      "t,cpu,cpuMax,cores,gpu,gpuHot,battery,thermal,board,source,fps\n" +
      "1795000005000,42.1,45.2,40.1;41.2,38.5,40.0,33.5,LIGHT,SOC=43.1;SKIN=35.2," +
      "dumpsys+battery+thermal,59.8\n"

    assertEquals(expected, log.encode())
  }

  @Test
  fun `encode decode round-trip keeps nulls cores board thermal and fps`() {
    val sparse = SessionSample(t = started + 1_000L)
    val log = SessionLog(started, listOf(sparse, fullSample))

    assertEquals(log, decodeSession(log.encode()))
  }

  @Test
  fun `a real v1 file decodes with a null fps`() {
    // Verbatim header and rows from a session recorded by the 0.1.0 release on
    // the target phone, so the fixture keeps the real stored v1 column set.
    val text = "# rsensors session v1\n" +
      "# started=1795247232817\n" +
      "t,cpu,cpuMax,cores,gpu,gpuHot,battery,thermal,board,source\n" +
      "1795247232818,45.228,,,45.228,,34.5,NONE," +
      "SOC=45.228;SKIN=34.5;NPU=45.228;TPU=45.228;POWER_AMPLIFIER=37.479," +
      "dumpsys+battery+thermal\n" +
      "1795247237848,40.345,,,40.345,,34.7,NONE," +
      "SOC=40.345;SKIN=34.7;NPU=40.345;TPU=40.345;POWER_AMPLIFIER=37.408," +
      "dumpsys+battery+thermal\n"

    val decoded = decodeSession(text)

    assertNotNull(decoded)
    assertEquals(1_795_247_232_817L, decoded!!.startedAt)
    assertEquals(2, decoded.samples.size)
    assertEquals(
      SessionSample(
        t = 1_795_247_232_818L,
        cpu = 45.228,
        gpu = 45.228,
        battery = 34.5,
        thermal = ThermalStatus.NONE,
        board = listOf(
          "SOC" to 45.228,
          "SKIN" to 34.5,
          "NPU" to 45.228,
          "TPU" to 45.228,
          "POWER_AMPLIFIER" to 37.479,
        ),
        source = "dumpsys+battery+thermal",
        fps = null,
      ),
      decoded.samples[0],
    )
    assertEquals(ThermalStatus.NONE, decoded.samples[1].thermal)
    assertEquals(emptyList<Double>(), decoded.samples[1].cores)
    assertEquals("dumpsys+battery+thermal", decoded.samples[1].source)
    assertEquals(listOf(null, null), decoded.samples.map { it.fps })
  }

  @Test
  fun `a header-only v2 file decodes as a valid empty session`() {
    val log = SessionLog(started, emptyList())

    assertEquals(
      "# rsensors session v2\n" +
        "# started=1795000000000\n" +
        "t,cpu,cpuMax,cores,gpu,gpuHot,battery,thermal,board,source,fps\n",
      log.encode(),
    )
    assertEquals(log, decodeSession(log.encode()))
  }

  @Test
  fun `decode rejects text without the magic started line or header`() {
    val header = sessionHeader(started)

    assertNull(decodeSession(""))
    assertNull(decodeSession("not a session\n"))
    assertNull(decodeSession(header.replace(SESSION_MAGIC, "# rsensors session v3")))
    assertNull(decodeSession(header.replace(SESSION_COLUMNS, "t,cpu")))
    assertNull(decodeSession(header.replace("# started=1795000000000\n", "")))
    assertNull(decodeSession(header.replace("# started=1795000000000", "# started=nope")))
    // A v1 magic with the v2 header (or the reverse) is a damaged file, not a
    // session to guess about.
    assertNull(decodeSession(header.replace(SESSION_MAGIC, "# rsensors session v1")))
    assertNull(
      decodeSession(
        "# rsensors session v1\n" +
          "# started=1795000000000\n" +
          "$SESSION_COLUMNS\n",
      ),
    )
  }

  @Test
  fun `decode skips malformed rows but keeps the good ones`() {
    val good = fullSample.copy(t = started + 10_000L)
    val text = sessionHeader(started) +
      "1795000011000,not-a-number,,,,,,NONE,,\n" +
      "1795000012000,42.1,45.2,40.1;oops,38.5,40.0,33.5,LIGHT,,,1.0\n" +
      "1795000013000,42.1,45.2,40.1,38.5,40.0,33.5,NONE,SOC,,1.0\n" +
      "1795000014000,42.1,45.2,40.1,38.5,40.0,33.5,NOPE,,,1.0\n" +
      "1795000015000,42.1\n" +
      "1795000016000,42.1,45.2,40.1,38.5,40.0,33.5,NONE,,,not-a-number\n" +
      "\n" +
      good.encodeRow() + "\n"

    val decoded = decodeSession(text)

    assertNotNull(decoded)
    assertEquals(listOf(good), decoded!!.samples)
    assertEquals(started, decoded.startedAt)
  }

  @Test
  fun `decode accepts CRLF line endings`() {
    val log = SessionLog(started, listOf(fullSample))

    assertEquals(log, decodeSession(log.encode().replace("\n", "\r\n")))
  }

  @Test
  fun `toSessionSample maps every reading field`() {
    val reading = Reading(
      t = 7_000L,
      cpu = 1.0,
      cpuMax = 2.0,
      cores = listOf(3.0),
      gpu = 4.0,
      gpuHot = 5.0,
      battery = 6.0,
      thermalStatus = ThermalStatus.SEVERE,
      board = listOf("SOC" to 8.0),
      sourceName = "demo",
      fps = 9.0,
    )

    assertEquals(
      SessionSample(
        t = 7_000L,
        cpu = 1.0,
        cpuMax = 2.0,
        cores = listOf(3.0),
        gpu = 4.0,
        gpuHot = 5.0,
        battery = 6.0,
        thermal = ThermalStatus.SEVERE,
        board = listOf("SOC" to 8.0),
        source = "demo",
        fps = 9.0,
      ),
      reading.toSessionSample(),
    )
  }
}
