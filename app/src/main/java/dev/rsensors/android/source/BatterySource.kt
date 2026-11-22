package dev.rsensors.android.source

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.parseBatteryTemperature

/**
 * Battery temperature from the sticky `ACTION_BATTERY_CHANGED` broadcast.
 *
 * Registers no receiver: a null receiver returns the current sticky intent, so
 * each [poll] is a cheap read with no lifecycle to leak and no permission. The
 * extra updates only when the battery broadcast fires (roughly every 10–30 s),
 * so polling faster does not freshen the value (ANDROID.md §6).
 *
 * The broadcast is owned by the system; a null intent or a missing/unknown
 * temperature yields a null reading, and a runtime failure while querying it is
 * swallowed so one bad broadcast cannot kill the poller (ANDROID.md §2.2: a
 * missing sensor renders a dash, never a guess).
 *
 * @param context any context; only its system broadcast service is used
 */
class BatterySource(private val context: Context) : SensorSource {
  override val id: String = ID

  override fun poll(now: Long): Reading? =
    try {
      val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
      val battery = parseBatteryTemperature(tenths)
      if (battery == null) {
        null
      } else {
        Reading(t = now, battery = battery, sourceName = id)
      }
    } catch (e: RuntimeException) {
      null
    }

  companion object {
    const val ID = "battery"
  }
}
