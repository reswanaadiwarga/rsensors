package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stop-app risk policy for T18 (ANDROID.md §3 "Stop app", §7 "Stop policy",
 * §9 "Stop app decisions").
 *
 * Every expected [StopDecision] below is hand-derived from the spec's decision
 * order and reason strings, never copied from [stopDecision]'s output. The
 * helper builds the ordinary case — a known, cached user app with uid 10308 —
 * so each test states only the flag that decides its tier.
 */
class StopPolicyTest {

  private fun app(
    packageName: String? = "com.example.notes",
    uid: Int? = 10308,
    groups: Set<String> = setOf("Cached"),
    isKnownApp: Boolean = true,
    isSystemApp: Boolean = false,
    isUpdatedSystemApp: Boolean = false,
    isPersistent: Boolean = false,
    detailsHidden: Boolean = false,
    isLauncher: Boolean = false,
    isIme: Boolean = false,
    isSelf: Boolean = false,
    isShizuku: Boolean = false,
  ) = StopTarget(
    packageName = packageName,
    uid = uid,
    groups = groups,
    isKnownApp = isKnownApp,
    isSystemApp = isSystemApp,
    isUpdatedSystemApp = isUpdatedSystemApp,
    isPersistent = isPersistent,
    detailsHidden = detailsHidden,
    isLauncher = isLauncher,
    isIme = isIme,
    isSelf = isSelf,
    isShizuku = isShizuku,
  )

  private val protected = StopRisk.PROTECTED
  private val warning = StopRisk.WARNING
  private val normal = StopRisk.NORMAL

  // ---------------------------------------------------------------- protected

  @Test
  fun `self is protected and wins over every other flag`() {
    assertEquals(
      StopDecision(protected, "Rsensors is the overlay app."),
      stopDecision(
        app(
          packageName = "dev.rsensors.android",
          uid = 1000,
          groups = setOf("Visible"),
          isSystemApp = true,
          isPersistent = true,
          isSelf = true,
          isShizuku = true,
        ),
      ),
    )
  }

  @Test
  fun `Shizuku is protected`() {
    assertEquals(
      StopDecision(protected, "Shizuku provides the shell route."),
      stopDecision(app(packageName = "moe.shizuku.privileged.api", isShizuku = true)),
    )
  }

  @Test
  fun `missing package is native or unknown`() {
    assertEquals(
      StopDecision(protected, "Native or unknown process."),
      stopDecision(app(packageName = null, isKnownApp = false)),
    )
  }

  @Test
  fun `a package that does not resolve to a known app is protected`() {
    assertEquals(
      StopDecision(protected, "Native or unknown process."),
      stopDecision(app(packageName = "vendor.example.npu", isKnownApp = false)),
    )
  }

  @Test
  fun `unknown app is protected before the UID check can report a core process`() {
    assertEquals(
      StopDecision(protected, "Native or unknown process."),
      stopDecision(app(packageName = "kernel.thread", isKnownApp = false, uid = 1000)),
    )
  }

  @Test
  fun `uid below 10000 is a core system process`() {
    for (uid in listOf(0, 1000, 9999)) {
      assertEquals(
        "uid $uid",
        StopDecision(protected, "Core system process."),
        stopDecision(app(uid = uid)),
      )
    }
  }

  @Test
  fun `uid 10000 is the first ordinary app id`() {
    assertEquals(
      StopDecision(normal, null),
      stopDecision(app(uid = 10000)),
    )
  }

  @Test
  fun `persistent app is protected`() {
    assertEquals(
      StopDecision(protected, "Persistent app."),
      stopDecision(app(isPersistent = true)),
    )
  }

  @Test
  fun `core denylist wins over the persistent flag`() {
    assertEquals(
      StopDecision(protected, "Core system package."),
      stopDecision(
        app(packageName = "com.android.phone", isPersistent = true, isSystemApp = true),
      ),
    )
  }

  @Test
  fun `every core system package is protected`() {
    val denylist = listOf(
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

    for (pkg in denylist) {
      assertEquals(
        pkg,
        StopDecision(protected, "Core system package."),
        stopDecision(app(packageName = pkg)),
      )
    }
  }

  @Test
  fun `a core system package stays protected when it carries system flags`() {
    assertEquals(
      StopDecision(protected, "Core system package."),
      stopDecision(
        app(
          packageName = "com.android.systemui",
          groups = setOf("Visible", "Persistent"),
          isSystemApp = true,
          isUpdatedSystemApp = true,
        ),
      ),
    )
  }

  // --------------------------------------------------------------- OEM managers
  //
  // Hand-listed from the T19c-1 spec so a typo in the production set is caught.
  private val oemPackages = listOf(
    "com.miui.securitycenter",
    "com.miui.powerkeeper",
    "com.huawei.systemmanager",
    "com.huawei.powergenie",
    "com.hihonor.systemmanager",
    "com.hihonor.powergenie",
    "com.coloros.safecenter",
    "com.coloros.phonemanager",
    "com.coloros.securitypermission",
    "com.coloros.apprecover",
    "com.coloros.athena",
    "com.coloros.gamespace",
    "com.vivo.pem",
    "com.iqoo.secure",
    "com.vivo.abe",
    "com.vivo.daemonService",
    "com.vivo.permissionmanager",
  )

  @Test
  fun `every OEM manager package is protected`() {
    for (pkg in oemPackages) {
      assertEquals(
        pkg,
        StopDecision(protected, "OEM system package."),
        stopDecision(app(packageName = pkg)),
      )
    }
  }

  @Test
  fun `OEM package matching ignores case and surrounding spaces`() {
    assertEquals(
      StopDecision(protected, "OEM system package."),
      stopDecision(app(packageName = "  COM.MIUI.SECURITYCENTER  ")),
    )
    assertEquals(
      StopDecision(protected, "OEM system package."),
      stopDecision(app(packageName = "COM.VIVO.DAEMONSERVICE")),
    )
  }

  @Test
  fun `OEM package stays protected with persistent, system and in-use flags`() {
    assertEquals(
      StopDecision(protected, "OEM system package."),
      stopDecision(
        app(
          packageName = "com.miui.securitycenter",
          groups = setOf("Persistent", "Foreground"),
          isSystemApp = true,
          isUpdatedSystemApp = true,
          isPersistent = true,
        ),
      ),
    )
    assertEquals(
      StopDecision(protected, "OEM system package."),
      stopDecision(
        app(
          packageName = "com.huawei.powergenie",
          groups = setOf("Visible"),
          isSystemApp = true,
        ),
      ),
    )
  }

  @Test
  fun `OEM package stays protected when its details are hidden`() {
    assertEquals(
      StopDecision(protected, "OEM system package."),
      stopDecision(app(packageName = "com.coloros.athena", detailsHidden = true)),
    )
  }

  @Test
  fun `core package keeps the core reason ahead of the OEM check`() {
    assertEquals(
      StopDecision(protected, "Core system package."),
      stopDecision(app(packageName = "com.android.phone", isSystemApp = true)),
    )
  }

  @Test
  fun `native and system OOM groups are protected`() {
    val protectedGroups = listOf(
      "Native",
      "System",
      "Persistent",
      "Persistent Service",
      "native",
      "PERSISTENT SERVICE",
      "  Persistent Service  ",
    )

    for (group in protectedGroups) {
      assertEquals(
        group,
        StopDecision(protected, "System or persistent process."),
        stopDecision(app(groups = setOf(group))),
      )
    }
  }

  @Test
  fun `one protected group is enough to protect a cached app`() {
    assertEquals(
      StopDecision(protected, "System or persistent process."),
      stopDecision(app(groups = setOf("Cached", "native"))),
    )
  }

  // ------------------------------------------------------------------ warning

  @Test
  fun `AICore is a warning even with system, updated-system and visible flags`() {
    assertEquals(
      StopDecision(
        warning,
        "On-device AI; stopping it may disable AI features until it restarts.",
      ),
      stopDecision(
        app(
          packageName = "com.google.android.aicore",
          groups = setOf("Visible"),
          isSystemApp = true,
          isUpdatedSystemApp = true,
        ),
      ),
    )
  }

  @Test
  fun `AICore is a warning before the generic system-app reason`() {
    assertEquals(
      StopDecision(
        warning,
        "On-device AI; stopping it may disable AI features until it restarts.",
      ),
      stopDecision(app(packageName = "com.google.android.aicore", isSystemApp = true)),
    )
  }

  @Test
  fun `Google Play services is a warning before the generic system-app reason`() {
    assertEquals(
      StopDecision(
        warning,
        "Google Play services; many apps may break and Android may restart it.",
      ),
      stopDecision(
        app(packageName = "com.google.android.gms", isSystemApp = true, isUpdatedSystemApp = true),
      ),
    )
  }

  @Test
  fun `GMS in a protected group is protected before the GMS warning`() {
    assertEquals(
      StopDecision(protected, "System or persistent process."),
      stopDecision(
        app(
          packageName = "com.google.android.gms",
          groups = setOf("Persistent"),
          isSystemApp = true,
        ),
      ),
    )
  }

  @Test
  fun `launcher is a warning`() {
    assertEquals(
      StopDecision(
        warning,
        "Home screen; stopping it will return you to a blank screen until it restarts.",
      ),
      stopDecision(app(isLauncher = true)),
    )
  }

  @Test
  fun `IME is a warning`() {
    assertEquals(
      StopDecision(warning, "Keyboard; stopping it may leave you without a keyboard."),
      stopDecision(app(isIme = true)),
    )
  }

  @Test
  fun `hidden app details are a warning before the system-app reason`() {
    assertEquals(
      StopDecision(
        warning,
        "App details are hidden by Android; stopping may affect it.",
      ),
      stopDecision(app(detailsHidden = true, isSystemApp = true)),
    )
  }

  @Test
  fun `system app is a warning`() {
    assertEquals(
      StopDecision(warning, "System app; Android may restart it."),
      stopDecision(app(isSystemApp = true)),
    )
  }

  @Test
  fun `updated system app is a warning`() {
    assertEquals(
      StopDecision(warning, "System app; Android may restart it."),
      stopDecision(app(isUpdatedSystemApp = true)),
    )
  }

  @Test
  fun `launcher wins over the generic system-app reason`() {
    assertEquals(
      StopDecision(
        warning,
        "Home screen; stopping it will return you to a blank screen until it restarts.",
      ),
      stopDecision(app(isLauncher = true, isSystemApp = true, isUpdatedSystemApp = true)),
    )
  }

  @Test
  fun `in-use OOM groups are a warning`() {
    val inUseGroups = listOf(
      "Foreground",
      "Visible",
      "Perceptible",
      "Perceptible Low",
      "Heavy Weight",
      "visible",
      "PERCEPTIBLE LOW",
      "  Heavy Weight  ",
    )

    for (group in inUseGroups) {
      assertEquals(
        group,
        StopDecision(warning, "Currently in use; stopping it will close the app."),
        stopDecision(app(groups = setOf(group))),
      )
    }
  }

  @Test
  fun `an in-use group is a warning next to a cached group`() {
    assertEquals(
      StopDecision(warning, "Currently in use; stopping it will close the app."),
      stopDecision(app(groups = setOf("Cached", "Perceptible Low"))),
    )
  }

  @Test
  fun `AICore keeps its reason when it is also visible and a system app`() {
    assertEquals(
      StopDecision(
        warning,
        "On-device AI; stopping it may disable AI features until it restarts.",
      ),
      stopDecision(
        app(
          packageName = "com.google.android.aicore",
          groups = setOf("Foreground", "Visible"),
          isSystemApp = true,
          isUpdatedSystemApp = true,
        ),
      ),
    )
  }

  // ------------------------------------------------------------------- normal

  @Test
  fun `cached user app is normal with no reason`() {
    assertEquals(
      StopDecision(normal, null),
      stopDecision(app(packageName = "com.example.notes", uid = 10308, groups = setOf("Cached"))),
    )
  }

  @Test
  fun `service app is normal`() {
    assertEquals(
      StopDecision(normal, null),
      stopDecision(app(packageName = "com.example.sync", groups = setOf("Service"))),
    )
  }

  @Test
  fun `known app with no flags and no groups is normal`() {
    val decision = stopDecision(
      app(packageName = "com.example.plain", uid = null, groups = emptySet(), detailsHidden = false),
    )

    assertEquals(normal, decision.risk)
    assertEquals(null, decision.reason)
  }

  // --------------------------------------------------------- case-insensitivity

  @Test
  fun `package matching ignores case`() {
    assertEquals(
      StopDecision(protected, "Core system package."),
      stopDecision(app(packageName = "COM.ANDROID.SYSTEMUI")),
    )
    assertEquals(
      StopDecision(
        warning,
        "Google Play services; many apps may break and Android may restart it.",
      ),
      stopDecision(app(packageName = "Com.Google.Android.Gms")),
    )
    assertEquals(
      StopDecision(
        warning,
        "On-device AI; stopping it may disable AI features until it restarts.",
      ),
      stopDecision(app(packageName = "com.google.android.AICore")),
    )
  }
}
