package dev.rsensors.android.source

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.ThermalStatus
import dev.rsensors.android.core.thermalStatusFromInt

/**
 * Coarse thermal pressure from `PowerManager.getCurrentThermalStatus()`.
 *
 * Contributes only [Reading.thermalStatus], never a temperature: the status is
 * a severity word, and relabelling it as a CPU or GPU degrees value would
 * invent a number (ANDROID.md §2.2, §5).
 *
 * The API is available from Android 10 (API 29); older devices and a missing
 * service produce a null reading, as does a status the mapper cannot recognise,
 * so the chain falls through instead of drawing "UNKNOWN".
 *
 * @param context any context; only its system service lookup is used
 */
class ThermalStatusSource(private val context: Context) : SensorSource {
  override val id: String = ID

  override fun poll(now: Long): Reading? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val power = context.getSystemService(PowerManager::class.java) ?: return null
    val status = thermalStatusFromInt(power.currentThermalStatus)
    if (status == ThermalStatus.UNKNOWN) return null
    return Reading(t = now, thermalStatus = status, sourceName = id)
  }

  companion object {
    const val ID = "thermal"
  }
}
