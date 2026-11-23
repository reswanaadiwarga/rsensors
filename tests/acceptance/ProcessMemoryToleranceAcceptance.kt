// Independent acceptance checks for T19b-2 meminfo parser tolerance.
//
// Expected values are hand-derived from the task spec and from the checked-in
// `oom-trimmed.txt` fixture, never copied from the implementation's unit tests
// or from its output.
//
// Compile against `app/src/main/java/dev/rsensors/android/core/ProcessMemory.kt`
// and run ProcessMemoryToleranceAcceptanceKt from the repository root; it exits
// non-zero on any failure.

import dev.rsensors.android.core.AppMemory
import dev.rsensors.android.core.PROCESS_PAGE_SIZE
import dev.rsensors.android.core.ProcessMemory
import dev.rsensors.android.core.groupApps
import dev.rsensors.android.core.pageSlice
import dev.rsensors.android.core.parseMeminfoPss
import dev.rsensors.android.core.sortProcesses
import java.io.File
import kotlin.system.exitProcess

private var failures = 0
private var checks = 0

private fun expect(condition: Boolean, message: String) {
  checks++
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

private fun parse(lines: List<String>): List<ProcessMemory> =
  parseMeminfoPss(lines.joinToString("\n"))

fun main() {
  existingFixtureBehavior()
  pssSectionBoundaries()
  workProfileRows()
  unitVariants()
  pidSuffixForms()
  keywordCase()
  unknownHeadings()
  malformedPidRows()
  namesAndHeadingsEdgeCases()
  groupingSortingPagingUnchanged()

  println("$checks checks, $failures failures")
  if (failures != 0) exitProcess(1)
}

// ---------------------------------------------------------------------------
// Existing behavior that the tolerance change must not alter.
// ---------------------------------------------------------------------------

private fun existingFixtureBehavior() {
  val fixture = File("app/src/test/resources/fixtures/meminfo/oom-trimmed.txt")
  if (!fixture.isFile) {
    expect(false, "fixture exists at ${fixture.absolutePath}")
    return
  }
  val expected = listOf(
    ProcessMemory("vendor.example.npu", 512, 810_234L, "Native"),
    ProcessMemory("system", 1000, 500_123L, "System"),
    ProcessMemory("com.example.launcher", 1500, 96_000L, "Persistent"),
    ProcessMemory("dev.rsensors.android", 1600, 45_678L, "Persistent"),
    ProcessMemory("com.example.notes", 4100, 52_345L, "Cached"),
    ProcessMemory("com.example.notes:remote", 4101, 12_345L, "Cached"),
    ProcessMemory("com.example.photos", 4200, 21_000L, "Cached"),
    ProcessMemory("com.example.music", 4300, 9_500L, "Cached"),
    ProcessMemory("com.example.maps", 4400, 7_321L, "Cached"),
  )
  expectEquals(
    parseMeminfoPss(fixture.readText()),
    expected,
    "fixture: PSS rows only, file order kept, malformed/blank rows dropped",
  )
}

private fun pssSectionBoundaries() {
  expectEquals(
    parseMeminfoPss(
      """
      Applications Memory Usage (in Kilobytes):
      Total RSS by OOM adjustment:
          812,000K: Native
              812,000K: vendor.example.npu (pid 512)
          """.trimIndent(),
    ),
    emptyList<ProcessMemory>(),
    "RSS-only dump without the PSS header yields no rows",
  )

  expectEquals(
    parseMeminfoPss(""),
    emptyList<ProcessMemory>(),
    "an empty dump yields no rows",
  )

  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
        9,000K: Cached
            1,000K: com.example.ok (pid 10)
      Total SwapPSS by OOM adjustment:
          999,999K: Cached
              1,234K: com.example.ghost (pid 7777)
      """.trimIndent().lines(),
    ),
    listOf(ProcessMemory("com.example.ok", 10, 1_000L, "Cached")),
    "parsing stops at the next Total section; SwapPSS rows never leak",
  )
}

// ---------------------------------------------------------------------------
// T19b-2 tolerance requirements.
// ---------------------------------------------------------------------------

private fun workProfileRows() {
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached
             52,345kB: com.example.notes (pid 4100) (user 10)
             12,345K: com.example.notes:remote (pid 4101) (user 10)
             21,000K: com.example.photos (pid 4200 / activities) (user 10)
             11,000K: com.example.music (pid 4300/activities) (user 10)
             10,000K: com.example.maps (pid 4400 :remote) (user 10)
      """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example.notes", 4100, 52_345L, "Cached"),
      ProcessMemory("com.example.notes:remote", 4101, 12_345L, "Cached"),
      ProcessMemory("com.example.photos", 4200, 21_000L, "Cached"),
      ProcessMemory("com.example.music", 4300, 11_000L, "Cached"),
      ProcessMemory("com.example.maps", 4400, 10_000L, "Cached"),
    ),
    "work-profile (user N) rows parse with and without / activities or :remote",
  )
}

private fun unitVariants() {
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          1,300,000kB: Cached
             52,345KB: com.example.big (pid 4100)
          120,000Kb: Persistent Service
              8,000kb: com.example.small (pid 4101)
              9,000K: com.example.plain (pid 4102)
          7,000kB: Another-Group
              6,000Kb: com.example.hyphen (pid 4103)
      """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example.big", 4100, 52_345L, "Cached"),
      ProcessMemory("com.example.small", 4101, 8_000L, "Persistent Service"),
      ProcessMemory("com.example.plain", 4102, 9_000L, "Persistent Service"),
      ProcessMemory("com.example.hyphen", 4103, 6_000L, "Another-Group"),
    ),
    "kB/KB/Kb/kb parse in process rows and group-total lines",
  )
}

private fun pidSuffixForms() {
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached
             52,345K: com.example.a (pid 4100 / activities)
             52,345K: com.example.b (pid 4101/activities)
             52,345K: com.example.c (pid 4102:remote)
             52,345K: com.example.d (pid 4103 :remote)
             52,345K: com.example.e (pid 4104)
      """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example.a", 4100, 52_345L, "Cached"),
      ProcessMemory("com.example.b", 4101, 52_345L, "Cached"),
      ProcessMemory("com.example.c", 4102, 52_345L, "Cached"),
      ProcessMemory("com.example.d", 4103, 52_345L, "Cached"),
      ProcessMemory("com.example.e", 4104, 52_345L, "Cached"),
    ),
    "pid suffix / activities and :remote parse with and without spaces",
  )
}

private fun keywordCase() {
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached
              1,000K: com.example.a (PID 10) (User 10)
              2,000K: com.example.b (Pid 11) (USER 12)
      """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example.a", 10, 1_000L, "Cached"),
      ProcessMemory("com.example.b", 11, 2_000L, "Cached"),
    ),
    "pid/user keywords are matched case-insensitively",
  )
}

private fun unknownHeadings() {
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached
              1,000K: com.example.before (pid 10)
          7,000K: Cached+isolated
              2,000K: com.example.after (pid 11)
          6,000K: Native
              3,000K: com.example.native (pid 12)
          7,000K: com.example.app
              4,000K: com.example.dot (pid 13)
          8,000K: Cached (isolated)
              5,000K: com.example.paren (pid 14)
          """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example.before", 10, 1_000L, "Cached"),
      ProcessMemory("com.example.after", 11, 2_000L, "Unknown"),
      ProcessMemory("com.example.native", 12, 3_000L, "Native"),
      ProcessMemory("com.example.dot", 13, 4_000L, "Unknown"),
      ProcessMemory("com.example.paren", 14, 5_000L, "Unknown"),
    ),
    "non-clean captions become Unknown and never inherit the previous group",
  )

  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Vendor / Services
              1,000K: com.example.a (pid 10)
          8,000K: Vendor-Services
              2,000K: com.example.b (pid 11)
          7,000K: Foreground Service
              3,000K: com.example.c (pid 12)
      """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example.a", 10, 1_000L, "Vendor / Services"),
      ProcessMemory("com.example.b", 11, 2_000L, "Vendor-Services"),
      ProcessMemory("com.example.c", 12, 3_000L, "Foreground Service"),
    ),
    "clean captions with spaces, slash and hyphen are kept verbatim",
  )
}

private fun malformedPidRows() {
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Persistent Service
              1,000K: com.example.before (pid 10)
              3,000K: com.example.truncated (pid 13
              4,000K: com.example.broken (pid notanumber)
              5,000K: com.example.unclosed (PID 14 / activities
          7,000K: Cached (pid
              2,000K: com.example.after (pid 11)
      """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example.before", 10, 1_000L, "Persistent Service"),
      ProcessMemory("com.example.after", 11, 2_000L, "Persistent Service"),
    ),
    "malformed (pid rows are ignored and leave the current group unchanged",
  )

  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached
              2,000K:  (pid 11)
              12x00K: com.example.bad (pid 12)
              3,000K: com.example.empty (pid 13
              garbage without a pid
              1,000K: com.example.ok (pid 14)
      """.trimIndent().lines(),
    ),
    listOf(ProcessMemory("com.example.ok", 14, 1_000L, "Cached")),
    "blank names and malformed values are dropped while the group survives",
  )
}

private fun namesAndHeadingsEdgeCases() {
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached
              1,000K: com.example with space (pid 10)
              2,000K: com.example.foo (bar) (pid 11)
      """.trimIndent().lines(),
    ),
    listOf(
      ProcessMemory("com.example with space", 10, 1_000L, "Cached"),
      ProcessMemory("com.example.foo (bar)", 11, 2_000L, "Cached"),
    ),
    "process names may contain spaces and parenthesised text before the pid",
  )

  // A blank line between a heading and its rows must not clear the group.
  expectEquals(
    parse(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached

              1,000K: com.example.a (pid 10)
      """.trimIndent().lines(),
    ),
    listOf(ProcessMemory("com.example.a", 10, 1_000L, "Cached")),
    "blank lines do not reset the current group",
  )
}

// ---------------------------------------------------------------------------
// Grouping, sorting and paging are untouched.
// ---------------------------------------------------------------------------

private fun groupingSortingPagingUnchanged() {
  val rows = listOf(
    ProcessMemory("native-helper", 1, 900L, "Native"),
    ProcessMemory("com.example.b:remote", 2, 300L, "Cached"),
    ProcessMemory("com.example.a", 3, 500L, "Cached"),
    ProcessMemory("com.example.a", 4, 500L, "Cached"),
    ProcessMemory("com.example.c", 5, 100L, "Cached"),
  )

  expectEquals(
    sortProcesses(rows).map { "${it.name}/${it.pid}" },
    listOf("native-helper/1", "com.example.a/3", "com.example.a/4", "com.example.b:remote/2", "com.example.c/5"),
    "sortProcesses orders by PSS, then name, then pid",
  )

  expectEquals(
    groupApps(rows),
    listOf(
      AppMemory("com.example.a", 1_000L, 2),
      AppMemory("com.example.b", 300L, 1),
      AppMemory("com.example.c", 100L, 1),
    ),
    "groupApps sums package/remote rows, hides Native/System and sorts by total",
  )

  expectEquals(PROCESS_PAGE_SIZE, 8, "default page size stays eight")

  val last = pageSlice((1..20).toList(), 2)
  expectEquals(last.items, (17..20).toList(), "last page items")
  expectEquals(last.index, 2, "last page index")
  expectEquals(last.count, 3, "last page count")
  expectEquals(last.firstNumber, 17, "last page first number")
  expectEquals(last.lastNumber, 20, "last page last number")

  val empty = pageSlice(emptyList<Int>(), 3)
  expectEquals(empty.total, 0, "empty page total")
  expectEquals(empty.firstNumber, 0, "empty page first number")
  expectEquals(empty.lastNumber, 0, "empty page last number")
}
