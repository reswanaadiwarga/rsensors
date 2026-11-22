package dev.rsensors.android.core

/**
 * Version/OS compatibility predicates for the Shizuku manager (ANDROID.md §3, §9).
 *
 * The setup guide warns about exactly one combination it knows to fail: a
 * Shizuku 13.6.0 build on Android 16 or newer, where the UserService starter
 * crashes before app code runs. This file is pure so the decision can be tested
 * without Android.
 */

/** Manager release the guide calls out; a prefix covers build suffixes such as `.r123`. */
const val KNOWN_BAD_SHIZUKU_VERSION = "13.6.0"

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
