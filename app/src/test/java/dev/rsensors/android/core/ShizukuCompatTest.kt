package dev.rsensors.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's known-bad Shizuku/OS combination (ANDROID.md §3, §9, §8 T15h/T15j).
 *
 * The decision is only the manager version name and the API level: Android 16
 * (API 36) and above with a Shizuku 13.6.0 build must warn, everything else must
 * not. Expected values are read from §9, not from the implementation.
 */
class ShizukuCompatTest {

  @Test
  fun `13_6_0 on Android 16 is flagged`() {
    assertTrue(hasKnownShizukuProblem("13.6.0", 36))
  }

  @Test
  fun `13_6_0 build suffix is flagged`() {
    assertTrue(hasKnownShizukuProblem("13.6.0.r1234.abcdef", 36))
  }

  @Test
  fun `13_6_0 patch release is flagged`() {
    assertTrue(hasKnownShizukuProblem("13.6.0.1", 36))
  }

  @Test
  fun `13_5_4 on Android 16 is not flagged`() {
    assertFalse(hasKnownShizukuProblem("13.5.4", 36))
  }

  @Test
  fun `missing or blank version is not flagged`() {
    assertFalse(hasKnownShizukuProblem(null, 36))
    assertFalse(hasKnownShizukuProblem("", 36))
    assertFalse(hasKnownShizukuProblem("   ", 36))
  }

  @Test
  fun `the Android 16 boundary is API 36`() {
    assertFalse(hasKnownShizukuProblem("13.6.0", 35))
    assertTrue(hasKnownShizukuProblem("13.6.0", 36))
    assertTrue(hasKnownShizukuProblem("13.6.0", 37))
  }
}
