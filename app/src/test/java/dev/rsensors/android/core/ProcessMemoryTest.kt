package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Process-memory page logic for T17 (ANDROID.md §3, §7, §9): parser, grouping,
 * sorting, paging and formatting.
 *
 * Expected values are hand-derived from the trimmed `dumpsys meminfo --oom`
 * fixture and from the task spec's examples, never copied from the parser's
 * output.
 */
class ProcessMemoryTest {

  private fun fixture(path: String): String {
    val stream = javaClass.getResourceAsStream(path)
    assertNotNull("fixture $path is missing", stream)
    return stream!!.bufferedReader().use { it.readText() }
  }

  private val oomDump: String by lazy { fixture("/fixtures/meminfo/oom-trimmed.txt") }

  @Test
  fun `parses only the PSS section with the current OOM group`() {
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

    assertEquals(expected, parseMeminfoPss(oomDump))
  }

  @Test
  fun `RSS rows never appear in the parsed result`() {
    val names = parseMeminfoPss(oomDump).map { it.name }

    assertFalse("com.example.notes RSS row leaked", names.count { it == "com.example.notes" } > 1)
    assertFalse("RSS-only remote row leaked", names.count { it == "com.example.notes:remote" } > 1)
    assertEquals(9, names.size)
  }

  @Test
  fun `comma thousands and activities suffixes parse`() {
    val raw = """
      Total PSS by OOM adjustment:
      1,300,000K: Cached
        1,234,567K: com.example.big (pid 77 / activities)
            4,200K: com.example.plain (pid 78)
    """.trimIndent()

    assertEquals(
      listOf(
        ProcessMemory("com.example.big", 77, 1_234_567L, "Cached"),
        ProcessMemory("com.example.plain", 78, 4_200L, "Cached"),
      ),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `malformed lines and blank names are ignored`() {
    val raw = """
      Total PSS by OOM adjustment:
          9,000K: Cached
            1,000K: com.example.ok (pid 10)
            2,000K:  (pid 11)
            12x00K: com.example.bad (pid 12)
            3,000K: com.example.truncated (pid 13
            garbage without a pid
    """.trimIndent()

    assertEquals(
      listOf(ProcessMemory("com.example.ok", 10, 1_000L, "Cached")),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `work profile user suffix parses with and without an activities suffix`() {
    val raw = """
      Total PSS by OOM adjustment:
          9,000K: Cached
             52,345kB: com.example.notes (pid 4100) (user 10)
             12,345K: com.example.notes:remote (pid 4101) (user 10)
             21,000K: com.example.photos (pid 4200 / activities) (user 10)
    """.trimIndent()

    assertEquals(
      listOf(
        ProcessMemory("com.example.notes", 4100, 52_345L, "Cached"),
        ProcessMemory("com.example.notes:remote", 4101, 12_345L, "Cached"),
        ProcessMemory("com.example.photos", 4200, 21_000L, "Cached"),
      ),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `kB and KB unit variants parse in process rows and group totals`() {
    val raw = """
      Total PSS by OOM adjustment:
          1,300,000kB: Cached
             52,345KB: com.example.big (pid 4100)
          120,000Kb: Persistent Service
              8,000kb: com.example.small (pid 4101)
    """.trimIndent()

    assertEquals(
      listOf(
        ProcessMemory("com.example.big", 4100, 52_345L, "Cached"),
        ProcessMemory("com.example.small", 4101, 8_000L, "Persistent Service"),
      ),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `pid suffix forms parse with and without spaces`() {
    val raw = """
      Total PSS by OOM adjustment:
          9,000K: Cached
             52,345K: com.example.a (pid 4100 / activities)
             52,345K: com.example.b (pid 4101/activities)
             52,345K: com.example.c (pid 4102:remote)
             52,345K: com.example.d (pid 4103 :remote)
    """.trimIndent()

    assertEquals(
      listOf(
        ProcessMemory("com.example.a", 4100, 52_345L, "Cached"),
        ProcessMemory("com.example.b", 4101, 52_345L, "Cached"),
        ProcessMemory("com.example.c", 4102, 52_345L, "Cached"),
        ProcessMemory("com.example.d", 4103, 52_345L, "Cached"),
      ),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `pid and user keywords are case-insensitive`() {
    val raw = """
      Total PSS by OOM adjustment:
          9,000K: Cached
             1,000K: com.example.a (PID 10) (User 10)
    """.trimIndent()

    assertEquals(
      listOf(ProcessMemory("com.example.a", 10, 1_000L, "Cached")),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `an unclean heading resets the group to Unknown`() {
    val raw = """
      Total PSS by OOM adjustment:
          9,000K: Cached
              1,000K: com.example.before (pid 10)
          7,000K: Cached+isolated
              2,000K: com.example.after (pid 11)
    """.trimIndent()

    assertEquals(
      listOf(
        ProcessMemory("com.example.before", 10, 1_000L, "Cached"),
        ProcessMemory("com.example.after", 11, 2_000L, "Unknown"),
      ),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `a malformed pid row leaves the group unchanged`() {
    val raw = """
      Total PSS by OOM adjustment:
          9,000K: Persistent Service
              1,000K: com.example.before (pid 10)
              3,000K: com.example.truncated (pid 13
              2,000K: com.example.after (pid 11)
    """.trimIndent()

    assertEquals(
      listOf(
        ProcessMemory("com.example.before", 10, 1_000L, "Persistent Service"),
        ProcessMemory("com.example.after", 11, 2_000L, "Persistent Service"),
      ),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `a missing PSS header returns no rows`() {
    val raw = """
      Total RSS by OOM adjustment:
          9,000K: Cached
            1,000K: com.example.ok (pid 10)
    """.trimIndent()

    assertEquals(emptyList<ProcessMemory>(), parseMeminfoPss(raw))
    assertEquals(emptyList<ProcessMemory>(), parseMeminfoPss(""))
  }

  @Test
  fun `parsing stops at the next Total section`() {
    val raw = """
      Total PSS by OOM adjustment:
          9,000K: Cached
            1,000K: com.example.ok (pid 10)
      Total SwapPSS by OOM adjustment:
            5,000K: com.example.ghost (pid 99)
    """.trimIndent()

    assertEquals(
      listOf(ProcessMemory("com.example.ok", 10, 1_000L, "Cached")),
      parseMeminfoPss(raw),
    )
  }

  @Test
  fun `sortProcesses orders by PSS then name then pid without mutating input`() {
    val parsed = parseMeminfoPss(oomDump)
    val originalOrder = parsed.map { it.name }

    val sorted = sortProcesses(parsed)

    assertEquals(
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
      sorted.map { it.name },
    )
    assertEquals(originalOrder, parsed.map { it.name })
  }

  @Test
  fun `sortProcesses breaks ties by name and pid`() {
    val rows = listOf(
      ProcessMemory("z.example", 2, 100L, "Cached"),
      ProcessMemory("a.example", 9, 100L, "Cached"),
      ProcessMemory("a.example", 1, 100L, "Cached"),
      ProcessMemory("m.example", 5, 300L, "Cached"),
    )

    assertEquals(
      listOf(
        ProcessMemory("m.example", 5, 300L, "Cached"),
        ProcessMemory("a.example", 1, 100L, "Cached"),
        ProcessMemory("a.example", 9, 100L, "Cached"),
        ProcessMemory("z.example", 2, 100L, "Cached"),
      ),
      sortProcesses(rows),
    )
  }

  @Test
  fun `groupApps sums package and remote processes and hides Native and System`() {
    val expected = listOf(
      AppMemory("com.example.launcher", 96_000L, 1),
      AppMemory("com.example.notes", 64_690L, 2),
      AppMemory("dev.rsensors.android", 45_678L, 1),
      AppMemory("com.example.photos", 21_000L, 1),
      AppMemory("com.example.music", 9_500L, 1),
      AppMemory("com.example.maps", 7_321L, 1),
    )

    assertEquals(expected, groupApps(parseMeminfoPss(oomDump)))
  }

  @Test
  fun `groupApps exclusion is case-insensitive`() {
    val rows = listOf(
      ProcessMemory("com.example.a", 1, 10L, "native"),
      ProcessMemory("com.example.b", 2, 20L, "SYSTEM"),
      ProcessMemory("com.example.c", 3, 30L, "Native"),
      ProcessMemory("com.example.d", 4, 40L, "System"),
      ProcessMemory("com.example.e", 5, 50L, "Cached"),
    )

    assertEquals(listOf(AppMemory("com.example.e", 50L, 1)), groupApps(rows))
  }

  @Test
  fun `groupApps sorts equal totals by package name`() {
    val rows = listOf(
      ProcessMemory("com.example.b:remote", 2, 70L, "Cached"),
      ProcessMemory("com.example.a", 1, 70L, "Cached"),
    )

    assertEquals(
      listOf(
        AppMemory("com.example.a", 70L, 1),
        AppMemory("com.example.b", 70L, 1),
      ),
      groupApps(rows),
    )
  }

  @Test
  fun `paging an empty list yields the empty page`() {
    val page = pageSlice(emptyList<Int>(), 3)

    assertEquals(0, page.index)
    assertEquals(0, page.count)
    assertEquals(0, page.total)
    assertEquals(0, page.firstNumber)
    assertEquals(0, page.lastNumber)
    assertEquals(emptyList<Int>(), page.items)
  }

  @Test
  fun `paging an exact single page`() {
    val page = pageSlice((1..8).toList(), 0)

    assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), page.items)
    assertEquals(0, page.index)
    assertEquals(1, page.count)
    assertEquals(8, page.total)
    assertEquals(1, page.firstNumber)
    assertEquals(8, page.lastNumber)
  }

  @Test
  fun `paging a middle page`() {
    val page = pageSlice((1..20).toList(), 1)

    assertEquals((9..16).toList(), page.items)
    assertEquals(1, page.index)
    assertEquals(3, page.count)
    assertEquals(20, page.total)
    assertEquals(9, page.firstNumber)
    assertEquals(16, page.lastNumber)
  }

  @Test
  fun `paging the last page`() {
    val page = pageSlice((1..20).toList(), 2)

    assertEquals((17..20).toList(), page.items)
    assertEquals(2, page.index)
    assertEquals(3, page.count)
    assertEquals(20, page.total)
    assertEquals(17, page.firstNumber)
    assertEquals(20, page.lastNumber)
  }

  @Test
  fun `out of range page indexes clamp`() {
    val last = pageSlice((1..20).toList(), 99)
    assertEquals(2, last.index)
    assertEquals((17..20).toList(), last.items)

    val first = pageSlice((1..20).toList(), -5)
    assertEquals(0, first.index)
    assertEquals((1..8).toList(), first.items)
    assertEquals(1, first.firstNumber)
    assertEquals(8, first.lastNumber)
  }

  @Test
  fun `a non-positive page size is coerced to one`() {
    val zero = pageSlice(listOf("a", "b", "c"), 1, 0)
    assertEquals(listOf("b"), zero.items)
    assertEquals(1, zero.index)
    assertEquals(3, zero.count)
    assertEquals(3, zero.total)
    assertEquals(2, zero.firstNumber)
    assertEquals(2, zero.lastNumber)

    val negative = pageSlice(listOf("a", "b", "c"), 0, -7)
    assertEquals(listOf("a"), negative.items)
    assertEquals(3, negative.count)
    assertEquals(1, negative.firstNumber)
    assertEquals(1, negative.lastNumber)
  }

  @Test
  fun `the default page size is eight and the spec 42-item example matches`() {
    assertEquals(8, PROCESS_PAGE_SIZE)

    val page = pageSlice((1..42).toList(), 5)
    assertEquals(listOf(41, 42), page.items)
    assertEquals(5, page.index)
    assertEquals(6, page.count)
    assertEquals(42, page.total)
    assertEquals(41, page.firstNumber)
    assertEquals(42, page.lastNumber)
  }

  @Test
  fun `fmtProcessRam matches the decimal unit examples`() {
    assertEquals(DASH, fmtProcessRam(-1L))
    assertEquals("0.0M", fmtProcessRam(0L))
    assertEquals("0.5M", fmtProcessRam(512L))
    assertEquals("735M", fmtProcessRam(718_000L))
    assertEquals("1.2G", fmtProcessRam(1_200_000L))
  }

  @Test
  fun `fmtProcessRam switches from one decimal M to whole M at ten million bytes`() {
    // 9,765 KiB * 1024 = 9,999,360 B; 9,766 KiB * 1024 = 10,000,384 B.
    assertEquals("10.0M", fmtProcessRam(9_765L))
    assertEquals("10M", fmtProcessRam(9_766L))
  }

  @Test
  fun `fmtProcessCount uses the singular only for one`() {
    assertEquals("0 apps", fmtProcessCount(0, apps = true))
    assertEquals("1 app", fmtProcessCount(1, apps = true))
    assertEquals("42 apps", fmtProcessCount(42, apps = true))
    assertEquals("0 processes", fmtProcessCount(0, apps = false))
    assertEquals("1 process", fmtProcessCount(1, apps = false))
    assertEquals("42 processes", fmtProcessCount(42, apps = false))
  }

  @Test
  fun `core sources stay Android-free`() {
    val moduleDir = File("").absoluteFile
    val coreDir = listOf(
      File(moduleDir, "src/main/java/dev/rsensors/android/core"),
      File(moduleDir, "app/src/main/java/dev/rsensors/android/core"),
    ).firstOrNull { it.isDirectory }
    assertNotNull("core source directory not found from $moduleDir", coreDir)

    val offenders = coreDir!!.listFiles { file -> file.extension == "kt" }
      .orEmpty()
      .flatMap { file ->
        file.readLines()
          .filter { line -> line.trimStart().startsWith("import android") }
          .map { line -> "${file.name}: ${line.trim()}" }
      }

    assertTrue("core/ must not import Android: $offenders", offenders.isEmpty())
  }
}
