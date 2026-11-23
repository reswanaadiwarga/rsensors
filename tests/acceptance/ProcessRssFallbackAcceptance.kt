// Independent acceptance checks for T19b-3 RSS fallback and PSS/RSS label.
//
// Expected values are hand-derived from the task spec and from the checked-in
// `oom-trimmed.txt` fixture, never copied from the implementation's unit tests
// or from its output.
//
// Compile against `app/src/main/java/dev/rsensors/android/core/ProcessMemory.kt`
// (plus a `DASH` stub from Format.kt) and run
// ProcessRssFallbackAcceptanceKt from the repository root; it exits non-zero on
// any failure. Pass a path to a real `ps -A -o PID,RSS,NAME` capture as the one
// argument to check that device output with the same parser (T19b-3 device
// evidence); no argument runs the deterministic fixture checks.
//
// SPEC CLARIFICATION (T19b-3 delta): the original dispatch said both
// "rss >= 0 KiB" and "malformed/negative/zero/commas-in-RSS skipped". The
// implementer/docs now resolve that in favour of the explicit inequality: zero
// RSS is accepted deliberately because real kernel threads report RSS 0 (779
// such rows on the target). These checks pin that documented decision.

import dev.rsensors.android.core.AppMemory
import dev.rsensors.android.core.PROCESS_PAGE_SIZE
import dev.rsensors.android.core.ProcessMemory
import dev.rsensors.android.core.ProcessMetric
import dev.rsensors.android.core.ProcessSnapshot
import dev.rsensors.android.core.fmtProcessCount
import dev.rsensors.android.core.fmtProcessRam
import dev.rsensors.android.core.groupApps
import dev.rsensors.android.core.pageSlice
import dev.rsensors.android.core.parseMeminfoPss
import dev.rsensors.android.core.parseProcessSnapshot
import dev.rsensors.android.core.parsePsRss
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

/** One hand-derived `ps` row: every successful parse carries the RSS group. */
private fun rss(name: String, pid: Int, memoryKb: Long): ProcessMemory =
  ProcessMemory(name, pid, memoryKb, "RSS")

fun main(args: Array<String>) {
  if (args.isNotEmpty()) {
    realPsCapture(args[0])
    println("$checks checks, $failures failures")
    if (failures != 0) exitProcess(1)
    return
  }
  psHeaders()
  psColumnMapping()
  psNames()
  psRejectedRows()
  psZeroBoundary()
  psGroupAndAppsVisibility()
  snapshotRouting()
  metricLabels()
  existingPssBehaviorUnchanged()
  groupingSortingPagingUnchanged()
  formattingUnchanged()

  println("$checks checks, $failures failures")
  if (failures != 0) exitProcess(1)
}

/**
 * Checks one real `ps -A -o PID,RSS,NAME` capture with the same parser. The
 * exact row count is device state, so this asserts the spec invariants and
 * prints the counts for the report instead of pinning a golden number.
 */
private fun realPsCapture(path: String) {
  val file = File(path)
  if (!file.isFile) {
    expect(false, "real ps capture exists at ${file.absolutePath}")
    return
  }
  val raw = file.readText()
  val rows = parsePsRss(raw)
  val snapshot = parseProcessSnapshot(raw)
  val apps = groupApps(rows)
  println(
    "real ps: nonblank_lines=${raw.lineSequence().count { it.isNotBlank() }} " +
      "rows=${rows.size} zero_rss=${rows.count { it.memoryKb == 0L }} " +
      "negative_rss=${rows.count { it.memoryKb < 0L }} " +
      "names_with_space=${rows.count { it.name.contains(' ') }} " +
      "apps=${apps.size} max_kb=${rows.maxOfOrNull { it.memoryKb }} " +
      "metric=${snapshot.metric}",
  )
  expect(rows.isNotEmpty(), "real ps capture has at least one row")
  expect(rows.all { it.pid > 0 }, "every real ps row has a positive pid")
  expect(rows.all { it.memoryKb >= 0L }, "every real ps row has non-negative RSS")
  expect(rows.all { it.name.isNotEmpty() }, "every real ps row has a non-empty name")
  expect(rows.all { it.group == "RSS" }, "every real ps row carries the RSS group")
  expectEquals(snapshot.metric, ProcessMetric.RSS, "real ps capture routes to RSS")
  expectEquals(snapshot.rows.size, rows.size, "real ps snapshot keeps every parsed row")
  expect(apps.isNotEmpty(), "real ps capture produces Apps rows, not an empty filter")
}

// ---------------------------------------------------------------------------
// Header recognition: case-insensitive whole tokens, NAME/CMD/COMMAND.
// ---------------------------------------------------------------------------

private fun psHeaders() {
  expectEquals(
    parsePsRss(
      """
      PID   RSS NAME
        1  1234 init
      """.trimIndent(),
    ),
    listOf(rss("init", 1, 1_234L)),
    "PID RSS NAME header parses a row",
  )

  expectEquals(
    parsePsRss(
      """
      pid rss name
        2 2048 com.example.low
      """.trimIndent(),
    ),
    listOf(rss("com.example.low", 2, 2_048L)),
    "lower-case header tokens are accepted case-insensitively",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS CMD
       10 2048 com.example.one
      """.trimIndent(),
    ),
    listOf(rss("com.example.one", 10, 2_048L)),
    "CMD is accepted as the name column",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS COMMAND
       11 4096 com.example.two
      """.trimIndent(),
    ),
    listOf(rss("com.example.two", 11, 4_096L)),
    "COMMAND is accepted as the name column",
  )

  expectEquals(
    parsePsRss(
      """
      USER PID RSS NAMED
      u0_10 1 100 should-not-parse
      """.trimIndent(),
    ),
    emptyList<ProcessMemory>(),
    "a NAMED token is not a whole NAME token",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS
        1 100 no-name-column
      """.trimIndent(),
    ),
    emptyList<ProcessMemory>(),
    "a header without a name column yields no rows",
  )

  expectEquals(
    parsePsRss("1 100 init\n2 200 system"),
    emptyList<ProcessMemory>(),
    "rows without a header yield no rows",
  )

  expectEquals(parsePsRss(""), emptyList<ProcessMemory>(), "empty input yields no rows")
}

// ---------------------------------------------------------------------------
// Position mapping: reordered and extra columns, not a fixed layout.
// ---------------------------------------------------------------------------

private fun psColumnMapping() {
  expectEquals(
    parsePsRss(
      """
      NAME RSS PID
      com.example.notes 52345 4100
      """.trimIndent(),
    ),
    listOf(rss("com.example.notes", 4100, 52_345L)),
    "NAME RSS PID maps each column by header position",
  )

  expectEquals(
    parsePsRss(
      """
      USER PID RSS NAME
      u0_10 10 1024 com.example.one
      """.trimIndent(),
    ),
    listOf(rss("com.example.one", 10, 1_024L)),
    "an extra leading column is skipped via its header position",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS NAME USER
        1 100 com.example.a u0_10
        2 200 com.example.b u0_10
      """.trimIndent(),
    ),
    listOf(rss("com.example.a", 1, 100L), rss("com.example.b", 2, 200L)),
    "a non-trailing name column is read as one token when the row width matches",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS NAME USER
        1 100 com.example.a u0_10 extra
      """.trimIndent(),
    ),
    emptyList<ProcessMemory>(),
    "a non-trailing name column rejects a row wider than the header",
  )
}

// ---------------------------------------------------------------------------
// Name handling: a trailing name may contain spaces.
// ---------------------------------------------------------------------------

private fun psNames() {
  expectEquals(
    parsePsRss(
      """
      PID RSS NAME
      100 2048 com.example with space
      """.trimIndent(),
    ),
    listOf(rss("com.example with space", 100, 2_048L)),
    "a trailing NAME joins every remaining token with single spaces",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS COMMAND
      101 512 /system/bin/foo --flag value
      """.trimIndent(),
    ),
    listOf(rss("/system/bin/foo --flag value", 101, 512L)),
    "a trailing COMMAND keeps a command line with spaces",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS NAME
      102 2048 com.example.a:remote
      """.trimIndent(),
    ),
    listOf(rss("com.example.a:remote", 102, 2_048L)),
    "a colon helper name is preserved verbatim",
  )
}

// ---------------------------------------------------------------------------
// Rejected rows: malformed, non-positive pid, negative/non-integer/comma RSS.
// ---------------------------------------------------------------------------

private fun psRejectedRows() {
  val raw = """
    PID RSS NAME
      0 100 zero-pid
     -1 100 negative-pid
    abc 100 nonnumeric-pid
     12 -5 negative-rss
     13 100
     14 x nonnumeric-rss
     15 1,234 comma-rss
     16 100 valid
  """.trimIndent()

  expectEquals(
    parsePsRss(raw),
    listOf(rss("valid", 16, 100L)),
    "zero/negative pid, negative/non-integer/comma RSS and short rows are skipped",
  )

  expectEquals(
    parsePsRss(
      """
      PID RSS NAME
        1 100 first

        2 200 second
      """.trimIndent(),
    ),
    listOf(rss("first", 1, 100L), rss("second", 2, 200L)),
    "blank lines do not interrupt parsing",
  )
}

// ---------------------------------------------------------------------------
// Zero RSS boundary: accepted deliberately (documented in ANDROID.md and the
// unit test) because real `ps` output has 779 zero-RSS kernel threads.
// ---------------------------------------------------------------------------

private fun psZeroBoundary() {
  expectEquals(
    parsePsRss(
      """
      PID RSS NAME
       17 0 kernel-thread
      """.trimIndent(),
    ),
    listOf(rss("kernel-thread", 17, 0L)),
    "zero RSS is a valid non-negative value under 'rss >= 0 KiB'",
  )
}

// ---------------------------------------------------------------------------
// Groups and Apps visibility: RSS is never hidden as Native/System.
// ---------------------------------------------------------------------------

private fun psGroupAndAppsVisibility() {
  val rssRows = parsePsRss(
    """
    PID RSS NAME
      1 1000 system
      2 2000 com.example.a
      3 3000 vendor.native
    """.trimIndent(),
  )

  expectEquals(
    rssRows.map { it.group }.distinct(),
    listOf("RSS"),
    "every ps row carries the RSS group",
  )

  expectEquals(
    groupApps(rssRows),
    listOf(
      AppMemory("vendor.native", 3_000L, 1),
      AppMemory("com.example.a", 2_000L, 1),
      AppMemory("system", 1_000L, 1),
    ),
    "Apps keeps RSS rows named system/vendor.native because group RSS is not hidden",
  )

  val pssRows = listOf(
    ProcessMemory("system", 1, 1_000L, "System"),
    ProcessMemory("com.example.a", 2, 2_000L, "Cached"),
    ProcessMemory("vendor.native", 3, 3_000L, "Native"),
  )
  expectEquals(
    groupApps(pssRows),
    listOf(AppMemory("com.example.a", 2_000L, 1)),
    "Apps still hides Native/System on the PSS route",
  )

  val helperRows = listOf(
    rss("com.example.a", 10, 1_000L),
    rss("com.example.a:remote", 11, 500L),
  )
  expectEquals(
    groupApps(helperRows),
    listOf(AppMemory("com.example.a", 1_500L, 2)),
    "RSS helper processes still collapse into their base package",
  )
}

// ---------------------------------------------------------------------------
// Snapshot routing: PSS wins, then RSS, then an empty PSS snapshot.
// ---------------------------------------------------------------------------

private fun snapshotRouting() {
  val fixture = File("app/src/test/resources/fixtures/meminfo/oom-trimmed.txt")
  if (!fixture.isFile) {
    expect(false, "fixture exists at ${fixture.absolutePath}")
    return
  }

  val pssSnapshot = parseProcessSnapshot(fixture.readText())
  expectEquals(pssSnapshot.metric, ProcessMetric.PSS, "fixture dump routes to the PSS metric")
  expectEquals(pssSnapshot.rows.size, 9, "fixture dump keeps its nine PSS rows")

  expectEquals(
    parseProcessSnapshot(
      """
      PID RSS NAME
        1 1234 init
      """.trimIndent(),
    ),
    ProcessSnapshot(listOf(rss("init", 1, 1_234L)), ProcessMetric.RSS),
    "a ps dump routes to the RSS metric",
  )

  expectEquals(
    parseProcessSnapshot(
      """
      PID RSS NAME
        1 100 init
      Total PSS by OOM adjustment:
          9,000K: Cached
            2,000K: com.example.app (pid 42)
      """.trimIndent(),
    ),
    ProcessSnapshot(
      listOf(ProcessMemory("com.example.app", 42, 2_000L, "Cached")),
      ProcessMetric.PSS,
    ),
    "PSS rows win when both shapes are present",
  )

  expectEquals(
    parseProcessSnapshot(
      """
      Total PSS by OOM adjustment:
      PID RSS NAME
        1 100 init
      """.trimIndent(),
    ).metric,
    ProcessMetric.RSS,
    "an empty PSS section falls through to the ps rows",
  )

  expectEquals(
    parseProcessSnapshot("not a memory dump"),
    ProcessSnapshot(emptyList(), ProcessMetric.PSS),
    "unparseable input is an empty PSS snapshot",
  )
  expectEquals(
    parseProcessSnapshot(""),
    ProcessSnapshot(emptyList(), ProcessMetric.PSS),
    "empty input is an empty PSS snapshot",
  )
  expectEquals(
    parseProcessSnapshot(
      """
      PID RSS NAME
        0 100 zero-pid
        1 1,000 comma-rss
      """.trimIndent(),
    ),
    ProcessSnapshot(emptyList(), ProcessMetric.PSS),
    "a ps header whose rows all fail yields the empty PSS snapshot",
  )
  expectEquals(
    parseProcessSnapshot(
      """
      Total RSS by OOM adjustment:
        812,000K: Native
          812,000K: vendor.example.npu (pid 512)
      """.trimIndent(),
    ),
    ProcessSnapshot(emptyList(), ProcessMetric.PSS),
    "a meminfo dump with only an RSS section is not mistaken for ps output",
  )
}

private fun metricLabels() {
  expectEquals(ProcessMetric.PSS.label, "PSS", "PSS metric label")
  expectEquals(ProcessMetric.RSS.label, "RSS", "RSS metric label")
}

// ---------------------------------------------------------------------------
// The PSS route itself must be byte-for-byte the pre-T19b-3 behavior.
// ---------------------------------------------------------------------------

private fun existingPssBehaviorUnchanged() {
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
  expectEquals(parseMeminfoPss(fixture.readText()), expected, "PSS fixture parse is unchanged")
  expectEquals(
    parseMeminfoPss(
      """
      Total PSS by OOM adjustment:
          9,000K: Cached
            1,000K: com.example.ok (pid 10)
      Total SwapPSS by OOM adjustment:
            5,000K: com.example.ghost (pid 99)
      """.trimIndent(),
    ),
    listOf(ProcessMemory("com.example.ok", 10, 1_000L, "Cached")),
    "PSS section boundaries are unchanged",
  )
}

private fun groupingSortingPagingUnchanged() {
  val fixture = File("app/src/test/resources/fixtures/meminfo/oom-trimmed.txt")
  if (!fixture.isFile) {
    expect(false, "fixture exists at ${fixture.absolutePath}")
    return
  }
  val parsed = parseMeminfoPss(fixture.readText())

  expectEquals(
    sortProcesses(parsed).map { it.name },
    listOf(
      "vendor.example.npu",
      "system",
      "com.example.launcher",
      "com.example.notes",
      "dev.rsensors.android",
      "com.example.photos",
      "com.example.notes:remote",
      "com.example.music",
      "com.example.maps",
    ),
    "sorting by descending memory then name then pid is unchanged",
  )

  expectEquals(
    groupApps(parsed),
    listOf(
      AppMemory("com.example.launcher", 96_000L, 1),
      AppMemory("com.example.notes", 64_690L, 2),
      AppMemory("dev.rsensors.android", 45_678L, 1),
      AppMemory("com.example.photos", 21_000L, 1),
      AppMemory("com.example.music", 9_500L, 1),
      AppMemory("com.example.maps", 7_321L, 1),
    ),
    "Apps grouping, Native/System exclusion and ordering are unchanged",
  )

  val first = pageSlice(parsed, 0)
  expectEquals(first.items.size, PROCESS_PAGE_SIZE, "first page holds eight rows")
  expectEquals(first.firstNumber, 1, "first page starts at row 1")
  expectEquals(first.lastNumber, 8, "first page ends at row 8")
  expectEquals(first.count, 2, "nine rows produce two pages")
  expectEquals(first.total, 9, "page slice total stays nine")

  val last = pageSlice(parsed, 1)
  expectEquals(last.items.size, 1, "last page holds the ninth row")
  expectEquals(last.firstNumber, 9, "last page starts at row 9")
  expectEquals(last.lastNumber, 9, "last page ends at row 9")

  val empty = pageSlice(emptyList<ProcessMemory>(), 0)
  expectEquals(empty.items, emptyList<ProcessMemory>(), "empty page has no rows")
  expectEquals(empty.firstNumber, 0, "empty page first number is 0")
  expectEquals(empty.lastNumber, 0, "empty page last number is 0")
  expectEquals(empty.count, 0, "empty page count is 0")
}

private fun formattingUnchanged() {
  expectEquals(fmtProcessRam(810_234L), "830M", "810234 KiB formats as 830M")
  expectEquals(fmtProcessRam(52_345L), "54M", "52345 KiB formats as 54M")
  expectEquals(fmtProcessRam(8_192L), "8.4M", "8192 KiB formats as 8.4M")
  expectEquals(fmtProcessRam(1_000_000L), "1.0G", "1000000 KiB formats as 1.0G")
  expectEquals(fmtProcessRam(-1L), "\u2014", "negative memory formats as DASH")
  expectEquals(fmtProcessCount(0, apps = true), "0 apps", "zero apps noun")
  expectEquals(fmtProcessCount(1, apps = true), "1 app", "one app noun")
  expectEquals(fmtProcessCount(2, apps = false), "2 processes", "process noun")
  expectEquals(fmtProcessCount(1, apps = false), "1 process", "one process noun")
}
