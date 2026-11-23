package dev.rsensors.android.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.rsensors.android.core.KNOWN_BAD_SHIZUKU_VERSION
import dev.rsensors.android.core.SHIZUKU_1354_APK
import dev.rsensors.android.core.SHIZUKU_1354_RELEASE_URL
import dev.rsensors.android.core.hasKnownShizukuProblem
import dev.rsensors.android.core.oemFamily
import dev.rsensors.android.core.oemGuidance
import rikka.shizuku.Shizuku

/**
 * Shizuku setup and troubleshooting guide (ANDROID.md §3, §8 T15g, T15h).
 *
 * The screen exists because pairing can be fine while `Start` still says
 * "searching for wireless debugging device service"; the fix is toggling
 * Wireless debugging, which restarts the connection Shizuku starts through. The
 * guide is text with exact UI labels, not screenshots, so it stays correct when
 * an OEM renames a Settings row. The "After a reboot" card carries one extra
 * brand-aware hint from [dev.rsensors.android.core.OemGuidance] because the
 * reboot step is where OEM autostart and battery policy usually bite; it is a
 * hint, not a claim that the step works on that phone.
 *
 * Layout: the live status card sits above a scrolling list of one collapsible
 * card per topic. The status is the evidence every step is judged against, so it
 * stays pinned while the reader works down the list whenever the window is tall
 * enough (portrait); in a short window (landscape) it scrolls with the list
 * instead, because a pinned card there would hide the guide entirely. The topics
 * are a menu of independent cards, not a wall of text.
 *
 * The status card also carries one conditional amber warning when the installed
 * manager and the OS are the combination the guide treats as broken. Shizuku
 * 13.6.0 supports Android 16 QPR1 in general; on some Android 16 devices,
 * including HyperOS 3, its background helper (UserService) fails to start. The
 * warning is a hint for that failure, not a version requirement: the version
 * card links the known-working 13.5.4 release and gives the uninstall/reinstall
 * steps, and any other combination shows nothing.
 *
 * The status card is read live in [onCreate], [onResume] and from the Refresh
 * button. Every Shizuku, package-manager and Settings call is guarded: a missing
 * app, a stopped server or a denied permission is a normal state to display,
 * never a crash. Wireless debugging is best-effort; its row is hidden when the
 * setting is unreadable.
 *
 * Side effects: launches the Shizuku app, its store page, Developer options,
 * Diagnostics, the app's system settings page, or an external browser for the
 * Shizuku 13.5.4 release page. The app itself makes no network calls.
 */
class ShizukuHelpActivity : ComponentActivity() {
  private var installed by mutableStateOf(false)
  private var running by mutableStateOf(false)
  private var granted by mutableStateOf(false)
  private var version by mutableStateOf<String?>(null)
  private var wirelessDebugging by mutableStateOf<Boolean?>(null)
  private var versionWarning by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    refresh()
    setContent {
      RsensorsTheme {
        ShizukuHelpScreen(
          installed = installed,
          running = running,
          granted = granted,
          version = version,
          wirelessDebugging = wirelessDebugging,
          versionWarning = versionWarning,
          oemHint = oemGuidance(oemFamily(Build.MANUFACTURER, Build.BRAND)).shizukuBody,
          onRefresh = ::refresh,
          onOpenShizuku = ::openShizuku,
          onOpenDeveloperOptions = ::openDeveloperOptions,
          onOpenDiagnostics = ::openDiagnostics,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  /** Re-reads every status the card shows. */
  private fun refresh() {
    installed = isInstalled()
    running = isShizukuRunning()
    granted = hasShizukuPermission()
    version = managerVersion()
    wirelessDebugging = readWirelessDebugging()
    versionWarning = hasKnownShizukuProblem(version, Build.VERSION.SDK_INT)
  }

  /**
   * Package presence via `getPackageInfo`: unlike the launch intent it also
   * finds a manager whose launcher activity is disabled. Package visibility is
   * granted by the manifest `<queries>` entry.
   */
  private fun isInstalled(): Boolean =
    try {
      packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
      true
    } catch (e: Exception) {
      false
    }

  /** `pingBinder` is false when the server is stopped or absent. */
  private fun isShizukuRunning(): Boolean =
    try {
      Shizuku.pingBinder()
    } catch (e: Exception) {
      false
    }

  /** Throws while the server is stopped, which counts as "not granted". */
  private fun hasShizukuPermission(): Boolean =
    try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
      false
    }

  /**
   * The installed manager's version, or null when the package is absent.
   *
   * `getPackageInfo(String, Int)` is the only overload that works on every
   * supported API level; it is deprecated on 33+, hence the suppression.
   */
  @Suppress("DEPRECATION")
  private fun managerVersion(): String? =
    try {
      packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0).versionName
    } catch (e: Exception) {
      null
    }

  /**
   * Whether Wireless debugging is on, or null when the setting cannot be read.
   *
   * `ADB_WIFI_ENABLED` is a hidden global setting that HyperOS does not always
   * expose; a denial or a missing value hides the row instead of guessing.
   */
  private fun readWirelessDebugging(): Boolean? =
    try {
      val value = Settings.Global.getInt(contentResolver, ADB_WIFI_ENABLED, SETTING_UNSET)
      if (value == SETTING_UNSET) null else value != 0
    } catch (e: Exception) {
      null
    }

  /**
   * Opens the Shizuku app, or its store page when the package is missing.
   *
   * Every step is guarded: no manager, no market app and no app-details page
   * must all leave the screen usable with its status card authoritative.
   */
  private fun openShizuku() {
    val launch = try {
      packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    } catch (e: Exception) {
      null
    }
    if (launch != null) {
      try {
        startActivity(launch)
        return
      } catch (e: Exception) {
        // Installed but not launchable; fall through to the app details page.
      }
    }
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$SHIZUKU_PACKAGE".toUri())
    try {
      startActivity(market)
    } catch (e: Exception) {
      try {
        startActivity(
          Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$SHIZUKU_PACKAGE".toUri(),
          ),
        )
      } catch (e: Exception) {
        // Nothing openable; the status card and the steps stay authoritative.
      }
    }
  }

  /**
   * Opens Developer options, falling back to this app's settings page and then
   * to system Settings when the shortcut is not handled (some OEM builds).
   */
  private fun openDeveloperOptions() {
    val candidates = listOf(
      Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
      Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
      try {
        startActivity(intent)
        return
      } catch (e: Exception) {
        // Try the next, less specific screen.
      }
    }
  }

  /**
   * Opens Diagnostics, the screen that lists the live Shizuku state, the manager
   * version, source errors and the thermal zones (T15g).
   *
   * It is the last-resort step in the guide: its Copy button produces the details
   * a helper needs.
   */
  private fun openDiagnostics() {
    try {
      startActivity(Intent(this, DiagnosticsActivity::class.java))
    } catch (e: Exception) {
      // Nothing openable; the guide's steps remain readable.
    }
  }

  private companion object {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** `Settings.Global` sentinel for "not present"; 0 and 1 are the real values. */
    const val SETTING_UNSET = -1

    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
  }
}

/** Bit of the first [guideSections] entry, the section that opens on its own. */
private const val FIRST_TIME_BIT = 1

/** Expand/collapse duration; short enough to feel like a tap, long enough to follow. */
private const val SECTION_ANIMATION_MILLIS = 160

/**
 * Shortest window that keeps the status card pinned above the list.
 *
 * The pinned block measures ~360 dp; this leaves room for the heading and at
 * least two closed cards. A landscape phone window falls below it and scrolls
 * the card with the list instead (see [ShizukuHelpScreen]).
 */
private val PINNED_STATUS_MIN_HEIGHT = 560.dp

@Composable
private fun ShizukuHelpScreen(
  installed: Boolean,
  running: Boolean,
  granted: Boolean,
  version: String?,
  wirelessDebugging: Boolean?,
  versionWarning: Boolean,
  oemHint: String,
  onRefresh: () -> Unit,
  onOpenShizuku: () -> Unit,
  onOpenDeveloperOptions: () -> Unit,
  onOpenDiagnostics: () -> Unit,
) {
  // One bit per section keeps the whole expand state in a single Bundle-friendly
  // value, so it survives a configuration change through rememberSaveable without
  // a custom Saver. Several bits may be set: the cards are independent.
  //
  // The default is decided once, from the status at first composition. A ready
  // Shizuku (installed, running, permission granted) means the reader is not
  // troubleshooting, so every card starts collapsed and the page reads as a menu.
  // Otherwise only the first-time steps are open, because that is the only topic
  // that can make the status card turn green. Refresh re-reads the status but
  // deliberately leaves this mask alone: an open card is the reader's choice.
  var expandedMask by rememberSaveable {
    mutableStateOf(if (installed && running && granted) 0 else FIRST_TIME_BIT)
  }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      // The pinned block (title row plus status card) is about 360 dp at the
      // default font scale. A landscape phone window is ~375 dp tall, so keeping
      // the card pinned there leaves the section list at zero height and the
      // guide unreachable. Below this height the card scrolls with the list
      // instead; in portrait the card stays pinned as required.
      val pinStatus = maxHeight >= PINNED_STATUS_MIN_HEIGHT
      val statusCard: @Composable () -> Unit = {
        StatusCard(
          installed = installed,
          running = running,
          granted = granted,
          version = version,
          wirelessDebugging = wirelessDebugging,
          versionWarning = versionWarning,
          onOpenShizuku = onOpenShizuku,
          onOpenDeveloperOptions = onOpenDeveloperOptions,
        )
      }

      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Shizuku setup", style = MaterialTheme.typography.headlineMedium)
          Button(onClick = onRefresh) { Text("Refresh") }
        }

        if (pinStatus) statusCard()

        SectionList(
          sections = guideSections(oemHint),
          modifier = Modifier.weight(1f),
          expandedMask = expandedMask,
          onToggle = { index -> expandedMask = expandedMask xor (1 shl index) },
          onOpenDiagnostics = onOpenDiagnostics,
          leading = if (pinStatus) null else statusCard,
        )
      }
    }
  }
}

/**
 * The scrolling list of section cards.
 *
 * @param sections the guide in display order; supplied per screen so the reboot
 *   card can carry the device's OEM hint
 * @param expandedMask one bit per [sections] index; a set bit is open
 * @param onToggle receives the index whose card was tapped
 * @param onOpenDiagnostics invoked by the last card's trailing button
 * @param leading optional first item, used to scroll the status card with the
 *   list in a window too short to pin it
 */
@Composable
private fun SectionList(
  sections: List<GuideSection>,
  expandedMask: Int,
  onToggle: (Int) -> Unit,
  onOpenDiagnostics: () -> Unit,
  modifier: Modifier = Modifier,
  leading: (@Composable () -> Unit)? = null,
) {
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (leading != null) {
      item(key = "status") { leading() }
    }
    itemsIndexed(sections, key = { _, section -> section.title }) { index, section ->
      val bit = 1 shl index
      GuideSectionCard(
        section = section,
        expanded = expandedMask and bit != 0,
        onToggle = { onToggle(index) },
        onOpenDiagnostics = onOpenDiagnostics,
      )
    }
  }
}

/**
 * The live status card.
 *
 * @param installed Shizuku is installed; when its version name is unreadable the
 *   row still says Installed instead of claiming it is missing
 * @param running Shizuku server answering its binder
 * @param granted Rsensors holds the Shizuku permission
 * @param version installed Shizuku version, or null when absent
 * @param wirelessDebugging wireless debugging state, or null when unreadable; a
 *   null hides the row rather than claiming Off
 * @param versionWarning show the amber line about the known-bad combination; the
 *   line names the section that explains it, and no version row changes
 */
@Composable
private fun StatusCard(
  installed: Boolean,
  running: Boolean,
  granted: Boolean,
  version: String?,
  wirelessDebugging: Boolean?,
  versionWarning: Boolean,
  onOpenShizuku: () -> Unit,
  onOpenDeveloperOptions: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text("Live status", style = MaterialTheme.typography.titleSmall)
      // One plain line names the app and its version: "Shizuku 13.5.4" or
      // "Shizuku not installed", with no version requirements attached.
      StatusLine(
        label = "Shizuku",
        value = version ?: if (installed) "Installed" else "Not installed",
        good = installed,
      )
      StatusLine("Server", if (running) "Running" else "Stopped", running)
      StatusLine("Permission", if (granted) "Granted" else "Missing", granted)
      wirelessDebugging?.let {
        StatusLine("Wireless debugging", if (it) "On" else "Off", it)
      }
      if (versionWarning) {
        Text(
          text = "On some Android 16 devices, including HyperOS 3, Shizuku " +
            "$KNOWN_BAD_SHIZUKU_VERSION may fail to start its background helper. " +
            "See \u201cShizuku version problems\u201d below.",
          style = MaterialTheme.typography.bodySmall,
          color = Color(Palette.amber),
        )
      }

      Spacer(Modifier.height(2.dp))
      OutlinedButton(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) {
        Text("Open Shizuku")
      }
      OutlinedButton(onClick = onOpenDeveloperOptions, modifier = Modifier.fillMaxWidth()) {
        Text("Developer options")
      }
    }
  }
}

/**
 * One `label  value` line.
 *
 * @param good true draws the value green, false amber, null leaves it neutral
 *   for values that have no pass/fail reading (the version number)
 */
@Composable
private fun StatusLine(label: String, value: String, good: Boolean?) {
  val color = when (good) {
    true -> Color(Palette.green)
    false -> Color(Palette.amber)
    null -> MaterialTheme.colorScheme.onBackground
  }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Text(value, color = color, style = MaterialTheme.typography.bodyMedium)
  }
}

/**
 * One collapsible guide topic: a card whose header toggles the body.
 *
 * The body is composed only while expanded, so collapsed text is neither drawn
 * nor focusable for accessibility services. The parent owns the expand mask, so
 * several cards can be open at once.
 *
 * @param section title plus the copy shown when open
 * @param expanded current state; drives the chevron, the content and the a11y
 *   state description
 * @param onToggle invoked by a tap anywhere on the header row
 * @param onOpenDiagnostics invoked by the last section's trailing button
 */
@Composable
private fun GuideSectionCard(
  section: GuideSection,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenDiagnostics: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
  ) {
    Column {
      GuideSectionHeader(title = section.title, expanded = expanded, onToggle = onToggle)
      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(
          animationSpec = tween(durationMillis = SECTION_ANIMATION_MILLIS),
        ) + fadeIn(animationSpec = tween(durationMillis = SECTION_ANIMATION_MILLIS)),
        exit = shrinkVertically(
          animationSpec = tween(durationMillis = SECTION_ANIMATION_MILLIS),
        ) + fadeOut(animationSpec = tween(durationMillis = SECTION_ANIMATION_MILLIS)),
      ) {
        Column(
          modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          GuideSectionBody(section, onOpenDiagnostics)
        }
      }
    }
  }
}

/**
 * The card header: bold title, chevron on the right, whole row is the button.
 *
 * The row is at least 48 dp tall so the tap target meets the accessibility
 * minimum even with a one-line title. Touch and accessibility share one node on
 * purpose: a separate `clickable` modifier produced two semantic nodes (the
 * description on the parent, the click action on a child), so TalkBack focused a
 * button with no label. Here a single `semantics` node carries the button role,
 * the description and the click action; [detectTapGestures] handles touch, and
 * the title and chevron clear their own semantics so the header is the only
 * focus stop with that text. (`mergeDescendants` was tried and drops the click
 * action on this Compose version, measured on a uiautomator dump.)
 *
 * @param expanded selects the chevron direction and the announced state
 * @param onToggle invoked by a tap anywhere on the row or by the click action
 */
@Composable
private fun GuideSectionHeader(
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val state = if (expanded) "expanded" else "collapsed"
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 48.dp)
      .pointerInput(onToggle) {
        detectTapGestures(onTap = { onToggle() })
      }
      .semantics {
        role = Role.Button
        stateDescription = state
        contentDescription = "$title, $state"
        onClick {
          onToggle()
          true
        }
      }
      .padding(horizontal = 12.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f).clearAndSetSemantics {},
    )
    Text(
      text = if (expanded) "\u25BE" else "\u25B8",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
      modifier = Modifier.clearAndSetSemantics {},
    )
  }
}

/**
 * The copy of one section, in the order the long-page version used: intro,
 * numbered steps, note, trailing action, link, "why this works".
 *
 * @param section the copy; [GuideSection.steps] are numbered from 1 here
 * @param onOpenDiagnostics invoked by the last section's button, the only section
 *   with that action; [GuideSection.linkUrl] is a separate outbound link
 */
@Composable
private fun GuideSectionBody(section: GuideSection, onOpenDiagnostics: () -> Unit) {
  val uriHandler = LocalUriHandler.current
  section.intro?.let { Paragraph(it) }
  section.steps.forEachIndexed { index, step ->
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        "${index + 1}.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(Palette.green),
      )
      Text(step, style = MaterialTheme.typography.bodyMedium)
    }
  }
  section.note?.let { Paragraph(it) }
  if (section.actionLabel != null) {
    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
      Text(section.actionLabel)
    }
  }
  val linkLabel = section.linkLabel
  val linkUrl = section.linkUrl
  if (linkLabel != null && linkUrl != null) {
    // A phone with no browser (or a managed profile with the intent blocked) must
    // leave the copy readable; the steps above still name the asset to download.
    OutlinedButton(
      onClick = { runCatching { uriHandler.openUri(linkUrl) } },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(linkLabel)
    }
  }
  section.why?.let {
    Text(
      text = "Why this works: $it",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )
  }
}

/** Dim body text used for intro/outro lines inside a section. */
@Composable
private fun Paragraph(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
  )
}

/**
 * One guide topic and its body copy.
 *
 * @param steps each entry becomes one numbered line, in order
 * @param note practical follow-up text shown after the steps
 * @param why one-sentence mechanism note for readers who want the reason
 * @param actionLabel label of the trailing button; the last section only
 * @param linkLabel label of an outbound link button; only rendered together with
 *   [linkUrl]
 * @param linkUrl absolute URL opened by that button, e.g. the Shizuku release page
 */
private class GuideSection(
  val title: String,
  val intro: String?,
  val steps: List<String>,
  val note: String? = null,
  val why: String? = null,
  val actionLabel: String? = null,
  val linkLabel: String? = null,
  val linkUrl: String? = null,
)

/** Step (a): install, enable, pair once, start, grant. */
private val FIRST_TIME_STEPS = listOf(
  "Install Shizuku from the Play Store. Open it once.",
  "Turn on Developer options: Settings \u2192 About phone \u2192 tap Build number 7 " +
    "times. Developer options appears in Settings.",
  "Turn on Wireless debugging: Developer options \u2192 Wireless debugging \u2192 On. " +
    "Choose \u201cAlways allow on this network\u201d when asked.",
  "Pair Shizuku: open Shizuku \u2192 Pairing. In Wireless debugging, tap \u201cPair " +
    "device with pairing code\u201d and type the code into the Shizuku notification.",
  "Wait until Shizuku says pairing succeeded. Press Start in Shizuku; the screen " +
    "reads \u201cShizuku is running\u201d when the server is up.",
  "Return to Rsensors and tap \u201cGrant Shizuku permission\u201d. The Shizuku " +
    "permission row turns Granted.",
)

/** Step (b): the debugging-connection toggle, no re-pairing. */
private val QUICK_FIX_STEPS = listOf(
  "Turn Wireless debugging Off.",
  "Wait 3 seconds.",
  "Turn Wireless debugging On. Choose \u201cAlways allow on this network\u201d.",
  "Wait about 5 seconds. The phone needs a moment to announce the connection again.",
  "Open Shizuku and press Start. It reads \u201cShizuku is running\u201d when the " +
    "server starts.",
)

/** Step (c): re-pair sequence, only when pairing is lost. */
private val FULL_RESET_STEPS = listOf(
  "Turn Wireless debugging Off.",
  "Wait 3 seconds.",
  "Turn Wireless debugging On.",
  "In Shizuku, tap Pairing.",
  "In Wireless debugging, tap \u201cPair device with pairing code\u201d, then type the " +
    "code into the Shizuku notification.",
  "Wait until Shizuku says pairing succeeded.",
  "Press Start. It reads \u201cShizuku is running\u201d when the server is up.",
)

/** Step (d): the reboot flow. */
private val REBOOT_STEPS = listOf(
  "Keep Wireless debugging on.",
  "Open Shizuku and press Start.",
  "If Start keeps searching, use the searching fix above.",
  "Return to Rsensors. CPU and GPU come back on the next poll: 2 seconds with the " +
    "card open, 5 seconds with it closed.",
)

/** Step (e): the BAT-only checks while the server runs. */
private val BAT_STEPS = listOf(
  "Open the Rsensors main screen. The Shizuku permission row must read Granted; tap " +
    "\u201cGrant Shizuku permission\u201d if it reads Missing.",
  "Wait one poll: 2 seconds with the card open, 5 seconds with it closed.",
  "Check the Service row. It must read Running; tap \u201cStart monitoring\u201d if it " +
    "reads Stopped.",
  "Unlock the screen. A locked or sleeping screen pauses polling on purpose, so BAT is " +
    "expected then.",
)

/** Step (f): version checks, kept separate so the last-resort list stays short. */
private val VERSION_STEPS = listOf(
  "This is about a failure on some Android 16 devices, especially HyperOS 3, " +
    "not about Android 16 itself: Shizuku 13.6.0 supports Android 16 QPR1 in " +
    "general, so if it works on your phone, keep it. This downgrade is only for " +
    "that failure.",
  "Use the link below to open the Shizuku 13.5.4 release on GitHub. Scroll to " +
    "Assets; if the list is collapsed, tap Assets. Download " +
    "$SHIZUKU_1354_APK (~3.4 MB). Do not download the Source code zip or tar.",
  "Android will not install an older version over 13.6.0. Uninstall Shizuku " +
    "first: this deletes its pairing and permission data, so Rsensors must be " +
    "granted the Shizuku permission again.",
  "Install the APK, open Shizuku, and pair and start the server again. Then " +
    "return to Rsensors and grant the Shizuku permission.",
  "Keep 13.5.4 until a release fixes the failure. Update again only if the newer " +
    "version works on your phone.",
)

/** Step (g): the last-resort checks, ending with the Diagnostics Copy button. */
private val LAST_RESORT_STEPS = listOf(
  "Open Rsensors \u2192 Diagnostics. Check that Shizuku is installed, the server is " +
    "running, and the permission is granted. The Shizuku version is listed there.",
  "Reboot the phone and repeat the Start steps.",
  "If it still fails, use Copy on the Diagnostics screen to copy the details when " +
    "asking for help.",
)

/**
 * The guide in order; every title is a card header.
 *
 * The order is the reading order the guide's cross-references assume ("use the
 * searching fix above"), so it is not sorted or filtered. [oemHint] is the
 * brand-aware Shizuku note on the reboot card; it stays a hint, so the card
 * still describes the generic reboot flow.
 */
private fun guideSections(oemHint: String): List<GuideSection> = listOf(
  GuideSection(
    title = "First-time setup",
    intro = "Pair Shizuku once, start its server, then let Rsensors use it. " +
      "CPU and GPU temperatures replace BAT when these steps are done.",
    steps = FIRST_TIME_STEPS,
    why = "Pairing gives Shizuku a key. Start uses that key over the phone's " +
      "debugging connection to start the Shizuku service that Rsensors reads " +
      "temperatures through.",
  ),
  GuideSection(
    title = "Start keeps searching",
    intro = "Shizuku says it is searching for the wireless debugging device " +
      "service. Pairing is still intact: the phone is not announcing the " +
      "debugging connection Shizuku starts through. You do not need to pair " +
      "again for this.",
    steps = QUICK_FIX_STEPS,
    note = "If Start still searches, repeat these steps once. If that fails, " +
      "reboot the phone and run them a third time.",
    why = "Pairing and the connection Shizuku starts through are separate. The " +
      "toggle restarts the second one.",
  ),
  GuideSection(
    title = "Pairing is lost or the quick fix keeps failing",
    intro = "This is the full reset. Use it only in those two cases: it replaces " +
      "the pairing key and then starts the server.",
    steps = FULL_RESET_STEPS,
    why = "A new pairing key replaces the old one. Start still uses the " +
      "debugging connection, so the key alone does not start it.",
  ),
  GuideSection(
    title = "After a reboot",
    intro = "Android ends the Shizuku server when the phone restarts. Rsensors " +
      "shows BAT until the server is running again.",
    steps = REBOOT_STEPS,
    note = oemHint,
    why = "Shizuku's server is a background process, and Android stops those on " +
      "reboot. Nothing restarts it by itself.",
  ),
  GuideSection(
    title = "Rsensors shows BAT while Shizuku is running",
    intro = "The server is up, but Rsensors has no permission to read through " +
      "it, or no poll is running.",
    steps = BAT_STEPS,
    why = "BAT means only the battery sensor answered. CPU and GPU come from the " +
      "system's thermal service, which Rsensors can reach only through Shizuku.",
  ),
  GuideSection(
    title = "Shizuku version problems",
    intro = "If the server says Running but Rsensors still shows BAT, or Start " +
      "never connects, check the Shizuku version in Diagnostics. Shizuku " +
      "$KNOWN_BAD_SHIZUKU_VERSION supports Android 16 QPR1 in general; this " +
      "section covers the one failure it has on some phones.",
    steps = VERSION_STEPS,
    linkLabel = "Open the Shizuku 13.5.4 release on GitHub",
    linkUrl = SHIZUKU_1354_RELEASE_URL,
  ),
  GuideSection(
    title = "Why the toggle works",
    intro = "Pairing and the connection Shizuku starts through are two separate " +
      "things. Pairing saves a key on the phone. Start only asks the debugging " +
      "connection to accept that key. When the connection is not announced, " +
      "Start keeps searching. Turning Wireless debugging off and on starts the " +
      "connection again, so Start finds it. Pairing again changes the key " +
      "without restarting the connection, which is why it does not fix this " +
      "symptom.",
    steps = emptyList(),
  ),
  GuideSection(
    title = "If nothing above works",
    intro = "These are the last checks, after the toggle fix, the full reset and " +
      "a reboot.",
    steps = LAST_RESORT_STEPS,
    note = "For the version decision, see \u201cShizuku version problems\u201d above.",
    actionLabel = "Open Diagnostics",
  ),
)
