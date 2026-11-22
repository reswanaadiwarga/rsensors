package dev.rsensors.android.source

/**
 * Supplies the raw `dumpsys meminfo --oom` dump (ANDROID.md §3, §9, §8 T17).
 *
 * The process page scans through this interface so the PSS parsing stays on
 * the pure [dev.rsensors.android.core.parseMeminfoPss] path and a missing
 * Shizuku server is a null dump instead of an exception. Implementations run
 * on the scan thread and must not throw: the caller turns any failure into an
 * unavailable page and the temperature poll stays intact.
 */
interface MeminfoRunner {
  /**
   * Runs one memory dump in the shell-side UserService.
   *
   * @return the full stdout of `dumpsys meminfo --oom`, or null when no dump
   *   could be obtained (Shizuku stopped, permission revoked, dead binder)
   */
  fun runMeminfo(): String?
}
