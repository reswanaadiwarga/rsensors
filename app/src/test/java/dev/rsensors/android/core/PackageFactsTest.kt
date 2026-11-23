package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Package-facts parsing for T19c-3b (ANDROID.md §3 "Stop app", §8 T19c-3b).
 *
 * The fixtures mimic the `Package [name]` block printed by
 * `dumpsys package <pkg>`: app id, app flags near the top, then a user and
 * permission section whose own `flags=[ … ]` lines must not be mistaken for the
 * package's flags. Expected values are hand-derived from those blocks, not
 * copied from the parser.
 */
class PackageFactsTest {

  @Test
  fun `system package reads appId and SYSTEM from pkgFlags`() {
    val raw = """
      Packages:
        Package [com.android.systemui] (8a1f):
          userId=1000
          appId=1000
          pkg=Package{90b1 com.android.systemui}
          codePath=/system/priv-app/SystemUI
          resourcePath=/system/priv-app/SystemUI
          versionCode=36
          pkgFlags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]
          privateFlags=[ HAS_DOMAIN_URLS ]
          User 0:
            installed=true
          requested permissions:
            android.permission.PERSISTENT
              flags=[ PERSISTENT ]
    """.trimIndent()

    assertEquals(
      PackageFacts(uid = 1000, isSystem = true, isUpdatedSystem = false, isPersistent = false),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `updated system app is read from the first package flags block`() {
    val raw = """
      Packages:
        Package [com.google.android.aicore] (b02c):
          userId=10308
          pkg=Package{77ee com.google.android.aicore}
          codePath=/system/priv-app/AICore
          flags=[ SYSTEM UPDATED_SYSTEM_APP HAS_CODE ]
          privateFlags=[ PRIVATE_FLAG_IS_RESOURCE_OVERLAY ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10308,
        isSystem = true,
        isUpdatedSystem = true,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `persistent package is read from pkgFlags`() {
    val raw = """
      Packages:
        Package [android] (0d10):
          userId=1000
          pkg=Package{0001 android}
          codePath=/system/framework/framework-res.apk
          pkgFlags=[ SYSTEM PERSISTENT HAS_CODE ]
    """.trimIndent()

    assertEquals(
      PackageFacts(uid = 1000, isSystem = true, isUpdatedSystem = false, isPersistent = true),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `all three flags combine`() {
    val raw = """
      Packages:
        Package [com.example.updated] (1abc):
          appId=10042
          pkg=Package{2222 com.example.updated}
          codePath=/data/app/~~x/com.example.updated
          flags=[ SYSTEM UPDATED_SYSTEM_APP PERSISTENT HAS_CODE ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10042,
        isSystem = true,
        isUpdatedSystem = true,
        isPersistent = true,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `appId wins over userId`() {
    val raw = """
      Packages:
        Package [com.example.multi] (33cc):
          userId=1000
          appId=10023
          pkg=Package{3333 com.example.multi}
          pkgFlags=[ HAS_CODE ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10023,
        isSystem = false,
        isUpdatedSystem = false,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `userId is the fallback when no appId is printed`() {
    val raw = """
      Packages:
        Package [com.example.legacy] (44dd):
          userId=10100
          pkg=Package{4444 com.example.legacy}
          flags=[ HAS_CODE ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10100,
        isSystem = false,
        isUpdatedSystem = false,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `missing package block returns null`() {
    val raw = """
      Database versions:
        sdkVersion=36
        dataVersion=1
      Packages:
      SharedUser [android.uid.system] (1e2f):
        userId=1000
        flags=[ SYSTEM PERSISTENT ]
    """.trimIndent()

    assertNull(parsePackageFacts(raw))
  }

  @Test
  fun `package block without uid or flags returns null`() {
    val raw = """
      Packages:
        Package [com.example.empty] (55ee):
          pkg=Package{5555 com.example.empty}
          codePath=/data/app/~~y/com.example.empty
          versionCode=1
    """.trimIndent()

    assertNull(parsePackageFacts(raw))
  }

  @Test
  fun `appId without a flags block keeps the uid and false flags`() {
    val raw = """
      Packages:
        Package [com.example.uidonly] (66ff):
          appId=10456
          pkg=Package{6666 com.example.uidonly}
          codePath=/data/app/~~z/com.example.uidonly
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10456,
        isSystem = false,
        isUpdatedSystem = false,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `flags block without a uid keeps flags and null uid`() {
    val raw = """
      Packages:
        Package [com.example.flagsonly] (77aa):
          pkg=Package{7777 com.example.flagsonly}
          pkgFlags=[ UPDATED_SYSTEM_APP ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = null,
        isSystem = false,
        isUpdatedSystem = true,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `permission flags later in the dump are ignored`() {
    val raw = """
      Packages:
        Package [com.example.perms] (88bb):
          userId=10122
          pkg=Package{8888 com.example.perms}
          flags=[ SYSTEM HAS_CODE ]
          User 0:
            installed=true
          requested permissions:
            android.permission.READ_PHONE_STATE
              flags=[ UPDATED_SYSTEM_APP PERSISTENT ]
            android.permission.WRITE_SECURE_SETTINGS
              flags=[ PERSISTENT ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10122,
        isSystem = true,
        isUpdatedSystem = false,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `pkgFlags is preferred over an earlier flags line`() {
    val raw = """
      Packages:
        Package [com.example.prefer] (99cc):
          userId=10222
          pkg=Package{9999 com.example.prefer}
          flags=[ SYSTEM ]
          pkgFlags=[ UPDATED_SYSTEM_APP PERSISTENT ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10222,
        isSystem = false,
        isUpdatedSystem = true,
        isPersistent = true,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `flag tokens and key spacing are case insensitive`() {
    val raw = """
      Packages:
        Package [com.example.case] (aabb):
          APPID = 10999
          pkg=Package{aabb com.example.case}
          PKGFLAGS = [ system   UpDaTeD_SyStEm_ApP    persistent ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10999,
        isSystem = true,
        isUpdatedSystem = true,
        isPersistent = true,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `only whole flag tokens count`() {
    val raw = """
      Packages:
        Package [com.example.tokens] (bbcc):
          appId=10333
          pkg=Package{bbcc com.example.tokens}
          pkgFlags=[ SYSTEMISH NONPERSISTENT UPDATED_SYSTEM_APP_EXTRA HAS_CODE ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10333,
        isSystem = false,
        isUpdatedSystem = false,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }

  @Test
  fun `only the first package block is read`() {
    val raw = """
      Packages:
        Package [com.example.first] (ccdd):
          appId=10555
          pkgFlags=[ SYSTEM ]
        Package [com.example.second] (ddee):
          appId=10666
          pkgFlags=[ PERSISTENT ]
    """.trimIndent()

    assertEquals(
      PackageFacts(
        uid = 10555,
        isSystem = true,
        isUpdatedSystem = false,
        isPersistent = false,
      ),
      parsePackageFacts(raw),
    )
  }
}
