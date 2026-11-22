package dev.rsensors.android.source

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * [SysfsReader] backed by the process's own `java.io.File` access.
 *
 * Used at tier 0: on devices that expose the thermal nodes to the app this
 * reads them directly; on locked-down devices every call fails and records an
 * error, and [SysfsSource] simply yields null fields.
 *
 * [open] always attempts the read and classifies the failure (ANDROID.md §5):
 * - permission denied -> `EACCES`
 * - path missing -> `ENOENT`
 * - any other I/O problem -> the exception's simple class name
 *
 * It deliberately has no `exists()` / `canRead()` pre-check: under SELinux
 * those probes can report a denied node as missing, which would mislabel the
 * denial as `ENOENT`.
 *
 * Side effects: appends to an in-memory error log; [open] and [listDir] never
 * throw. Single owner thread.
 */
class DirectReader : SysfsReader {
  private val log = mutableListOf<SysfsError>()

  override fun open(path: String): String? =
    try {
      File(path).readText(Charsets.UTF_8).trim()
    } catch (e: SecurityException) {
      fail(path, reasonFor(e))
    } catch (e: IOException) {
      fail(path, reasonFor(e))
    }

  override fun listDir(path: String): List<String> {
    val dir = File(path)
    if (!dir.exists()) return failList(path, "ENOENT")
    if (!dir.canRead()) return failList(path, "EACCES")
    return try {
      val names = dir.list()
      if (names == null) failList(path, "EACCES") else names.toList()
    } catch (e: SecurityException) {
      failList(path, "EACCES")
    } catch (e: IOException) {
      failList(path, e.javaClass.simpleName)
    }
  }

  override fun errors(): List<SysfsError> = log.toList()

  override fun clearErrors() {
    log.clear()
  }

  private fun fail(path: String, reason: String): String? {
    log += SysfsError(path, reason)
    return null
  }

  private fun failList(path: String, reason: String): List<String> {
    log += SysfsError(path, reason)
    return emptyList()
  }

  companion object {
    private const val EACCES = "EACCES"
    private const val ENOENT = "ENOENT"

    /**
     * Maps a read failure to the Diagnostics reason (ANDROID.md §5).
     *
     * Android reports an SELinux-denied node as a [FileNotFoundException] with
     * an "EACCES (Permission denied)" message; that counts as `EACCES`. Any
     * other missing-file failure is `ENOENT`, and everything else keeps the
     * exception's simple class name.
     *
     * @param e failure thrown by the read; never null
     * @return short machine-readable reason for [SysfsError]
     */
    internal fun reasonFor(e: Throwable): String =
      when (e) {
        is SecurityException -> EACCES
        is FileNotFoundException -> if (isPermissionMessage(e.message)) EACCES else ENOENT
        else -> e.javaClass.simpleName
      }

    private fun isPermissionMessage(message: String?): Boolean {
      val lower = message?.lowercase(Locale.ROOT) ?: return false
      return "permission" in lower || "access is denied" in lower
    }
  }
}
