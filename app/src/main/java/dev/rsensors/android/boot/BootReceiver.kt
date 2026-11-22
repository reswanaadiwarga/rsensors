package dev.rsensors.android.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import dev.rsensors.android.overlay.OverlayPreferences
import dev.rsensors.android.overlay.OverlayService

/**
 * Starts the overlay after a reboot (ANDROID.md §6, §8 T10).
 *
 * Intent: the pill is meant to be always available, so the app restores it
 * without the user opening the launcher. Only the overlay service is started —
 * recording is never resumed by a boot, because a session is an explicit choice
 * made in the UI.
 *
 * Inputs: the broadcast intent. [Intent.ACTION_BOOT_COMPLETED] and the vendor
 * [ACTION_QUICKBOOT_POWERON] are the only actions handled; anything else
 * returns without side effects.
 *
 * Behaviour: the start is attempted only when the user left "Start overlay at
 * boot" on ([OverlayPreferences.isBootEnabled]) and the overlay grant is still
 * present ([Settings.canDrawOverlays]); otherwise the receiver logs the reason
 * and does nothing. `specialUse` is not one of the FGS types Android 15 forbids
 * from BOOT_COMPLETED, and the boot broadcast is exempt from the Android 12+
 * background-start rule — but an OEM autostart policy (HyperOS, ColorOS) can
 * still refuse the start, which the launcher's "At boot" section explains.
 *
 * Errors: every failure is logged and swallowed. A broadcast receiver that
 * throws would crash the process at boot, which is strictly worse than a
 * missing overlay, so the whole body is guarded.
 *
 * Log tag: `Rsensors boot`, so `adb logcat -s "Rsensors boot"` shows only the
 * boot decision.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON -> Unit
      else -> return
    }
    try {
      if (!OverlayPreferences.isBootEnabled(context)) {
        Log.i(TAG, "skipped: disabled in settings")
        return
      }
      if (!Settings.canDrawOverlays(context)) {
        Log.i(TAG, "skipped: overlay permission missing")
        return
      }
      OverlayService.start(context)
      Log.i(TAG, "overlay start requested")
    } catch (t: Throwable) {
      // Includes ForegroundServiceStartNotAllowedException and any OEM refusal
      // surfaced as a runtime exception; the receiver must survive all of them.
      Log.i(TAG, "start failed: ${t.javaClass.simpleName}: ${t.message}")
    }
  }

  companion object {
    /** Dedicated tag so the boot decision is greppable on its own. */
    const val TAG = "Rsensors boot"

    /**
     * Sent by some Xiaomi/OPPO builds instead of, or ahead of, BOOT_COMPLETED.
     *
     * Not in [Intent] as a public constant, so it is spelled out here; the
     * service start is idempotent, so receiving both actions is harmless.
     */
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
  }
}
