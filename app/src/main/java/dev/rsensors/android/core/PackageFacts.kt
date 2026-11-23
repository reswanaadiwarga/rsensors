package dev.rsensors.android.core

import java.util.Locale

/**
 * Package flags and UID for the selected process row (ANDROID.md §3 "Stop app",
 * §8 T19c-3b).
 *
 * Android package visibility can hide an [android.content.pm.ApplicationInfo],
 * so the stop policy loses the UID and the system/persistent flags for that app.
 * [parsePackageFacts] reads them from the shell-side
 * `dumpsys package <pkg>` stdout on demand, for one selected package only.
 * Nothing here touches Android: the runner supplies raw stdout and this file
 * stays testable on the JVM.
 *
 * @property uid the package's app id; null when the dump carried neither
 *   `appId` nor `userId`
 * @property isSystem true when the package flags contained `SYSTEM`
 * @property isUpdatedSystem true when they contained `UPDATED_SYSTEM_APP`
 * @property isPersistent true when they contained `PERSISTENT`
 */
data class PackageFacts(
  val uid: Int?,
  val isSystem: Boolean,
  val isUpdatedSystem: Boolean,
  val isPersistent: Boolean,
)

/** `Package [name]` opens one package block in `dumpsys package`. */
private val PACKAGE_HEADER = Regex("""^\s*Package\s*\[[^\]]*]""", RegexOption.IGNORE_CASE)

/**
 * Sections that follow the package's own fields. Permission/user blocks can
 * print their own `flags=[ … ]`, so the fallback flag search ends here even
 * when the target package block prints no package-level flags.
 */
private val PACKAGE_BLOCK_END = Regex(
  """^\s*(User\s+\d+\s*:|requested permissions:|install permissions:|""" +
    """runtime permissions:|SharedUser\s*\[|Hidden system packages:)""",
  RegexOption.IGNORE_CASE,
)

/** `appId=10123`; the preferred source of the package UID. */
private val APP_ID = Regex("""\bappId\s*=\s*(\d+)\b""", RegexOption.IGNORE_CASE)

/** `userId=10123`; the older fallback when no appId is printed. */
private val USER_ID = Regex("""\buserId\s*=\s*(\d+)\b""", RegexOption.IGNORE_CASE)

/**
 * The preferred package-level flag line, `pkgFlags=[ SYSTEM … ]`. The leading
 * lookbehind keeps `privateFlags` and other `…Flags` fields from matching.
 */
private val PKG_FLAGS = Regex(
  """(?<![\w])pkgFlags\s*=\s*\[([^\]]*)]""",
  RegexOption.IGNORE_CASE,
)

/** The older package-level line, `flags=[ SYSTEM … ]`. */
private val FLAGS = Regex(
  """(?<![\w])flags\s*=\s*\[([^\]]*)]""",
  RegexOption.IGNORE_CASE,
)

/** Flags are whitespace-separated whole tokens, possibly with extra spaces. */
private val FLAG_SPACES = Regex("""\s+""")

/**
 * Reads one package's UID and stop-relevant flags from `dumpsys package <pkg>`
 * stdout (T19c-3b).
 *
 * Only the first `Package [name]` block is read; a later `Package` header ends
 * it, as does the first user/permission section, so permission-level
 * `flags=[ … ]` lines can never be mistaken for the package's own flags.
 * `appId` is preferred over `userId`; `pkgFlags` is preferred over the first
 * package-level `flags` line. Flag tokens are matched case-insensitively as
 * whole words, so `SYSTEMISH` or `NONPERSISTENT` do not enable a flag.
 *
 * A dump with no package block, or one with neither a UID nor any flag block,
 * yields null so the caller can keep the existing hidden-details warning. An
 * appId without flags (or flags without a UID) still yields the facts that are
 * present.
 *
 * Pure: no Android calls and no side effects.
 *
 * @param raw stdout of the shell-side `dumpsys package <pkg>` command
 * @return parsed facts, or null when the block carries nothing usable
 */
fun parsePackageFacts(raw: String): PackageFacts? {
  var started = false
  var appId: Int? = null
  var userId: Int? = null
  var pkgFlags: String? = null
  var firstFlags: String? = null

  for (line in raw.lineSequence()) {
    if (!started) {
      if (PACKAGE_HEADER.containsMatchIn(line)) started = true
      continue
    }
    if (PACKAGE_HEADER.containsMatchIn(line)) break
    if (PACKAGE_BLOCK_END.containsMatchIn(line)) break

    if (appId == null) {
      APP_ID.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { appId = it }
    }
    if (userId == null) {
      USER_ID.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { userId = it }
    }
    if (pkgFlags == null) {
      PKG_FLAGS.find(line)?.groupValues?.get(1)?.let { pkgFlags = it }
    }
    if (firstFlags == null) {
      FLAGS.find(line)?.groupValues?.get(1)?.let { firstFlags = it }
    }
  }

  if (!started) return null
  val uid = appId ?: userId
  val flags = pkgFlags ?: firstFlags
  if (uid == null && flags == null) return null

  val tokens = flags
    ?.split(FLAG_SPACES)
    ?.filter { it.isNotEmpty() }
    ?.map { it.uppercase(Locale.ROOT) }
    ?.toSet()
    .orEmpty()
  return PackageFacts(
    uid = uid,
    isSystem = "SYSTEM" in tokens,
    isUpdatedSystem = "UPDATED_SYSTEM_APP" in tokens,
    isPersistent = "PERSISTENT" in tokens,
  )
}
