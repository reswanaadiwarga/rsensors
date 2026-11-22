package dev.rsensors.android.source

/**
 * One failed sysfs access, kept for the Diagnostics screen.
 *
 * @property path the file or directory that failed
 * @property reason short machine-readable cause: `ENOENT`, `EACCES`, a parse
 *   marker (`parse`, `zero`, `range`), or an I/O exception's simple name
 */
data class SysfsError(val path: String, val reason: String)

/**
 * Minimal file access used by [SysfsSource], injectable so the same source runs
 * on a direct `File` reader (stock) and a future Shizuku reader.
 *
 * Both read methods are defensive: they never throw for a missing, denied, or
 * malformed node. Instead they return null/empty and log a [SysfsError].
 *
 * Threading: one owner thread; implementations need no synchronisation.
 */
interface SysfsReader {
  /** Reads [path] as trimmed UTF-8 text, or null with an error recorded. */
  fun open(path: String): String?

  /** Lists child names of [path], or empty with an error recorded. */
  fun listDir(path: String): List<String>

  /** Snapshot of errors since the last [clearErrors]. */
  fun errors(): List<SysfsError>

  /** Drops the accumulated error log; called by the source at each poll. */
  fun clearErrors()
}
