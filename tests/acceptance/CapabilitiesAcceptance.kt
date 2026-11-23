// Independent acceptance checks for T19a feature-by-feature Diagnostics status.
//
// Expected values are derived by hand from the task spec (row order, labels,
// precedence, exact reason copy where the spec quotes it, and the existing
// formatting contracts fmtTemp/fmtRam/fmtAge). They are never copied from
// buildCapabilities output or from the implementer's unit test.
//
// Compile against the app's core sources and run CapabilitiesAcceptanceKt; it
// exits non-zero on any failure.
import dev.rsensors.android.core.CapabilityFacts
import dev.rsensors.android.core.CapabilityFeature
import dev.rsensors.android.core.CapabilityRow
import dev.rsensors.android.core.CapabilityState
import dev.rsensors.android.core.ProbeStatus
import dev.rsensors.android.core.STALE_AFTER_MS
import dev.rsensors.android.core.ThermalStatus
import dev.rsensors.android.core.buildCapabilities
import dev.rsensors.android.overlay.DiagnosticsStore
import kotlin.system.exitProcess

private var failures = 0
private var checks = 0

private fun expect(condition: Boolean, message: String) {
  checks++
  if (!condition) {
    failures++
    println("FAIL: $message")
  } else {
    println("ok: $message")
  }
}

private fun expectEquals(actual: Any?, expected: Any?, label: String) {
  expect(actual == expected, "$label: expected <$expected>, got <$actual>")
}

/** One healthy stamp. Each check changes only the fact under test. */
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

private fun rows(input: CapabilityFacts): List<CapabilityRow> = buildCapabilities(input)

private fun row(input: CapabilityFacts, feature: CapabilityFeature): CapabilityRow =
  rows(input).first { it.feature == feature }

private val SHELL_ROWS = listOf(
  CapabilityFeature.CPU_GPU,
  CapabilityFeature.BOARD,
  CapabilityFeature.FPS,
  CapabilityFeature.PROCESS_MEMORY,
  CapabilityFeature.STOP,
)

fun main() {
  // C1: exactly eight rows, spec order, spec labels.
  val healthy = rows(facts())
  expectEquals(healthy.size, 8, "row count")
  expectEquals(
    healthy.map { it.feature },
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
    "feature order",
  )
  expectEquals(
    healthy.map { it.label },
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
    "labels",
  )

  // C2: healthy facts are all OK, with the spec's value text.
  expect(healthy.all { it.state == CapabilityState.OK }, "healthy rows all OK: $healthy")
  expectEquals(row(facts(), CapabilityFeature.BATTERY).detail, "36\u00b0", "battery fmtTemp")
  expectEquals(row(facts(), CapabilityFeature.THERMAL_STATUS).detail, "NONE", "thermal status name")
  expectEquals(
    row(facts(), CapabilityFeature.THERMAL_STATUS).source,
    "PowerManager",
    "thermal source",
  )
  val cpuGpu = row(facts(), CapabilityFeature.CPU_GPU)
  expect(cpuGpu.detail.contains("CPU 51\u00b0"), "CPU value in detail: ${cpuGpu.detail}")
  expect(cpuGpu.detail.contains("GPU 49\u00b0"), "GPU value in detail: ${cpuGpu.detail}")
  expectEquals(cpuGpu.source, "dumpsys thermalservice", "CPU/GPU source from sourceName")
  expectEquals(row(facts(), CapabilityFeature.BOARD).detail, "2 board sensors.", "board count")
  expectEquals(row(facts(), CapabilityFeature.FPS).detail, "60 fps", "FPS probe passthrough")
  expectEquals(row(facts(), CapabilityFeature.RAM).detail, "4.5 / 7.8 GB", "RAM fmtRam")
  expectEquals(row(facts(), CapabilityFeature.RAM).source, "ActivityManager", "RAM source")
  expectEquals(
    row(facts(), CapabilityFeature.PROCESS_MEMORY).detail,
    "42 processes",
    "process probe passthrough",
  )
  expectEquals(
    row(facts(), CapabilityFeature.STOP).detail,
    "Last stop: com.example.notes",
    "stop probe passthrough",
  )

  // C2b: the formatters really round/divide (values chosen independently of
  // any sample fixture): 41.6 -> 42 degrees, 63.4 -> 63, 58.6 -> 59,
  // 3.2e9/6.4e9 -> 3.2 / 6.4 GB.
  val alt = rows(
    facts(
      battery = 41.6,
      cpu = 63.4,
      gpu = 58.6,
      boardCount = 5,
      ramUsedBytes = 3_200_000_000L,
      ramTotalBytes = 6_400_000_000L,
    ),
  )
  expectEquals(alt.first { it.feature == CapabilityFeature.BATTERY }.detail, "42\u00b0", "battery rounds")
  val altCpu = alt.first { it.feature == CapabilityFeature.CPU_GPU }.detail
  expect(altCpu.contains("CPU 63\u00b0"), "CPU rounds: $altCpu")
  expect(altCpu.contains("GPU 59\u00b0"), "GPU rounds: $altCpu")
  expectEquals(
    alt.first { it.feature == CapabilityFeature.BOARD }.detail,
    "5 board sensors.",
    "board count from input",
  )
  expectEquals(
    alt.first { it.feature == CapabilityFeature.RAM }.detail,
    "3.2 / 6.4 GB",
    "RAM decimal GB division",
  )

  // C3: the shell gate order demo -> installed -> running -> granted -> overlay.
  SHELL_ROWS.forEach { feature ->
    val demo = row(
      facts(demo = true, shizukuInstalled = false, shizukuRunning = false, shizukuGranted = false, overlayRunning = false),
      feature,
    )
    expect(
      demo.state == CapabilityState.UNAVAILABLE && demo.detail.contains("demo", ignoreCase = true),
      "$feature demo gate: $demo",
    )

    val missing = row(
      facts(shizukuInstalled = false, shizukuRunning = false, shizukuGranted = false),
      feature,
    )
    expect(
      missing.state == CapabilityState.UNAVAILABLE,
      "$feature not-installed state: $missing",
    )
    expectEquals(missing.detail, "Shizuku is not installed.", "$feature not-installed detail")

    val stopped = row(facts(shizukuRunning = false, shizukuGranted = false), feature)
    expect(stopped.state == CapabilityState.UNAVAILABLE, "$feature stopped state: $stopped")
    expectEquals(stopped.detail, "Shizuku server is not running.", "$feature stopped detail")

    val denied = row(facts(shizukuGranted = false), feature)
    expect(denied.state == CapabilityState.UNAVAILABLE, "$feature denied state: $denied")
    expectEquals(denied.detail, "Shizuku permission is not granted.", "$feature denied detail")

    val noOverlay = row(facts(overlayRunning = false), feature)
    expect(noOverlay.state == CapabilityState.UNAVAILABLE, "$feature overlay state: $noOverlay")
    expect(
      noOverlay.detail.contains("Monitoring is stopped"),
      "$feature overlay detail: ${noOverlay.detail}",
    )

    // Permission outranks the overlay, and the stopped server outranks both.
    val deniedWhileStopped = row(facts(shizukuGranted = false, overlayRunning = false), feature)
    expectEquals(
      deniedWhileStopped.detail,
      "Shizuku permission is not granted.",
      "$feature permission outranks overlay",
    )
    val serverDownWhileStopped = row(
      facts(shizukuRunning = false, shizukuGranted = false, overlayRunning = false),
      feature,
    )
    expectEquals(
      serverDownWhileStopped.detail,
      "Shizuku server is not running.",
      "$feature server outranks permission and overlay",
    )

    expectEquals(row(facts(), feature).state, CapabilityState.OK, "$feature gate open uses probe")
  }

  // C4: BATTERY has no Shizuku dependency; null is its own exact reason.
  val noBattery = row(
    facts(battery = null, shizukuInstalled = false, shizukuRunning = false, shizukuGranted = false),
    CapabilityFeature.BATTERY,
  )
  expectEquals(noBattery.state, CapabilityState.UNAVAILABLE, "battery null state")
  expectEquals(
    noBattery.detail,
    "Battery temperature is not reported.",
    "battery null detail",
  )

  // C5: thermal API floor and UNKNOWN mapping.
  val api28 = row(facts(sdkInt = 28, thermalStatus = ThermalStatus.SEVERE), CapabilityFeature.THERMAL_STATUS)
  expectEquals(api28.state, CapabilityState.UNAVAILABLE, "thermal API 28 state")
  expect(api28.detail.contains("Android 10"), "thermal API 28 detail: ${api28.detail}")
  val unknownThermal = row(
    facts(sdkInt = 29, thermalStatus = ThermalStatus.UNKNOWN),
    CapabilityFeature.THERMAL_STATUS,
  )
  expectEquals(unknownThermal.state, CapabilityState.UNAVAILABLE, "thermal UNKNOWN state")
  expect(
    unknownThermal.detail.contains("No thermal status"),
    "thermal UNKNOWN detail: ${unknownThermal.detail}",
  )
  val severe = row(facts(sdkInt = 29, thermalStatus = ThermalStatus.SEVERE), CapabilityFeature.THERMAL_STATUS)
  expectEquals(severe.state, CapabilityState.OK, "thermal SEVERE state")
  expectEquals(severe.detail, "SEVERE", "thermal SEVERE detail")
  expectEquals(severe.source, "PowerManager", "thermal SEVERE source")

  // C6: CPU/GPU partial, none, chain error, staleness boundary.
  val partial = row(facts(gpu = null), CapabilityFeature.CPU_GPU)
  expectEquals(partial.state, CapabilityState.DEGRADED, "CPU-only state")
  expect(partial.detail.contains("CPU 51\u00b0"), "CPU-only keeps CPU: ${partial.detail}")
  expect(partial.detail.contains("GPU \u2014"), "CPU-only dashes GPU: ${partial.detail}")

  val none = row(facts(cpu = null, gpu = null), CapabilityFeature.CPU_GPU)
  expectEquals(none.state, CapabilityState.UNAVAILABLE, "no CPU/GPU state")
  expectEquals(
    none.detail,
    "No CPU/GPU temperature was reported.",
    "no CPU/GPU and no chain error names the reason",
  )

  val chain = row(
    facts(cpu = null, gpu = null, chainErrors = listOf("dumpsys: read failed", "sysfs: denied")),
    CapabilityFeature.CPU_GPU,
  )
  expectEquals(chain.state, CapabilityState.UNAVAILABLE, "chain-error state")
  expectEquals(chain.detail, "dumpsys: read failed", "first chain error is the detail")

  val atThreshold = row(facts(sampleAgeMs = STALE_AFTER_MS), CapabilityFeature.CPU_GPU)
  expectEquals(atThreshold.state, CapabilityState.OK, "age == STALE_AFTER_MS is not stale")
  val pastThreshold = row(facts(sampleAgeMs = STALE_AFTER_MS + 1_000L), CapabilityFeature.CPU_GPU)
  expectEquals(pastThreshold.state, CapabilityState.DEGRADED, "stale CPU/GPU state")
  expect(
    pastThreshold.detail.contains("CPU 51\u00b0") && pastThreshold.detail.contains("GPU 49\u00b0"),
    "stale CPU/GPU keeps values: ${pastThreshold.detail}",
  )
  expect(
    pastThreshold.detail.contains("16s ago"),
    "stale CPU/GPU age: ${pastThreshold.detail}",
  )
  expectEquals(
    row(facts(sourceName = ""), CapabilityFeature.CPU_GPU).source,
    "dumpsys thermalservice",
    "blank sourceName falls back for CPU/GPU",
  )
  expectEquals(
    row(facts(sourceName = "sysfs+thermal"), CapabilityFeature.CPU_GPU).source,
    "sysfs+thermal",
    "sourceName is used when present",
  )

  // C7: BOARD zero, positive and stale.
  val noBoard = row(facts(boardCount = 0), CapabilityFeature.BOARD)
  expectEquals(noBoard.state, CapabilityState.UNAVAILABLE, "board zero state")
  expectEquals(noBoard.detail, "No board sensors were reported.", "board zero detail")
  expectEquals(row(facts(boardCount = 3), CapabilityFeature.BOARD).detail, "3 board sensors.", "board three")
  val staleBoard = row(facts(sampleAgeMs = STALE_AFTER_MS + 1_000L), CapabilityFeature.BOARD)
  expectEquals(staleBoard.state, CapabilityState.DEGRADED, "stale board state")
  expect(staleBoard.detail.contains("2 board sensors"), "stale board count: ${staleBoard.detail}")
  expect(staleBoard.detail.contains("16s ago"), "stale board age: ${staleBoard.detail}")

  // C8: FPS probe passthrough, 0 fps is OK, blank source fallback, no probe.
  val zeroFps = row(
    facts(fps = ProbeStatus(CapabilityState.OK, "0 fps (idle screen)", "SurfaceFlinger")),
    CapabilityFeature.FPS,
  )
  expectEquals(zeroFps.state, CapabilityState.OK, "0 fps is OK")
  expectEquals(zeroFps.detail, "0 fps (idle screen)", "0 fps idle detail")
  val errFps = row(
    facts(fps = ProbeStatus(CapabilityState.UNAVAILABLE, "ERR no shell", "")),
    CapabilityFeature.FPS,
  )
  expectEquals(errFps.state, CapabilityState.UNAVAILABLE, "ERR fps state")
  expectEquals(errFps.detail, "ERR no shell", "ERR fps detail")
  expectEquals(errFps.source, "SurfaceFlinger", "blank FPS source fallback")
  val noFps = row(facts(fps = null), CapabilityFeature.FPS)
  expectEquals(noFps.state, CapabilityState.UNKNOWN, "no FPS probe state")
  expectEquals(noFps.detail, "No FPS sample yet.", "no FPS probe detail")
  expectEquals(noFps.source, "SurfaceFlinger", "no FPS probe source")
  // Delta: a probe whose failure was only the shell prerequisite is ignored once
  // the gates pass, so FPS reads as no sample rather than a stale reason.
  val shellBlockedFps = row(
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
  expectEquals(shellBlockedFps.state, CapabilityState.UNKNOWN, "shell-blocked FPS falls back to UNKNOWN")
  expectEquals(shellBlockedFps.detail, "No FPS sample yet.", "shell-blocked FPS detail")
  // The gate still wins while the prerequisite is actually missing.
  val gatedShellBlockedFps = row(
    facts(
      shizukuRunning = false,
      fps = ProbeStatus(CapabilityState.OK, "60 fps", "SurfaceFlinger", shellBlocked = true),
    ),
    CapabilityFeature.FPS,
  )
  expectEquals(
    gatedShellBlockedFps.detail,
    "Shizuku server is not running.",
    "shell-blocked FPS does not bypass the current gate",
  )

  // C9: RAM requires used+total with a positive total.
  expectEquals(
    row(facts(ramUsedBytes = 0L), CapabilityFeature.RAM).state,
    CapabilityState.OK,
    "zero used with valid total is still OK",
  )
  expectEquals(
    row(facts(ramUsedBytes = 0L), CapabilityFeature.RAM).detail,
    "0.0 / 7.8 GB",
    "zero used RAM detail",
  )
  listOf(
    facts(ramTotalBytes = 0L) to "total zero",
    facts(ramTotalBytes = null) to "total null",
    facts(ramUsedBytes = null) to "used null",
  ).forEach { (input, label) ->
    val ram = row(input, CapabilityFeature.RAM)
    expectEquals(ram.state, CapabilityState.UNAVAILABLE, "RAM $label state")
    expectEquals(
      ram.detail,
      "ActivityManager returned no memory total.",
      "RAM $label detail",
    )
  }

  // C10: PROCESS_MEMORY probe passthrough and ready fallback.
  val memFailed = row(
    facts(meminfo = ProbeStatus(CapabilityState.UNAVAILABLE, "No process data", "dumpsys meminfo")),
    CapabilityFeature.PROCESS_MEMORY,
  )
  expectEquals(memFailed.state, CapabilityState.UNAVAILABLE, "process failure state")
  expectEquals(memFailed.detail, "No process data", "process failure detail")
  val memReady = row(facts(meminfo = null), CapabilityFeature.PROCESS_MEMORY)
  expectEquals(memReady.state, CapabilityState.OK, "process ready state")
  expectEquals(
    memReady.detail,
    "Ready \u2014 open the process page to scan.",
    "process ready detail",
  )
  expectEquals(memReady.source, "dumpsys meminfo", "process ready source")
  expectEquals(
    row(facts(meminfo = ProbeStatus(CapabilityState.OK, "42 processes", "")), CapabilityFeature.PROCESS_MEMORY).source,
    "dumpsys meminfo",
    "blank process source fallback",
  )
  // Delta: a probe that only recorded a missing shell prerequisite is ignored
  // once every gate passes; a real command failure without the flag stays.
  val memShellBlocked = row(
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
  expectEquals(memShellBlocked.state, CapabilityState.OK, "shell-blocked meminfo state")
  expectEquals(
    memShellBlocked.detail,
    "Ready \u2014 open the process page to scan.",
    "shell-blocked meminfo detail",
  )
  val memCommandFailure = row(
    facts(
      meminfo = ProbeStatus(
        CapabilityState.UNAVAILABLE,
        "Couldn't read process memory",
        "dumpsys meminfo",
        shellBlocked = false,
      ),
    ),
    CapabilityFeature.PROCESS_MEMORY,
  )
  expectEquals(memCommandFailure.state, CapabilityState.UNAVAILABLE, "non-shell meminfo state")
  expectEquals(
    memCommandFailure.detail,
    "Couldn't read process memory",
    "non-shell meminfo detail",
  )
  val gatedShellBlockedMem = row(
    facts(
      shizukuRunning = false,
      meminfo = ProbeStatus(
        CapabilityState.UNAVAILABLE,
        "Process memory needs Shizuku",
        "dumpsys meminfo",
        shellBlocked = true,
      ),
    ),
    CapabilityFeature.PROCESS_MEMORY,
  )
  expectEquals(
    gatedShellBlockedMem.detail,
    "Shizuku server is not running.",
    "shell-blocked meminfo does not bypass the current gate",
  )

  // C11: STOP probe passthrough, BLOCKED protected, ready fallback.
  val protected = row(
    facts(stop = ProbeStatus(CapabilityState.BLOCKED, "This app is protected.", "am force-stop")),
    CapabilityFeature.STOP,
  )
  expectEquals(protected.state, CapabilityState.BLOCKED, "protected stop state")
  expectEquals(protected.detail, "This app is protected.", "protected stop detail")
  val stopReady = row(facts(stop = null), CapabilityFeature.STOP)
  expectEquals(stopReady.state, CapabilityState.OK, "stop ready state")
  expectEquals(stopReady.detail, "Ready \u2014 no stop attempted.", "stop ready detail")
  expectEquals(stopReady.source, "am force-stop", "stop ready source")
  expectEquals(
    row(facts(stop = ProbeStatus(CapabilityState.OK, "Last stop: x", "")), CapabilityFeature.STOP).source,
    "am force-stop",
    "blank stop source fallback",
  )
  // Delta: protected-target refusal is a degraded attempt, not a blocked
  // feature, and the builder passes that state/detail through.
  val protectedDegraded = row(
    facts(
      stop = ProbeStatus(
        CapabilityState.DEGRADED,
        "Last attempt blocked: com.example.system is protected.",
        "am force-stop",
      ),
    ),
    CapabilityFeature.STOP,
  )
  expectEquals(protectedDegraded.state, CapabilityState.DEGRADED, "protected stop degraded state")
  expectEquals(
    protectedDegraded.detail,
    "Last attempt blocked: com.example.system is protected.",
    "protected stop degraded detail",
  )
  val stopShellBlocked = row(
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
  expectEquals(stopShellBlocked.state, CapabilityState.OK, "shell-blocked stop state")
  expectEquals(stopShellBlocked.detail, "Ready \u2014 no stop attempted.", "shell-blocked stop detail")
  val stopCommandFailure = row(
    facts(
      stop = ProbeStatus(
        CapabilityState.UNAVAILABLE,
        "No response from Shizuku",
        "am force-stop",
        shellBlocked = false,
      ),
    ),
    CapabilityFeature.STOP,
  )
  expectEquals(stopCommandFailure.state, CapabilityState.UNAVAILABLE, "non-shell stop state")
  expectEquals(stopCommandFailure.detail, "No response from Shizuku", "non-shell stop detail")
  val gatedShellBlockedStop = row(
    facts(
      overlayRunning = false,
      stop = ProbeStatus(
        CapabilityState.UNAVAILABLE,
        "Grant Shizuku permission to stop apps",
        "am force-stop",
        shellBlocked = true,
      ),
    ),
    CapabilityFeature.STOP,
  )
  expect(
    gatedShellBlockedStop.detail.contains("Monitoring is stopped"),
    "shell-blocked stop does not bypass the current gate: ${gatedShellBlockedStop.detail}",
  )

  // C12: a stale probe is still precedence-gated (no all-clear from an old value).
  val staleProbe = rows(
    facts(
      overlayRunning = false,
      fps = ProbeStatus(CapabilityState.OK, "60 fps", "SurfaceFlinger"),
      meminfo = ProbeStatus(CapabilityState.OK, "42 processes", "dumpsys meminfo"),
      stop = ProbeStatus(CapabilityState.OK, "Last stop: x", "am force-stop"),
    ),
  )
  SHELL_ROWS.forEach { feature ->
    expectEquals(
      staleProbe.first { it.feature == feature }.state,
      CapabilityState.UNAVAILABLE,
      "$feature overlay gate outranks an old probe",
    )
  }

  // C13: the teardown hook really forgets all three probe results, so a dead
  // service cannot keep feeding the table. (The service calling it in onDestroy
  // is reviewed separately; this pins the store's own contract.)
  DiagnosticsStore.fps = ProbeStatus(CapabilityState.OK, "60 fps", "SurfaceFlinger")
  DiagnosticsStore.meminfo = ProbeStatus(CapabilityState.OK, "42 processes", "dumpsys meminfo")
  DiagnosticsStore.stop = ProbeStatus(CapabilityState.OK, "Last stop: x", "am force-stop")
  DiagnosticsStore.clear()
  expect(DiagnosticsStore.fps == null, "clear() forgets the FPS probe")
  expect(DiagnosticsStore.meminfo == null, "clear() forgets the process-memory probe")
  expect(DiagnosticsStore.stop == null, "clear() forgets the stop probe")

  println("checks=$checks failures=$failures")
  if (failures != 0) exitProcess(1)
}
