package dev.rsensors.android.core

/**
 * Feature-by-feature Diagnostics status (ANDROID.md §8 T19).
 *
 * The overlay's shell routes fail for several independent reasons — demo mode,
 * Shizuku not installed, stopped or ungranted, the overlay not running — and a
 * measurement can be present but stale. The Diagnostics screen needs one honest
 * line per feature instead of a single source error, so this file turns the
 * facts the screen already holds into exactly eight ordered rows.
 *
 * The builder is pure: it reads only [CapabilityFacts] and the formatting
 * helpers, never Android services, stores or threads. That keeps every status
 * rule unit-testable on the JVM and makes the screen's reading of a probe the
 * same decision the tests pin down.
 *
 * Precedence is deliberate: demo and Shizuku/overlay gates outrank a probe,
 * because a value captured earlier cannot be described as working now. Battery,
 * thermal status and system RAM never depend on the shell, so they keep
 * reporting while the shell tier is down.
 */

/** One measurement or action reported by the Diagnostics feature table. */
enum class CapabilityFeature { BATTERY, THERMAL_STATUS, CPU_GPU, BOARD, FPS, RAM, PROCESS_MEMORY, STOP }

/**
 * Outcome for one feature.
 *
 * [OK] is a value that is present and fresh (a 0 fps sample is still a real
 * measurement), [DEGRADED] is a partial or stale value, [UNAVAILABLE] means the
 * route cannot produce a value, [BLOCKED] is a deliberate policy refusal, and
 * [UNKNOWN] means nothing has been attempted yet.
 */
enum class CapabilityState { OK, DEGRADED, UNAVAILABLE, BLOCKED, UNKNOWN }

/**
 * Last attempt of an optional shell probe, as published by the overlay.
 *
 * @property state outcome of that attempt
 * @property detail human-readable reason or value; already user-facing
 * @property source shell command or service that produced it; may be blank
 * @property shellBlocked true when the attempt failed because a shell
 *   prerequisite (demo mode, Shizuku, or the overlay) was missing, not because
 *   the command itself failed. Recovery can safely discard such a probe; a
 *   command failure must still be shown.
 */
data class ProbeStatus(
  val state: CapabilityState,
  val detail: String,
  val source: String,
  val shellBlocked: Boolean = false,
)

/**
 * Everything the feature table needs for one refresh.
 *
 * @property sdkInt platform API level, used for the thermal-status floor
 * @property demo demo mode suppresses every shell route
 * @property overlayRunning false means no poller is feeding this screen
 * @property shizukuInstalled whether the Shizuku manager package is present
 * @property shizukuRunning whether the Shizuku server answered
 * @property shizukuGranted whether this app holds shell permission
 * @property cpu last CPU temperature in °C, or null when unread
 * @property gpu last GPU temperature in °C, or null when unread
 * @property boardCount number of named board sensors in the last sample
 * @property battery last battery temperature in °C, or null when unreported
 * @property thermalStatus coarse platform thermal status
 * @property sampleAgeMs age of the sample the shell values came from, or null
 *   when there is no sample
 * @property sourceName source that produced the merged reading; blank is fine
 * @property chainErrors source failures from the last poll, as `source: message`
 * @property ramUsedBytes used system RAM in bytes, or null when unavailable
 * @property ramTotalBytes physical RAM in bytes; zero means no usable figure
 * @property fps last FPS probe attempt, or null when none has run
 * @property meminfo last process-memory probe attempt, or null
 * @property stop last stop probe attempt, or null
 */
data class CapabilityFacts(
  val sdkInt: Int,
  val demo: Boolean,
  val overlayRunning: Boolean,
  val shizukuInstalled: Boolean,
  val shizukuRunning: Boolean,
  val shizukuGranted: Boolean,
  val cpu: Double?,
  val gpu: Double?,
  val boardCount: Int,
  val battery: Double?,
  val thermalStatus: ThermalStatus,
  val sampleAgeMs: Long?,
  val sourceName: String,
  val chainErrors: List<String>,
  val ramUsedBytes: Long?,
  val ramTotalBytes: Long?,
  val fps: ProbeStatus?,
  val meminfo: ProbeStatus?,
  val stop: ProbeStatus?,
)

/**
 * One rendered feature row.
 *
 * @property feature stable identity, also the row order key
 * @property label user-facing feature name
 * @property state outcome shown next to the label
 * @property source producer or command, e.g. `dumpsys meminfo`
 * @property detail value or exact reason for a non-OK state
 */
data class CapabilityRow(
  val feature: CapabilityFeature,
  val label: String,
  val state: CapabilityState,
  val source: String,
  val detail: String,
)

/** Fallback for the thermal dump and its board-sensor section. */
private const val DUMPSYS_SOURCE = "dumpsys thermalservice"

private const val LABEL_BATTERY = "Battery temperature"
private const val LABEL_THERMAL = "Thermal status"
private const val LABEL_CPU_GPU = "CPU/GPU temperature"
private const val LABEL_BOARD = "Board sensors"
private const val LABEL_FPS = "FPS"
private const val LABEL_RAM = "System RAM"
private const val LABEL_PROCESS = "Process memory"
private const val LABEL_STOP = "Stop app"

/**
 * Builds the eight Diagnostics rows in display order.
 *
 * The result always has exactly one row per [CapabilityFeature], so the screen
 * can render it without checking for holes. See the file header for precedence.
 *
 * @return rows ordered BATTERY, THERMAL_STATUS, CPU_GPU, BOARD, FPS, RAM,
 *   PROCESS_MEMORY, STOP
 */
fun buildCapabilities(facts: CapabilityFacts): List<CapabilityRow> = listOf(
  batteryRow(facts),
  thermalRow(facts),
  cpuGpuRow(facts),
  boardRow(facts),
  fpsRow(facts),
  ramRow(facts),
  processMemoryRow(facts),
  stopRow(facts),
)

private fun batteryRow(facts: CapabilityFacts): CapabilityRow {
  val battery = facts.battery
  return if (battery == null) {
    row(
      CapabilityFeature.BATTERY,
      LABEL_BATTERY,
      CapabilityState.UNAVAILABLE,
      "BatteryManager",
      "Battery temperature is not reported.",
    )
  } else {
    row(
      CapabilityFeature.BATTERY,
      LABEL_BATTERY,
      CapabilityState.OK,
      "BatteryManager",
      fmtTemp(battery),
    )
  }
}

private fun thermalRow(facts: CapabilityFacts): CapabilityRow {
  val (state, detail) = when {
    facts.sdkInt < 29 ->
      CapabilityState.UNAVAILABLE to "Requires Android 10 (API 29) or newer."
    facts.thermalStatus == ThermalStatus.UNKNOWN ->
      CapabilityState.UNAVAILABLE to "No thermal status was reported."
    else -> CapabilityState.OK to facts.thermalStatus.name
  }
  return row(CapabilityFeature.THERMAL_STATUS, LABEL_THERMAL, state, "PowerManager", detail)
}

private fun cpuGpuRow(facts: CapabilityFacts): CapabilityRow {
  val source = facts.sourceName.ifBlank { DUMPSYS_SOURCE }
  blockedShellRow(CapabilityFeature.CPU_GPU, LABEL_CPU_GPU, source, facts)?.let { return it }

  val cpu = facts.cpu
  val gpu = facts.gpu
  val hasValue = cpu != null || gpu != null
  val stale = hasValue && isStale(facts.sampleAgeMs)
  val state = when {
    !hasValue -> CapabilityState.UNAVAILABLE
    cpu == null || gpu == null -> CapabilityState.DEGRADED
    stale -> CapabilityState.DEGRADED
    else -> CapabilityState.OK
  }
  var detail = when {
    !hasValue && facts.chainErrors.isNotEmpty() -> facts.chainErrors.first()
    !hasValue -> "No CPU/GPU temperature was reported."
    else -> "CPU ${fmtTemp(cpu)}, GPU ${fmtTemp(gpu)}"
  }
  if (stale) detail += " Sample ${fmtAge(facts.sampleAgeMs!!)}."
  return row(CapabilityFeature.CPU_GPU, LABEL_CPU_GPU, state, source, detail)
}

private fun boardRow(facts: CapabilityFacts): CapabilityRow {
  val source = facts.sourceName.ifBlank { DUMPSYS_SOURCE }
  blockedShellRow(CapabilityFeature.BOARD, LABEL_BOARD, source, facts)?.let { return it }

  val count = facts.boardCount
  if (count <= 0) {
    return row(
      CapabilityFeature.BOARD,
      LABEL_BOARD,
      CapabilityState.UNAVAILABLE,
      source,
      "No board sensors were reported.",
    )
  }
  val stale = isStale(facts.sampleAgeMs)
  val state = if (stale) CapabilityState.DEGRADED else CapabilityState.OK
  var detail = "$count board sensors."
  if (stale) detail += " Sample ${fmtAge(facts.sampleAgeMs!!)}."
  return row(CapabilityFeature.BOARD, LABEL_BOARD, state, source, detail)
}

private fun fpsRow(facts: CapabilityFacts): CapabilityRow {
  blockedShellRow(CapabilityFeature.FPS, LABEL_FPS, "SurfaceFlinger", facts)?.let { return it }
  // A probe tagged shell-blocked is stale once the gates above pass, so it reads
  // as no sample rather than a prerequisite that is no longer true (T19).
  val probe = facts.fps?.takeUnless { it.shellBlocked } ?: return row(
    CapabilityFeature.FPS,
    LABEL_FPS,
    CapabilityState.UNKNOWN,
    "SurfaceFlinger",
    "No FPS sample yet.",
  )
  return row(
    CapabilityFeature.FPS,
    LABEL_FPS,
    probe.state,
    probe.source.ifBlank { "SurfaceFlinger" },
    probe.detail,
  )
}

private fun ramRow(facts: CapabilityFacts): CapabilityRow {
  val used = facts.ramUsedBytes
  val total = facts.ramTotalBytes
  return if (used != null && total != null && total > 0L) {
    row(CapabilityFeature.RAM, LABEL_RAM, CapabilityState.OK, "ActivityManager", fmtRam(used, total))
  } else {
    row(
      CapabilityFeature.RAM,
      LABEL_RAM,
      CapabilityState.UNAVAILABLE,
      "ActivityManager",
      "ActivityManager returned no memory total.",
    )
  }
}

private fun processMemoryRow(facts: CapabilityFacts): CapabilityRow {
  blockedShellRow(CapabilityFeature.PROCESS_MEMORY, LABEL_PROCESS, "dumpsys meminfo", facts)
    ?.let { return it }
  // Ignore a probe whose failure was only a now-passing shell gate; a command
  // failure without the flag is still real and stays visible (T19).
  val probe = facts.meminfo?.takeUnless { it.shellBlocked } ?: return row(
    CapabilityFeature.PROCESS_MEMORY,
    LABEL_PROCESS,
    CapabilityState.OK,
    "dumpsys meminfo",
    "Ready \u2014 open the process page to scan.",
  )
  return row(
    CapabilityFeature.PROCESS_MEMORY,
    LABEL_PROCESS,
    probe.state,
    probe.source.ifBlank { "dumpsys meminfo" },
    probe.detail,
  )
}

private fun stopRow(facts: CapabilityFacts): CapabilityRow {
  blockedShellRow(CapabilityFeature.STOP, LABEL_STOP, "am force-stop", facts)?.let { return it }
  // Same rule as process memory: a stale prerequisite reason is not a result.
  val probe = facts.stop?.takeUnless { it.shellBlocked } ?: return row(
    CapabilityFeature.STOP,
    LABEL_STOP,
    CapabilityState.OK,
    "am force-stop",
    "Ready \u2014 no stop attempted.",
  )
  return row(
    CapabilityFeature.STOP,
    LABEL_STOP,
    probe.state,
    probe.source.ifBlank { "am force-stop" },
    probe.detail,
  )
}

/**
 * The shared gate for every shell-dependent row.
 *
 * Demo outranks Shizuku state, which outranks the overlay state: each check is a
 * prerequisite for the next route, so reporting the first failure is the only
 * reason the user can act on. A missing manager and a stopped server are kept
 * distinct because the fixes differ (install vs start).
 *
 * @return the blocking row, or null when the feature-specific probe may run
 */
private fun blockedShellRow(
  feature: CapabilityFeature,
  label: String,
  source: String,
  facts: CapabilityFacts,
): CapabilityRow? {
  val detail = when {
    facts.demo -> "Demo mode is on."
    !facts.shizukuInstalled -> "Shizuku is not installed."
    !facts.shizukuRunning -> "Shizuku server is not running."
    !facts.shizukuGranted -> "Shizuku permission is not granted."
    !facts.overlayRunning -> "Monitoring is stopped \u2014 start the overlay."
    else -> return null
  }
  return row(feature, label, CapabilityState.UNAVAILABLE, source, detail)
}

/** Strictly older than [STALE_AFTER_MS], matching [Reading.stale]. */
private fun isStale(ageMillis: Long?): Boolean =
  ageMillis != null && ageMillis > STALE_AFTER_MS

private fun row(
  feature: CapabilityFeature,
  label: String,
  state: CapabilityState,
  source: String,
  detail: String,
): CapabilityRow = CapabilityRow(feature, label, state, source, detail)
