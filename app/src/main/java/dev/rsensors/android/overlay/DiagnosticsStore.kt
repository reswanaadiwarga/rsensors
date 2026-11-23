package dev.rsensors.android.overlay

import dev.rsensors.android.core.ProbeStatus

/**
 * Process-wide results of the optional shell probes (ANDROID.md §8 T19).
 *
 * The overlay owns the probes: FPS runs on the poll worker, meminfo and stop run
 * on the process-page thread, and Diagnostics only reads the outcome. Each
 * field is the *last attempt*, not a value cache, so a failure keeps its exact
 * reason and the feature table never has to parse logs or page text.
 *
 * The fields are [Volatile] because writers and the Diagnostics reader run on
 * different threads; each assignment of an immutable [ProbeStatus] is atomic.
 * A null field means no attempt has run since the process started or the last
 * [clear].
 */
object DiagnosticsStore {
  /** Last FPS probe on the poll worker, or null when none has run. */
  @Volatile var fps: ProbeStatus? = null

  /** Last process-memory scan attempt, or null when none has run. */
  @Volatile var meminfo: ProbeStatus? = null

  /** Last stop attempt, or null when none has run. */
  @Volatile var stop: ProbeStatus? = null

  /**
   * Forgets every probe. Called when the service tears down so Diagnostics
   * reports "ready"/"no sample" instead of a result from a dead service.
   */
  fun clear() {
    fps = null
    meminfo = null
    stop = null
  }
}
