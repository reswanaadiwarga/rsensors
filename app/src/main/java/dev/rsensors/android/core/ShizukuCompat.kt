package dev.rsensors.android.core

/**
 * Version/OS compatibility predicates for the Shizuku manager (ANDROID.md §3, §9).
 *
 * The setup guide warns about one conservative combination: a Shizuku 13.6.0
 * build on Android 16 or newer. 13.6.0 does support Android 16 QPR1 in general;
 * on some Android 16 devices, including HyperOS 3, the UserService starter
 * crashes in `LoadedApk.makeApplicationInner` before app code runs (upstream
 * #2629/#2633), so the warning is a hint to try the known-working 13.5.4
 * release, not a claim that every Android 16 device fails. This file is pure so
 * the decision can be tested without Android.
 */

/** Manager release the guide calls out; a prefix covers build suffixes such as `.r123`. */
const val KNOWN_BAD_SHIZUKU_VERSION = "13.6.0"

/** GitHub release page for the fallback manager version the guide downloads from. */
const val SHIZUKU_1354_RELEASE_URL =
  "https://github.com/RikkaApps/Shizuku/releases/tag/v13.5.4"

/**
 * Exact asset file name on [SHIZUKU_1354_RELEASE_URL].
 *
 * The page also offers "Source code" zip/tar assets; this is the installable
 * APK (~3.4 MB) listed under Assets.
 */
const val SHIZUKU_1354_APK = "shizuku-v13.5.4.r1049.0e53409-release.apk"

/** First API level of Android 16. */
const val ANDROID_16_SDK = 36

/**
 * Whether the detected manager/OS pair is the combination the guide warns about.
 *
 * The guide states no version requirement: any other version, a missing version
 * and any OS below Android 16 are all "no warning".
 *
 * @param versionName installed manager's version name, or null when unreadable
 * @param sdkInt `Build.VERSION.SDK_INT` of the running OS
 * @return true only for a `13.6.0` prefix on API 36+
 */
fun hasKnownShizukuProblem(versionName: String?, sdkInt: Int): Boolean =
  versionName != null &&
    versionName.startsWith(KNOWN_BAD_SHIZUKU_VERSION) &&
    sdkInt >= ANDROID_16_SDK
