package dev.rsensors.android.core

/**
 * Post-stop verification for the T18 stop action (ANDROID.md §8 T18).
 *
 * `am force-stop` exiting 0 is not proof the package stopped: Android accepts
 * the command, then the app can be restarted by a vendor process manager or a
 * service, and some builds print a benign `Force stopping …` line. The shell
 * side therefore re-reads `ps -A -o PID,RSS,NAME` and asks this file whether
 * any process still belongs to the package.
 *
 * Pure: no Android imports, no I/O, so the match rule is JVM-testable on rows
 * produced by [parsePsRss].
 */

/**
 * Names in [rows] that belong to [packageName], in input order.
 *
 * A row matches only when its name is the package itself or begins with
 * `packageName:` (an Android child process such as `pkg:remote`). The package
 * part is matched exactly, so `pkg2`, `pkg.backup` and `other.pkg` never count:
 * a substring hit would report a successful stop as still running. Comparison
 * is case-sensitive because Android package names are lowercase.
 *
 * @param rows parsed process rows; only [ProcessMemory.name] is read, so a
 *   `dumpsys meminfo` snapshot is acceptable too
 * @param packageName package to look for; surrounding whitespace is trimmed,
 *   and a blank name matches nothing
 * @return matching process names, possibly empty; duplicates are preserved
 *   because two live processes are two pieces of evidence
 */
fun stillRunningProcesses(rows: List<ProcessMemory>, packageName: String): List<String> {
  val target = packageName.trim()
  if (target.isEmpty()) return emptyList()
  val childPrefix = "$target:"
  return rows
    .map { it.name }
    .filter { it == target || it.startsWith(childPrefix) }
}
