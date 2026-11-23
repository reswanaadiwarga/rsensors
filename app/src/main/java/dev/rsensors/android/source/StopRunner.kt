package dev.rsensors.android.source

/**
 * Stops a whole package through the shell-side UserService (ANDROID.md §3, §9,
 * §8 T18).
 *
 * The process page's stop action goes through this interface so a missing
 * Shizuku server, a revoked permission or a dead binder is a null response
 * instead of an exception, and the page can report the failure without
 * crashing. Implementations must not throw and must never fall back to a raw
 * `kill`: the only supported action is `am force-stop <package>` under the
 * shell UID.
 */
interface StopRunner {
  /**
   * Stops every process of [packageName] in the shell-side UserService.
   *
   * The implementation validates the package name before touching a process
   * and passes it as a `ProcessBuilder` argument, never as a shell string.
   * `am force-stop` exiting 0 is not proof: after a successful exit the
   * implementation re-reads the process table and rejects a still-running
   * package.
   *
   * @param packageName package to force-stop; an invalid name is rejected
   *   without running anything
   * @return `OK` when the stop command exited 0 and (when the verification
   *   could run) no process of the package remained; `ERR <reason>` when it was
   *   rejected, exited non-zero, failed, or left a process running; or null
   *   when no shell response could be obtained (Shizuku stopped, permission
   *   revoked, dead binder)
   */
  fun stopPackage(packageName: String): String?
}
