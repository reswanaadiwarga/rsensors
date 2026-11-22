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
   *
   * @param packageName package to force-stop; an invalid name is rejected
   *   without running anything
   * @return `OK` when the stop command succeeded, `ERR <reason>` when it was
   *   rejected or failed, or null when no shell response could be obtained
   *   (Shizuku stopped, permission revoked, dead binder)
   */
  fun stopPackage(packageName: String): String?
}
