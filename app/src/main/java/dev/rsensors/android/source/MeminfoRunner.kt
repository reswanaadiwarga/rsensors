package dev.rsensors.android.source

/**
 * Supplies raw process-memory stdout for the T17 page (ANDROID.md §3, §9,
 * §8 T17, T19b-3).
 *
 * [runMeminfo] returns either the `dumpsys meminfo --oom` dump or, when that
 * dump has no PSS rows or fails, the `ps -A -o PID,RSS,NAME` fallback. The page
 * scans through this interface so both shapes stay on the pure
 * [dev.rsensors.android.core.parseProcessSnapshot] path and a missing Shizuku
 * server is a null dump instead of an exception. Implementations run on the
 * scan thread and must not throw: the caller turns any failure into an
 * unavailable page and the temperature poll stays intact.
 */
interface MeminfoRunner {
  /**
   * Runs one process-memory read in the shell-side UserService.
   *
   * @return the stdout of `dumpsys meminfo --oom`, or the stdout of
   *   `ps -A -o PID,RSS,NAME` when the dump has no PSS rows or fails; null when
   *   neither route produced output (Shizuku stopped, permission revoked, dead
   *   binder)
   */
  fun runMeminfo(): String?
}
