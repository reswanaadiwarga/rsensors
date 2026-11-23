// Independent acceptance checks for T19c-3b lazy package-flags resolution.
//
// Expected values are derived from the task spec and hand-written fixtures,
// never from the implementation's unit tests or from its output.
//
// Compile against app/src/main/java/dev/rsensors/android/core/PackageFacts.kt,
// then run PackageFactsAcceptanceKt from the repository root; it exits
// non-zero on any failure.

import dev.rsensors.android.core.PackageFacts
import dev.rsensors.android.core.parsePackageFacts
import kotlin.system.exitProcess

private var failures = 0
private var checks = 0

private fun expect(condition: Boolean, message: String) {
  checks++
  if (!condition) {
    failures++
    println("FAIL: $message")
  } else {
    println("ok: $message")
  }
}

private fun expectEquals(actual: Any?, expected: Any?, label: String) {
  expect(actual == expected, "$label: expected <$expected>, got <$actual>")
}

fun main() {
  systemFromPkgFlags()
  updatedSystemFromFlags()
  persistentFromPkgFlags()
  combinedFlags()
  appIdPreferredOverUserId()
  userIdFallback()
  pkgFlagsPreferredOverFlags()
  permissionFlagsDoNotLeak()
  missingBlockIsNull()
  blockWithoutUidOrFlagsIsNull()
  caseAndSpacing()
  wholeTokensOnly()
  firstPackageBlockOnly()
  partialBlocks()
  realisticDumpShape()

  println("$checks checks, $failures failures")
  if (failures != 0) exitProcess(1)
}

// Spec: whole-token, case-insensitive SYSTEM/UPDATED_SYSTEM_APP/PERSISTENT.
private fun systemFromPkgFlags() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.system] (0001):
          userId=1000
          appId=1000
          pkg=Package{0001 com.example.system}
          codePath=/system/priv-app/System
          pkgFlags=[ SYSTEM HAS_CODE ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 1000, isSystem = true, isUpdatedSystem = false, isPersistent = false),
    "SYSTEM in pkgFlags uses appId",
  )
}

private fun updatedSystemFromFlags() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.updated] (0002):
          userId=10308
          pkg=Package{0002 com.example.updated}
          flags=[ SYSTEM UPDATED_SYSTEM_APP HAS_CODE ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10308, isSystem = true, isUpdatedSystem = true, isPersistent = false),
    "first package-level flags supplies UPDATED_SYSTEM_APP and userId",
  )
}

private fun persistentFromPkgFlags() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [android] (0003):
          appId=1000
          pkgFlags=[ SYSTEM PERSISTENT HAS_CODE ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 1000, isSystem = true, isUpdatedSystem = false, isPersistent = true),
    "PERSISTENT in pkgFlags is read",
  )
}

private fun combinedFlags() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.all] (0004):
          appId=10308
          flags=[ SYSTEM UPDATED_SYSTEM_APP PERSISTENT HAS_CODE ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10308, isSystem = true, isUpdatedSystem = true, isPersistent = true),
    "all three flags combine from one block",
  )
}

private fun appIdPreferredOverUserId() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.multi] (0005):
          userId=1000
          appId=10023
          pkgFlags=[ HAS_CODE ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10023, isSystem = false, isUpdatedSystem = false, isPersistent = false),
    "appId wins even when userId is printed first",
  )
}

private fun userIdFallback() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.legacy] (0006):
          userId=10100
          flags=[ HAS_CODE ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10100, isSystem = false, isUpdatedSystem = false, isPersistent = false),
    "userId is the fallback when no appId is printed",
  )
}

private fun pkgFlagsPreferredOverFlags() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.prefer] (0007):
          userId=10222
          flags=[ SYSTEM ]
          pkgFlags=[ UPDATED_SYSTEM_APP PERSISTENT ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10222, isSystem = false, isUpdatedSystem = true, isPersistent = true),
    "pkgFlags is preferred over an earlier package-level flags line",
  )
}

private fun permissionFlagsDoNotLeak() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.perms] (0008):
          userId=10122
          flags=[ SYSTEM HAS_CODE ]
          User 0:
            installed=true
          requested permissions:
            android.permission.READ_PHONE_STATE
              flags=[ UPDATED_SYSTEM_APP PERSISTENT ]
            android.permission.WRITE_SECURE_SETTINGS
              flags=[ PERSISTENT ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10122, isSystem = true, isUpdatedSystem = false, isPersistent = false),
    "permission flags later in the dump never enable package flags",
  )

  // Stronger form: no package-level flag block at all, only permission flags.
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.permsonly] (0009):
          appId=10777
          pkg=Package{0009 com.example.permsonly}
          requested permissions:
            android.permission.READ_PHONE_STATE
              flags=[ SYSTEM UPDATED_SYSTEM_APP PERSISTENT ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10777, isSystem = false, isUpdatedSystem = false, isPersistent = false),
    "permission flags do not supply flags when the package has none",
  )
}

private fun missingBlockIsNull() {
  expect(
    parsePackageFacts(
      """
      Database versions:
        sdkVersion=36
        dataVersion=1
      Packages:
      SharedUser [android.uid.system] (1e2f):
        userId=1000
        flags=[ SYSTEM PERSISTENT ]
      """.trimIndent(),
    ) == null,
    "a dump without a Package [name] block yields null despite a SharedUser flags line",
  )
  expect(parsePackageFacts("") == null, "empty input yields null")
}

private fun blockWithoutUidOrFlagsIsNull() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.empty] (000a):
          pkg=Package{000a com.example.empty}
          codePath=/data/app/~~x/com.example.empty
          versionCode=1
      """.trimIndent(),
    ),
    null,
    "a package block with neither appId/userId nor flags yields null",
  )
}

private fun caseAndSpacing() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.case] (000b):
          APPID = 10999
          PKGFLAGS = [ system   UpDaTeD_SyStEm_ApP    persistent ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10999, isSystem = true, isUpdatedSystem = true, isPersistent = true),
    "keys, values and extra spaces are handled case-insensitively",
  )
}

private fun wholeTokensOnly() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.tokens] (000c):
          appId=10333
          pkgFlags=[ SYSTEMISH NONPERSISTENT UPDATED_SYSTEM_APP_EXTRA HAS_CODE ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10333, isSystem = false, isUpdatedSystem = false, isPersistent = false),
    "only whole flag tokens count",
  )
}

private fun firstPackageBlockOnly() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.first] (000d):
          appId=10555
          pkgFlags=[ SYSTEM ]
        Package [com.example.second] (000e):
          appId=10666
          pkgFlags=[ PERSISTENT ]
      """.trimIndent(),
    ),
    PackageFacts(uid = 10555, isSystem = true, isUpdatedSystem = false, isPersistent = false),
    "only the first Package block is read",
  )
}

private fun partialBlocks() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.uidonly] (000f):
          appId=10456
          pkg=Package{000f com.example.uidonly}
      """.trimIndent(),
    ),
    PackageFacts(uid = 10456, isSystem = false, isUpdatedSystem = false, isPersistent = false),
    "an appId without any flag block keeps the uid and reports no flags",
  )
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.example.flagsonly] (0010):
          pkgFlags=[ UPDATED_SYSTEM_APP ]
      """.trimIndent(),
    ),
    PackageFacts(uid = null, isSystem = false, isUpdatedSystem = true, isPersistent = false),
    "a flag block without a uid keeps the flags and reports a null uid",
  )
}

// Mirrors the Android 16 shape captured from the target: appId, an older
// `flags=[ … ]`, then `pkgFlags=[ … ]`, then requested/install permissions.
private fun realisticDumpShape() {
  expectEquals(
    parsePackageFacts(
      """
      Packages:
        Package [com.android.chrome] (ad76357):
          appId=10180
          pkg=Package{de14d6c com.android.chrome}
          codePath=/data/app/~~x/com.android.chrome
          flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA UPDATED_SYSTEM_APP ]
          privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION ]
          installerPackageName=com.android.vending
          installPermissionsFixed=false
          pkgFlags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA UPDATED_SYSTEM_APP ]
          privatePkgFlags=[ PRODUCT ]
          requested permissions:
            android.permission.POST_NOTIFICATIONS
              flags=[ PERSISTENT ]
          install permissions:
            android.permission.INTERNET: granted=true
          User 0: installed=true hidden=false
            runtime permissions:
      """.trimIndent(),
    ),
    PackageFacts(uid = 10180, isSystem = true, isUpdatedSystem = true, isPersistent = false),
    "an Android 16-shaped block uses pkgFlags and ignores the permission flags after it",
  )
}
