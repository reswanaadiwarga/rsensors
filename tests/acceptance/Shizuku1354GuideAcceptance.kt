// Independent acceptance checks for the Shizuku 13.5.4 version-problem guide.
//
// Expected values are hand-derived from the task dispatch and the published
// GitHub facts, NOT from the implementation or its unit test:
//  - release page https://github.com/RikkaApps/Shizuku/releases/tag/v13.5.4
//  - asset shizuku-v13.5.4.r1049.0e53409-release.apk (~3.4 MB), alongside the
//    Source code zip/tar assets;
//  - 13.6.0 supports Android 16 QPR1 in general; the failure is the
//    HyperOS/MediaTek UserService issue, so the warning must not claim all of
//    Android 16;
//  - Android refuses an in-place downgrade and uninstall clears Shizuku's
//    pairing/permission data;
//  - the guide order and the last Diagnostics action are the parent commit's.
//
// The UI source and the two docs are scanned as copy (Compose cannot run here).
// The core constants and hasKnownShizukuProblem are called directly.
//
// Compile with core/ShizukuCompat.kt and run Shizuku1354GuideAcceptanceKt from
// the repository root. Set RSENSORS_ACCEPT_ROOT to scan another tree (used for
// the mutation/red run). Exits non-zero on any failure.

import dev.rsensors.android.core.KNOWN_BAD_SHIZUKU_VERSION
import dev.rsensors.android.core.SHIZUKU_1354_APK
import dev.rsensors.android.core.SHIZUKU_1354_RELEASE_URL
import dev.rsensors.android.core.hasKnownShizukuProblem
import java.io.File
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

private fun expectEquals(actual: Any?, expected: Any?, label: String) {
  expect(actual == expected, "$label: expected <$expected>, got <$actual>")
}

private fun normalized(text: String): String =
  text.replace(Regex("\\s+"), " ").trim()

private fun File.readOrFail(label: String): String {
  if (!isFile) {
    expect(false, "$label exists at $path")
    return ""
  }
  return readText()
}

private fun decodeEscapes(raw: String): String {
  val sb = StringBuilder()
  var i = 0
  while (i < raw.length) {
    val c = raw[i]
    if (c == '\\' && i + 1 < raw.length) {
      when (raw[i + 1]) {
        'n' -> { sb.append('\n'); i += 2 }
        't' -> { sb.append('\t'); i += 2 }
        'r' -> { sb.append('\r'); i += 2 }
        '"' -> { sb.append('"'); i += 2 }
        '\\' -> { sb.append('\\'); i += 2 }
        '$' -> { sb.append('$'); i += 2 }
        'u' -> {
          val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
          sb.append(hex.toInt(16).toChar())
          i += 6
        }
        else -> { sb.append(raw[i + 1]); i += 2 }
      }
    } else {
      sb.append(c)
      i++
    }
  }
  return sb.toString()
}

private val stringLiteral = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

/** Joins the `"..." +` chunks of one Kotlin entry and resolves the two constants. */
private fun joinEntry(entry: String): String {
  var text = stringLiteral.findAll(entry)
    .map { decodeEscapes(it.groupValues[1]) }
    .joinToString("")
  text = text
    .replace("\${SHIZUKU_1354_APK}", SHIZUKU_1354_APK)
    .replace("\$SHIZUKU_1354_APK", SHIZUKU_1354_APK)
    .replace("\${KNOWN_BAD_SHIZUKU_VERSION}", KNOWN_BAD_SHIZUKU_VERSION)
    .replace("\$KNOWN_BAD_SHIZUKU_VERSION", KNOWN_BAD_SHIZUKU_VERSION)
  return text
}

/** Body between a marker and the next match of [end], or "" (recorded as a failure). */
private fun between(source: String, startMarker: String, end: Regex, label: String): String {
  val start = source.indexOf(startMarker)
  if (start < 0) {
    expect(false, "$label: marker <$startMarker> found")
    return ""
  }
  val from = start + startMarker.length
  val match = end.find(source, from)
  if (match == null) {
    expect(false, "$label: terminator found after <$startMarker>")
    return ""
  }
  return source.substring(from, match.range.first)
}

fun main() {
  val root = File(System.getenv("RSENSORS_ACCEPT_ROOT") ?: ".")
  val ui = root.resolve(
    "app/src/main/java/dev/rsensors/android/ui/ShizukuHelpActivity.kt",
  ).readOrFail("help activity source")
  val readme = root.resolve("README.md").readOrFail("README")
  val androidDoc = root.resolve("ANDROID.md").readOrFail("ANDROID.md")

  constants()
  warningCopy(ui)
  versionSteps(ui)
  linkButton(ui)
  guideStructure(ui)
  behavior()
  docs(readme, androidDoc)

  println("checks=$checks failures=$failures")
  if (failures != 0) {
    println("ACCEPTANCE FAILED")
    exitProcess(1)
  }
  println("ACCEPTANCE PASSED")
}

// --------------------------------------------------------------- constants
private fun constants() {
  val expectedUrl = "https://github.com/RikkaApps/Shizuku/releases/tag/v13.5.4"
  val expectedApk = "shizuku-v13.5.4.r1049.0e53409-release.apk"

  expectEquals(SHIZUKU_1354_RELEASE_URL, expectedUrl, "13.5.4 release tag URL is exact")
  expectEquals(SHIZUKU_1354_APK, expectedApk, "13.5.4 release APK asset name is exact")
  expect(
    SHIZUKU_1354_RELEASE_URL.startsWith("https://github.com/RikkaApps/Shizuku/releases/tag/"),
    "release URL is a GitHub tag URL, not a download or repository root",
  )
  expect(
    SHIZUKU_1354_APK.endsWith("-release.apk") && SHIZUKU_1354_APK.startsWith("shizuku-v13.5.4."),
    "APK name is the published shizuku-v13.5.4.*-release.apk asset",
  )
  expect(
    !SHIZUKU_1354_APK.contains("source", ignoreCase = true) &&
      !SHIZUKU_1354_APK.endsWith(".zip") && !SHIZUKU_1354_APK.endsWith(".tar"),
    "APK name is not a Source code archive",
  )
}

// ----------------------------------------------------------- warning copy
private fun warningCopy(ui: String) {
  val start = ui.indexOf("if (versionWarning) {")
  expect(start >= 0, "status warning is guarded by versionWarning")
  val spacer = ui.indexOf("Spacer(Modifier.height(2.dp))", start)
  expect(spacer > start, "warning block terminator found")
  val block = if (start >= 0 && spacer > start) ui.substring(start, spacer) else ""
  val warning = joinEntry(block)

  val required = "On some Android 16 devices, including HyperOS 3, Shizuku " +
    "$KNOWN_BAD_SHIZUKU_VERSION may fail to start its background helper."
  expect(
    normalized(warning).contains(required),
    "warning says <On some Android 16 devices, including HyperOS 3, ... may fail to start its background helper>: $warning",
  )
  expect(
    !warning.contains("known problem on Android 16"),
    "warning no longer says <known problem on Android 16>",
  )
  expect(
    !ui.contains("known problem on Android 16"),
    "the help screen has no <known problem on Android 16> claim anywhere",
  )
  expect(
    !warning.contains("all Android 16") && !warning.contains("every Android 16"),
    "warning does not claim all/every Android 16 device fails",
  )
  expect(
    warning.contains("Shizuku version problems"),
    "warning points at the version-problems section",
  )
}

// ------------------------------------------------------------ five steps
private fun versionSteps(ui: String) {
  val body = between(
    ui,
    "private val VERSION_STEPS = listOf(",
    Regex("(?m)^\\)[ \\t]*$"),
    "VERSION_STEPS body",
  )
  val entries = body.split(Regex(",\\s*\\n\\s*(?=\")"))
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { normalized(joinEntry(it)) }

  expectEquals(entries.size, 5, "version section has five explicit steps")

  val steps = if (entries.size == 5) entries else emptyList()
  if (steps.size != 5) return

  val (s1, s2, s3, s4, s5) = steps

  // Step 1: narrow scope, keep 13.6 when it works.
  expect(s1.contains("some Android 16 devices"), "step 1 narrows the problem to some devices: $s1")
  expect(s1.contains("HyperOS 3"), "step 1 names HyperOS 3")
  expect(s1.contains("not about Android 16 itself"), "step 1 says it is not about Android 16 itself")
  expect(s1.contains("supports Android 16 QPR1"), "step 1 records QPR1 support")
  expect(
    s1.contains("if it works on your phone, keep it"),
    "step 1 keeps 13.6.0 when it works",
  )

  // Step 2: link -> Assets -> exact APK, not Source.
  expect(s2.contains("link below"), "step 2 uses the link below")
  expect(s2.contains("Assets"), "step 2 says to open Assets")
  expect(s2.contains("tap Assets"), "step 2 explains expanding collapsed Assets")
  expect(s2.contains(SHIZUKU_1354_APK), "step 2 names the exact APK <$SHIZUKU_1354_APK>")
  expect(s2.contains("~3.4 MB"), "step 2 gives the ~3.4 MB size")
  expect(
    s2.contains("Do not download the Source code zip or tar"),
    "step 2 rejects the Source code archive",
  )

  // Step 3: uninstall first, pairing/permission data is cleared.
  expect(
    s3.contains("will not install an older version"),
    "step 3 explains Android refuses an in-place downgrade",
  )
  expect(s3.contains("Uninstall Shizuku"), "step 3 says uninstall Shizuku first")
  expect(
    s3.contains("pairing and permission data"),
    "step 3 says pairing and permission data is deleted",
  )
  expect(
    s3.contains("granted the Shizuku permission again"),
    "step 3 says the permission must be granted again",
  )

  // Step 4: install, pair, start, re-grant.
  expect(s4.contains("Install the APK"), "step 4 installs the APK")
  expect(
    s4.contains("pair and start the server again"),
    "step 4 re-pairs and starts the server",
  )
  expect(
    s4.contains("grant the Shizuku permission") ||
      s4.contains("granted the Shizuku permission"),
    "step 4 re-grants the permission",
  )

  // Step 5: stay on 13.5.4 until fixed.
  expect(s5.contains("Keep 13.5.4"), "step 5 keeps 13.5.4")
  expect(s5.contains("until a release fixes"), "step 5 says until a fix ships")
}

// ------------------------------------------------------------- link button
private fun linkButton(ui: String) {
  expect(
    ui.contains("import androidx.compose.ui.platform.LocalUriHandler"),
    "help screen imports LocalUriHandler",
  )
  expect(
    ui.contains("val uriHandler = LocalUriHandler.current"),
    "help screen obtains LocalUriHandler",
  )
  expect(
    ui.contains("if (linkLabel != null && linkUrl != null)"),
    "link button renders only when both label and URL are set",
  )
  expect(
    ui.contains("runCatching { uriHandler.openUri(linkUrl) }"),
    "openUri is wrapped in runCatching so a missing browser cannot crash",
  )
  expect(
    !ui.contains("https://github.com/RikkaApps/Shizuku/releases"),
    "help screen does not hardcode the release URL; it uses the constant",
  )
  expect(
    ui.contains("linkUrl = SHIZUKU_1354_RELEASE_URL"),
    "version section passes SHIZUKU_1354_RELEASE_URL",
  )
}

// --------------------------------------------------------- guide structure
private fun guideStructure(ui: String) {
  val body = between(
    ui,
    "private fun guideSections(oemHint: String): List<GuideSection> = listOf(",
    Regex("(?m)^\\)[ \\t]*$"),
    "guideSections body",
  )
  val blocks = body.split("GuideSection(").drop(1)
  val sections = blocks.map { block ->
    val title = Regex("title = \"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""
    Triple(
      title,
      block.contains("linkUrl = SHIZUKU_1354_RELEASE_URL"),
      Regex("actionLabel = \"([^\"]*)\"").find(block)?.groupValues?.get(1),
    )
  }

  val expectedTitles = listOf(
    "First-time setup",
    "Start keeps searching",
    "Pairing is lost or the quick fix keeps failing",
    "After a reboot",
    "Rsensors shows BAT while Shizuku is running",
    "Shizuku version problems",
    "Why the toggle works",
    "If nothing above works",
  )
  expectEquals(
    sections.map { it.first },
    expectedTitles,
    "guide sections and order are unchanged from the parent",
  )

  val versionIndex = sections.indexOfFirst { it.first == "Shizuku version problems" }
  expectEquals(versionIndex, 5, "version card keeps its position before Why the toggle works")
  if (versionIndex >= 0) {
    expect(sections[versionIndex].second, "version card has the release link")
    expect(
      Regex("linkLabel = \"([^\"]*)\"").find(blocks[versionIndex])?.groupValues?.get(1)
        ?.let { it.contains("GitHub") && it.contains("13.5.4") && it.contains("release") } == true,
      "version-card link label names the 13.5.4 GitHub release",
    )
  }

  expectEquals(
    sections.count { it.second },
    1,
    "only the version card carries an outbound link",
  )

  val actions = sections.mapIndexedNotNull { index, s -> s.third?.let { index to it } }
  expectEquals(actions.size, 1, "exactly one section has a trailing action")
  if (actions.isNotEmpty()) {
    expectEquals(actions.single().first, sections.lastIndex, "the Diagnostics action is on the last section")
    expectEquals(actions.single().second, "Open Diagnostics", "the last action is still Open Diagnostics")
  }
  expect(
    sections.lastOrNull()?.first == "If nothing above works" && sections.last().second.not(),
    "the last section has no link, so Diagnostics stays its last action",
  )
}

// ---------------------------------------------------------------- behavior
private fun behavior() {
  // Parent behavior: a 13.6.0 prefix on API 36+ warns; everything else does not.
  val warns = listOf(
    "13.6.0" to 36,
    "13.6.0.r1049" to 36,
    "13.6.0" to 37,
  )
  for ((version, sdk) in warns) {
    expect(
      hasKnownShizukuProblem(version, sdk),
      "warns for $version on API $sdk",
    )
  }

  val quiet = listOf(
    "13.6.1" to 36,
    "13.6" to 36,
    "13.5.4" to 36,
    "13.6.0" to 35,
    null to 36,
    "" to 36,
    "   " to 36,
  )
  for ((version, sdk) in quiet) {
    expect(
      !hasKnownShizukuProblem(version, sdk),
      "does not warn for <$version> on API $sdk",
    )
  }
}

// -------------------------------------------------------------------- docs
private fun docs(readme: String, androidDoc: String) {
  val r = normalized(readme)
  val a = normalized(androidDoc)
  val url = "https://github.com/RikkaApps/Shizuku/releases/tag/v13.5.4"

  // README.
  expect(r.contains(url), "README links the 13.5.4 release")
  expect(r.contains("supports Android 16 QPR1 in general"), "README records QPR1 support")
  expect(
    r.contains("HyperOS") && r.contains("MediaTek"),
    "README names the tested HyperOS / MediaTek device",
  )
  expect(
    r.contains("uninstall") && r.contains("pairing and permission data") &&
      r.contains("granted the permission again"),
    "README explains uninstall clears pairing/permission and re-grant",
  )
  expect(
    r.contains("Other devices and versions may differ") &&
      r.contains("if 13.6.0 works on your phone, keep it"),
    "README scopes the failure to the tested device and keeps working 13.6.0",
  )

  // ANDROID.md.
  expect(a.contains(url), "ANDROID.md links the 13.5.4 release")
  expect(a.contains("supports Android 16 QPR1 in general"), "ANDROID.md records QPR1 support")
  expect(
    a.contains("Do not generalise this to every Android 16 device"),
    "ANDROID.md explicitly refuses the universal Android 16 claim",
  )
  expect(
    a.contains("HyperOS") && a.contains("MediaTek"),
    "ANDROID.md names the tested HyperOS / MediaTek target",
  )
  expect(
    a.contains("Android refuses an in-place downgrade"),
    "ANDROID.md explains Android refuses an in-place downgrade",
  )
  expect(
    a.contains("uninstalling clears Shizuku's pairing/permission data") &&
      a.contains("permissions must be granted again"),
    "ANDROID.md explains uninstall clears pairing/permission and re-grant",
  )
  expect(
    a.contains("uninstall, reinstall from the [13.5.4 release]"),
    "ANDROID.md gives the uninstall/reinstall order with the link",
  )

  // No universal or other-phone verification claim in either doc.
  val banned = listOf(
    "works on all", "works on every", "fails on all", "fails on every",
    "all Android 16 devices fail", "every Android 16 device fails",
    "verified on other", "tested on another", "confirmed on other",
    "guaranteed", "universally",
  )
  for ((name, text) in listOf("README" to r.lowercase(), "ANDROID.md" to a.lowercase())) {
    for (phrase in banned) {
      expect(!text.contains(phrase), "$name must not claim <$phrase>")
    }
  }
}
