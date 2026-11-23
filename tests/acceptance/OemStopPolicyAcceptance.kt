// Independent acceptance checks for T19c-1 (OEM protected stop packages).
//
// Expected values are hand-derived from the task spec, not from the
// implementation's unit tests or output:
//  - the 17 OEM package names are transcribed from the dispatch;
//  - the OEM reason string is the literal quoted in the dispatch;
//  - the core reason string is the literal quoted in ANDROID.md §9;
//  - "previous tier" expectations for unlisted packages are the behaviour that
//    ANDROID.md §9 pins as unchanged, so they are the parent (97585c4) policy.
//
// No adb/device access: this exercises the pure policy only.
//
// Compile against app/src/main/java/dev/rsensors/android/core/StopPolicy.kt and
// run OemStopPolicyAcceptanceKt from the repository root. Exits non-zero on any
// failure; prints one line per check so the red run is reproducible.

import dev.rsensors.android.core.StopDecision
import dev.rsensors.android.core.StopRisk
import dev.rsensors.android.core.StopTarget
import dev.rsensors.android.core.stopDecision
import kotlin.system.exitProcess

private var checks = 0
private var failures = 0

private fun expect(condition: Boolean, label: String) {
  checks++
  if (condition) {
    println("ok: $label")
  } else {
    failures++
    println("FAIL: $label")
  }
}

private fun expectDecision(actual: StopDecision, expected: StopDecision, label: String) {
  expect(
    actual == expected,
    "$label: expected $expected, got $actual",
  )
}

private fun expectRisk(actual: StopDecision, expected: StopRisk, label: String) {
  expect(actual.risk == expected, "$label: expected $expected, got $actual")
}

private fun expectReason(actual: StopDecision, expected: String, label: String) {
  expect(actual.reason == expected, "$label: expected reason <$expected>, got <$actual>")
}

/** Ordinary known cached user app: uid 10308, one Cached OOM group. */
private fun app(pkg: String?): StopTarget = StopTarget(
  packageName = pkg,
  uid = 10308,
  groups = setOf("Cached"),
  isKnownApp = true,
)

/** Every OEM package named by the T19c-1 spec, exactly as written there. */
private val oemPackages = listOf(
  // Xiaomi
  "com.miui.securitycenter",
  "com.miui.powerkeeper",
  // Huawei / HONOR
  "com.huawei.systemmanager",
  "com.huawei.powergenie",
  "com.hihonor.systemmanager",
  "com.hihonor.powergenie",
  // ColorOS / Oppo / Realme
  "com.coloros.safecenter",
  "com.coloros.phonemanager",
  "com.coloros.securitypermission",
  "com.coloros.apprecover",
  "com.coloros.athena",
  "com.coloros.gamespace",
  // vivo / iQOO
  "com.vivo.pem",
  "com.iqoo.secure",
  "com.vivo.abe",
  "com.vivo.daemonService",
  "com.vivo.permissionmanager",
)

private const val OEM_REASON = "OEM system package."
private const val CORE_REASON = "Core system package."

fun main() {
  val oem = StopDecision(StopRisk.PROTECTED, OEM_REASON)

  // --- C1: every listed OEM package is PROTECTED with the exact OEM reason.
  for (pkg in oemPackages) {
    expectDecision(stopDecision(app(pkg)), oem, "OEM exact $pkg")
  }

  // --- C2: matching normalizes case and surrounding whitespace.
  for (pkg in oemPackages) {
    val upper = "  ${pkg.uppercase()}  "
    expectDecision(stopDecision(app(upper)), oem, "OEM upper+trim $pkg")
  }
  // Mixed case, including the camelCase spelling from the dispatch.
  expectDecision(stopDecision(app("Com.Vivo.DaemonService")), oem, "OEM mixed case vivo daemon")
  expectDecision(stopDecision(app("COM.COLOROS.ATHENA")), oem, "OEM upper coloros athena")

  // --- C3: flags never downgrade an OEM package; the reason stays OEM.
  for (pkg in oemPackages) {
    val flagged = StopTarget(
      packageName = pkg,
      uid = 10308,
      groups = setOf("Persistent", "Visible", "Foreground"),
      isKnownApp = true,
      isSystemApp = true,
      isUpdatedSystemApp = true,
      isPersistent = true,
      detailsHidden = true,
      isLauncher = true,
      isIme = true,
    )
    expectDecision(stopDecision(flagged), oem, "OEM beats all flags $pkg")
  }

  // --- C4: AOSP core packages still report the core reason, even flagged.
  val flaggedCore = StopTarget(
    packageName = "com.android.phone",
    uid = 10308,
    groups = setOf("Persistent", "Visible"),
    isKnownApp = true,
    isSystemApp = true,
    isUpdatedSystemApp = true,
    isPersistent = true,
    detailsHidden = true,
  )
  expectDecision(
    stopDecision(flaggedCore),
    StopDecision(StopRisk.PROTECTED, CORE_REASON),
    "core reason outranks persistent/OEM path",
  )
  for (core in listOf("android", "com.android.systemui", "com.android.bluetooth")) {
    expectDecision(
      stopDecision(app(core)),
      StopDecision(StopRisk.PROTECTED, CORE_REASON),
      "core exact $core",
    )
  }

  // --- C5a: unlisted packages keep their previous tier and reason.
  expectRisk(stopDecision(app("com.example.notes")), StopRisk.NORMAL, "unlisted cached normal")
  expect(stopDecision(app("com.example.notes")).reason == null, "unlisted cached has no reason")

  val flaggedUnlisted = StopTarget(
    packageName = "com.example.vendor.tool",
    uid = 10308,
    groups = setOf("Cached"),
    isKnownApp = true,
    isSystemApp = true,
    isUpdatedSystemApp = true,
  )
  val unlistedDecision = stopDecision(flaggedUnlisted)
  expectRisk(unlistedDecision, StopRisk.WARNING, "unlisted system stays WARNING")
  expect(
    unlistedDecision.reason == "System app; Android may restart it.",
    "unlisted system keeps previous reason, got <${unlistedDecision.reason}>",
  )

  // All the flags at once: the parent precedence still wins, never the OEM text.
  expectDecision(
    stopDecision(
      flaggedUnlisted.copy(
        groups = setOf("Persistent", "Visible"),
        isPersistent = true,
        detailsHidden = true,
      ),
    ),
    StopDecision(StopRisk.PROTECTED, "Persistent app."),
    "unlisted all flags stays PROTECTED/Persistent app.",
  )

  expectDecision(
    stopDecision(app("com.example.vendor.tool").copy(isPersistent = true)),
    StopDecision(StopRisk.PROTECTED, "Persistent app."),
    "unlisted persistent stays PROTECTED/Persistent app.",
  )
  expectRisk(
    stopDecision(app("com.example.vendor.tool").copy(groups = setOf("Visible"))),
    StopRisk.WARNING,
    "unlisted visible stays WARNING",
  )
  expectRisk(
    stopDecision(app("com.example.vendor.tool").copy(groups = setOf("System"))),
    StopRisk.PROTECTED,
    "unlisted System group stays PROTECTED",
  )
  expectRisk(
    stopDecision(app("com.example.vendor.tool").copy(detailsHidden = true)),
    StopRisk.WARNING,
    "unlisted hidden details stays WARNING",
  )

  // --- C5b: exact set membership only; substrings must not be protected.
  val lookalikes = listOf(
    "com.miui.securitycenter.extra",
    "prefix.com.miui.securitycenter",
    "com.miui.securitycent",
    "com.coloros.athena2",
    "com.vivo.daemonservicex",
    "com.huawei.systemmanager2",
    "com.hihonor.powergenie.beta",
    "com.iqoo.securetv",
  )
  for (pkg in lookalikes) {
    val d = stopDecision(app(pkg))
    expectRisk(d, StopRisk.NORMAL, "lookalike not protected $pkg")
    expect(d.reason == null, "lookalike has no reason $pkg, got <${d.reason}>")
  }

  // --- C6: self / Shizuku / native / low-UID checks are unchanged, and they
  // still outrank the name denylists.
  expectDecision(
    stopDecision(app("com.miui.securitycenter").copy(isSelf = true)),
    StopDecision(StopRisk.PROTECTED, "Rsensors is the overlay app."),
    "self outranks OEM",
  )
  expectDecision(
    stopDecision(app("com.coloros.safecenter").copy(isShizuku = true)),
    StopDecision(StopRisk.PROTECTED, "Shizuku provides the shell route."),
    "Shizuku outranks OEM",
  )
  expectDecision(
    stopDecision(StopTarget(packageName = "com.vivo.pem", uid = 10308, isKnownApp = false)),
    StopDecision(StopRisk.PROTECTED, "Native or unknown process."),
    "unknown package stays native",
  )
  expectDecision(
    stopDecision(StopTarget(packageName = null, uid = null)),
    StopDecision(StopRisk.PROTECTED, "Native or unknown process."),
    "null package stays native",
  )
  for (uid in listOf(0, 1000, 9999)) {
    expectDecision(
      stopDecision(app("com.iqoo.secure").copy(uid = uid)),
      StopDecision(StopRisk.PROTECTED, "Core system process."),
      "low uid stays core-process reason (uid $uid)",
    )
  }

  // --- C6b: untouched warning/normal behaviour.
  val aicore = StopTarget(
    packageName = "com.google.android.aicore",
    uid = 10308,
    groups = setOf("Visible"),
    isKnownApp = true,
    isSystemApp = true,
    isUpdatedSystemApp = true,
  )
  expectRisk(stopDecision(aicore), StopRisk.WARNING, "AICore stays WARNING")
  expect(
    stopDecision(aicore).reason?.contains("AI") == true,
    "AICore keeps its AI reason, got <${stopDecision(aicore).reason}>",
  )
  expectRisk(stopDecision(app("com.google.android.gms")), StopRisk.WARNING, "GMS stays WARNING")
  expectRisk(
    stopDecision(app("com.example.home").copy(isLauncher = true)),
    StopRisk.WARNING,
    "launcher stays WARNING",
  )
  expectRisk(
    stopDecision(app("com.example.ime").copy(isIme = true)),
    StopRisk.WARNING,
    "IME stays WARNING",
  )

  println("checks=$checks failures=$failures")
  if (failures != 0) {
    println("ACCEPTANCE FAILED")
    exitProcess(1)
  }
  println("ACCEPTANCE PASSED")
}
