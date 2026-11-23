package dev.rsensors.android.core

import java.util.Locale

/**
 * Pure model and helpers for the T17 process-memory page (ANDROID.md §3, §9).
 *
 * The page reads a snapshot of `dumpsys meminfo --oom` and shows either every
 * process or apps grouped by base package. When the dump yields no PSS rows the
 * caller falls back to `ps -A -o PID,RSS,NAME`; the snapshot records which
 * metric it actually holds so the page never calls RSS "PSS" (T19b-3). Nothing
 * here touches Android: the Shizuku runner supplies raw stdout and the overlay
 * only renders the results, which keeps parsing and paging testable on the JVM.
 *
 * Decimal units (1024 bytes per KiB, 1e6/1e9 for MB/GB) match [fmtRam] and
 * Android's own memory reporting; bytes are never binary GiB.
 */

/** The metric a [ProcessSnapshot] carries; the page footer shows its label. */
enum class ProcessMetric(val label: String) {
  PSS("PSS"),
  RSS("RSS"),
}

/**
 * One process row: PSS from meminfo or RSS from `ps`, always in KiB.
 *
 * [uid] is the shell-reported Linux UID when a `ps -A -o PID,UID,NAME` lookup
 * matched this pid, and null when it did not (T19c-3a). The stop policy uses it
 * to protect core processes whose `ApplicationInfo` is hidden from the app.
 */
data class ProcessMemory(
  val name: String,
  val pid: Int,
  val memoryKb: Long,
  val group: String,
  val uid: Int? = null,
)

/**
 * One app row: all processes whose name is the package or package:something.
 *
 * [uid] is the first non-null process UID in the group, because every process
 * of an app shares its UID; null when no member row had one (T19c-3a).
 */
data class AppMemory(
  val packageName: String,
  val memoryKb: Long,
  val processCount: Int,
  val uid: Int? = null,
)

/** Parsed rows plus the metric they were measured with. */
data class ProcessSnapshot(
  val rows: List<ProcessMemory>,
  val metric: ProcessMetric,
)

/**
 * One page of an arbitrary list.
 *
 * [firstNumber] and [lastNumber] are 1-based positions within [total]; both are
 * 0 for an empty page. On every page but the last, [items] holds a full page, so
 * its size is the page size; the last page derives its first number from
 * [total] instead.
 */
data class PageSlice<T>(
  val items: List<T>,
  val index: Int,
  val count: Int,
  val total: Int,
) {
  /** 1-based number of the first item on this page, or 0 when empty. */
  val firstNumber: Int
    get() = when {
      items.isEmpty() -> 0
      index == count - 1 -> total - items.size + 1
      else -> index * items.size + 1
    }

  /** 1-based number of the last item on this page, or 0 when empty. */
  val lastNumber: Int
    get() = if (items.isEmpty()) 0 else firstNumber + items.size - 1
}

/** Rows per process-memory page (ANDROID.md §3). */
const val PROCESS_PAGE_SIZE = 8

/** Heading that opens the section T17 reads; the RSS section above it is ignored. */
private const val PSS_HEADER = "Total PSS by OOM adjustment:"

/** Group shown when a total line's caption is not a recognisable group name. */
private const val UNKNOWN_GROUP = "Unknown"

/**
 * Group carried by `ps` rows. RSS has no OOM adjustment, so this deliberately
 * is not `Native`/`System`: the Apps filter must keep those rows visible
 * instead of hiding every native and system process (T19b-3).
 */
private const val RSS_GROUP = "RSS"

/** Header tokens accepted for the `ps` name column, lower-case for lookup. */
private val PS_NAME_HEADERS = setOf("name", "cmd", "command")

/** `ps` pads columns with spaces; split on any run of whitespace. */
private val PS_WHITESPACE = Regex("\\s+")

/**
 * A group-total line such as `130,000K: Cached` or `130,000kB: Foreground Service`.
 *
 * The unit is `K` with an optional `B` (any case). The caption is captured
 * loosely because some real dumps carry junk captions; [CLEAN_GROUP] decides
 * whether it names a group or becomes [UNKNOWN_GROUP].
 */
private val GROUP_LINE = Regex(
  """^\s*[\d,]+\s*[kK][bB]?:\s*(.*?)\s*$""",
)

/**
 * A caption that names a real OOM group: letters with spaces, `/` or `-`
 * (`Foreground Service`, `Persistent Service`). Anything else (`Cached+foo`,
 * an unclosed pid, an embedded dot) is not trusted as a heading.
 */
private val CLEAN_GROUP = Regex("""^[A-Za-z][A-Za-z /-]*$""")

/**
 * A process row such as `52,345K: com.example.notes (pid 4100 / activities)`,
 * `52,345kB: com.example.notes (pid 4100) (user 10)` or
 * `52,345K: com.example.notes:remote (pid 4101:remote)`.
 *
 * The unit is `K` with an optional `B` (any case). The pid parenthesis may end
 * with a `/ activities` or `:remote` suffix, with or without surrounding
 * spaces, and a work-profile row may add a trailing `(user N)`.
 * `pid`/`user` are matched case-insensitively because vendor dumps vary.
 */
private val PROCESS_LINE = Regex(
  """^\s*([\d,]+)\s*[kK][bB]?:\s*(.+?)\s*\(\s*pid\s+(\d+)(?:\s*[/:]\s*[^)]*)?\s*\)""" +
    """(?:\s*\(\s*user\s+\d+\s*\))?\s*$""",
  RegexOption.IGNORE_CASE,
)

/**
 * Parses the `Total PSS by OOM adjustment:` section of a `dumpsys meminfo
 * --oom` dump.
 *
 * Only that section is read: the earlier RSS section and anything after the
 * next `Total ...` section (for example `Total SwapPSS by OOM adjustment:`) are
 * ignored, so a caller may pass the whole dump. Each row inherits the most
 * recent group heading (`Cached`, `Native`, …); a total line whose caption is
 * not a clean group name (see [CLEAN_GROUP]) sets [UNKNOWN_GROUP] instead of
 * keeping the previous group. A malformed row containing `(pid` never acts as a
 * heading, so it cannot corrupt the group of the rows after it. File order is
 * preserved; [sortProcesses] applies the display order.
 *
 * Malformed rows, blank names, group totals and dumps without the PSS header
 * yield no row rather than an error, because this runs on an untrusted shell
 * dump.
 */
fun parseMeminfoPss(raw: String): List<ProcessMemory> {
  val result = mutableListOf<ProcessMemory>()
  var inPss = false
  var group = ""

  for (line in raw.lineSequence()) {
    if (!inPss) {
      if (line.trim() == PSS_HEADER) inPss = true
      continue
    }
    if (line.trim().startsWith("Total ")) break

    val process = PROCESS_LINE.find(line)
    if (process != null) {
      val name = process.groupValues[2].trim()
      val memoryKb = process.groupValues[1].replace(",", "").toLongOrNull()
      val pid = process.groupValues[3].toIntOrNull()
      if (name.isNotEmpty() && memoryKb != null && pid != null) {
        result += ProcessMemory(name, pid, memoryKb, group)
      }
      continue
    }

    // A row that mentions a pid but does not parse is a broken process row,
    // not a heading; leaving the group alone keeps the following rows grouped.
    if (line.contains("(pid", ignoreCase = true)) continue

    val heading = GROUP_LINE.find(line) ?: continue
    val caption = heading.groupValues[1].trim()
    group = if (CLEAN_GROUP.matches(caption)) caption else UNKNOWN_GROUP
  }

  return result
}

/**
 * Parses the tabular output of `ps -A -o PID,RSS,NAME` (T19b-3).
 *
 * Columns are located from the first header line that contains `PID`, `RSS` and
 * a name column (`NAME`, `CMD` or `COMMAND`), case-insensitively, so a vendor
 * that reorders or adds columns still parses. Each later row is read at those
 * positions. The name column takes the rest of the line joined by single
 * spaces when it is last (a process name can contain spaces); otherwise it is
 * exactly one token.
 *
 * Rows are kept only when the pid is positive, the RSS is a non-negative plain
 * KiB integer and the name is non-empty. `ps` prints plain decimal integers, so
 * thousands separators are malformed, not accepted. Every row gets
 * [RSS_GROUP] because `ps` reports no OOM adjustment. A missing header or a
 * malformed line yields no row rather than an error, because this runs on an
 * untrusted shell dump.
 */
fun parsePsRss(raw: String): List<ProcessMemory> {
  val result = mutableListOf<ProcessMemory>()
  var headerSize = -1
  var pidIndex = -1
  var rssIndex = -1
  var nameIndex = -1

  for (line in raw.lineSequence()) {
    if (line.isBlank()) continue
    val tokens = line.trim().split(PS_WHITESPACE)

    if (headerSize < 0) {
      val pid = tokens.indexOfFirst { it.equals("PID", ignoreCase = true) }
      val rss = tokens.indexOfFirst { it.equals("RSS", ignoreCase = true) }
      val name = tokens.indexOfFirst { it.lowercase(Locale.ROOT) in PS_NAME_HEADERS }
      if (pid >= 0 && rss >= 0 && name >= 0) {
        headerSize = tokens.size
        pidIndex = pid
        rssIndex = rss
        nameIndex = name
      }
      continue
    }

    if (tokens.size < headerSize) continue
    val nameIsLast = nameIndex == headerSize - 1
    if (!nameIsLast && tokens.size != headerSize) continue

    val pid = tokens[pidIndex].toIntOrNull() ?: continue
    if (pid <= 0) continue
    val memoryKb = tokens[rssIndex].toLongOrNull() ?: continue
    if (memoryKb < 0L) continue
    val name = if (nameIsLast) {
      tokens.subList(nameIndex, tokens.size).joinToString(" ")
    } else {
      tokens[nameIndex]
    }
    if (name.isEmpty()) continue
    result += ProcessMemory(name, pid, memoryKb, RSS_GROUP)
  }

  return result
}

/**
 * Maps pid to Linux UID from `ps -A -o PID,UID,NAME` stdout (T19c-3a).
 *
 * Columns are located from the first header line that contains `PID`, `UID` and
 * a name column (`NAME`, `CMD` or `COMMAND`), case-insensitively, and later
 * rows are read at those header positions. The name column is not stored: this
 * map only enriches already-parsed rows, so a row with just pid and UID is
 * still usable and a process name containing spaces needs no special handling.
 *
 * A row is kept only when its pid is a positive plain integer and its uid is a
 * non-negative plain integer; `ps` prints decimal numbers, so separators and
 * usernames are malformed, not accepted. When the same pid appears twice, the
 * last row wins. A missing header or an unreadable line yields no entry rather
 * than an error, because this runs on an untrusted shell dump.
 *
 * Pure: no Android calls and no side effects.
 *
 * @param raw stdout of the shell-side UID command
 * @return pid-to-uid entries in first-seen pid order; empty when nothing parses
 */
fun parsePsUid(raw: String): Map<Int, Int> {
  val result = LinkedHashMap<Int, Int>()
  var pidIndex = -1
  var uidIndex = -1

  for (line in raw.lineSequence()) {
    if (line.isBlank()) continue
    val tokens = line.trim().split(PS_WHITESPACE)

    if (pidIndex < 0) {
      val pid = tokens.indexOfFirst { it.equals("PID", ignoreCase = true) }
      val uid = tokens.indexOfFirst { it.equals("UID", ignoreCase = true) }
      val name = tokens.indexOfFirst { it.lowercase(Locale.ROOT) in PS_NAME_HEADERS }
      if (pid >= 0 && uid >= 0 && name >= 0) {
        pidIndex = pid
        uidIndex = uid
      }
      continue
    }

    val pid = tokens.getOrNull(pidIndex)?.toIntOrNull() ?: continue
    if (pid <= 0) continue
    val uid = tokens.getOrNull(uidIndex)?.toIntOrNull() ?: continue
    if (uid < 0) continue
    // Last duplicate wins, matching ps's own latest-row ordering.
    result[pid] = uid
  }

  return result
}

/**
 * Returns [rows] with `uid` filled from [uids] by pid (T19c-3a).
 *
 * Rows whose pid is absent keep their existing uid (normally null), so the
 * function is idempotent and never invents a UID. Order, names, memory and
 * groups are preserved, and the input list is not mutated.
 *
 * @param rows parsed process rows
 * @param uids pid-to-uid entries from [parsePsUid]
 * @return a new list of copied rows
 */
fun attachUids(rows: List<ProcessMemory>, uids: Map<Int, Int>): List<ProcessMemory> =
  rows.map { row -> row.copy(uid = uids[row.pid] ?: row.uid) }

/**
 * Chooses the process list for one scan (T19b-3).
 *
 * A non-empty PSS section wins, because meminfo is the richer route and its
 * rows carry OOM groups. A `ps` dump is used only when meminfo produced no PSS
 * rows; both shapes in one string still resolve to PSS. When neither parses,
 * the caller gets an empty PSS snapshot and shows its existing no-data state.
 */
fun parseProcessSnapshot(raw: String): ProcessSnapshot {
  val pss = parseMeminfoPss(raw)
  if (pss.isNotEmpty()) return ProcessSnapshot(pss, ProcessMetric.PSS)
  val rss = parsePsRss(raw)
  if (rss.isNotEmpty()) return ProcessSnapshot(rss, ProcessMetric.RSS)
  return ProcessSnapshot(emptyList(), ProcessMetric.PSS)
}

/**
 * Display order for the Processes view: largest memory first, then name and pid
 * so equal totals are deterministic. Returns a new list and leaves [processes]
 * untouched.
 */
fun sortProcesses(processes: List<ProcessMemory>): List<ProcessMemory> =
  processes.sortedWith(
    compareByDescending<ProcessMemory> { it.memoryKb }
      .thenBy { it.name }
      .thenBy { it.pid },
  )

/** OOM groups the Apps view hides; on the target the largest row is a native shim. */
private val HIDDEN_GROUPS = setOf("native", "system")

/**
 * Groups the Apps view by base package and hides [HIDDEN_GROUPS]
 * (case-insensitive).
 *
 * A process named `package` and its helpers `package:remote` collapse into one
 * [AppMemory] with the summed memory, the process count and the first non-null
 * member `uid` (all processes of an app share one UID, T19c-3a). The result is
 * a new list sorted by total memory descending, then package name. RSS rows
 * carry the [RSS_GROUP] group, which is not hidden, so the fallback list is not
 * emptied by the Apps filter.
 */
fun groupApps(processes: List<ProcessMemory>): List<AppMemory> =
  processes
    .filterNot { it.group.lowercase(Locale.ROOT) in HIDDEN_GROUPS }
    .groupBy { it.name.substringBefore(':') }
    .map { (packageName, rows) ->
      AppMemory(
        packageName = packageName,
        memoryKb = rows.sumOf { it.memoryKb },
        processCount = rows.size,
        uid = rows.firstNotNullOfOrNull { it.uid },
      )
    }
    .sortedWith(compareByDescending<AppMemory> { it.memoryKb }.thenBy { it.packageName })

/**
 * Slices [items] into a page.
 *
 * @param pageIndex requested zero-based page; clamped into `0..count-1`
 * @param pageSize rows per page; non-positive values are coerced to 1 so paging
 *   can never divide by zero or produce an empty page
 */
fun <T> pageSlice(
  items: List<T>,
  pageIndex: Int,
  pageSize: Int = PROCESS_PAGE_SIZE,
): PageSlice<T> {
  if (items.isEmpty()) return PageSlice(emptyList(), index = 0, count = 0, total = 0)

  val size = pageSize.coerceAtLeast(1)
  val count = (items.size + size - 1) / size
  val index = pageIndex.coerceIn(0, count - 1)
  val from = index * size
  val to = minOf(from + size, items.size)
  return PageSlice(items.subList(from, to).toList(), index, count, items.size)
}

/** Decimal byte-per-unit constants: Android reports memory in decimal units. */
private const val BYTES_PER_GB = 1_000_000_000.0
private const val BYTES_PER_MB = 1_000_000.0

/** At or above this many bytes a row switches from one-decimal MB to whole MB. */
private const val WHOLE_MEGABYTE_BYTES = 10_000_000.0

/**
 * A row's PSS or RSS for the list, in decimal units: `1.2G`, `735M`, `0.5M`,
 * `0.0M`.
 *
 * Negative input means the value is unavailable and renders as [DASH]. Input is
 * KiB from meminfo or `ps` and is converted with 1024 bytes per KiB.
 */
fun fmtProcessRam(memoryKb: Long): String {
  if (memoryKb < 0L) return DASH
  val bytes = memoryKb * 1024.0
  return when {
    bytes >= BYTES_PER_GB -> String.format(Locale.ROOT, "%.1fG", bytes / BYTES_PER_GB)
    bytes >= WHOLE_MEGABYTE_BYTES -> String.format(Locale.ROOT, "%.0fM", bytes / BYTES_PER_MB)
    else -> String.format(Locale.ROOT, "%.1fM", bytes / BYTES_PER_MB)
  }
}

/**
 * Footer count with the correct noun: `0 apps`, `1 app`, `42 apps`,
 * `1 process`, `42 processes`. [apps] selects the app noun for the Apps view.
 */
fun fmtProcessCount(count: Int, apps: Boolean): String {
  val noun = when {
    apps && count == 1 -> "app"
    apps -> "apps"
    count == 1 -> "process"
    else -> "processes"
  }
  return "$count $noun"
}
