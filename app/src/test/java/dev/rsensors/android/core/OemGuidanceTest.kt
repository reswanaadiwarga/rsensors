package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brand-aware autostart and Shizuku guidance (ANDROID.md §8 T19d-1).
 *
 * Expectations are hand-derived from the dispatch: manufacturer is consulted
 * before brand, matching ignores case and surrounding spaces, the aliases in
 * the task map to the listed families, and every family carries complete,
 * distinct product copy that names the OEM settings the reader must change.
 * The copy assertions are intentionally narrow substring checks, so they pin
 * the spec's setting names without freezing the whole sentence.
 */
class OemGuidanceTest {

  // ---------------------------------------------------------------- detection

  @Test
  fun `Xiaomi Redmi and Poco map to Xiaomi`() {
    assertEquals(OemFamily.XIAOMI, oemFamily("Xiaomi", "Xiaomi"))
    assertEquals(OemFamily.XIAOMI, oemFamily("Redmi", "Redmi"))
    assertEquals(OemFamily.XIAOMI, oemFamily("POCO", "POCO"))
  }

  @Test
  fun `Samsung maps to Samsung`() {
    assertEquals(OemFamily.SAMSUNG, oemFamily("Samsung", "samsung"))
  }

  @Test
  fun `Huawei and Honor map to the Huawei Honor family`() {
    assertEquals(OemFamily.HUAWEI_HONOR, oemFamily("HUAWEI", "HUAWEI"))
    assertEquals(OemFamily.HUAWEI_HONOR, oemFamily("HONOR", "HONOR"))
  }

  @Test
  fun `Oppo OnePlus Realme and ColorOS map to ColorOS`() {
    assertEquals(OemFamily.COLOROS, oemFamily("OPPO", "OPPO"))
    assertEquals(OemFamily.COLOROS, oemFamily("OnePlus", "OnePlus"))
    assertEquals(OemFamily.COLOROS, oemFamily("realme", "realme"))
    assertEquals(OemFamily.COLOROS, oemFamily("ColorOS", "ColorOS"))
  }

  @Test
  fun `vivo and iQOO map to vivo`() {
    assertEquals(OemFamily.VIVO, oemFamily("vivo", "vivo"))
    assertEquals(OemFamily.VIVO, oemFamily("iQOO", "iQOO"))
  }

  @Test
  fun `stock-like makers map to AOSP`() {
    for (name in listOf("Google", "AOSP", "Motorola", "Sony", "ASUS", "Nothing")) {
      assertEquals(name, OemFamily.AOSP, oemFamily(name, name))
    }
  }

  @Test
  fun `unknown blank and null makers fall back to other`() {
    assertEquals(OemFamily.OTHER, oemFamily("unknown", "unknown"))
    assertEquals(OemFamily.OTHER, oemFamily("", " "))
    assertEquals(OemFamily.OTHER, oemFamily(null, null))
  }

  @Test
  fun `detection ignores case and surrounding spaces`() {
    assertEquals(OemFamily.XIAOMI, oemFamily("  xIaOmI  ", null))
    assertEquals(OemFamily.SAMSUNG, oemFamily(null, " sAmSuNg "))
    assertEquals(OemFamily.COLOROS, oemFamily(" onePLUS ", null))
  }

  @Test
  fun `manufacturer is consulted before brand`() {
    // A manufacturer hit wins even when the brand names another family.
    assertEquals(OemFamily.SAMSUNG, oemFamily("samsung", "Redmi"))
    assertEquals(OemFamily.AOSP, oemFamily("Google", "Redmi"))
    // A manufacturer miss, including an unknown one, still checks the brand.
    assertEquals(OemFamily.XIAOMI, oemFamily("unknown", "poco"))
    assertEquals(OemFamily.VIVO, oemFamily(null, "iQOO"))
  }

  // ----------------------------------------------------------------- guidance

  @Test
  fun `every family has complete non-blank guidance`() {
    for (family in OemFamily.values()) {
      val guidance = oemGuidance(family)
      assertEquals(family, guidance.family)
      assertTrue("$family title", guidance.bootTitle.isNotBlank())
      assertTrue("$family body", guidance.bootBody.isNotBlank())
      assertTrue("$family Shizuku hint", guidance.shizukuBody.isNotBlank())
    }
  }

  @Test
  fun `guidance differs across families`() {
    val all = OemFamily.values().map(::oemGuidance)
    assertEquals("boot titles must be distinct", 7, all.map { it.bootTitle }.toSet().size)
    assertEquals("boot bodies must be distinct", 7, all.map { it.bootBody }.toSet().size)
    assertEquals("Shizuku hints must be distinct", 7, all.map { it.shizukuBody }.toSet().size)
    assertNotEquals(
      oemGuidance(OemFamily.XIAOMI).bootBody,
      oemGuidance(OemFamily.AOSP).bootBody,
    )
  }

  @Test
  fun `copy makes no universal support claim`() {
    val banned = listOf(
      "works",
      "guaranteed",
      "guarantee",
      "all devices",
      "universally",
      "fully supported",
    )
    for (family in OemFamily.values()) {
      val guidance = oemGuidance(family)
      val copy = "${guidance.bootTitle} ${guidance.bootBody} ${guidance.shizukuBody}"
        .lowercase()
      for (phrase in banned) {
        assertTrue("$family copy must not contain \"$phrase\": $copy", !copy.contains(phrase))
      }
    }
  }

  @Test
  fun `Xiaomi copy names its autostart settings`() {
    val copy = oemGuidance(OemFamily.XIAOMI).bootBody.lowercase()
    assertTrue(copy.contains("autostart"))
    assertTrue(copy.contains("background autostart"))
    assertTrue(copy.contains("no restrictions"))
    assertTrue(copy.contains("recents"))
  }

  @Test
  fun `Samsung copy names background limits and Auto Blocker`() {
    val copy = oemGuidance(OemFamily.SAMSUNG).bootBody.lowercase()
    assertTrue(copy.contains("sleeping apps"))
    assertTrue(copy.contains("background usage limits"))
    assertTrue(copy.contains("auto blocker"))
    assertTrue(copy.contains("battery"))
  }

  @Test
  fun `ColorOS copy names auto launch and permission monitoring`() {
    val guidance = oemGuidance(OemFamily.COLOROS)
    val boot = guidance.bootBody.lowercase()
    assertTrue(boot.contains("auto launch"))
    assertTrue(boot.contains("background activity"))
    assertTrue(boot.contains("system optimization"))
    assertTrue(guidance.shizukuBody.lowercase().contains("permission monitoring"))
  }

  @Test
  fun `vivo copy names autostart and the server restart`() {
    val guidance = oemGuidance(OemFamily.VIVO)
    assertTrue(guidance.bootBody.lowercase().contains("autostart"))
    assertTrue(guidance.bootBody.lowercase().contains("background power"))
    val hint = guidance.shizukuBody.lowercase()
    assertTrue(hint.contains("start again") || hint.contains("restart"))
  }

  @Test
  fun `Huawei copy names app launch and USB debugging`() {
    val guidance = oemGuidance(OemFamily.HUAWEI_HONOR)
    assertTrue(guidance.bootBody.lowercase().contains("app launch"))
    assertTrue(guidance.bootBody.lowercase().contains("manage manually"))
    val hint = guidance.shizukuBody.lowercase()
    assertTrue(hint.contains("usb debugging"))
    assertTrue(hint.contains("wireless debugging"))
  }

  @Test
  fun `AOSP copy says there is no OEM manager but battery limits may apply`() {
    val copy = oemGuidance(OemFamily.AOSP).bootBody.lowercase()
    assertTrue(copy.contains("no oem autostart manager"))
    assertTrue(copy.contains("unrestricted"))
  }
}
