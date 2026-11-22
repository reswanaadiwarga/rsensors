package dev.rsensors.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rsensors.android.core.DASH
import dev.rsensors.android.core.SessionLog
import dev.rsensors.android.core.SessionSample
import dev.rsensors.android.core.hasFpsValues
import dev.rsensors.android.source.SessionStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One saved session's metadata, graph and sample table (ANDROID.md §3, §8
 * T12/T13, T15d).
 *
 * Reads the file named in [EXTRA_SESSION_NAME] on a background executor and
 * shows start, duration and sample count above the full-session
 * [SessionGraph], then a scrollable time/CPU/GPU/BAT/SOC/SKIN table; a v2
 * session with at least one recorded FPS also gets an FPS column, while v1
 * sessions keep exactly the old columns. Every header cell is tappable and
 * opens a dialog explaining what that column measures; the texts are shaped by
 * the HAL rather than the column name, because SOC and SKIN are board zones
 * rather than extra CPU probes. Missing board values render as an em dash
 * (§2.2). A missing or corrupt file becomes a message, never a crash.
 */
class SessionDetailActivity : ComponentActivity() {
  private var sessionName = ""
  private var log by mutableStateOf<SessionLog?>(null)
  private var loaded by mutableStateOf(false)

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val store by lazy { SessionStore(File(filesDir, SessionStore.DIRECTORY)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    sessionName = intent.getStringExtra(EXTRA_SESSION_NAME).orEmpty()
    setContent {
      RsensorsTheme { SessionDetailScreen(log, loaded) }
    }
    loadSession()
  }

  override fun onDestroy() {
    executor.shutdownNow()
    super.onDestroy()
  }

  /** Decodes the session off the main thread; null covers missing and corrupt. */
  private fun loadSession() {
    val name = sessionName
    executor.execute {
      val result = if (name.isEmpty()) null else store.read(name)
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        log = result
        loaded = true
      }
    }
  }

  companion object {
    /** Extra carrying the session file name from [SessionsActivity]. */
    const val EXTRA_SESSION_NAME = "dev.rsensors.android.extra.SESSION_NAME"
  }
}

@Composable
private fun SessionDetailScreen(log: SessionLog?, loaded: Boolean) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Session", style = MaterialTheme.typography.headlineMedium)
      when {
        !loaded -> Text("Loading\u2026")
        log == null -> Text(
          "Session not found or unreadable.",
          color = MaterialTheme.colorScheme.error,
        )
        else -> SessionContent(log)
      }
    }
  }
}

@Composable
private fun ColumnScope.SessionContent(log: SessionLog) {
  // Which column's explanation is open; null keeps the dialog off-screen. The
  // state lives here so both the graph and the table above/below it keep their
  // own concerns, and rotation simply closes a small help dialog.
  var explainedColumn by remember { mutableStateOf<String?>(null) }
  val endedAt = log.samples.lastOrNull()?.t ?: log.startedAt
  Text("Start ${fmtSessionTime(log.startedAt)}")
  Text(
    "Duration ${fmtSessionDuration(log.startedAt, endedAt)} \u00b7 " +
      "${log.samples.size} samples",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
  )
  SessionGraph(log, Modifier.fillMaxWidth())
  if (log.samples.isEmpty()) {
    Text("No samples in this session.")
    return
  }

  Text(
    "Temperatures in \u00b0C, one decimal; \u2014 means the sensor was unavailable.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
  )
  // A v1 session has no FPS values anywhere, so it keeps the five temperature
  // columns; v2 sessions show the column for every row.
  val hasFps = hasFpsValues(log.samples)
  SampleTableHeader(hasFps = hasFps, onExplain = { explainedColumn = it })
  explainedColumn?.let { column ->
    ColumnHelpDialog(column = column, onDismiss = { explainedColumn = null })
  }
  LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
    items(log.samples) { sample -> SampleTableRow(sample, hasFps) }
  }
}

/**
 * The table header. Every label opens its column's explanation; the FPS column
 * exists only for sessions that recorded a frame rate.
 */
@Composable
private fun SampleTableHeader(hasFps: Boolean, onExplain: (String) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
    HeaderCell("time", TIME_WEIGHT, onExplain)
    HeaderCell("CPU", NUMBER_WEIGHT, onExplain)
    HeaderCell("GPU", NUMBER_WEIGHT, onExplain)
    HeaderCell("BAT", NUMBER_WEIGHT, onExplain)
    HeaderCell("SOC", NUMBER_WEIGHT, onExplain)
    HeaderCell("SKIN", NUMBER_WEIGHT, onExplain)
    if (hasFps) HeaderCell("FPS", NUMBER_WEIGHT, onExplain)
  }
}

/**
 * One tappable header cell; the whole cell is the target for its explanation.
 *
 * A hairline under the label is the affordance rather than a ⓘ glyph: at 360 dp
 * the six numeric columns are ~41 dp wide, which does not fit `SKIN` plus an
 * icon at 12 sp monospace. The cell is at least [HEADER_MIN_HEIGHT] tall so the
 * tap does not have to hit the glyphs.
 */
@Composable
private fun RowScope.HeaderCell(label: String, weight: Float, onExplain: (String) -> Unit) {
  Column(
    modifier = Modifier
      .weight(weight)
      .heightIn(min = HEADER_MIN_HEIGHT)
      .clickable(role = Role.Button) { onExplain(label) }
      .semantics(mergeDescendants = true) { contentDescription = "About $label" }
      .padding(vertical = 4.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = label,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
    Box(
      modifier = Modifier
        .padding(top = 2.dp)
        .fillMaxWidth(0.85f)
        .height(1.dp)
        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)),
    )
  }
}

@Composable
private fun SampleTableRow(sample: SessionSample, hasFps: Boolean) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
    TableCell(fmtSampleTime(sample.t), TIME_WEIGHT)
    TableCell(fmtCell(sample.cpu), NUMBER_WEIGHT)
    TableCell(fmtCell(sample.gpu), NUMBER_WEIGHT)
    TableCell(fmtCell(sample.battery), NUMBER_WEIGHT)
    TableCell(fmtCell(boardValue(sample, "SOC")), NUMBER_WEIGHT)
    TableCell(fmtCell(boardValue(sample, "SKIN")), NUMBER_WEIGHT)
    if (hasFps) TableCell(fmtCell(sample.fps), NUMBER_WEIGHT)
  }
}

/** One monospace table cell; the weight is the column's share of the width. */
@Composable
private fun RowScope.TableCell(text: String, weight: Float) {
  Text(
    text = text,
    modifier = Modifier.weight(weight),
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    maxLines = 1,
  )
}

/**
 * Explains one column (ANDROID.md §9). The wording follows what the HAL
 * actually reports, so SOC and SKIN read as board zones and FPS separates
 * "idle" from "no measurement".
 */
@Composable
private fun ColumnHelpDialog(column: String, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(column) },
    text = { Text(COLUMN_HELP[column].orEmpty()) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
  )
}

/** `—` for an absent sensor, otherwise one decimal in °C. */
private fun fmtCell(value: Double?): String =
  if (value == null) DASH else String.format(Locale.US, "%.1f", value)

private fun boardValue(sample: SessionSample, name: String): Double? =
  sample.board.firstOrNull { (sensor, _) -> sensor == name }?.second

/** `HH:mm:ss` in the device zone; constructed per call. */
private fun fmtSampleTime(epochMillis: Long): String =
  SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

/** `m:ss` under an hour, `h:mm:ss` above it; a negative span clamps to zero. */
private fun fmtSessionDuration(startedAt: Long, endedAt: Long): String {
  val totalSeconds = (endedAt - startedAt).coerceAtLeast(0L) / 1000L
  val hours = totalSeconds / 3600L
  val minutes = (totalSeconds % 3600L) / 60L
  val seconds = totalSeconds % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%d:%02d", minutes, seconds)
  }
}

/** `yyyy-MM-dd HH:mm:ss` in the device zone; constructed per call. */
private fun fmtSessionTime(epochMillis: Long): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

private const val TIME_WEIGHT = 1.6f
private const val NUMBER_WEIGHT = 1f

/** Header tap target; comfortably above the 12 sp label on a narrow phone. */
private val HEADER_MIN_HEIGHT = 32.dp

/**
 * Column explanations, keyed by the header label the table shows.
 *
 * `time` is lowercase to match its column; the rest are the series labels.
 * The battery text says "reported by Android" because that column comes from
 * `BatteryManager`, not the thermal sensors; FPS distinguishes a measured `0`
 * from the `—` the UI shows while Shizuku is unavailable.
 */
private val COLUMN_HELP: Map<String, String> = mapOf(
  "time" to
    "When this sample was recorded. Polls pause while the screen is off, so gaps are expected.",
  "CPU" to "CPU temperature from the phone's thermal sensors, in \u00b0C.",
  "GPU" to "GPU temperature from the phone's thermal sensors, in \u00b0C.",
  "BAT" to "Battery temperature reported by Android, in \u00b0C.",
  "SOC" to
    "System-on-chip sensor from the phone's thermal sensors. On this phone it " +
    "closely tracks the CPU temperature.",
  "SKIN" to
    "Skin/surface temperature \u2014 how warm the outside of the phone feels. On " +
    "this phone it closely tracks the battery.",
  "FPS" to
    "Foreground app's frame rate at this sample. 0 means the app was idle; \u2014 means " +
    "Shizuku was unavailable.",
)
