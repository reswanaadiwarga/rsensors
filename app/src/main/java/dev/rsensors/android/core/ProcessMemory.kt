package dev.rsensors.android.core

import java.util.Locale

/**
 * Pure model and helpers for the T17 process-memory page (ANDROID.md §3, §9).
 *
 * The page reads a snapshot of `dumpsys meminfo --oom` and shows either every
 * process or apps grouped by base package. Nothing here touches Android: the
 * Shizuku runner supplies raw stdout and the overlay only renders the results,
 * which keeps the parsing and paging testable on the JVM.
 *
 * Decimal units (1024 bytes per KiB, 1e6/1e9 for MB/GB) match [fmtRam] and
 * Android's own memory reporting; bytes are never binary GiB.
 */

/** One process row parsed from the PSS section of `dumpsys meminfo --oom`. */
data class ProcessMemory(
  val name: String,
  val pid: Int,
  val pssKb: Long,
  val group: String,
)

/** One app row: all processes whose name is the package or package:something. */
data class AppMemory(
  val packageName: String,
  val pssKb: Long,
  val processCount: Int,
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

/**
 * A group-total line such as `130,000K: Cached`. Group names are alphabetic
 * with optional spaces; a line carrying `(pid` is a process row, not a group.
 */
private val GROUP_LINE = Regex("""^\s*[\d,]+K:\s*([A-Za-z][A-Za-z ]*)\s*$""")

/**
 * A process row such as `52,345K: com.example.notes (pid 4100 / activities)`.
 * The parenthesised pid may carry a `/ activities` (or similar) suffix.
 */
private val PROCESS_LINE = Regex(
  """^\s*([\d,]+)K:\s*(.+?)\s*\(pid\s+(\d+)(?:\s*/\s*[^)]*)?\)\s*$""",
)

/**
 * Parses the `Total PSS by OOM adjustment:` section of a `dumpsys meminfo
 * --oom` dump.
 *
 * Only that section is read: the earlier RSS section and anything after the
 * next `Total ...` section (for example `Total SwapPSS by OOM adjustment:`) are
 * ignored, so a caller may pass the whole dump. Each row inherits the most
 * recent group heading (`Cached`, `Native`, …). File order is preserved;
 * [sortProcesses] applies the display order.
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
      val pssKb = process.groupValues[1].replace(",", "").toLongOrNull()
      val pid = process.groupValues[3].toIntOrNull()
      if (name.isNotEmpty() && pssKb != null && pid != null) {
        result += ProcessMemory(name, pid, pssKb, group)
      }
      continue
    }

    val heading = GROUP_LINE.find(line)
    if (heading != null) group = heading.groupValues[1].trim()
  }

  return result
}

/**
 * Display order for the Processes view: largest PSS first, then name and pid so
 * equal totals are deterministic. Returns a new list and leaves [processes]
 * untouched.
 */
fun sortProcesses(processes: List<ProcessMemory>): List<ProcessMemory> =
  processes.sortedWith(
    compareByDescending<ProcessMemory> { it.pssKb }
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
 * [AppMemory] with the summed PSS and the process count. The result is a new
 * list sorted by total PSS descending, then package name.
 */
fun groupApps(processes: List<ProcessMemory>): List<AppMemory> =
  processes
    .filterNot { it.group.lowercase(Locale.ROOT) in HIDDEN_GROUPS }
    .groupBy { it.name.substringBefore(':') }
    .map { (packageName, rows) ->
      AppMemory(
        packageName = packageName,
        pssKb = rows.sumOf { it.pssKb },
        processCount = rows.size,
      )
    }
    .sortedWith(compareByDescending<AppMemory> { it.pssKb }.thenBy { it.packageName })

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
 * A row's PSS for the list, in decimal units: `1.2G`, `735M`, `0.5M`, `0.0M`.
 *
 * Negative input means the value is unavailable and renders as [DASH]. Input is
 * KiB from meminfo and is converted with 1024 bytes per KiB.
 */
fun fmtProcessRam(pssKb: Long): String {
  if (pssKb < 0L) return DASH
  val bytes = pssKb * 1024.0
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
