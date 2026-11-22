package dev.rsensors.android.core

/**
 * Pure mapping of `PowerManager.getCurrentThermalStatus()` integers.
 *
 * The platform constants are `THERMAL_STATUS_NONE` (0) through
 * `THERMAL_STATUS_SHUTDOWN` (6); the app's [ThermalStatus] mirrors them and
 * adds [ThermalStatus.UNKNOWN] for values an OEM or a future platform version
 * might introduce. Keeping the mapping pure lets JVM tests cover it without a
 * `PowerManager` instance.
 *
 * @param value raw thermal status from the platform
 * @return the matching [ThermalStatus]; unrecognised values become
 *   [ThermalStatus.UNKNOWN] rather than a guessed severity
 */
fun thermalStatusFromInt(value: Int): ThermalStatus =
  when (value) {
    0 -> ThermalStatus.NONE
    1 -> ThermalStatus.LIGHT
    2 -> ThermalStatus.MODERATE
    3 -> ThermalStatus.SEVERE
    4 -> ThermalStatus.CRITICAL
    5 -> ThermalStatus.EMERGENCY
    6 -> ThermalStatus.SHUTDOWN
    else -> ThermalStatus.UNKNOWN
  }
