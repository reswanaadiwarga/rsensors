package dev.rsensors.android.source

import dev.rsensors.android.core.SessionLog
import dev.rsensors.android.core.SessionSample
import dev.rsensors.android.core.decodeSession
import dev.rsensors.android.core.encodeRow
import dev.rsensors.android.core.sessionHeader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter

/**
 * One list entry for the saved-sessions screen (ANDROID.md §3, §8 T12).
 *
 * @property name file name inside the sessions directory, the handle the detail
 *   screen is opened with
 * @property startedAt session start from the file header, epoch millis
 * @property endedAt last sample time, or [startedAt] for a session with no rows
 * @property samples recorded rows
 */
data class SessionSummary(
  val name: String,
  val startedAt: Long,
  val endedAt: Long,
  val samples: Int,
)

/**
 * File-backed session storage (ANDROID.md §9, §8 T12).
 *
 * Lives under `filesDir/sessions` in the app's private storage: one
 * `session-<startedAt>.csv` per recording, no permission and no network. Pure
 * `java.io`, no Android imports, so the whole store is JVM-testable.
 *
 * [create] returns a [SessionWriter] that writes the header immediately, flushes
 * every appended row, and is closed by the recorder on stop. A second session
 * started in the same millisecond gets a `-N` suffix instead of overwriting the
 * first. [list] decodes every candidate and drops files that are not valid v1
 * sessions, so a truncated or hand-edited file can never crash the screen.
 *
 * Threading: not synchronised. The overlay's single worker thread owns the
 * writer; [list] and [read] may run on any thread and only read files.
 */
class SessionStore(private val dir: File) {

  /**
   * Saved sessions, newest start first.
   *
   * @return summaries sorted by [SessionSummary.startedAt] descending; corrupt
   *   and unrelated files are skipped; an absent directory is empty
   */
  fun list(): List<SessionSummary> {
    val files = dir.listFiles() ?: return emptyList()
    return files.asSequence()
      .filter { it.isFile && isSessionFileName(it.name) }
      .mapNotNull { file ->
        val log = read(file.name) ?: return@mapNotNull null
        SessionSummary(
          name = file.name,
          startedAt = log.startedAt,
          endedAt = log.samples.lastOrNull()?.t ?: log.startedAt,
          samples = log.samples.size,
        )
      }
      .sortedWith(
        compareByDescending<SessionSummary> { it.startedAt }
          .thenByDescending { it.name },
      )
      .toList()
  }

  /**
   * Reads one session by file name.
   *
   * @return the decoded log, or null when the name is not a session file name,
   *   the file is missing, unreadable or not a valid v1 session
   */
  fun read(name: String): SessionLog? {
    if (!isSessionFileName(name)) return null
    val file = File(dir, name)
    if (!file.isFile) return null
    val text = try {
      file.readText(Charsets.UTF_8)
    } catch (e: IOException) {
      return null
    }
    return decodeSession(text)
  }

  /**
   * Deletes one saved session file.
   *
   * Deletion is permanent; the screen confirms it before calling this. A name
   * that is not a session file name is refused before the directory is touched,
   * so a traversal or an unrelated file in the directory can never be removed.
   *
   * @return true only when a session file existed and was removed; false for an
   *   invalid name, a missing file or a failed delete. Never throws.
   */
  fun delete(name: String): Boolean {
    if (!isSessionFileName(name)) return false
    val file = File(dir, name)
    if (!file.isFile) return false
    return try {
      file.delete()
    } catch (e: SecurityException) {
      false
    }
  }

  /**
   * Opens a new session file for [startedAt].
   *
   * @throws IOException when the directory cannot be created or the file cannot
   *   be opened; the recorder reports the failure and stays idle
   */
  fun create(startedAt: Long): SessionWriter {
    if (!dir.isDirectory && !dir.mkdirs()) {
      throw IOException("cannot create session directory $dir")
    }
    var suffix = 0
    var file = File(dir, fileName(startedAt, suffix))
    while (file.exists()) {
      suffix += 1
      file = File(dir, fileName(startedAt, suffix))
    }
    return SessionWriter(file, startedAt)
  }

  private fun fileName(startedAt: Long, suffix: Int): String =
    if (suffix == 0) "$PREFIX$startedAt$EXTENSION" else "$PREFIX$startedAt-$suffix$EXTENSION"

  /** Rejects path traversal and unrelated files before touching the directory. */
  private fun isSessionFileName(name: String): Boolean =
    name.startsWith(PREFIX) && name.endsWith(EXTENSION) &&
      '/' !in name && '\\' !in name && ".." !in name

  companion object {
    /** Directory under `filesDir` that holds the session CSVs (ANDROID.md §9). */
    const val DIRECTORY = "sessions"

    private const val PREFIX = "session-"
    private const val EXTENSION = ".csv"
  }
}

/**
 * Append-only writer for one session file.
 *
 * The init block writes and flushes the header, and every [append] flushes, so
 * a killed process still leaves a readable log with every completed poll.
 * [close] is idempotent. Owned by one thread (the overlay's poller); the file is
 * UTF-8 with `\n` line endings on every platform.
 */
class SessionWriter internal constructor(
  private val file: File,
  startedAt: Long,
) {
  private val out = BufferedWriter(
    OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8),
  )
  private var closed = false

  /** File name inside the sessions directory. */
  val name: String get() = file.name

  init {
    out.write(sessionHeader(startedAt))
    out.flush()
  }

  /**
   * Appends one row and flushes it.
   *
   * A call after [close] is ignored: a final poll that races the stop must not
   * reopen or crash the finished session.
   */
  fun append(sample: SessionSample) {
    if (closed) return
    out.write(sample.encodeRow())
    out.write("\n")
    out.flush()
  }

  /** Flushes and releases the file; safe to call more than once. */
  fun close() {
    if (closed) return
    closed = true
    out.close()
  }
}
