package dev.rsensors.android.core

import java.util.Locale

/**
 * Parser for `dumpsys thermalservice` (ANDROID.md §5, §8 T8).
 *
 * The dump contains two temperature blocks: `Cached temperatures` (what the
 * framework last stored, and on the target phone the *stale* one 44.776 °C CPU
 * value) and `Current temperatures from HAL` (the live reading). Only the
 * current block is a measurement, so the parse starts at its header and stops
 * at `Current cooling devices from HAL:`; everything else — including the
 * cached block that precedes it on Android 16 — is ignored.
 *
 * Values are already degrees Celsius and are never rescaled. The HAL may name
 * its sensors freely, so a name is preferred and the numeric `mType` is the
 * fallback documented in §5 (0 CPU, 1 GPU, 2 BATTERY, 3 SKIN, 9 NPU, 10 TPU,
 * 13 SOC). Unknown sensors are dropped rather than guessed.
 *
 * Pure function: no I/O, no clock. The caller stamps the sample time.
 *
 * @param text full stdout of `dumpsys thermalservice`; may be empty or partial
 * @return a reading with `t = 0`, the HAL values it found and a canonical
 *   `board` list, or null when the dump has no live CPU, GPU or battery value
 */
fun parseDumpsysThermal(text: String): Reading? {
  val values = mutableMapOf<HalSensor, Double>()

  var inBlock = false
  for (raw in text.lineSequence()) {
    val line = raw.trim()
    if (!inBlock) {
      if (line.startsWith(CURRENT_HEADER)) inBlock = true
      continue
    }
    if (line.startsWith(STOP_HEADER)) break

    val match = TEMPERATURE_LINE.find(line) ?: continue
    val value = match.groupValues[VALUE_GROUP].toDoubleOrNull() ?: continue
    val type = match.groupValues[TYPE_GROUP].toIntOrNull()
    val sensor = sensorFor(match.groupValues[NAME_GROUP], type) ?: continue
    // The first occurrence inside the live block wins; a repeated name in the
    // same block is not something the HAL emits, and first is deterministic.
    if (sensor !in values) values[sensor] = value
  }

  val cpu = values[HalSensor.CPU]
  val gpu = values[HalSensor.GPU]
  val battery = values[HalSensor.BATTERY]
  if (cpu == null && gpu == null && battery == null) return null

  return Reading(
    t = 0L,
    cpu = cpu,
    gpu = gpu,
    battery = battery,
    board = BOARD_ORDER.mapNotNull { sensor ->
      values[sensor]?.let { sensor.label to it }
    },
  )
}

/** HAL sensor names this app draws, in the order they appear in [Reading.board]. */
private enum class HalSensor(val label: String) {
  CPU("CPU"),
  GPU("GPU"),
  BATTERY("BATTERY"),
  SKIN("SKIN"),
  SOC("SOC"),
  NPU("NPU"),
  TPU("TPU"),
  POWER_AMPLIFIER("POWER_AMPLIFIER"),
}

/** Motherboard-ish HAL sensors, in the fixed order [Reading.board] reports. */
private val BOARD_ORDER = listOf(
  HalSensor.SOC,
  HalSensor.SKIN,
  HalSensor.NPU,
  HalSensor.TPU,
  HalSensor.POWER_AMPLIFIER,
)

/** `mName` values the HAL uses on the target phone; matched case-insensitively. */
private val NAME_TO_SENSOR: Map<String, HalSensor> =
  HalSensor.entries.associateBy { it.label.uppercase(Locale.ROOT) }

/** ANDROID.md §5 fallback table for dumps that name a sensor something else. */
private val TYPE_TO_SENSOR: Map<Int, HalSensor> = mapOf(
  0 to HalSensor.CPU,
  1 to HalSensor.GPU,
  2 to HalSensor.BATTERY,
  3 to HalSensor.SKIN,
  9 to HalSensor.NPU,
  10 to HalSensor.TPU,
  13 to HalSensor.SOC,
)

/**
 * Resolves one dump line to a sensor.
 *
 * @param name the raw `mName` field; may carry stray spaces
 * @param type the raw `mType` field, or null when it is not an integer
 * @return the matching sensor, or null when neither the name nor the type is
 *   one this app knows
 */
private fun sensorFor(name: String, type: Int?): HalSensor? =
  NAME_TO_SENSOR[name.trim().uppercase(Locale.ROOT)] ?: type?.let { TYPE_TO_SENSOR[it] }

private const val CURRENT_HEADER = "Current temperatures from HAL:"
private const val STOP_HEADER = "Current cooling devices from HAL:"

private const val VALUE_GROUP = 1
private const val TYPE_GROUP = 2
private const val NAME_GROUP = 3

/** One `Temperature{...}` entry; the regex is the one specified in ANDROID.md §5. */
private val TEMPERATURE_LINE =
  Regex("""Temperature\{mValue=(-?\d+(?:\.\d+)?), mType=(\d+), mName=([^,]+), mStatus=(\d+)\}""")
