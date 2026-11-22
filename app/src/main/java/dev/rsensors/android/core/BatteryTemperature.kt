package dev.rsensors.android.core

/**
 * Pure conversion of the platform's raw battery temperature.
 *
 * `BatteryManager.EXTRA_TEMPERATURE` reports tenths of a degree Celsius, and
 * Android uses [Int.MIN_VALUE] when the battery driver cannot supply a value.
 * That sentinel and a missing extra both become null; every other integer is a
 * legitimate reading, including 0 and negatives (cold soak, hardware quirks).
 * No clamping happens here so the UI shows what the device actually reports.
 *
 * @param tenths raw `EXTRA_TEMPERATURE`, or null when the extra is absent
 * @return degrees Celsius, or null when the platform had no value
 */
fun parseBatteryTemperature(tenths: Int?): Double? =
  when {
    tenths == null || tenths == Int.MIN_VALUE -> null
    else -> tenths / 10.0
  }
