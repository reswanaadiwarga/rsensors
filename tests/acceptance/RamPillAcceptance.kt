// Independent acceptance checks for RAM-PILL-1.
//
// Expected values are taken from the task spec and hand calculation, not from
// the implementation's output. Compile with the core sources and run RamPillAcceptanceKt.
import dev.rsensors.android.core.DASH
import dev.rsensors.android.core.PillVisibility
import dev.rsensors.android.core.RamUsage
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.STALE_AFTER_MS
import dev.rsensors.android.core.SegmentKind
import dev.rsensors.android.core.VisibilityKind
import dev.rsensors.android.core.fmtRamPill
import dev.rsensors.android.core.stripSegments

private var failures = 0

private fun expect(condition: Boolean, message: String) {
  if (!condition) {
    failures++
    println("FAIL: $message")
  } else {
    println("ok: $message")
  }
}

private fun expectEquals(actual: Any?, expected: Any?, label: String) {
  expect(actual == expected, "$label: expected <$expected>, got <$actual>")
}

private fun texts(reading: Reading?, now: Long, visibility: PillVisibility? = null, ram: RamUsage? = null) =
  if (visibility == null) {
    stripSegments(reading, now, ram = ram)
  } else {
    stripSegments(reading, now, visibility, ram = ram)
  }.segments.map { it.text }

private val liveRam = RamUsage(usedBytes = 8_600_000_000L, totalBytes = 12_000_000_000L)
private val fullReading = Reading(1_000L, cpu = 41.0, gpu = 39.0, battery = 34.0, fps = 0.0)

fun main() {
  // Criterion 1: used-only one-decimal decimal GB, e.g. 8.6G.
  expectEquals(fmtRamPill(8_600_000_000L), "8.6G", "fmtRamPill 8.6 GB")
  expectEquals(fmtRamPill(0L), "0.0G", "fmtRamPill 0")
  expectEquals(fmtRamPill(12_000_000_000L), "12.0G", "fmtRamPill 12 GB")
  expectEquals(fmtRamPill(4_500_000_000L), "4.5G", "fmtRamPill 4.5 GB")
  expectEquals(fmtRamPill(null), DASH, "fmtRamPill null")

  // Criterion 1: RAM is the fourth segment, after CPU/GPU, BAT, FPS.
  val full = stripSegments(fullReading, 1_000L, PillVisibility(), ram = liveRam)
  expectEquals(
    full.segments.map { it.text },
    listOf("CPU/GPU", "41°", "BAT", "34°", "FPS", "0", "RAM", "8.6G"),
    "full strip order",
  )
  expectEquals(
    full.segments.map { it.kind },
    listOf(
      SegmentKind.LABEL, SegmentKind.OK,
      SegmentKind.LABEL, SegmentKind.OK,
      SegmentKind.LABEL, SegmentKind.HOT,
      SegmentKind.LABEL, SegmentKind.INFO,
    ),
    "full strip kinds",
  )

  // Criterion 2: the two-argument default stays CPU/GPU-only, ram=false.
  expectEquals(
    texts(Reading(1_000L, cpu = 80.0, gpu = 50.0, battery = 34.0, fps = 60.0), 1_002L),
    listOf("CPU/GPU", "80°"),
    "two-argument default is CPU/GPU only",
  )
  expectEquals(
    texts(Reading(1_000L, cpu = 80.0, gpu = 50.0), 1_000L, ram = liveRam),
    listOf("CPU/GPU", "80°"),
    "supplying ram without visibility keeps CPU/GPU-only default",
  )

  // Criterion 2: RAM toggles independently of the other chips.
  expectEquals(
    texts(fullReading, 1_000L, PillVisibility(cpuGpu = false, bat = false, fps = false, ram = true), liveRam),
    listOf("RAM", "8.6G"),
    "RAM can be the only shown kind",
  )
  expectEquals(
    texts(fullReading, 1_000L, PillVisibility(ram = false), liveRam),
    listOf("CPU/GPU", "41°", "BAT", "34°", "FPS", "0"),
    "RAM off removes only RAM",
  )

  // Criterion 2: defaults and last-chip rule include RAM.
  expect(PillVisibility().ram, "PillVisibility default has RAM on")
  val noRam = PillVisibility().toggle(VisibilityKind.RAM)
  expect(!noRam.ram && noRam.cpuGpu && noRam.bat && noRam.fps, "toggle RAM flips only RAM")
  val ramOnly = PillVisibility(cpuGpu = false, bat = false, fps = false, ram = true)
  expectEquals(ramOnly.toggle(VisibilityKind.RAM), ramOnly, "last chip (RAM) cannot be turned off")
  expectEquals(
    PillVisibility(cpuGpu = true, bat = false, fps = false, ram = true).toggle(VisibilityKind.CPUGPU),
    PillVisibility(cpuGpu = false, bat = false, fps = false, ram = true),
    "turning off CPU is allowed when RAM remains on",
  )
  expectEquals(
    PillVisibility(cpuGpu = false, bat = true, fps = false, ram = true).toggle(VisibilityKind.BAT),
    PillVisibility(cpuGpu = false, bat = false, fps = false, ram = true),
    "turning off BAT is allowed when RAM remains on",
  )
  expectEquals(
    PillVisibility(cpuGpu = false, bat = false, fps = true, ram = true).toggle(VisibilityKind.FPS),
    PillVisibility(cpuGpu = false, bat = false, fps = false, ram = true),
    "turning off FPS is allowed when RAM remains on",
  )
  expectEquals(
    PillVisibility(cpuGpu = false, bat = false, fps = false, ram = false).normalised(),
    PillVisibility(cpuGpu = true, bat = false, fps = false, ram = false),
    "all-off normalises to CPU/GPU only",
  )

  // Criterion 3: null reading + live RAM keeps RAM and does not dim.
  val noReadingLiveRam = stripSegments(null, 1_000L, PillVisibility(), ram = liveRam)
  expectEquals(
    noReadingLiveRam.segments.map { it.text },
    listOf("CPU/GPU", "—", "BAT", "—", "FPS", "—", "RAM", "8.6G"),
    "null reading shows live RAM",
  )
  expectEquals(noReadingLiveRam.segments.last().kind, SegmentKind.INFO, "live RAM kind is INFO")
  expect(!noReadingLiveRam.dim, "null reading with live RAM is not dim")

  // Criterion 3: null reading + live RAM but RAM chip off still dims.
  val noReadingRamOff = stripSegments(null, 1_000L, PillVisibility(ram = false), ram = liveRam)
  expectEquals(
    noReadingRamOff.segments.map { it.text },
    listOf("CPU/GPU", "—", "BAT", "—", "FPS", "—"),
    "null reading with RAM chip off omits RAM",
  )
  expect(noReadingRamOff.dim, "null reading with RAM chip off is dim")

  // Criterion 3: null RAM renders RAM — / UNKNOWN, on both reading paths.
  val nullRamNoReading = stripSegments(null, 1_000L, PillVisibility(), ram = null)
  expectEquals(
    nullRamNoReading.segments.takeLast(2).map { it.text },
    listOf("RAM", "—"),
    "null reading and null RAM renders RAM dash",
  )
  expectEquals(nullRamNoReading.segments.last().kind, SegmentKind.UNKNOWN, "null RAM kind is UNKNOWN")
  expect(nullRamNoReading.dim, "null reading and null RAM is dim")

  val nullRamWithReading = stripSegments(Reading(1_000L, cpu = 41.0), 1_000L, PillVisibility(), ram = null)
  expectEquals(
    nullRamWithReading.segments.takeLast(2).map { it.text },
    listOf("RAM", "—"),
    "reading with null RAM renders RAM dash",
  )
  expectEquals(nullRamWithReading.segments.last().kind, SegmentKind.UNKNOWN, "null RAM kind is UNKNOWN with reading")

  // Stale sensor reading still dims RAM with the strip.
  val stale = stripSegments(
    Reading(1_000L, cpu = 80.0),
    1_000L + STALE_AFTER_MS + 1,
    PillVisibility(bat = false, fps = false),
    ram = liveRam,
  )
  expect(stale.dim, "stale reading dims the strip including RAM")
  expectEquals(stale.segments.last().text, "8.6G", "stale strip keeps the RAM value")

  // All-off visibility still normalises to CPU/GPU only in the strip.
  expectEquals(
    texts(fullReading, 1_000L, PillVisibility(cpuGpu = false, bat = false, fps = false, ram = false), liveRam),
    listOf("CPU/GPU", "41°"),
    "all-off visibility renders CPU/GPU only",
  )

  if (failures != 0) {
    println("ACCEPTANCE FAILED: $failures check(s)")
    kotlin.system.exitProcess(1)
  }
  println("ACCEPTANCE PASSED")
}
