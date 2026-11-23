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
 * 13 SOC). Samsung's `AP`/`BAT` aliases are recognised, while `SUBBAT` is
 * dropped before the type fallback so a secondary battery rail is never shown
 * as the main battery. Unknown sensors are dropped rather than guessed.
 *
 * A value outside `-40..150` °C is a sentinel or a unit error, not a
 * temperature, so that entry is discarded; if every entry for a sensor is
 * discarded the sensor stays absent. CPU and GPU may be reported by several
 * zones, and the hottest value is the one the overlay should show; battery and
 * board sensors keep their first occurrence for a stable display.
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
    if (value !in PLAUSIBLE_CELSIUS) continue
    val type = match.groupValues[TYPE_GROUP].toIntOrNull()
    val sensor = sensorFor(match.groupValues[NAME_GROUP], type) ?: continue
    val previous = values[sensor]
    values[sensor] = when {
      previous == null -> value
      sensor == HalSensor.CPU || sensor == HalSensor.GPU -> maxOf(previous, value)
      // Battery and board sensors keep the first occurrence so a duplicate
      // cannot make the displayed value flicker between entries.
      else -> previous
    }
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

/** `mName` values the HAL uses; matched case-insensitively after trimming. */
private val NAME_TO_SENSOR: Map<String, HalSensor> =
  HalSensor.entries.associateBy { it.label.uppercase(Locale.ROOT) } +
    mapOf(
      // Samsung reports the application processor as AP and the main battery
      // as BAT (ANDROID.md §5).
      "AP" to HalSensor.CPU,
      "BAT" to HalSensor.BATTERY,
    )

/**
 * Names dropped before any `mType` fallback.
 *
 * Samsung's `SUBBAT` is a secondary battery rail whose `mType` is still 2;
 * treating it as the main battery would display the wrong temperature.
 */
private val IGNORED_NAMES = setOf("SUBBAT")

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
 * An ignored name resolves to null without consulting [type], so `SUBBAT`
 * cannot become a battery through its `mType`. Any other unknown name falls
 * back to the numeric type.
 *
 * @param name the raw `mName` field; may carry stray spaces
 * @param type the raw `mType` field, or null when it is not an integer
 * @return the matching sensor, or null when the name is ignored or neither the
 *   name nor the type is one this app knows
 */
private fun sensorFor(name: String, type: Int?): HalSensor? {
  val normalised = name.trim().uppercase(Locale.ROOT)
  if (normalised in IGNORED_NAMES) return null
  return NAME_TO_SENSOR[normalised] ?: type?.let { TYPE_TO_SENSOR[it] }
}

/**
 * °C band a real HAL temperature can occupy. Values outside it are sentinels
 * (`999`) or a unit/scale mistake (`-273.15`); the entry is unusable rather
 * than a number to draw. Endpoints are inclusive so a genuinely cold or hot
 * sensor still reports.
 */
private val PLAUSIBLE_CELSIUS = -40.0..150.0

private const val CURRENT_HEADER = "Current temperatures from HAL:"
private const val STOP_HEADER = "Current cooling devices from HAL:"

private const val VALUE_GROUP = 1
private const val TYPE_GROUP = 2
private const val NAME_GROUP = 3

/** One `Temperature{...}` entry; the regex is the one specified in ANDROID.md §5. */
private val TEMPERATURE_LINE =
  Regex("""Temperature\{mValue=(-?\d+(?:\.\d+)?), mType=(\d+), mName=([^,]+), mStatus=(\d+)\}""")
