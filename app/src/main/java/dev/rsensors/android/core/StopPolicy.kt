package dev.rsensors.android.core

import java.util.Locale

/**
 * Pure stop-risk policy for the T18 process page (ANDROID.md §3 "Stop app",
 * §7 "Stop policy", §9 "Stop app decisions").
 *
 * "Important" here means *needed by the phone or by this tool*, not "large" and
 * not "system". A huge cached game is safe to stop; a tiny IME is not. The
 * overlay's view supplies the fields from `PackageManager`, the OOM group and
 * its own identity, so this file stays free of Android imports and testable on
 * the JVM.
 *
 * The three tiers drive the UI only:
 *  - [StopRisk.PROTECTED] — no Stop action at all.
 *  - [StopRisk.WARNING] — Stop is offered, behind an explicit confirmation that
 *    names the reason.
 *  - [StopRisk.NORMAL] — cached/background app; Stop still confirms.
 */

/** How much care the page must take before force-stopping a row. */
enum class StopRisk { PROTECTED, WARNING, NORMAL }

/**
 * Everything the policy needs to know about one selected row.
 *
 * [packageName] is the resolved base package, or null for a native/unresolved
 * process row. [uid] comes from the dump; Android allocates app ids from 10000,
 * so anything below is a core system process. [groups] are the row's OOM
 * adjustment group names as printed by `dumpsys meminfo --oom`; matching
 * ignores case and surrounding spaces. The boolean flags mirror
 * `ApplicationInfo`: an Apps row is [isKnownApp] even when package visibility
 * hides its `ApplicationInfo` ([detailsHidden] is then true), while a Processes
 * row is known only when its base package resolves
 * ([isKnownApp] = false otherwise). [isSelf]/[isShizuku] identify this overlay
 * and the shell provider.
 */
data class StopTarget(
  val packageName: String? = null,
  val uid: Int? = null,
  val groups: Set<String> = emptySet(),
  val isKnownApp: Boolean = false,
  val isSystemApp: Boolean = false,
  val isUpdatedSystemApp: Boolean = false,
  val isPersistent: Boolean = false,
  val detailsHidden: Boolean = false,
  val isLauncher: Boolean = false,
  val isIme: Boolean = false,
  val isSelf: Boolean = false,
  val isShizuku: Boolean = false,
)

/**
 * The policy verdict for one row: a tier plus the sentence shown to the user.
 *
 * [reason] is non-null for PROTECTED and WARNING and null only for NORMAL.
 */
data class StopDecision(val risk: StopRisk, val reason: String?)

/**
 * Packages whose absence breaks the phone or this tool's shell route. Matched
 * case-insensitively because Android package names are lowercase but a target
 * may arrive from a differently-cased source.
 */
private val CORE_PACKAGES = setOf(
  "android",
  "com.android.systemui",
  "com.android.phone",
  "com.android.bluetooth",
  "com.android.nfc",
  "com.android.telephony",
  "com.android.server.telecom",
  "com.android.providers.telephony",
  "com.android.providers.settings",
)

/** Lowercase OOM groups that identify a process the system needs running. */
private val PROTECTED_GROUPS = setOf(
  "native",
  "system",
  "persistent",
  "persistent service",
)

/** Lowercase OOM groups that identify a process the user is actively using. */
private val IN_USE_GROUPS = setOf(
  "foreground",
  "visible",
  "perceptible",
  "perceptible low",
  "heavy weight",
)

/** Trims and lowercases a package or OOM group for case-insensitive matching. */
private fun String.normalized(): String = trim().lowercase(Locale.ROOT)

/**
 * Classifies [target] into a [StopRisk] and explains it.
 *
 * The checks run in a fixed order because several flags overlap in practice:
 * this overlay and Shizuku outrank everything; unresolvable processes are
 * native and never stoppable; core UIDs and packages outrank their system and
 * persistent flags; and app-specific reasons (hidden details, AICore, GMS,
 * launcher, IME) outrank the generic "system app" and "currently in use"
 * sentences so the confirmation names the most useful cause. AICore in
 * particular is a system, updated-system, visible app but must stay stoppable
 * as a WARNING.
 *
 * Pure: no Android APIs and no side effects.
 */
fun stopDecision(target: StopTarget): StopDecision {
  fun protected(reason: String) = StopDecision(StopRisk.PROTECTED, reason)
  fun warning(reason: String) = StopDecision(StopRisk.WARNING, reason)

  if (target.isSelf) return protected("Rsensors is the overlay app.")
  if (target.isShizuku) return protected("Shizuku provides the shell route.")
  if (target.packageName == null || !target.isKnownApp) {
    return protected("Native or unknown process.")
  }

  val uid = target.uid
  if (uid != null && uid < 10000) return protected("Core system process.")

  val packageName = target.packageName.normalized()
  if (packageName in CORE_PACKAGES) return protected("Core system package.")

  // The denylist is checked first so a core package reports its real cause even
  // when it also carries FLAG_PERSISTENT (systemui and phone both do).
  if (target.isPersistent) return protected("Persistent app.")

  val groups = target.groups.map { it.normalized() }.toSet()
  if (groups.any { it in PROTECTED_GROUPS }) {
    return protected("System or persistent process.")
  }

  if (target.detailsHidden) {
    return warning("App details are hidden by Android; stopping may affect it.")
  }

  when (packageName) {
    "com.google.android.aicore" ->
      return warning("On-device AI; stopping it may disable AI features until it restarts.")
    "com.google.android.gms" ->
      return warning("Google Play services; many apps may break and Android may restart it.")
  }

  if (target.isLauncher) {
    return warning("Home screen; stopping it will return you to a blank screen until it restarts.")
  }
  if (target.isIme) {
    return warning("Keyboard; stopping it may leave you without a keyboard.")
  }
  if (target.isSystemApp || target.isUpdatedSystemApp) {
    return warning("System app; Android may restart it.")
  }
  if (groups.any { it in IN_USE_GROUPS }) {
    return warning("Currently in use; stopping it will close the app.")
  }

  return StopDecision(StopRisk.NORMAL, null)
}
