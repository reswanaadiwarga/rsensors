// Independent acceptance checks for T19d-1 (OEM-aware setup guidance).
//
// Expected values are hand-derived from the task dispatch, the parent tree and
// the pre-existing OEM notes in docs/UNIVERSALITY.md (which is unchanged by this
// task). They are NOT copied from the implementation's unit test or from
// running the implementation:
//  - the alias -> family map, manufacturer-before-brand rule, case-insensitive
//    trimming and unknown/blank/null -> OTHER rule are the dispatch text;
//  - "Xiaomi keeps HyperOS wording" is compared with the string that exists in
//    the parent commit (d72bcc0), not with the new implementation;
//  - the setting labels (Sleeping apps, App launch, Auto launch, Permission
//    monitoring, Autostart) are the platform names already recorded in
//    docs/UNIVERSALITY.md before this task;
//  - "no universal works claim" is a spec requirement, so the copy is scanned
//    for universal/support/verification language.
//
// No adb/device access: this exercises the pure mapping and copy only.
//
// Compile with app/src/main/java/dev/rsensors/android/core/OemGuidance.kt and run
// OemGuidanceAcceptanceKt from the repository root. It exits non-zero on any
// failure and prints one line per check so the red run is reproducible.

import dev.rsensors.android.core.OemFamily
import dev.rsensors.android.core.oemFamily
import dev.rsensors.android.core.oemGuidance
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

private fun expectFamily(
  expected: OemFamily,
  manufacturer: String?,
  brand: String?,
  label: String,
) {
  val actual = oemFamily(manufacturer, brand)
  expect(
    actual == expected,
    "$label: manufacturer=<$manufacturer> brand=<$brand> expected $expected, got $actual",
  )
}

private fun guidanceText(family: OemFamily): String {
  val guidance = oemGuidance(family)
  return "${guidance.bootTitle} ${guidance.bootBody} ${guidance.shizukuBody}".lowercase()
}

fun main() {
  // -------------------------------------------------------------- enum shape
  // The dispatch names exactly these seven outcomes; an extra or missing family
  // would make the README rows and the UI copy incomplete.
  expect(
    OemFamily.values().map { it.name }.toSet() ==
      setOf("AOSP", "XIAOMI", "SAMSUNG", "HUAWEI_HONOR", "COLOROS", "VIVO", "OTHER"),
    "OemFamily has exactly the seven families named by the spec, got " +
      OemFamily.values().map { it.name },
  )

  // ------------------------------------------------------------- Xiaomi group
  expectFamily(OemFamily.XIAOMI, "Xiaomi", "Xiaomi", "Xiaomi manufacturer")
  expectFamily(OemFamily.XIAOMI, "Redmi", "Redmi", "Redmi manufacturer")
  expectFamily(OemFamily.XIAOMI, "POCO", "POCO", "POCO manufacturer")
  expectFamily(OemFamily.XIAOMI, "poco", null, "POCO lower-case manufacturer")
  expectFamily(OemFamily.XIAOMI, null, "Redmi", "Redmi brand fallback")
  expectFamily(OemFamily.XIAOMI, "  xIaOmI  ", null, "trimmed mixed-case Xiaomi")

  // ------------------------------------------------------------ Samsung group
  expectFamily(OemFamily.SAMSUNG, "Samsung", "Samsung", "Samsung manufacturer")
  expectFamily(OemFamily.SAMSUNG, null, "SAMSUNG", "Samsung brand fallback")
  expectFamily(OemFamily.SAMSUNG, null, " sAmSuNg ", "trimmed mixed-case Samsung brand")

  // ------------------------------------------------------- Huawei/HONOR group
  expectFamily(OemFamily.HUAWEI_HONOR, "Huawei", "Huawei", "Huawei manufacturer")
  expectFamily(OemFamily.HUAWEI_HONOR, "HONOR", "HONOR", "HONOR manufacturer")
  expectFamily(OemFamily.HUAWEI_HONOR, null, "honor", "honor brand fallback")
  expectFamily(OemFamily.HUAWEI_HONOR, "  HoNoR ", null, "trimmed mixed-case HONOR")

  // ------------------------------------------------------------ ColorOS group
  expectFamily(OemFamily.COLOROS, "Oppo", "Oppo", "Oppo manufacturer")
  expectFamily(OemFamily.COLOROS, "OnePlus", "OnePlus", "OnePlus manufacturer")
  expectFamily(OemFamily.COLOROS, "realme", "realme", "Realme manufacturer")
  expectFamily(OemFamily.COLOROS, "ColorOS", "ColorOS", "ColorOS manufacturer")
  expectFamily(OemFamily.COLOROS, " onePLUS ", null, "trimmed mixed-case OnePlus")
  expectFamily(OemFamily.COLOROS, null, "realme", "Realme brand fallback")

  // --------------------------------------------------------------- vivo group
  expectFamily(OemFamily.VIVO, "vivo", "vivo", "vivo manufacturer")
  expectFamily(OemFamily.VIVO, "iQOO", "iQOO", "iQOO manufacturer")
  expectFamily(OemFamily.VIVO, "IQOO", null, "iQOO upper-case manufacturer")
  expectFamily(OemFamily.VIVO, null, "iQOO", "iQOO brand fallback")
  expectFamily(OemFamily.VIVO, "\tvIvO\t", null, "tab-trimmed mixed-case vivo")

  // --------------------------------------------------------------- AOSP group
  for (maker in listOf("Google", "AOSP", "Motorola", "Sony", "ASUS", "Nothing")) {
    expectFamily(OemFamily.AOSP, maker, maker, "stock-like maker $maker")
  }
  expectFamily(OemFamily.AOSP, "  nOtHiNg  ", null, "trimmed mixed-case Nothing")
  expectFamily(OemFamily.AOSP, null, "Motorola", "Motorola brand fallback")

  // -------------------------------------------------------------- OTHER group
  expectFamily(OemFamily.OTHER, "unknown", "unknown", "unknown manufacturer and brand")
  expectFamily(OemFamily.OTHER, "", " ", "blank manufacturer and blank brand")
  expectFamily(OemFamily.OTHER, null, null, "null manufacturer and brand")
  expectFamily(OemFamily.OTHER, null, "", "null manufacturer and empty brand")
  expectFamily(OemFamily.OTHER, "  \t ", null, "whitespace-only manufacturer")

  // ---------------------------------------------------- manufacturer before brand
  expectFamily(OemFamily.SAMSUNG, "samsung", "Redmi", "Samsung manufacturer beats Xiaomi brand")
  expectFamily(OemFamily.AOSP, "Google", "Redmi", "Google manufacturer beats Xiaomi brand")
  expectFamily(OemFamily.HUAWEI_HONOR, "Huawei", "Samsung", "Huawei manufacturer beats Samsung brand")
  expectFamily(OemFamily.VIVO, "vivo", "OnePlus", "vivo manufacturer beats ColorOS brand")
  expectFamily(OemFamily.XIAOMI, "unknown", "poco", "unknown manufacturer still checks brand")
  expectFamily(OemFamily.VIVO, null, "iQOO", "null manufacturer still checks brand")

  // ------------------------------------------------------------ complete copy
  for (family in OemFamily.values()) {
    val guidance = oemGuidance(family)
    expect(guidance.family == family, "$family guidance reports its own family")
    expect(guidance.bootTitle.isNotBlank(), "$family bootTitle is non-blank")
    expect(guidance.bootBody.isNotBlank(), "$family bootBody is non-blank")
    expect(guidance.shizukuBody.isNotBlank(), "$family shizukuBody is non-blank")
  }

  // Family-appropriate copy means the seven families cannot share one body or
  // one Shizuku hint.
  val bodies = OemFamily.values().map { oemGuidance(it).bootBody }
  val hints = OemFamily.values().map { oemGuidance(it).shizukuBody }
  expect(bodies.toSet().size == OemFamily.values().size, "boot bodies differ across families")
  expect(hints.toSet().size == OemFamily.values().size, "Shizuku hints differ across families")

  // --------------------------------------------------- family-specific setting names
  // The Xiaomi copy must keep the HyperOS wording and instructions that the
  // parent commit's "At boot" paragraph used.
  val xiaomi = oemGuidance(OemFamily.XIAOMI).bootBody.lowercase()
  expect(xiaomi.contains("hyperos"), "XIAOMI bootBody keeps HyperOS wording")
  expect(
    xiaomi.contains("hyperos may block autostart"),
    "XIAOMI bootBody keeps the parent HyperOS autostart sentence",
  )
  expect(xiaomi.contains("background autostart"), "XIAOMI bootBody names Background autostart")
  expect(xiaomi.contains("no restrictions"), "XIAOMI bootBody names the No restrictions battery option")
  expect(xiaomi.contains("recents"), "XIAOMI bootBody names the Recents lock")

  val samsung = oemGuidance(OemFamily.SAMSUNG)
  expect(
    samsung.bootBody.lowercase().contains("sleeping apps"),
    "SAMSUNG bootBody names Sleeping apps",
  )
  expect(
    samsung.shizukuBody.lowercase().contains("sleeping apps"),
    "SAMSUNG Shizuku hint names Sleeping apps",
  )
  expect(
    samsung.bootBody.lowercase().contains("auto blocker"),
    "SAMSUNG bootBody names Auto Blocker",
  )

  val huawei = oemGuidance(OemFamily.HUAWEI_HONOR)
  expect(
    huawei.bootBody.lowercase().contains("app launch"),
    "HUAWEI_HONOR bootBody names App launch",
  )
  expect(
    huawei.bootBody.lowercase().contains("manage manually"),
    "HUAWEI_HONOR bootBody names Manage manually",
  )
  expect(
    huawei.shizukuBody.lowercase().contains("usb debugging"),
    "HUAWEI_HONOR Shizuku hint names USB debugging",
  )

  val coloros = oemGuidance(OemFamily.COLOROS)
  expect(
    coloros.bootBody.lowercase().contains("auto launch"),
    "COLOROS bootBody names Auto launch",
  )
  expect(
    coloros.shizukuBody.lowercase().contains("permission monitoring"),
    "COLOROS Shizuku hint names Permission monitoring",
  )

  val vivo = oemGuidance(OemFamily.VIVO)
  expect(
    vivo.bootBody.lowercase().contains("autostart"),
    "VIVO bootBody names Autostart",
  )
  expect(
    vivo.shizukuBody.lowercase().contains("start again") ||
      vivo.shizukuBody.lowercase().contains("restart"),
    "VIVO Shizuku hint tells the reader to start the server again",
  )

  val aosp = oemGuidance(OemFamily.AOSP).bootBody.lowercase()
  expect(aosp.contains("no oem"), "AOSP bootBody says there is no OEM autostart manager")
  expect(aosp.contains("battery"), "AOSP bootBody still names the battery option")

  // AOSP and OTHER must not borrow another family's settings screen names.
  for (family in listOf(OemFamily.AOSP, OemFamily.OTHER)) {
    val text = guidanceText(family)
    for (borrowed in listOf(
      "hyperos", "sleeping apps", "app launch", "auto launch", "emui", "magicos",
      "coloros", "permission monitoring",
    )) {
      expect(
        !text.contains(borrowed),
        "$family copy avoids another family's setting <$borrowed>",
      )
    }
  }

  // -------------------------------------------------- no universal works claim
  val banned = listOf(
    "works on", "works with", "guaranteed", "guarantee", "all devices",
    "every device", "universally", "fully supported", "supported on all",
    "verified support", "on any phone",
  )
  for (family in OemFamily.values()) {
    val text = guidanceText(family)
    for (phrase in banned) {
      expect(!text.contains(phrase), "$family copy must not claim <$phrase>: $text")
    }
  }

  println("checks=$checks failures=$failures")
  if (failures != 0) {
    println("ACCEPTANCE FAILED")
    exitProcess(1)
  }
  println("ACCEPTANCE PASSED")
}
