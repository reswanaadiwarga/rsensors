package dev.rsensors.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.rsensors.android.overlay.OverlayPreferences
import dev.rsensors.android.overlay.OverlayService
import rikka.shizuku.Shizuku

/**
 * Launcher activity: permission flow and overlay start/stop (ANDROID.md §8 T6,
 * §8 T8).
 *
 * Requests nothing at launch. Every permission is requested from an explicit
 * button, because POST_NOTIFICATIONS, the overlay grant and Shizuku's permission
 * are user decisions the app must not front-load. The screen reflects live state
 * (overlay grant, notification grant, service running, recording, demo and boot
 * switches, Shizuku server and Shizuku grant), refreshed in [onResume], after
 * each launcher result and after a Shizuku permission answer.
 *
 * Shizuku cannot be started by an app (§6): the screen only links to the Shizuku
 * manager and asks it for permission. Every Shizuku call is wrapped, because the
 * API throws when the server is stopped — a normal state, not an error.
 *
 * Side effects: launches system settings and the Shizuku manager, starts/stops
 * [OverlayService], and writes the demo and boot flags through
 * [OverlayPreferences].
 *
 * T10 adds the "At boot" section: a persisted switch that
 * [dev.rsensors.android.boot.BootReceiver] reads after a reboot, and the
 * HyperOS autostart guidance. The switch is refreshed in the same
 * [refreshState] path as every other row, so it is correct after a return from
 * the app's system settings page.
 */
class MainActivity : ComponentActivity() {
  private var overlayGranted by mutableStateOf(false)
  private var notificationsGranted by mutableStateOf(false)
  private var demoEnabled by mutableStateOf(false)
  private var bootEnabled by mutableStateOf(true)

  /**
   * Optimistic mirror of [OverlayService.isRunning]: set immediately on
   * start/stop so the buttons respond in the same frame as the tap.
   */
  private var serviceRunning by mutableStateOf(false)

  /** Optimistic mirror of [OverlayService.isRecording]; refreshed on resume. */
  private var recording by mutableStateOf(false)

  private var shizukuRunning by mutableStateOf(false)
  private var shizukuGranted by mutableStateOf(false)
  private var shizukuInstalled by mutableStateOf(false)

  /**
   * Returns from either the overlay-permission page or the app-details page;
   * both are followed by [refreshState], because the grant and the
   * manufacturer's autostart switches live behind those screens.
   */
  private val systemSettingsLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshState() }

  private val notificationLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshState() }

  /**
   * Shizuku answers the request through this listener rather than through an
   * activity result, so it must be registered for the whole activity lifetime.
   * The callback arrives on the main thread; [refreshState] only reads state.
   */
  private val shizukuPermissionListener =
    Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
      // The grant is already visible to checkSelfPermission by the time the
      // listener runs, so a full refresh is the single source of truth.
      if (requestCode == SHIZUKU_REQUEST_CODE) refreshState()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    addShizukuPermissionListener()
    refreshState()
    setContent {
      RsensorsTheme {
        HomeScreen(
          overlayGranted = overlayGranted,
          notificationsGranted = notificationsGranted,
          serviceRunning = serviceRunning,
          recording = recording,
          demoEnabled = demoEnabled,
          bootEnabled = bootEnabled,
          shizukuInstalled = shizukuInstalled,
          shizukuRunning = shizukuRunning,
          shizukuGranted = shizukuGranted,
          showNotificationRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsGranted,
          onGrantOverlay = ::grantOverlay,
          onRequestNotifications = ::requestNotifications,
          onStart = ::startOverlay,
          onStop = ::stopOverlay,
          onRecordingChange = ::setRecordingEnabled,
          onOpenSessions = ::openSessions,
          onOpenDiagnostics = ::openDiagnostics,
          onDemoChange = ::setDemo,
          onBootChange = ::updateBootEnabled,
          onOpenAppSettings = ::openAppSettings,
          onOpenShizuku = ::openShizuku,
          onRequestShizuku = ::requestShizukuPermission,
          onOpenShizukuHelp = ::openShizukuHelp,
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    refreshState()
  }

  override fun onDestroy() {
    removeShizukuPermissionListener()
    super.onDestroy()
  }

  /** Re-reads every state the screen displays. */
  private fun refreshState() {
    overlayGranted = Settings.canDrawOverlays(this)
    notificationsGranted = notificationsGranted()
    demoEnabled = OverlayPreferences.isDemo(this)
    bootEnabled = OverlayPreferences.isBootEnabled(this)
    serviceRunning = OverlayService.isRunning
    recording = OverlayService.isRecording
    shizukuInstalled = isShizukuInstalled()
    shizukuRunning = isShizukuRunning()
    shizukuGranted = hasShizukuPermission()
  }

  private fun notificationsGranted(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

  /**
   * Package visibility is granted by the manifest `<queries>` entry; without it
   * `getLaunchIntentForPackage` returns null even when Shizuku is installed.
   */
  private fun isShizukuInstalled(): Boolean =
    try {
      packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null
    } catch (e: Exception) {
      false
    }

  /** `pingBinder` is false when the Shizuku server is stopped or not installed. */
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

  private fun addShizukuPermissionListener() {
    try {
      Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    } catch (e: Exception) {
      // The SDK is always present, but a missing provider must not crash the
      // activity; the screen simply never sees a permission answer.
    }
  }

  private fun removeShizukuPermissionListener() {
    try {
      Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    } catch (e: Exception) {
      // Nothing registered or already removed.
    }
  }

  private fun grantOverlay() {
    systemSettingsLauncher.launch(
      Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:$packageName".toUri(),
      ),
    )
  }

  /**
   * Opens this app's system settings page.
   *
   * That page is the entry point for the manufacturer's autostart/battery
   * switches; MIUI/HyperOS exposes "Autostart" and battery saver as rows on it
   * (or one level down), and the app cannot read or change them itself. The
   * result callback refreshes the screen so a returning user sees the boot
   * switch and overlay grant as they now stand.
   */
  private fun openAppSettings() {
    systemSettingsLauncher.launch(
      Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:$packageName".toUri(),
      ),
    )
  }

  private fun requestNotifications() {
    // The button is only shown from API 33, but guard the constant here so the
    // launch path is safe if it is ever called another way.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  private fun startOverlay() {
    OverlayService.start(this)
    serviceRunning = true
  }

  private fun stopOverlay() {
    OverlayService.stop(this)
    serviceRunning = false
    recording = false
  }

  /**
   * Applies the Record switch.
   *
   * Turning it on starts the overlay first when it is stopped, because the
   * recorder lives in the poller; the switch's state is set optimistically so a
   * tap is visible before the worker opens the file. Turning it off closes the
   * session through the service, which flushes every row on append.
   */
  private fun setRecordingEnabled(enabled: Boolean) {
    recording = enabled
    if (enabled) {
      if (!OverlayService.isRunning) {
        OverlayService.start(this)
        serviceRunning = true
      }
      OverlayService.startRecording(this)
    } else {
      OverlayService.stopRecording(this)
    }
  }

  private fun openSessions() {
    startActivity(Intent(this, SessionsActivity::class.java))
  }

  private fun openDiagnostics() {
    startActivity(Intent(this, DiagnosticsActivity::class.java))
  }

  /**
   * Opens the Shizuku setup/troubleshooting guide (ANDROID.md §8 T15g).
   *
   * Always reachable, including when Shizuku is not installed: the guide is the
   * one place that explains the tier-1 setup and the wireless-debugging toggle.
   */
  private fun openShizukuHelp() {
    startActivity(Intent(this, ShizukuHelpActivity::class.java))
  }

  private fun setDemo(enabled: Boolean) {
    OverlayPreferences.setDemo(this, enabled)
    demoEnabled = enabled
    if (OverlayService.isRunning) OverlayService.restart(this)
  }

  /**
   * Persists the boot switch. The running service is untouched: the flag is
   * only read by [BootReceiver] on the next boot, so there is nothing to
   * restart now.
   */
  private fun updateBootEnabled(enabled: Boolean) {
    OverlayPreferences.setBootEnabled(this, enabled)
    bootEnabled = enabled
  }

  /** Opens the Shizuku manager so the user can start its server. */
  private fun openShizuku() {
    val intent = try {
      packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    } catch (e: Exception) {
      null
    } ?: return
    try {
      startActivity(intent)
    } catch (e: Exception) {
      // Manager present but not launchable; the status row stays authoritative.
    }
  }

  /**
   * Asks Shizuku for shell permission. The answer arrives on
   * [shizukuPermissionListener]; a stopped server throws and leaves the row at
   * "Missing", which is the accurate state.
   */
  private fun requestShizukuPermission() {
    try {
      Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    } catch (e: Exception) {
      refreshState()
    }
  }

  private companion object {
    /** Request code echoed back by [Shizuku.OnRequestPermissionResultListener]. */
    const val SHIZUKU_REQUEST_CODE = 1001

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
  }
}

@Composable
private fun HomeScreen(
  overlayGranted: Boolean,
  notificationsGranted: Boolean,
  serviceRunning: Boolean,
  recording: Boolean,
  demoEnabled: Boolean,
  bootEnabled: Boolean,
  shizukuInstalled: Boolean,
  shizukuRunning: Boolean,
  shizukuGranted: Boolean,
  showNotificationRequest: Boolean,
  onGrantOverlay: () -> Unit,
  onRequestNotifications: () -> Unit,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onRecordingChange: (Boolean) -> Unit,
  onOpenSessions: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  onDemoChange: (Boolean) -> Unit,
  onBootChange: (Boolean) -> Unit,
  onOpenAppSettings: () -> Unit,
  onOpenShizuku: () -> Unit,
  onRequestShizuku: () -> Unit,
  onOpenShizukuHelp: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Rsensors", style = MaterialTheme.typography.headlineMedium)
      Text(
        "An always-on-top pill with CPU, GPU and battery temperatures.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
      )

      Spacer(Modifier.height(4.dp))
      StatusRow("Overlay permission", overlayGranted)
      StatusRow("Notifications", notificationsGranted)
      StatusRow("Service", serviceRunning, runningText = true)
      StatusRow(
        label = "Shizuku",
        value = when {
          !shizukuInstalled -> "Not installed"
          shizukuRunning -> "Running"
          else -> "Stopped"
        },
        good = shizukuRunning,
      )
      StatusRow("Shizuku permission", shizukuGranted)
      // Always visible, including when Shizuku is missing: the guide is also the
      // install/setup entry point, not only a troubleshooting page (T15g).
      OutlinedButton(onClick = onOpenShizukuHelp, modifier = Modifier.fillMaxWidth()) {
        Text("Shizuku setup guide")
      }

      Spacer(Modifier.height(4.dp))
      if (!overlayGranted) {
        Button(onClick = onGrantOverlay, modifier = Modifier.fillMaxWidth()) {
          Text("Grant overlay permission")
        }
      }
      if (showNotificationRequest) {
        Button(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
          Text("Allow notifications")
        }
      }
      if (shizukuInstalled && !shizukuRunning) {
        Button(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) {
          Text("Open Shizuku")
        }
      }
      if (shizukuRunning && !shizukuGranted) {
        Button(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth()) {
          Text("Grant Shizuku permission")
        }
      }
      if (!serviceRunning) {
        Button(
          onClick = onStart,
          enabled = overlayGranted,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Start monitoring")
        }
      } else {
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
          Text("Stop monitoring")
        }
      }
      Text(
        "Stops polling and recording. Shizuku and the permissions stay untouched.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Record session")
          Text(
            if (overlayGranted) {
              "Saves a CSV while the overlay polls"
            } else {
              "Needs overlay permission"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
          )
        }
        Switch(
          checked = recording,
          onCheckedChange = onRecordingChange,
          enabled = overlayGranted,
        )
      }
      Text(
        "Screen-off pauses polling, so a recording has a gap while the phone sleeps.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
      )

      Spacer(Modifier.height(4.dp))
      Text("At boot", style = MaterialTheme.typography.titleSmall)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Start monitoring at boot")
          Text(
            if (overlayGranted) {
              "Restores the pill after a reboot"
            } else {
              "Needs overlay permission"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
          )
        }
        Switch(checked = bootEnabled, onCheckedChange = onBootChange)
      }
      Text(
        "HyperOS may block autostart. In the app's system settings, allow " +
          "Autostart and remove the battery restriction, or the overlay stays " +
          "off until you start it by hand. Shizuku still has to be started " +
          "after every reboot, so the pill shows battery temperature until then.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
      )
      OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
        Text("App settings")
      }

      Button(onClick = onOpenSessions, modifier = Modifier.fillMaxWidth()) {
        Text("Sessions")
      }
      OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
        Text("Diagnostics")
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Demo data (sine ramps)", modifier = Modifier.weight(1f))
        Switch(checked = demoEnabled, onCheckedChange = onDemoChange)
      }

      Text(
        "With Shizuku running and permission granted, CPU and GPU come from " +
          "Android's thermal service. Without Shizuku, Rsensors can show only " +
          "battery temperature.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
      )
    }
  }
}

/**
 * One status line.
 *
 * @param runningText when true the value reads Running/Stopped instead of
 *   Granted/Missing; the colour is green only in the good state
 */
@Composable
private fun StatusRow(label: String, granted: Boolean, runningText: Boolean = false) {
  val value = when {
    runningText -> if (granted) "Running" else "Stopped"
    granted -> "Granted"
    else -> "Missing"
  }
  StatusRow(label, value, granted)
}

/**
 * One status line with an explicit value, for three-state rows such as Shizuku
 * (Running / Stopped / Not installed).
 *
 * @param good true draws the value green, false amber
 */
@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label)
    Text(
      value,
      color = if (good) Color(Palette.green) else Color(Palette.amber),
    )
  }
}
