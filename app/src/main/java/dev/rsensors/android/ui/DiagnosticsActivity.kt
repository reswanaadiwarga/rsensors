package dev.rsensors.android.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rsensors.android.core.CapabilityFacts
import dev.rsensors.android.core.CapabilityRow
import dev.rsensors.android.core.CapabilityState
import dev.rsensors.android.core.DASH
import dev.rsensors.android.core.ProbeStatus
import dev.rsensors.android.core.RamUsage
import dev.rsensors.android.core.ThermalStatus
import dev.rsensors.android.core.ZoneCategory
import dev.rsensors.android.core.buildCapabilities
import dev.rsensors.android.core.fmtAge
import dev.rsensors.android.core.fmtFps
import dev.rsensors.android.core.fmtTemp
import dev.rsensors.android.overlay.DiagnosticsStore
import dev.rsensors.android.overlay.OverlayPreferences
import dev.rsensors.android.overlay.OverlayService
import dev.rsensors.android.overlay.ReadingStore
import dev.rsensors.android.overlay.SourceSnapshot
import dev.rsensors.android.overlay.ZoneOverrideStore
import dev.rsensors.android.source.BatterySource
import dev.rsensors.android.source.DirectReader
import dev.rsensors.android.source.SysfsSource
import dev.rsensors.android.source.ThermalStatusSource
import dev.rsensors.android.source.ZoneReport
import dev.rsensors.android.source.probeThermalZones
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/**
 * Diagnostics: the live source snapshot, the whole thermal-zone table and a
 * manual zone picker (ANDROID.md §2.2, §3, §5, §8 T7).
 *
 * The screen is deliberately useful even when the overlay is not running: it
 * still probes `/sys/class/thermal` with [DirectReader] on a background thread
 * and shows the raw read status. That is what makes the app usable on an
 * arbitrary phone, where the heuristic may name the wrong zone.
 *
 * Each zone row carries Auto / CPU / GPU / BAT chips. A tap writes
 * [ZoneOverrideStore] and re-probes; the overlay's sysfs source reads the same
 * store on every poll, so the pill changes within one poll. Rows whose `type`
 * is unreadable get no chips, because there is no stable key to assign.
 *
 * Shizuku state and its manager version are read live here with every call
 * guarded: a stopped server is a normal tier-0 state, not an error. A denied or
 * empty thermal directory renders an explanation, never a crash.
 *
 * Side effects: reads SharedPreferences, launches one short-lived executor per
 * activity, probes the battery, thermal status, RAM and the overlay's last shell
 * probes, and writes the clipboard when Copy is pressed.
 *
 * T19 adds the "Feature status" table above the snapshot: one row per
 * measurement or action with its state, source and the exact reason when it is
 * not working. It is assembled by the pure [buildCapabilities] so the screen
 * only gathers facts, and the same rows are appended to the clipboard report.
 */
class DiagnosticsActivity : ComponentActivity() {
  private var snapshot by mutableStateOf<SourceSnapshot?>(null)
  private var capabilities by mutableStateOf<List<CapabilityRow>>(emptyList())
  private var overlayRunning by mutableStateOf(false)
  private var zones by mutableStateOf<List<ZoneReport>>(emptyList())
  private var overrides by mutableStateOf<Map<String, ZoneCategory>>(emptyMap())
  private var shizukuRunning by mutableStateOf(false)
  private var shizukuGranted by mutableStateOf(false)
  private var shizukuVersion by mutableStateOf<String?>(null)
  private var loaded by mutableStateOf(false)
  private var refreshedAt by mutableStateOf(System.currentTimeMillis())
  private var copied by mutableStateOf(false)

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      RsensorsTheme {
        DiagnosticsScreen(
          snapshot = snapshot,
          capabilities = capabilities,
          overlayRunning = overlayRunning,
          shizukuRunning = shizukuRunning,
          shizukuGranted = shizukuGranted,
          shizukuVersion = shizukuVersion,
          zones = zones,
          overrides = overrides,
          loaded = loaded,
          refreshedAt = refreshedAt,
          copied = copied,
          onRefresh = ::refresh,
          onCopy = ::copyReport,
          onOverride = ::setOverride,
          onOpenShizukuHelp = ::openShizukuHelp,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  override fun onDestroy() {
    executor.shutdownNow()
    super.onDestroy()
  }

  /** Re-reads the snapshot, Shizuku state and overrides, then probes zones. */
  private fun refresh() {
    // The service clears the store on stop, but the process flag also covers a
    // service death that never reached onDestroy.
    overlayRunning = OverlayService.isRunning
    snapshot = ReadingStore.snapshot
    overrides = ZoneOverrideStore.load(this)
    refreshedAt = System.currentTimeMillis()
    copied = false
    readShizukuState()
    capabilities = buildCapabilities(buildFacts())
    probeZones()
  }

  /**
   * Collects the facts behind the feature table.
   *
   * Battery, thermal status and RAM are probed directly and need no Shizuku, so
   * those rows stay truthful while the shell tier is down. Everything else comes
   * from the last snapshot and [DiagnosticsStore], with the reading's FPS as the
   * fallback before the store has one.
   */
  private fun buildFacts(): CapabilityFacts {
    val current = snapshot
    val reading = current?.reading
    val battery = try {
      BatterySource(this).poll(refreshedAt)
    } catch (e: Exception) {
      null
    }
    val thermal = try {
      ThermalStatusSource(this).poll(refreshedAt)
    } catch (e: Exception) {
      null
    }
    val ram = readRam()
    return CapabilityFacts(
      sdkInt = Build.VERSION.SDK_INT,
      demo = OverlayPreferences.isDemo(this),
      overlayRunning = overlayRunning,
      shizukuInstalled = shizukuVersion != null,
      shizukuRunning = shizukuRunning,
      shizukuGranted = shizukuGranted,
      cpu = reading?.cpu,
      gpu = reading?.gpu,
      boardCount = reading?.board?.size ?: 0,
      battery = battery?.battery,
      thermalStatus = thermal?.thermalStatus ?: ThermalStatus.UNKNOWN,
      sampleAgeMs = reading?.ageMillis(refreshedAt),
      sourceName = reading?.sourceName.orEmpty(),
      chainErrors = current?.chainErrors?.map { "${it.source}: ${it.message}" }.orEmpty(),
      ramUsedBytes = ram?.usedBytes,
      ramTotalBytes = ram?.totalBytes,
      fps = DiagnosticsStore.fps ?: reading?.fps?.let {
        ProbeStatus(CapabilityState.OK, "${fmtFps(it)} fps", "SurfaceFlinger")
      },
      meminfo = DiagnosticsStore.meminfo,
      stop = DiagnosticsStore.stop,
    )
  }

  /**
   * One direct `ActivityManager.MemoryInfo` read for the RAM row.
   *
   * Mirrors the overlay's poll: a non-positive total is no figure at all, and a
   * failed lookup leaves the row unavailable instead of crashing the screen.
   * Needs no permission, so it survives a stopped Shizuku server.
   */
  private fun readRam(): RamUsage? =
    try {
      val info = ActivityManager.MemoryInfo()
      getSystemService(ActivityManager::class.java).getMemoryInfo(info)
      if (info.totalMem <= 0L) {
        null
      } else {
        RamUsage(
          usedBytes = (info.totalMem - info.availMem).coerceAtLeast(0L),
          totalBytes = info.totalMem,
        )
      }
    } catch (e: Exception) {
      null
    }

  /**
   * Reads Shizuku twice, each call guarded: `pingBinder` is false and
   * `checkSelfPermission` throws when the server is stopped, which is expected
   * on a stock phone.
   */
  private fun readShizukuState() {
    shizukuRunning = try {
      Shizuku.pingBinder()
    } catch (e: Exception) {
      false
    }
    shizukuGranted = try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
      false
    }
    shizukuVersion = shizukuManagerVersion()
  }

  /**
   * The installed Shizuku manager version, or null when the package is absent.
   *
   * `getPackageInfo(String, Int)` is the only overload that works on every
   * supported API level; it is deprecated on 33+, hence the suppression.
   */
  @Suppress("DEPRECATION")
  private fun shizukuManagerVersion(): String? =
    try {
      packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0).versionName
    } catch (e: Exception) {
      null
    }

  /** Probes sysfs off the main thread; a failing probe reads as an empty table. */
  private fun probeZones() {
    val probeOverrides = overrides
    executor.execute {
      val reports = try {
        probeThermalZones(DirectReader(), SysfsSource.DEFAULT_THERMAL_ROOT, probeOverrides)
      } catch (e: Exception) {
        emptyList()
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        zones = reports
        loaded = true
      }
    }
  }

  /**
   * Applies one chip. Write first, then refresh so both the table and the
   * overlay read the persisted choice; null and OTHER mean Auto.
   */
  private fun setOverride(type: String, category: ZoneCategory?) {
    ZoneOverrideStore.set(this, type, category)
    refresh()
  }

  /** Opens the Shizuku setup/troubleshooting guide (T15g). */
  private fun openShizukuHelp() {
    startActivity(Intent(this, ShizukuHelpActivity::class.java))
  }

  /** Builds a plain-text report and puts it on the clipboard. */
  private fun copyReport() {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    try {
      clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, buildReport()))
      copied = true
    } catch (e: Exception) {
      // A restricted clipboard (or a system policy) must not crash the screen.
    }
  }

  /** Plain-text form of everything shown, including every zone and error. */
  private fun buildReport(): String {
    val current = snapshot
    val reading = current?.reading
    val report = StringBuilder()
    report.appendLine("Rsensors diagnostics")
    report.appendLine("time: ${fmtTime(System.currentTimeMillis())}")
    report.appendLine("overlay: ${if (overlayRunning) "running" else "not running"}")
    if (current == null) {
      report.appendLine("snapshot: none")
    } else {
      val source = reading?.sourceName?.ifBlank { DASH } ?: DASH
      val age = reading?.let { fmtAge(it.ageMillis(refreshedAt)) } ?: "no reading"
      report.appendLine("source: $source, age: $age")
      report.appendLine(
        "cpu=${fmtTemp(reading?.cpu)} gpu=${fmtTemp(reading?.gpu)} " +
          "bat=${fmtTemp(reading?.battery)} thrm=${reading?.thermalStatus?.name ?: DASH}",
      )
      report.appendLine("chain errors: ${current.chainErrors.size}")
      current.chainErrors.forEach { report.appendLine("  ${it.source}: ${it.message}") }
      report.appendLine("sysfs errors: ${current.sysfsErrors.size}")
      current.sysfsErrors.forEach { report.appendLine("  ${it.path} ${it.reason}") }
    }
    report.appendLine(
      "shizuku: ${if (shizukuRunning) "running" else "stopped"}, " +
        "permission=${if (shizukuGranted) "granted" else "missing"}, " +
        "manager=${shizukuVersion ?: "not installed"}",
    )
    report.appendLine("Feature status:")
    capabilities.forEach { row ->
      report.appendLine("  ${row.label}: ${row.state.name} [${row.source}] ${row.detail}")
    }
    report.appendLine("thermal zones: ${zones.size}")
    zones.forEach { zone ->
      val celsius = zone.celsius?.let { String.format(Locale.US, "%.1f", it) } ?: DASH
      report.appendLine(
        "#${zone.index} ${zone.type ?: DASH} raw=${zone.rawTemp ?: DASH} " +
          "c=$celsius cat=${zone.category.name} err=${zone.error ?: DASH}",
      )
    }
    return report.toString()
  }

  private companion object {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val CLIP_LABEL = "Rsensors diagnostics"
  }
}

/** Source-error paths shown inline; the clipboard report keeps all of them. */
private const val MAX_ERROR_LINES = 8

@Composable
private fun DiagnosticsScreen(
  snapshot: SourceSnapshot?,
  capabilities: List<CapabilityRow>,
  overlayRunning: Boolean,
  shizukuRunning: Boolean,
  shizukuGranted: Boolean,
  shizukuVersion: String?,
  zones: List<ZoneReport>,
  overrides: Map<String, ZoneCategory>,
  loaded: Boolean,
  refreshedAt: Long,
  copied: Boolean,
  onRefresh: () -> Unit,
  onCopy: () -> Unit,
  onOverride: (String, ZoneCategory?) -> Unit,
  onOpenShizukuHelp: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = onRefresh) { Text("Refresh") }
          OutlinedButton(onClick = onCopy) { Text(if (copied) "Copied" else "Copy") }
        }
      }

      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item {
          CapabilitiesSection(capabilities)
        }
        item {
          SnapshotSection(snapshot, overlayRunning, refreshedAt)
        }
        item {
          ShizukuSection(shizukuRunning, shizukuGranted, shizukuVersion, onOpenShizukuHelp)
        }
        item {
          ErrorsSection(snapshot)
        }
        item {
          Text("Thermal zones (${zones.size})", style = MaterialTheme.typography.titleMedium)
        }
        item {
          Text(
            "Read-only probe of ${SysfsSource.DEFAULT_THERMAL_ROOT}. " +
              "Override chips appear when the zone type is readable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
          )
        }
        if (loaded && zones.isEmpty()) {
          item {
            Text(
              "No thermal zones listed. The kernel may deny the directory to this app.",
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        items(zones, key = { it.index }) { zone ->
          ZoneRow(zone, overrides, onOverride)
        }
      }
    }
  }
}

/**
 * The feature table: one compact row per measurement or action, above the
 * snapshot so a broken feature is visible before its value.
 */
@Composable
private fun CapabilitiesSection(rows: List<CapabilityRow>) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Feature status", style = MaterialTheme.typography.titleMedium)
    rows.forEach { row -> CapabilityRowView(row) }
  }
}

/**
 * Label and state on one line, then source and detail in smaller text.
 *
 * The state word is the only colour signal; details stay in the body colour so
 * a long reason is still readable. An empty source is omitted rather than drawn
 * as a stray label.
 */
@Composable
private fun CapabilityRowView(row: CapabilityRow) {
  Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(row.label, style = MaterialTheme.typography.bodyMedium)
      Text(
        row.state.name,
        style = MaterialTheme.typography.bodyMedium,
        color = capabilityColor(row.state),
      )
    }
    if (row.source.isNotBlank()) {
      Text(
        "Source ${row.source}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
      )
    }
    Text(
      row.detail,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
    )
  }
}

/** OK green, degraded amber, unavailable/blocked red, unknown gray. */
private fun capabilityColor(state: CapabilityState): Color = when (state) {
  CapabilityState.OK -> Color(Palette.green)
  CapabilityState.DEGRADED -> Color(Palette.amber)
  CapabilityState.UNAVAILABLE, CapabilityState.BLOCKED -> Color(Palette.red)
  CapabilityState.UNKNOWN -> Color(Palette.gray)
}

@Composable
private fun SnapshotSection(
  snapshot: SourceSnapshot?,
  overlayRunning: Boolean,
  refreshedAt: Long,
) {
  val reading = snapshot?.reading
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("Overlay", style = MaterialTheme.typography.titleMedium)
    Text(
      if (overlayRunning) "Running" else "Not running \u2014 the zone probe still runs",
      color = if (overlayRunning) Color(Palette.green) else Color(Palette.amber),
    )
    if (snapshot == null) {
      Text(
        "No snapshot yet. Start the overlay to record readings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
      )
      return@Column
    }
    Text("Source ${reading?.sourceName?.ifBlank { DASH } ?: DASH}")
    Text(
      "Age " + (reading?.let { fmtAge(it.ageMillis(refreshedAt)) } ?: "no reading") +
        " \u00b7 ${fmtTime(snapshot.now)}",
    )
    Text(
      "CPU ${fmtTemp(reading?.cpu)}  GPU ${fmtTemp(reading?.gpu)}  " +
        "BAT ${fmtTemp(reading?.battery)}  THRM ${reading?.thermalStatus?.name ?: DASH}",
      fontFamily = FontFamily.Monospace,
    )
  }
}

@Composable
private fun ShizukuSection(
  running: Boolean,
  granted: Boolean,
  version: String?,
  onOpenShizukuHelp: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("Shizuku", style = MaterialTheme.typography.titleMedium)
    Text(
      "Server ${if (running) "Running" else "Stopped"}",
      color = if (running) Color(Palette.green) else Color(Palette.amber),
    )
    Text(
      "Permission ${if (granted) "Granted" else "Missing"}",
      color = if (granted) Color(Palette.green) else Color(Palette.amber),
    )
    Text("Manager ${version ?: "Not installed"}")
    OutlinedButton(onClick = onOpenShizukuHelp) { Text("Shizuku help") }
  }
}

@Composable
private fun ErrorsSection(snapshot: SourceSnapshot?) {
  val chainErrors = snapshot?.chainErrors.orEmpty()
  val sysfsErrors = snapshot?.sysfsErrors.orEmpty()
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("Source errors", style = MaterialTheme.typography.titleMedium)
    if (chainErrors.isEmpty() && sysfsErrors.isEmpty()) {
      Text("None")
      return@Column
    }
    chainErrors.forEach { error ->
      Text(
        "${error.source}: ${error.message}",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.error,
      )
    }
    if (sysfsErrors.isNotEmpty()) {
      Text("sysfs ${sysfsErrors.size} path errors", fontSize = 12.sp)
      sysfsErrors.take(MAX_ERROR_LINES).forEach { error ->
        Text(
          "${error.path} ${error.reason}",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.error,
        )
      }
      if (sysfsErrors.size > MAX_ERROR_LINES) {
        Text(
          "\u2026 ${sysfsErrors.size - MAX_ERROR_LINES} more; Copy includes the full list",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
      }
    }
  }
}

@Composable
private fun ZoneRow(
  zone: ZoneReport,
  overrides: Map<String, ZoneCategory>,
  onOverride: (String, ZoneCategory?) -> Unit,
) {
  val type = zone.type
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TableCell("#${zone.index}", 32.dp)
      Text(
        text = type ?: DASH,
        modifier = Modifier.weight(1f),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      TableCell(zone.rawTemp ?: DASH, 54.dp, TextAlign.End)
      TableCell(fmtCelsius(zone.celsius), 46.dp, TextAlign.End)
      TableCell(shortCategory(zone.category), 34.dp)
      Text(
        text = zone.error.orEmpty(),
        modifier = Modifier.width(48.dp),
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.error,
      )
    }
    if (type != null) {
      val key = type.trim().lowercase()
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OverrideChip("Auto", overrides[key] == null) { onOverride(type, null) }
        OverrideChip("CPU", overrides[key] == ZoneCategory.CPU) {
          onOverride(type, ZoneCategory.CPU)
        }
        OverrideChip("GPU", overrides[key] == ZoneCategory.GPU) {
          onOverride(type, ZoneCategory.GPU)
        }
        OverrideChip("BAT", overrides[key] == ZoneCategory.BATTERY) {
          onOverride(type, ZoneCategory.BATTERY)
        }
      }
    }
  }
}

/** One compact monospace table cell, optionally end-aligned for numbers. */
@Composable
private fun TableCell(text: String, width: Dp, align: TextAlign? = null) {
  Text(
    text = text,
    modifier = Modifier.width(width),
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    maxLines = 1,
    textAlign = align,
  )
}

@Composable
private fun OverrideChip(label: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label, fontSize = 10.sp) },
  )
}

/** `62.0` in °C, or the dash when the value was rejected. */
private fun fmtCelsius(value: Double?): String =
  if (value == null) DASH else String.format(Locale.US, "%.1f", value)

/** Three-letter category for the narrow table; OTHER keeps its first letter. */
private fun shortCategory(category: ZoneCategory): String = when (category) {
  ZoneCategory.CPU -> "CPU"
  ZoneCategory.GPU -> "GPU"
  ZoneCategory.BATTERY -> "BAT"
  ZoneCategory.OTHER -> "OTH"
}

/** `yyyy-MM-dd HH:mm:ss` in the device zone; constructed per call. */
private fun fmtTime(epochMillis: Long): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
