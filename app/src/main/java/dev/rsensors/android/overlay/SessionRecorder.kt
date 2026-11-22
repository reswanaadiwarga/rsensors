package dev.rsensors.android.overlay

import android.util.Log
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.toSessionSample
import dev.rsensors.android.source.SessionStore
import dev.rsensors.android.source.SessionWriter
import java.io.IOException

/**
 * Session recorder owned by the overlay's poller thread (ANDROID.md §3, §8 T12).
 *
 * Wraps a [SessionStore] and keeps at most one open writer: [start] opens a file
 * at the given wall-clock time, [append] writes every polled [Reading] and
 * flushes it, and [stop] flushes and closes. Because the service only calls
 * [append] from the poll loop, screen-off pausing naturally leaves a gap in the
 * session instead of inventing samples (§9).
 *
 * I/O errors are logged and end the recording in [append]; they never propagate
 * into the poller, where an exception would take the overlay down.
 *
 * Threading: not synchronised. The service creates, uses and closes it on the
 * worker thread only; [isRecording] is safe to read from that same thread.
 */
class SessionRecorder(private val store: SessionStore) {
  private var writer: SessionWriter? = null

  /** True while a session file is open for appending. */
  val isRecording: Boolean get() = writer != null

  /**
   * File name of the open session, or null when idle.
   *
   * The service publishes this so the sessions screen can hide the file that is
   * still being written; it changes only when [start] or [stop] succeeds.
   */
  val currentName: String? get() = writer?.name

  /**
   * Opens a session file started at [now].
   *
   * @return true when a file is open afterwards, including the case where a
   *   session was already being recorded; false when the file could not be
   *   created (read-only storage, full disk)
   */
  fun start(now: Long): Boolean {
    if (writer != null) return true
    writer = try {
      store.create(now)
    } catch (e: IOException) {
      Log.i(TAG, "recording create failed: ${e.javaClass.simpleName}")
      null
    }
    return writer != null
  }

  /** Appends one poll; a write failure closes the file and stops recording. */
  fun append(reading: Reading) {
    val current = writer ?: return
    try {
      current.append(reading.toSessionSample())
    } catch (e: IOException) {
      Log.i(TAG, "recording write failed: ${e.javaClass.simpleName}")
      closeQuietly(current)
      writer = null
    }
  }

  /** Flushes and closes the open session, if any. */
  fun stop() {
    val current = writer ?: return
    writer = null
    closeQuietly(current)
  }

  private fun closeQuietly(writer: SessionWriter) {
    try {
      writer.close()
    } catch (e: IOException) {
      Log.i(TAG, "recording close failed: ${e.javaClass.simpleName}")
    }
  }

  private companion object {
    /** Same tag as the service: recording events sit next to poll lifecycle. */
    const val TAG = "Rsensors"
  }
}
