package dev.rsensors.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rsensors.android.overlay.OverlayService
import dev.rsensors.android.source.SessionStore
import dev.rsensors.android.source.SessionSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Saved-session list (ANDROID.md §3, §8 T12, T15e).
 *
 * Reads [SessionStore] on one background executor so a slow directory or a
 * corrupt file cannot block the first frame; the screen shows the newest
 * session first and reloads in [onResume], which also covers returning from the
 * detail screen. A tap hands only the file name to [SessionDetailActivity], so
 * the detail screen always renders what is on disk.
 *
 * T15e adds management: a long-press enters selection mode, a tap then toggles
 * a row instead of opening it, and the Delete action asks for confirmation
 * before removing the CSVs. The session currently being written is filtered out
 * because [OverlayService.recordingSessionName] names it; it appears once the
 * recorder stops. Deletion runs on the same executor as the list load, and the
 * list is always reloaded from disk afterwards so a partial failure leaves the
 * surviving files visible instead of guessing at the result.
 */
class SessionsActivity : ComponentActivity() {
  private var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
  private var loaded by mutableStateOf(false)

  /** File names ticked for deletion; empty outside selection mode. */
  private var selection by mutableStateOf<Set<String>>(emptySet())

  /** True while the delete confirmation dialog is open. */
  private var confirmDelete by mutableStateOf(false)

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val store by lazy { SessionStore(File(filesDir, SessionStore.DIRECTORY)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      RsensorsTheme {
        SessionsScreen(
          sessions = sessions,
          loaded = loaded,
          selection = selection,
          confirmDelete = confirmDelete,
          onOpenSession = ::openSession,
          onToggleSelection = ::toggleSelection,
          onClearSelection = ::clearSelection,
          onRequestDelete = { confirmDelete = true },
          onCancelDelete = { confirmDelete = false },
          onConfirmDelete = ::deleteSelected,
        )
      }
    }
    loadSessions()
  }

  override fun onResume() {
    super.onResume()
    // A recording started or stopped while the screen was away changes what the
    // list should hide, so re-read both the store and the service flag.
    loadSessions()
  }

  override fun onDestroy() {
    executor.shutdownNow()
    super.onDestroy()
  }

  /** Lists the store off the main thread and publishes the result. */
  private fun loadSessions() {
    executor.execute {
      // The open recording is still being written; hide it so the list can
      // never offer a half-written file for deletion (ANDROID.md §9).
      val activeName = OverlayService.recordingSessionName
      val list = try {
        store.list().filterNot { it.name == activeName }
      } catch (e: Exception) {
        // A storage failure reads as "no sessions"; the empty state explains
        // that nothing could be shown rather than crashing the screen.
        emptyList()
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        sessions = list
        loaded = true
      }
    }
  }

  private fun openSession(session: SessionSummary) {
    startActivity(
      Intent(this, SessionDetailActivity::class.java)
        .putExtra(SessionDetailActivity.EXTRA_SESSION_NAME, session.name),
    )
  }

  /** Adds or removes one row; an empty set leaves selection mode. */
  private fun toggleSelection(session: SessionSummary) {
    selection = if (session.name in selection) {
      selection - session.name
    } else {
      selection + session.name
    }
  }

  private fun clearSelection() {
    selection = emptySet()
    confirmDelete = false
  }

  /**
   * Deletes every selected file on the executor, reloads the list and clears
   * the selection.
   *
   * The toast reports the deleted and failed counts separately: a file that
   * vanished between load and delete is a failure, but the reloaded list still
   * shows exactly what remains on disk, so nothing is silently hidden.
   */
  private fun deleteSelected() {
    val names = selection.toList()
    confirmDelete = false
    if (names.isEmpty()) return
    executor.execute {
      var deleted = 0
      var failed = 0
      names.forEach { name ->
        if (store.delete(name)) deleted += 1 else failed += 1
      }
      val list = try {
        store.list().filterNot { it.name == OverlayService.recordingSessionName }
      } catch (e: Exception) {
        emptyList()
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        sessions = list
        loaded = true
        selection = emptySet()
        val message = when {
          failed == 0 -> "Deleted $deleted session(s)"
          deleted == 0 -> "Could not delete $failed session(s)"
          else -> "Deleted $deleted session(s), $failed failed"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
      }
    }
  }
}

@Composable
private fun SessionsScreen(
  sessions: List<SessionSummary>,
  loaded: Boolean,
  selection: Set<String>,
  confirmDelete: Boolean,
  onOpenSession: (SessionSummary) -> Unit,
  onToggleSelection: (SessionSummary) -> Unit,
  onClearSelection: () -> Unit,
  onRequestDelete: () -> Unit,
  onCancelDelete: () -> Unit,
  onConfirmDelete: () -> Unit,
) {
  // Back leaves selection mode first; it only leaves the screen when no row is
  // selected, so a stray tap cannot close the list mid-selection (T15e).
  BackHandler(enabled = selection.isNotEmpty(), onBack = onClearSelection)

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (selection.isEmpty()) {
        Text("Sessions", style = MaterialTheme.typography.headlineMedium)
      } else {
        SelectionHeader(
          count = selection.size,
          onCancel = onClearSelection,
          onDelete = onRequestDelete,
        )
      }
      Text(
        "One CSV per recording. Screen-off pauses the recorder, so a session " +
          "has a gap while the phone sleeps. The session being recorded appears " +
          "after it stops. Long-press selects a session for deletion.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
      )
      when {
        !loaded -> Text("Loading\u2026")
        sessions.isEmpty() -> Text(
          "No sessions yet. Turn on Record session on the main screen.",
        )
        else -> LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          items(sessions, key = { it.name }) { session ->
            SessionRow(
              session = session,
              selected = session.name in selection,
              selecting = selection.isNotEmpty(),
              onOpen = onOpenSession,
              onToggleSelection = onToggleSelection,
            )
          }
        }
      }
    }
  }

  if (confirmDelete && selection.isNotEmpty()) {
    DeleteDialog(
      count = selection.size,
      onConfirm = onConfirmDelete,
      onDismiss = onCancelDelete,
    )
  }
}

/** `N selected` with the Cancel and Delete actions that replace the title. */
@Composable
private fun SelectionHeader(count: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "$count selected",
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.headlineSmall,
      maxLines = 1,
    )
    TextButton(onClick = onCancel) { Text("Cancel") }
    TextButton(onClick = onDelete) { Text("Delete") }
  }
}

/** Permanent-deletion confirmation; the only path that removes session files. */
@Composable
private fun DeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete $count session(s)?") },
    text = { Text("The CSV file(s) will be permanently removed. This cannot be undone.") },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * One session summary: start, sample count and duration.
 *
 * A long-press always enters/toggles selection mode; a tap opens the detail
 * outside selection mode and toggles the row inside it. A selected row draws a
 * tinted background and reports `selected = true` to accessibility services.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
  session: SessionSummary,
  selected: Boolean,
  selecting: Boolean,
  onOpen: (SessionSummary) -> Unit,
  onToggleSelection: (SessionSummary) -> Unit,
) {
  val tint = if (selected) {
    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_ALPHA))
  } else {
    Modifier
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .then(tint)
      .combinedClickable(
        onClick = { if (selecting) onToggleSelection(session) else onOpen(session) },
        onLongClick = { onToggleSelection(session) },
      )
      .semantics { this.selected = selected }
      .padding(vertical = 8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(fmtSessionTime(session.startedAt))
      Text("${session.samples} samples")
    }
    Text(
      "Duration ${fmtSessionDuration(session.startedAt, session.endedAt)}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
  }
}

/** Tint of a selected row: the primary green, light enough to read white text. */
private const val SELECTED_ALPHA = 0.22f

/** `yyyy-MM-dd HH:mm:ss` in the device zone; constructed per call. */
private fun fmtSessionTime(epochMillis: Long): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

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
