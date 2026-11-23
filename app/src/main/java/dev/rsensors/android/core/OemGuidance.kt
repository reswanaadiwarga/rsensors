package dev.rsensors.android.core

/**
 * Brand-aware setup guidance for autostart and Shizuku (ANDROID.md §8 T19d-1).
 *
 * The overlay can be refused a background start by an OEM manager the app cannot
 * read or change, and some OEM builds make the Shizuku wireless-debugging route
 * harder. The app therefore names the settings for the phone it is installed on
 * instead of always describing HyperOS. This file is pure: the UI passes
 * `Build.MANUFACTURER` and `Build.BRAND`, and the copy stays testable on the JVM.
 *
 * Scope: guidance only. Nothing here detects a policy, changes a setting or
 * promises that a feature works; Diagnostics remains the per-feature source of
 * truth. Device verification is still the single POCO X7 Pro build named in
 * ANDROID.md.
 */

/**
 * The OEM software family whose settings screen the guidance describes.
 *
 * [AOSP] and [OTHER] are deliberately separate: [AOSP] is a recognised
 * near-stock maker (no OEM autostart manager to name), while [OTHER] is an
 * unrecognised maker where a device manager may exist but its labels are unknown.
 */
enum class OemFamily { AOSP, XIAOMI, SAMSUNG, HUAWEI_HONOR, COLOROS, VIVO, OTHER }

/**
 * The copy shown for one [family].
 *
 * @property bootTitle short label naming the family, for documentation or a
 *   heading; not rendered by the main screen's single "At boot" paragraph
 * @property bootBody what to change so the overlay may start after a reboot;
 *   product copy, no version notes
 * @property shizukuBody an OEM-specific troubleshooting hint for the Shizuku
 *   server after a reboot; a hint, not a promise and not a requirement
 */
data class OemGuidance(
  val family: OemFamily,
  val bootTitle: String,
  val bootBody: String,
  val shizukuBody: String,
)

/**
 * Classifies a build from `Build.MANUFACTURER` and `Build.BRAND`.
 *
 * The manufacturer is consulted first, then the brand, so a maker that reports
 * its parent company (for example manufacturer `Google`, brand `Redmi`) is
 * classified by the manufacturer. Matching is case-insensitive, trims
 * surrounding whitespace and accepts a token anywhere in the value, because a
 * manufacturer string may carry a company suffix. `null`, blank and unrecognised
 * values fall through; when both miss the result is [OemFamily.OTHER].
 *
 * A recognised near-stock maker (Google, AOSP, Motorola, Sony, Asus, Nothing)
 * maps to [OemFamily.AOSP]; unrecognised makers map to [OemFamily.OTHER].
 */
fun oemFamily(manufacturer: String?, brand: String?): OemFamily =
  familyOf(manufacturer) ?: familyOf(brand) ?: OemFamily.OTHER

/**
 * Returns the copy for [family].
 *
 * Pure and total: every family has non-blank title, body and Shizuku hint, and
 * no branch depends on the device or the test environment.
 */
fun oemGuidance(family: OemFamily): OemGuidance = when (family) {
  OemFamily.XIAOMI -> OemGuidance(
    family = family,
    bootTitle = "Xiaomi, Redmi and POCO",
    bootBody = "HyperOS may block autostart. In the app's system settings, allow " +
      "Autostart and Background autostart, set the battery option to No " +
      "restrictions, and lock the app in Recents, or the overlay stays off until " +
      "you start it by hand. Shizuku still has to be started after every reboot, " +
      "so CPU/GPU and FPS stay unavailable until then; battery and RAM still work.",
    shizukuBody = "On HyperOS, allow Shizuku to autostart and set its battery " +
      "option to No restrictions. If Shizuku cannot find the wireless debugging " +
      "connection, toggle Wireless debugging off and on before pressing Start.",
  )
  OemFamily.SAMSUNG -> OemGuidance(
    family = family,
    bootTitle = "Samsung sleeping apps",
    bootBody = "Samsung limits background apps through Sleeping apps. In Settings " +
      "\u2192 Battery \u2192 Background usage limits, remove Rsensors from Sleeping " +
      "apps and Deep sleeping apps, and allow background activity in the app's " +
      "settings. If installation was blocked, allow this app in Auto Blocker. " +
      "Shizuku still needs starting after every reboot.",
    shizukuBody = "After a reboot, start Shizuku again and make sure it is not in " +
      "Sleeping apps; Samsung may otherwise stop the server while the screen is off.",
  )
  OemFamily.HUAWEI_HONOR -> OemGuidance(
    family = family,
    bootTitle = "Huawei and HONOR app launch",
    bootBody = "EMUI and MagicOS manage background starts through App launch. In " +
      "Settings \u2192 Battery \u2192 App launch, switch Rsensors to Manage " +
      "manually, then enable Auto-launch, Secondary launch and Run in background. " +
      "Shizuku still needs starting after every reboot.",
    shizukuBody = "Many EMUI and MagicOS builds do not offer Wireless debugging. " +
      "Use Shizuku over USB debugging instead, and keep USB debugging on when you " +
      "start the server again.",
  )
  OemFamily.COLOROS -> OemGuidance(
    family = family,
    bootTitle = "ColorOS, Oppo and Realme app launch",
    bootBody = "ColorOS may stop the overlay after a restart. In the app's " +
      "settings, allow Auto launch and Allow background activity, and turn off " +
      "system optimization for Rsensors so it is not closed in the background. " +
      "Shizuku still needs starting after every reboot.",
    shizukuBody = "If Shizuku's permission is revoked after a reboot, turn off " +
      "Permission monitoring in Developer options, then grant the permission again " +
      "in Rsensors.",
  )
  OemFamily.VIVO -> OemGuidance(
    family = family,
    bootTitle = "vivo and iQOO autostart",
    bootBody = "In Settings \u2192 Battery \u2192 Background power consumption " +
      "management, allow Autostart and high background power use for Rsensors, or " +
      "the overlay stays off after a reboot. Shizuku still needs starting after " +
      "every reboot.",
    shizukuBody = "vivo stops the Shizuku server after a restart. Open Shizuku and " +
      "press Start again, and check that Shizuku is allowed to autostart if it " +
      "keeps stopping.",
  )
  OemFamily.AOSP -> OemGuidance(
    family = family,
    bootTitle = "Stock Android battery settings",
    bootBody = "Stock Android has no OEM autostart manager; a near-stock maker " +
      "may still add one. If the overlay does not return after a restart, set the " +
      "battery option to Unrestricted for Rsensors and turn off any battery saver " +
      "that stops background apps. Shizuku still needs starting after every reboot.",
    shizukuBody = "If Shizuku stops after a reboot, start it again from the Shizuku " +
      "app; stock Android does not restart the server by itself.",
  )
  OemFamily.OTHER -> OemGuidance(
    family = family,
    bootTitle = "Device autostart settings",
    bootBody = "This phone may have its own autostart or background-app manager. " +
      "In the app's system settings, allow Rsensors to start on its own and keep " +
      "running in the background, or the overlay stays off until you start it by " +
      "hand. Shizuku still needs starting after every reboot.",
    shizukuBody = "If Shizuku loses its connection after a reboot, start it again " +
      "from the Shizuku app and check the phone's battery settings for background " +
      "limits.",
  )
}

/** Lower-cased maker tokens for each named family; order is not significant. */
private val XIAOMI_TOKENS = listOf("xiaomi", "redmi", "poco")
private val SAMSUNG_TOKENS = listOf("samsung")
private val HUAWEI_HONOR_TOKENS = listOf("huawei", "honor")
private val COLOROS_TOKENS = listOf("oppo", "oneplus", "realme", "coloros")
private val VIVO_TOKENS = listOf("vivo", "iqoo")
private val AOSP_TOKENS = listOf("google", "aosp", "motorola", "sony", "asus", "nothing")

/**
 * Maps one raw maker string, or null when it matches no alias.
 *
 * A token hit is enough: manufacturer strings such as "Xiaomi Communications
 * Co., Ltd." still classify as Xiaomi.
 */
private fun familyOf(raw: String?): OemFamily? {
  val value = raw?.trim()?.lowercase().orEmpty()
  if (value.isEmpty()) return null
  return when {
    value.matchesAny(XIAOMI_TOKENS) -> OemFamily.XIAOMI
    value.matchesAny(SAMSUNG_TOKENS) -> OemFamily.SAMSUNG
    value.matchesAny(HUAWEI_HONOR_TOKENS) -> OemFamily.HUAWEI_HONOR
    value.matchesAny(COLOROS_TOKENS) -> OemFamily.COLOROS
    value.matchesAny(VIVO_TOKENS) -> OemFamily.VIVO
    value.matchesAny(AOSP_TOKENS) -> OemFamily.AOSP
    else -> null
  }
}

private fun String.matchesAny(tokens: List<String>): Boolean = tokens.any { contains(it) }
