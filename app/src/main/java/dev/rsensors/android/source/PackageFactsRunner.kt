package dev.rsensors.android.source

/**
 * Supplies raw `dumpsys package <pkg>` stdout for the T19c-3b hidden-package
 * facts (ANDROID.md §3 "Stop app", §9 "Stop app decisions").
 *
 * The process page asks for one package only when the user selects a row whose
 * [android.content.pm.ApplicationInfo] Android hides. The pure
 * [dev.rsensors.android.core.parsePackageFacts] then reads the UID and the
 * system/updated-system/persistent flags from that dump, so the Stop policy
 * keeps its protection tier without broadening package visibility.
 *
 * Implementations run on the process page's own worker thread and must not
 * throw: a missing Shizuku server, revoked permission, dead binder or failed
 * shell command is a null dump. The caller then resolves the selection with no
 * facts and keeps the existing hidden-details warning.
 */
interface PackageFactsRunner {
  /**
   * Runs one `dumpsys package <packageName>` read in the shell-side
   * UserService.
   *
   * @param packageName the selected row's already-displayed package name; the
   *   UserService validates it with the same rule as `stopPackage`
   * @return raw `dumpsys package` stdout, or null when Shizuku/the binder/the
   *   shell command failed; a rejected name is the UserService's
   *   `ERR invalid package` reply, which the parser reads as no facts
   */
  fun runPackageDump(packageName: String): String?
}
