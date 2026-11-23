package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature-by-feature Diagnostics status for T19a (ANDROID.md §8 T19).
 *
 * Every expected value is derived from the task's status rules by hand, not
 * from [buildCapabilities] output. The helper's defaults describe one fully
 * healthy device stamp, so each test changes only the fact under test.
 */
class CapabilitiesTest {

  private fun facts(
    sdkInt: Int = 34,
    demo: Boolean = false,
    overlayRunning: Boolean = true,
    shizukuInstalled: Boolean = true,
    shizukuRunning: Boolean = true,
    shizukuGranted: Boolean = true,
    cpu: Double? = 51.0,
    gpu: Double? = 49.0,
    boardCount: Int = 2,
    battery: Double? = 36.0,
    thermalStatus: ThermalStatus = ThermalStatus.NONE,
    sampleAgeMs: Long? = 1_000L,
    sourceName: String = "dumpsys thermalservice",
    chainErrors: List<String> = emptyList(),
    ramUsedBytes: Long? = 4_500_000_000L,
    ramTotalBytes: Long? = 7_800_000_000L,
    fps: ProbeStatus? = ProbeStatus(CapabilityState.OK, "60 fps", "SurfaceFlinger"),
    meminfo: ProbeStatus? = ProbeStatus(CapabilityState.OK, "42 processes", "dumpsys meminfo"),
    stop: ProbeStatus? = ProbeStatus(CapabilityState.OK, "Last stop: com.example.notes", "am force-stop"),
  ): CapabilityFacts = CapabilityFacts(
    sdkInt = sdkInt,
    demo = demo,
    overlayRunning = overlayRunning,
    shizukuInstalled = shizukuInstalled,
    shizukuRunning = shizukuRunning,
    shizukuGranted = shizukuGranted,
    cpu = cpu,
    gpu = gpu,
    boardCount = boardCount,
    battery = battery,
    thermalStatus = thermalStatus,
    sampleAgeMs = sampleAgeMs,
    sourceName = sourceName,
    chainErrors = chainErrors,
    ramUsedBytes = ramUsedBytes,
    ramTotalBytes = ramTotalBytes,
    fps = fps,
    meminfo = meminfo,
    stop = stop,
  )

  private fun row(input: CapabilityFacts, feature: CapabilityFeature): CapabilityRow =
    buildCapabilities(input).first { it.feature == feature }

  @Test
  fun `healthy facts produce all eight rows in fixed order with labels`() {
    val rows = buildCapabilities(facts())

    assertEquals(
      listOf(
        CapabilityFeature.BATTERY,
        CapabilityFeature.THERMAL_STATUS,
        CapabilityFeature.CPU_GPU,
        CapabilityFeature.BOARD,
        CapabilityFeature.FPS,
        CapabilityFeature.RAM,
        CapabilityFeature.PROCESS_MEMORY,
        CapabilityFeature.STOP,
      ),
      rows.map { it.feature },
    )
    assertEquals(
      listOf(
        "Battery temperature",
        "Thermal status",
        "CPU/GPU temperature",
        "Board sensors",
        "FPS",
        "System RAM",
        "Process memory",
        "Stop app",
      ),
      rows.map { it.label },
    )
    assertTrue("not every healthy row is OK: $rows", rows.all { it.state == CapabilityState.OK })
    assertEquals("36\u00b0", row(facts(), CapabilityFeature.BATTERY).detail)
    assertEquals("NONE", row(facts(), CapabilityFeature.THERMAL_STATUS).detail)
    assertEquals("4.5 / 7.8 GB", row(facts(), CapabilityFeature.RAM).detail)
  }

  @Test
  fun `healthy CPU and GPU row carries both values and the dumpsys source`() {
    val cpuGpu = row(facts(), CapabilityFeature.CPU_GPU)

    assertEquals(CapabilityState.OK, cpuGpu.state)
    assertEquals("dumpsys thermalservice", cpuGpu.source)
    assertTrue("detail lacks CPU: ${cpuGpu.detail}", cpuGpu.detail.contains("CPU 51\u00b0"))
    assertTrue("detail lacks GPU: ${cpuGpu.detail}", cpuGpu.detail.contains("GPU 49\u00b0"))
  }

  @Test
  fun `stopped Shizuku blocks shell rows but leaves battery thermal and RAM OK`() {
    val rows = buildCapabilities(facts(shizukuRunning = false))

    listOf(
      CapabilityFeature.CPU_GPU,
      CapabilityFeature.BOARD,
      CapabilityFeature.FPS,
      CapabilityFeature.PROCESS_MEMORY,
      CapabilityFeature.STOP,
    ).forEach { feature ->
      val shell = rows.first { it.feature == feature }
      assertEquals("$feature state", CapabilityState.UNAVAILABLE, shell.state)
      assertTrue(
        "$feature detail was '${shell.detail}'",
        shell.detail.contains("Shizuku server is not running"),
      )
    }
    listOf(
      CapabilityFeature.BATTERY,
      CapabilityFeature.THERMAL_STATUS,
      CapabilityFeature.RAM,
    ).forEach { feature ->
      assertEquals("$feature state", CapabilityState.OK, rows.first { it.feature == feature }.state)
    }
  }

  @Test
  fun `Shizuku missing and permission missing are distinct reasons`() {
    val missing = row(
      facts(shizukuInstalled = false, shizukuRunning = false, shizukuGranted = false),
      CapabilityFeature.CPU_GPU,
    )
    val denied = row(
      facts(shizukuRunning = true, shizukuGranted = false),
      CapabilityFeature.CPU_GPU,
    )

    assertEquals(CapabilityState.UNAVAILABLE, missing.state)
    assertTrue(missing.detail, missing.detail.contains("Shizuku is not installed."))
    assertFalse(missing.detail, missing.detail.contains("server is not running"))

    assertEquals(CapabilityState.UNAVAILABLE, denied.state)
    assertTrue(denied.detail, denied.detail.contains("Shizuku permission is not granted."))
    assertFalse(denied.detail, denied.detail.contains("not installed"))
  }

  @Test
  fun `demo mode blocks shell rows while unshelled values still show`() {
    val rows = buildCapabilities(
      facts(
        demo = true,
        shizukuInstalled = false,
        shizukuRunning = false,
        shizukuGranted = false,
      ),
    )

    listOf(
      CapabilityFeature.CPU_GPU,
      CapabilityFeature.BOARD,
      CapabilityFeature.FPS,
      CapabilityFeature.PROCESS_MEMORY,
      CapabilityFeature.STOP,
    ).forEach { feature ->
      val shell = rows.first { it.feature == feature }
      assertEquals("$feature state", CapabilityState.UNAVAILABLE, shell.state)
      assertTrue("$feature detail was '${shell.detail}'", shell.detail.contains("Demo mode is on."))
    }
    assertEquals(CapabilityState.OK, rows.first { it.feature == CapabilityFeature.BATTERY }.state)
    assertEquals("36\u00b0", rows.first { it.feature == CapabilityFeature.BATTERY }.detail)
    assertEquals(CapabilityState.OK, rows.first { it.feature == CapabilityFeature.THERMAL_STATUS }.state)
    assertEquals(CapabilityState.OK, rows.first { it.feature == CapabilityFeature.RAM }.state)
  }

  @Test
  fun `only one temperature degrades the CPU GPU row`() {
    val cpuGpu = row(facts(gpu = null), CapabilityFeature.CPU_GPU)

    assertEquals(CapabilityState.DEGRADED, cpuGpu.state)
    assertTrue(cpuGpu.detail, cpuGpu.detail.contains("CPU 51\u00b0"))
    assertTrue(cpuGpu.detail, cpuGpu.detail.contains("GPU \u2014"))
  }

  @Test
  fun `no CPU GPU value uses the first chain error as detail`() {
    val cpuGpu = row(
      facts(
        cpu = null,
        gpu = null,
        chainErrors = listOf("dumpsys: read failed", "sysfs: denied"),
      ),
      CapabilityFeature.CPU_GPU,
    )

    assertEquals(CapabilityState.UNAVAILABLE, cpuGpu.state)
    assertEquals("dumpsys: read failed", cpuGpu.detail)
  }

  @Test
  fun `no CPU GPU value and no chain error names the missing measurement`() {
    val cpuGpu = row(
      facts(cpu = null, gpu = null, chainErrors = emptyList()),
      CapabilityFeature.CPU_GPU,
    )

    assertEquals(CapabilityState.UNAVAILABLE, cpuGpu.state)
    assertEquals("No CPU/GPU temperature was reported.", cpuGpu.detail)
  }

  @Test
  fun `thermal status needs API 29 and maps an unknown status to unavailable`() {
    val old = row(facts(sdkInt = 28), CapabilityFeature.THERMAL_STATUS)
    val unknown = row(
      facts(thermalStatus = ThermalStatus.UNKNOWN),
      CapabilityFeature.THERMAL_STATUS,
    )
    val present = row(
      facts(thermalStatus = ThermalStatus.SEVERE),
      CapabilityFeature.THERMAL_STATUS,
    )

    assertEquals(CapabilityState.UNAVAILABLE, old.state)
    assertTrue(old.detail, old.detail.contains("Requires Android 10 (API 29) or newer."))
    assertEquals(CapabilityState.UNAVAILABLE, unknown.state)
    assertTrue(unknown.detail, unknown.detail.contains("No thermal status was reported."))
    assertEquals(CapabilityState.OK, present.state)
    assertEquals("SEVERE", present.detail)
    assertEquals("PowerManager", present.source)
  }

  @Test
  fun `FPS probe states are used and a zero reading is OK`() {
    val zero = row(
      facts(fps = ProbeStatus(CapabilityState.OK, "0 fps (idle screen)", "SurfaceFlinger")),
      CapabilityFeature.FPS,
    )
    val failed = row(
      facts(fps = ProbeStatus(CapabilityState.UNAVAILABLE, "ERR no shell", "")),
      CapabilityFeature.FPS,
    )
    val missing = row(facts(fps = null), CapabilityFeature.FPS)

    assertEquals(CapabilityState.OK, zero.state)
    assertEquals("0 fps (idle screen)", zero.detail)
    assertEquals(CapabilityState.UNAVAILABLE, failed.state)
    assertTrue(failed.detail, failed.detail.contains("ERR no shell"))
    assertEquals("SurfaceFlinger", failed.source)
    assertEquals(CapabilityState.UNKNOWN, missing.state)
    assertTrue(missing.detail, missing.detail.contains("No FPS sample yet."))
  }

  @Test
  fun `process memory probe is used and no probe is ready to scan`() {
    val ok = row(facts(), CapabilityFeature.PROCESS_MEMORY)
    val failed = row(
      facts(meminfo = ProbeStatus(CapabilityState.UNAVAILABLE, "No process data", "dumpsys meminfo")),
      CapabilityFeature.PROCESS_MEMORY,
    )
    val missing = row(facts(meminfo = null), CapabilityFeature.PROCESS_MEMORY)

    assertEquals(CapabilityState.OK, ok.state)
    assertEquals("42 processes", ok.detail)
    assertEquals("dumpsys meminfo", ok.source)
    assertEquals(CapabilityState.UNAVAILABLE, failed.state)
    assertEquals("No process data", failed.detail)
    assertEquals(CapabilityState.OK, missing.state)
    assertTrue(missing.detail, missing.detail.contains("open the process page to scan"))
  }

  @Test
  fun `stop probe states are used and no probe is ready to stop`() {
    val ok = row(facts(), CapabilityFeature.STOP)
    val blocked = row(
      facts(stop = ProbeStatus(CapabilityState.BLOCKED, "This app is protected.", "am force-stop")),
      CapabilityFeature.STOP,
    )
    val failed = row(
      facts(stop = ProbeStatus(CapabilityState.UNAVAILABLE, "No response from Shizuku", "am force-stop")),
      CapabilityFeature.STOP,
    )
    val missing = row(facts(stop = null), CapabilityFeature.STOP)

    assertEquals(CapabilityState.OK, ok.state)
    assertEquals("Last stop: com.example.notes", ok.detail)
    assertEquals(CapabilityState.BLOCKED, blocked.state)
    assertEquals("This app is protected.", blocked.detail)
    assertEquals(CapabilityState.UNAVAILABLE, failed.state)
    assertEquals("No response from Shizuku", failed.detail)
    assertEquals(CapabilityState.OK, missing.state)
    assertTrue(missing.detail, missing.detail.contains("no stop attempted"))
    assertEquals("am force-stop", missing.source)
  }

  @Test
  fun `a shell blocked meminfo probe is ignored once the shell is healthy`() {
    val meminfo = row(
      facts(
        meminfo = ProbeStatus(
          CapabilityState.UNAVAILABLE,
          "Process memory needs Shizuku",
          "dumpsys meminfo",
          shellBlocked = true,
        ),
      ),
      CapabilityFeature.PROCESS_MEMORY,
    )

    assertEquals(CapabilityState.OK, meminfo.state)
    assertTrue(meminfo.detail, meminfo.detail.contains("open the process page to scan"))
    assertFalse(meminfo.detail, meminfo.detail.contains("needs Shizuku"))
  }

  @Test
  fun `a shell blocked stop probe is ignored once the shell is healthy`() {
    val stop = row(
      facts(
        stop = ProbeStatus(
          CapabilityState.UNAVAILABLE,
          "Grant Shizuku permission to stop apps",
          "am force-stop",
          shellBlocked = true,
        ),
      ),
      CapabilityFeature.STOP,
    )

    assertEquals(CapabilityState.OK, stop.state)
    assertTrue(stop.detail, stop.detail.contains("no stop attempted"))
    assertFalse(stop.detail, stop.detail.contains("Grant Shizuku"))
  }

  @Test
  fun `non-shell command failures are still surfaced`() {
    val meminfo = row(
      facts(
        meminfo = ProbeStatus(CapabilityState.UNAVAILABLE, "No process data", "dumpsys meminfo"),
      ),
      CapabilityFeature.PROCESS_MEMORY,
    )
    val stop = row(
      facts(
        stop = ProbeStatus(
          CapabilityState.UNAVAILABLE,
          "No response from Shizuku",
          "am force-stop",
        ),
      ),
      CapabilityFeature.STOP,
    )

    assertEquals(CapabilityState.UNAVAILABLE, meminfo.state)
    assertEquals("No process data", meminfo.detail)
    assertEquals(CapabilityState.UNAVAILABLE, stop.state)
    assertEquals("No response from Shizuku", stop.detail)
  }

  @Test
  fun `a shell blocked FPS probe falls back to no sample`() {
    val fps = row(
      facts(
        fps = ProbeStatus(
          CapabilityState.UNAVAILABLE,
          "No reply from Shizuku.",
          "SurfaceFlinger",
          shellBlocked = true,
        ),
      ),
      CapabilityFeature.FPS,
    )

    assertEquals(CapabilityState.UNKNOWN, fps.state)
    assertEquals("No FPS sample yet.", fps.detail)
  }

  @Test
  fun `RAM without a total is unavailable and a valid total is formatted`() {
    val zero = row(facts(ramTotalBytes = 0L), CapabilityFeature.RAM)
    val ok = row(facts(), CapabilityFeature.RAM)

    assertEquals(CapabilityState.UNAVAILABLE, zero.state)
    assertTrue(zero.detail, zero.detail.contains("no memory total"))
    assertEquals(CapabilityState.OK, ok.state)
    assertEquals("4.5 / 7.8 GB", ok.detail)
    assertEquals("ActivityManager", ok.source)
  }

  @Test
  fun `stopped overlay makes shell rows say monitoring is stopped`() {
    val rows = buildCapabilities(facts(overlayRunning = false))

    listOf(
      CapabilityFeature.CPU_GPU,
      CapabilityFeature.BOARD,
      CapabilityFeature.FPS,
      CapabilityFeature.PROCESS_MEMORY,
      CapabilityFeature.STOP,
    ).forEach { feature ->
      val shell = rows.first { it.feature == feature }
      assertEquals("$feature state", CapabilityState.UNAVAILABLE, shell.state)
      assertTrue(
        "$feature detail was '${shell.detail}'",
        shell.detail.contains("Monitoring is stopped") && shell.detail.contains("start"),
      )
    }
    assertEquals(CapabilityState.OK, rows.first { it.feature == CapabilityFeature.BATTERY }.state)
  }

  @Test
  fun `stale sample degrades CPU GPU and board rows with the age`() {
    val stale = facts(sampleAgeMs = STALE_AFTER_MS + 1_000L)
    val cpuGpu = row(stale, CapabilityFeature.CPU_GPU)
    val board = row(stale, CapabilityFeature.BOARD)

    assertEquals(CapabilityState.DEGRADED, cpuGpu.state)
    assertTrue(cpuGpu.detail, cpuGpu.detail.contains("16s ago"))
    assertTrue(cpuGpu.detail, cpuGpu.detail.contains("CPU 51\u00b0"))
    assertEquals(CapabilityState.DEGRADED, board.state)
    assertTrue(board.detail, board.detail.contains("16s ago"))
    assertTrue(board.detail, board.detail.contains("2 board sensors"))
  }
}
