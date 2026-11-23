// Independent acceptance checks for T19b-4 thermal hardening.
//
// Expected values are hand-derived from the task spec. They are NOT copied from
// the implementation's unit tests or from running the implementation.
//
// Compile against app/src/main/java/dev/rsensors/android/core/{DumpsysParser,
// ZoneMap,Reading}.kt and run ThermalHardeningAcceptanceKt from the repository
// root. It exits non-zero on any failure. No adb/device access is required.
//
// Checks tagged "delta" are the behaviour T19b-4 is supposed to change; checks
// tagged "regression" pin behaviour the spec says must stay unchanged and can
// only be falsified by mutation of the shared code path.

import dev.rsensors.android.core.Zone
import dev.rsensors.android.core.ZoneCategory
import dev.rsensors.android.core.ZoneMap
import dev.rsensors.android.core.parseDumpsysThermal
import kotlin.math.abs
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

private fun expectClose(actual: Double?, expected: Double, label: String) {
  checks++
  if (actual != null && abs(actual - expected) <= 1e-9) {
    println("ok: $label")
  } else {
    failures++
    println("FAIL: $label: expected <$expected>, got <$actual>")
  }
}

/** One HAL temperature entry; value is a string so NaN/Infinity can be fed in. */
private fun entry(value: String, type: Int, name: String): String =
  "Temperature{mValue=$value, mType=$type, mName=$name, mStatus=0}"

/**
 * A dump with a stale cached CPU entry before the live block and a battery entry
 * after the stop header. Both surroundings must be ignored by every live-block
 * check below.
 */
private fun currentBlock(vararg entries: String): String = buildString {
  appendLine("Cached temperatures:")
  appendLine("  " + entry("99.0", 0, "CPU"))
  appendLine("Current temperatures from HAL:")
  for (line in entries) appendLine("  $line")
  appendLine("Current cooling devices from HAL:")
  appendLine("  " + entry("88.0", 2, "BATTERY"))
}

fun main() {
  duplicateAggregation()
  rangeHardening()
  aliasesAndFallback()
  currentBlockOnly()
  zoneAliases()
  zoneScoringAndOverrides()

  println("$checks checks, $failures failures")
  if (failures != 0) exitProcess(1)
}

// Spec: CPU/GPU take the maximum; BATTERY and board sensors keep the first valid
// occurrence, and board order is fixed SOC, SKIN, ...
private fun duplicateAggregation() {
  val reading = parseDumpsysThermal(
    currentBlock(
      entry("61.3", 0, "CPU"),
      entry("38.351", 0, "CPU"),
      entry("70.0", 0, "CPU"),
      entry("38.351", 1, "GPU"),
      entry("56.0", 1, "GPU"),
      entry("80.0", 1, "GPU"),
      entry("33.5", 2, "BATTERY"),
      entry("34.5", 2, "BATTERY"),
      entry("30.0", 3, "SKIN"),
      entry("31.0", 3, "SKIN"),
      entry("35.0", 13, "SOC"),
      entry("36.0", 13, "SOC"),
    ),
  )
  expect(reading != null, "delta: duplicate block yields a reading")
  if (reading == null) return
  expectClose(reading.cpu, 70.0, "delta: duplicate CPU keeps the hottest (70.0, not first 61.3)")
  expectClose(reading.gpu, 80.0, "delta: duplicate GPU keeps the hottest (80.0, not first 38.351)")
  expectClose(reading.battery, 33.5, "regression: duplicate BATTERY keeps first valid (33.5)")
  expectEquals(
    reading.board,
    listOf("SOC" to 35.0, "SKIN" to 30.0),
    "regression: duplicate board sensors keep first (SOC 35.0, SKIN 30.0) in fixed order",
  )
}

private fun rangeHardening() {
  val endpoints = parseDumpsysThermal(
    currentBlock(
      entry("-40.0", 0, "CPU"),
      entry("150.0", 1, "GPU"),
      entry("-5.5", 2, "BATTERY"),
      entry("0.0", 3, "SKIN"),
    ),
  )
  expect(endpoints != null, "regression: inclusive endpoints/zero yield a reading")
  if (endpoints != null) {
    expectClose(endpoints.cpu, -40.0, "regression: -40.0 accepted (inclusive endpoint)")
    expectClose(endpoints.gpu, 150.0, "regression: 150.0 accepted (inclusive endpoint)")
    expectClose(endpoints.battery, -5.5, "regression: genuine -5.5 kept")
    expectEquals(endpoints.board, listOf("SKIN" to 0.0), "regression: 0.0 accepted, not rejected as falsy")
  }

  expect(parseDumpsysThermal(currentBlock(entry("-40.1", 0, "CPU"))) == null,
    "delta: -40.1 rejected as implausible")
  expect(parseDumpsysThermal(currentBlock(entry("150.1", 1, "GPU"))) == null,
    "delta: 150.1 rejected as implausible")
  expect(parseDumpsysThermal(currentBlock(entry("999", 0, "CPU"))) == null,
    "delta: sentinel 999 rejected")
  expect(parseDumpsysThermal(currentBlock(entry("-273.15", 2, "BATTERY"))) == null,
    "delta: -273.15 rejected")

  val allRejected = parseDumpsysThermal(
    currentBlock(
      entry("999", 0, "CPU"),
      entry("200.0", 1, "GPU"),
      entry("-273.15", 2, "BATTERY"),
    ),
  )
  expect(allRejected == null, "delta: all rejected entries -> no reading")

  val sentinelThenValid = parseDumpsysThermal(
    currentBlock(
      entry("999", 0, "CPU"),
      entry("45.0", 0, "CPU"),
      entry("-273.15", 1, "GPU"),
      entry("40.0", 1, "GPU"),
      entry("999", 2, "BATTERY"),
      entry("33.5", 2, "BATTERY"),
      entry("999", 3, "SKIN"),
      entry("30.0", 3, "SKIN"),
    ),
  )
  expect(sentinelThenValid != null, "delta: sentinel+valid block yields a reading")
  if (sentinelThenValid != null) {
    expectClose(sentinelThenValid.cpu, 45.0, "delta: sentinel CPU does not mask later valid 45.0")
    expectClose(sentinelThenValid.gpu, 40.0, "delta: sentinel GPU does not mask later valid 40.0")
    expectClose(sentinelThenValid.battery, 33.5, "delta: sentinel BATTERY does not mask later valid 33.5")
    expectEquals(sentinelThenValid.board, listOf("SKIN" to 30.0),
      "delta: sentinel board sensor does not mask later valid 30.0")
  }

  val validThenSentinel = parseDumpsysThermal(
    currentBlock(
      entry("45.0", 0, "CPU"),
      entry("999", 0, "CPU"),
    ),
  )
  expectClose(validThenSentinel?.cpu, 45.0, "delta: later sentinel does not replace valid CPU 45.0")

  expect(parseDumpsysThermal(
    currentBlock(entry("NaN", 0, "CPU"), entry("Infinity", 1, "GPU"), entry("-Infinity", 2, "BATTERY")),
  ) == null, "regression: NaN/Infinity/-Infinity never parse as temperatures")

  val nanThenValid = parseDumpsysThermal(
    currentBlock(entry("NaN", 0, "CPU"), entry("45.0", 0, "CPU")),
  )
  expectClose(nanThenValid?.cpu, 45.0, "regression: NaN entry does not poison a later valid CPU entry")
}

private fun aliasesAndFallback() {
  val aliases = parseDumpsysThermal(
    currentBlock(
      entry("55.2", 14, " AP "),
      entry("36.4", 14, "bAt"),
    ),
  )
  expect(aliases != null, "delta: AP/BAT alias block yields a reading")
  if (aliases != null) {
    expectClose(aliases.cpu, 55.2, "delta: case-insensitive trimmed AP -> CPU despite mType 14")
    expectClose(aliases.battery, 36.4, "delta: case-insensitive trimmed BAT -> BATTERY despite mType 14")
    expect(aliases.gpu == null, "delta: AP/BAT do not invent a GPU")
  }

  expect(parseDumpsysThermal(currentBlock(entry("36.0", 2, "SUBBAT"))) == null,
    "delta: SUBBAT ignored before mType=2 fallback (no reading)")
  expect(parseDumpsysThermal(currentBlock(entry("36.0", 2, "subbat"))) == null,
    "delta: lowercase subbat also ignored before mType fallback")

  val subbatWithCpu = parseDumpsysThermal(
    currentBlock(
      entry("38.0", 0, "CPU"),
      entry("36.0", 2, "SUBBAT"),
    ),
  )
  expect(subbatWithCpu != null, "delta: SUBBAT block with CPU still yields a reading")
  if (subbatWithCpu != null) {
    expectClose(subbatWithCpu.cpu, 38.0, "delta: CPU survives alongside SUBBAT")
    expect(subbatWithCpu.battery == null, "delta: SUBBAT never becomes the main battery")
  }

  val typeFallback = parseDumpsysThermal(
    currentBlock(
      entry("51.0", 0, "UNKNOWN_CPU_NAME"),
      entry("47.0", 1, "UNKNOWN_GPU_NAME"),
      entry("34.0", 2, "UNKNOWN_BAT_NAME"),
    ),
  )
  expect(typeFallback != null, "regression: unknown names still use the mType table")
  if (typeFallback != null) {
    expectClose(typeFallback.cpu, 51.0, "regression: unknown name mType=0 -> CPU")
    expectClose(typeFallback.gpu, 47.0, "regression: unknown name mType=1 -> GPU")
    expectClose(typeFallback.battery, 34.0, "regression: unknown name mType=2 -> BATTERY")
  }
}

private fun currentBlockOnly() {
  val cachedOnly = """
    |Cached temperatures:
    |  ${entry("44.776", 0, "CPU")}
    |  ${entry("44.761", 1, "GPU")}
    |  ${entry("31.2", 2, "BATTERY")}
    |HAL Ready: true
  """.trimMargin()
  expect(parseDumpsysThermal(cachedOnly) == null, "regression: cached-only dump returns null")

  val live = parseDumpsysThermal(currentBlock(entry("42.0", 0, "CPU")))
  expectClose(live?.cpu, 42.0, "regression: current block beats cached CPU 99.0")
  expect(live?.battery == null, "regression: battery after stop header is ignored")

  val stopAtHeader = """
    |Current temperatures from HAL:
    |  ${entry("42.0", 0, "CPU")}
    |Current cooling devices from HAL:
    |  ${entry("120.0", 0, "CPU")}
    |  ${entry("77.0", 2, "BATTERY")}
  """.trimMargin()
  val stopped = parseDumpsysThermal(stopAtHeader)
  expectClose(stopped?.cpu, 42.0, "regression: parser stops at Current cooling devices header")
  expect(stopped?.battery == null, "regression: entries after stop header are not parsed")
}

private fun zoneAliases() {
  expectEquals(ZoneMap.categorize("soc-thermal"), ZoneCategory.CPU,
    "delta: soc-thermal (Kirin) -> CPU")
  expectEquals(ZoneMap.categorize("ap"), ZoneCategory.CPU,
    "delta: ap (Samsung) -> CPU")
  expectEquals(ZoneMap.categorize("  AP  "), ZoneCategory.CPU,
    "delta: ap alias is trimmed/case-insensitive")
  expectEquals(ZoneMap.categorize("gpu_tmu"), ZoneCategory.GPU,
    "delta: gpu_tmu (Samsung) -> GPU")
  expectEquals(ZoneMap.categorize("kgsl"), ZoneCategory.GPU,
    "delta: kgsl (Qualcomm) -> GPU")
  expectEquals(ZoneMap.categorize("subbat"), ZoneCategory.OTHER,
    "regression: subbat -> OTHER (never BATTERY)")
  expectEquals(ZoneMap.categorize("SUBBAT"), ZoneCategory.OTHER,
    "regression: SUBBAT -> OTHER after normalisation")
}

private fun zoneScoringAndOverrides() {
  // Existing heuristics must be unchanged.
  expectEquals(ZoneMap.categorize("cpu-0-0-usr"), ZoneCategory.CPU,
    "regression: cpu-* still CPU")
  expectEquals(ZoneMap.categorize("gpuss-0-usr"), ZoneCategory.GPU,
    "regression: gpuss-* still GPU")
  expectEquals(ZoneMap.categorize("mtktscpu"), ZoneCategory.CPU,
    "regression: mtktscpu still CPU")
  expectEquals(ZoneMap.categorize("mtktsbattery"), ZoneCategory.BATTERY,
    "regression: mtktsbattery still BATTERY")
  expectEquals(ZoneMap.categorize("gpu0"), ZoneCategory.GPU,
    "regression: gpu0 still GPU")
  expectEquals(ZoneMap.categorize("skin"), ZoneCategory.OTHER,
    "regression: unrelated zone still OTHER")

  // Exact aliases beat prefixes regardless of index.
  val exactBeatsPrefix = ZoneMap.pick(
    listOf(
      Zone(0, "cpu-0-0-usr", 45.0),
      Zone(5, "cpu", 50.0),
    ),
  )
  expectEquals(exactBeatsPrefix[ZoneCategory.CPU]?.index, 5,
    "regression: exact cpu alias beats cpu- prefix even at a higher index")

  val gpuAliasBeatsPrefix = ZoneMap.pick(
    listOf(
      Zone(0, "gpu-0", 45.0),
      Zone(5, "gpu_tmu", 50.0),
    ),
  )
  expectEquals(gpuAliasBeatsPrefix[ZoneCategory.GPU]?.index, 5,
    "delta: gpu_tmu exact alias outranks gpu- prefix at a higher index")

  val equalScore = ZoneMap.pick(
    listOf(
      Zone(3, "gpu0", 46.0),
      Zone(1, "gpu0", 45.0),
    ),
  )
  expectEquals(equalScore[ZoneCategory.GPU]?.index, 1,
    "regression: equal scores still tie-break on lowest index")

  // Manual overrides still outrank the new aliases.
  val overrideBeatsAlias = ZoneMap.pick(
    listOf(
      Zone(0, "cpu", 40.0),
      Zone(9, "skin", 50.0),
    ),
    mapOf("skin" to ZoneCategory.CPU),
  )
  expectEquals(overrideBeatsAlias[ZoneCategory.CPU]?.index, 9,
    "regression: manual override outranks the exact cpu alias")

  val overrideRemovesAlias = ZoneMap.pick(
    listOf(Zone(2, "ap", 50.0)),
    mapOf("ap" to ZoneCategory.OTHER),
  )
  expect(overrideRemovesAlias.isEmpty(), "regression: override to OTHER removes the ap alias")

  val overrideNormalised = ZoneMap.pick(
    listOf(Zone(4, "kgsl", 48.0)),
    mapOf("  KGSL " to ZoneCategory.BATTERY),
  )
  expectEquals(overrideNormalised[ZoneCategory.BATTERY]?.index, 4,
    "regression: override keys are trimmed/lowercased for the kgsl alias")

  expectEquals(ZoneMap.categorize(" ap ", mapOf("AP" to ZoneCategory.GPU)), ZoneCategory.GPU,
    "regression: categorize(override) outranks the ap alias")
}
