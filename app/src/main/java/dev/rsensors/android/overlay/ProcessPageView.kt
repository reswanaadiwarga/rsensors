package dev.rsensors.android.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.rsensors.android.core.PROCESS_PAGE_SIZE
import dev.rsensors.android.core.PageSlice
import dev.rsensors.android.core.ProcessMemory
import dev.rsensors.android.core.RamUsage
import dev.rsensors.android.core.fmtAge
import dev.rsensors.android.core.fmtProcessCount
import dev.rsensors.android.core.fmtProcessRam
import dev.rsensors.android.core.fmtRam
import dev.rsensors.android.core.groupApps
import dev.rsensors.android.core.pageSlice
import dev.rsensors.android.core.sortProcesses
import dev.rsensors.android.ui.Palette

/**
 * The process-memory page of the expanded overlay card (ANDROID.md §3, §9, §8
 * T17).
 *
 * Replaces the card's normal content while open: a `Back` / `RAM BY PROCESS` /
 * `Refresh` header, the card's `Used …` summary with an `Apps` / `Processes`
 * filter, eight PSS rows, and a footer with the snapshot age and a pager. The
 * list itself is a snapshot supplied by the service; this view owns only the
 * presentation state (filter, page, labels) and never scans by itself.
 *
 * The page is clickable with a no-op listener so taps on its background are
 * consumed instead of reaching the panel's collapse gesture. The header buttons,
 * filter chips and pager consume their own taps.
 *
 * Threading: constructed and mutated on the main thread only, like
 * [OverlayView]. App labels are resolved through [PackageManager] once per
 * package and cached on this view.
 */
class ProcessPageView(
  context: Context,
  private val callbacks: Callbacks,
) : LinearLayout(context) {
  /** Page actions the panel forwards; all arrive on the main thread. */
  interface Callbacks {
    /** The header's Back button was pressed. */
    fun onBack()

    /** The header's Refresh button was pressed. */
    fun onRefresh()

    /** The unavailable state's Start Shizuku button was pressed. */
    fun onStartShizuku()
  }

  /** The two filter modes; [apps] also selects the footer's noun. */
  private enum class Mode(val label: String, val apps: Boolean) {
    APPS("Apps", true),
    PROCESSES("Processes", false),
  }

  /** What the state area is currently reporting, if anything. */
  private enum class Content { LOADING, DATA, ERROR, UNAVAILABLE }

  /** One display row: an app label or a full process name plus its PSS. */
  private class DisplayRow(val name: String, val pssKb: Long)

  /** Fixed row views recycled for each page; the page size is always eight. */
  private class RowHolder(val container: LinearLayout, val name: TextView, val value: TextView)

  private var mode = Mode.APPS
  private var content = Content.LOADING

  /**
   * Last successful snapshot. Kept while a refresh is loading so a cancelled
   * scan can restore it instead of leaving the page on the loading state.
   */
  private var processes: List<ProcessMemory> = emptyList()
  private var capturedAt = 0L

  /** Message shown for the error and unavailable states. */
  private var stateMessage = ""

  /** Whether the unavailable state offers the Start Shizuku button. */
  private var showStart = false

  private var ram: RamUsage? = null
  private var now = System.currentTimeMillis()

  private var pageIndex = 0

  /** Resolved app labels keyed by package; the fallback package name is cached too. */
  private val labelCache = HashMap<String, String>()

  private val backButton = pageButton("\u2039 Back", "Back to card") { callbacks.onBack() }
  private val refreshButton = pageButton("Refresh", "Refresh process memory") { callbacks.onRefresh() }

  private val titleView = TextView(context).apply {
    text = "RAM BY PROCESS"
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    letterSpacing = TITLE_LETTER_SPACING
    gravity = Gravity.CENTER
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private val summaryText = TextView(context).apply {
    setTextColor(Palette.white)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private val appsChip = modeChip("Apps", Mode.APPS)
  private val processesChip = modeChip("Processes", Mode.PROCESSES)

  private val rows: List<RowHolder> = List(PROCESS_PAGE_SIZE) { buildRow() }

  private val listContainer = LinearLayout(context).apply {
    orientation = VERTICAL
    rows.forEach { row ->
      addView(row.container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
  }

  private val stateText = TextView(context).apply {
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    gravity = Gravity.CENTER
  }

  private val startButton = pageButton("Start Shizuku", "Start Shizuku") {
    callbacks.onStartShizuku()
  }.apply {
    visibility = GONE
  }

  private val stateContainer = LinearLayout(context).apply {
    orientation = VERTICAL
    addView(stateText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    addView(
      startButton,
      LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(STATE_BUTTON_GAP_DP)
      },
    )
  }

  private val footerText = TextView(context).apply {
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOTER_TEXT_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private val prevButton = pager("\u2039", "Previous page") { goToPage(pageIndex - 1) }
  private val nextButton = pager("\u203A", "Next page") { goToPage(pageIndex + 1) }

  private val pageLabel = TextView(context).apply {
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOTER_TEXT_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    gravity = Gravity.CENTER
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private val footerRow = LinearLayout(context).apply {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(footerText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    addView(prevButton)
    addView(
      pageLabel,
      LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(PAGER_GAP_DP)
        marginEnd = dp(PAGER_GAP_DP)
      },
    )
    addView(nextButton)
  }

  init {
    orientation = VERTICAL
    isClickable = true
    isFocusable = false
    // Consume background taps so they never reach the panel's collapse gesture.
    setOnClickListener { /* deliberate no-op */ }

    addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    addView(
      buildSummary(),
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(ROW_GAP_DP)
      },
    )
    addView(
      listContainer,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(ROW_TOP_GAP_DP)
      },
    )
    addView(
      stateContainer,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(STATE_TOP_GAP_DP)
      },
    )
    addView(
      footerRow,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(ROW_GAP_DP)
      },
    )
    render()
  }

  /** Shows the scanning state and disables Refresh until a result arrives. */
  fun setLoading() {
    content = Content.LOADING
    render()
  }

  /**
   * Installs a fresh snapshot.
   *
   * The current page is kept when it still exists in the new list and clamped
   * otherwise; the filter is untouched.
   *
   * @param processes parsed PSS rows, already in dump order
   * @param capturedAt epoch millis the dump finished, the footer's age origin
   */
  fun setData(processes: List<ProcessMemory>, capturedAt: Long) {
    this.processes = processes
    this.capturedAt = capturedAt
    this.now = System.currentTimeMillis()
    content = Content.DATA
    render()
  }

  /**
   * Shows a scan failure and enables Refresh.
   *
   * @param message the service's reason; a retry hint is appended here
   */
  fun setError(message: String) {
    stateMessage = message
    content = Content.ERROR
    render()
  }

  /**
   * Shows the no-data state (demo or missing Shizuku) and enables Refresh.
   *
   * @param message the reason shown to the user
   * @param showStart true only when the service can offer the Shizuku manager
   */
  fun setUnavailable(message: String, showStart: Boolean) {
    stateMessage = message
    this.showStart = showStart
    content = Content.UNAVAILABLE
    render()
  }

  /**
   * Updates the summary line and the snapshot age from the normal poll.
   *
   * The process rows stay a snapshot; only the `Used …` figures and `updated …`
   * text change. Called on the main thread every poll while the page is open.
   */
  fun setRam(ram: RamUsage?, now: Long) {
    this.ram = ram
    this.now = now
    summaryText.text = summaryLine()
    if (content == Content.DATA) {
      render()
    } else {
      contentDescription = pageDescription(total = 0)
    }
  }

  private fun buildHeader(): LinearLayout =
    LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(backButton)
      addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      addView(refreshButton)
    }

  private fun buildSummary(): LinearLayout =
    LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(summaryText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      addView(appsChip)
      addView(
        processesChip,
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
          marginStart = dp(CHIP_GAP_DP)
        },
      )
    }

  /**
   * One fixed-height list row: name with weight 1, ellipsized at the end, and
   * the PSS value flush right. Rows are not clickable (T17 has no detail view).
   */
  private fun buildRow(): RowHolder {
    val name = TextView(context).apply {
      setTextColor(Palette.white)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SIZE_SP)
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
      isFocusable = false
      isClickable = false
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val value = TextView(context).apply {
      setTextColor(Palette.blue)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SIZE_SP)
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      isFocusable = false
      isClickable = false
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
      setPadding(dp(VALUE_GAP_DP), 0, 0, 0)
    }
    val container = LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(ROW_PAD_V_DP), 0, dp(ROW_PAD_V_DP))
      isFocusable = false
      isClickable = false
      addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    return RowHolder(container, name, value)
  }

  /**
   * A compact header / Start Shizuku button: rounded gray fill, white 10 sp
   * monospace text, matching the card's action strip.
   *
   * @param text visible label and part of the content description
   * @param description accessibility label; the service decides the action
   */
  private fun pageButton(text: String, description: String, onClick: () -> Unit): TextView =
    TextView(context).apply {
      this.text = text
      contentDescription = description
      setTextColor(Palette.white)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP)
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      gravity = Gravity.CENTER
      background = GradientDrawable().apply {
        cornerRadius = BUTTON_RADIUS_DP * resources.displayMetrics.density
        setColor(Palette.gray)
      }
      minWidth = 0
      minimumWidth = 0
      minHeight = 0
      minimumHeight = 0
      setPadding(dp(BUTTON_PAD_H_DP), dp(BUTTON_PAD_V_DP), dp(BUTTON_PAD_H_DP), dp(BUTTON_PAD_V_DP))
      isFocusable = true
      isClickable = true
      setOnClickListener { onClick() }
    }

  /** One Apps/Processes filter chip, styled like the card's visibility chips. */
  private fun modeChip(label: String, chipMode: Mode): TextView =
    TextView(context).apply {
      text = label
      setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP)
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      gravity = Gravity.CENTER
      minWidth = 0
      minimumWidth = 0
      minHeight = 0
      minimumHeight = 0
      setPadding(dp(BUTTON_PAD_H_DP), dp(BUTTON_PAD_V_DP), dp(BUTTON_PAD_H_DP), dp(BUTTON_PAD_V_DP))
      isFocusable = true
      isClickable = true
      setOnClickListener { selectMode(chipMode) }
      background = GradientDrawable().apply {
        cornerRadius = BUTTON_RADIUS_DP * resources.displayMetrics.density
        setColor(Palette.gray)
      }
    }

  /** A pager arrow: text-only, white when usable and gray at the ends. */
  private fun pager(glyph: String, description: String, onClick: () -> Unit): TextView =
    TextView(context).apply {
      text = glyph
      contentDescription = description
      setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP)
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      gravity = Gravity.CENTER
      setPadding(dp(PAGER_PAD_H_DP), dp(PAGER_PAD_V_DP), dp(PAGER_PAD_H_DP), dp(PAGER_PAD_V_DP))
      isFocusable = true
      isClickable = true
      setOnClickListener { onClick() }
    }

  /** Switches the filter, resets to the first page and repaints (T17). */
  private fun selectMode(next: Mode) {
    if (mode == next) return
    mode = next
    pageIndex = 0
    render()
  }

  /** Moves one page; the target is clamped by [render]'s slice. */
  private fun goToPage(target: Int) {
    if (content != Content.DATA) return
    val count = pageSlice(displayItems(), 0).count
    pageIndex = target.coerceIn(0, (count - 1).coerceAtLeast(0))
    render()
  }

  /**
   * The current filter's rows in display order.
   *
   * Apps groups by base package (the pure [groupApps], which also hides
   * `Native`/`System`) and resolves a [PackageManager] label; Processes shows
   * the sorted full process name.
   */
  private fun displayItems(): List<DisplayRow> = when (mode) {
    Mode.APPS -> groupApps(sortProcesses(processes)).map { app ->
      DisplayRow(appLabel(app.packageName), app.pssKb)
    }

    Mode.PROCESSES -> sortProcesses(processes).map { process ->
      DisplayRow(process.name, process.pssKb)
    }
  }

  /** Package label if the package is installed, otherwise the package name. */
  private fun appLabel(packageName: String): String {
    labelCache[packageName]?.let { return it }
    val label = try {
      val manager = context.packageManager
      manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
      packageName
    } catch (e: Exception) {
      packageName
    }
    labelCache[packageName] = label
    return label
  }

  /** Repaints every region from the current state; the single render path. */
  private fun render() {
    val items = displayItems()
    val slice = pageSlice(items, pageIndex)
    pageIndex = slice.index
    val data = content == Content.DATA
    val hasItems = data && items.isNotEmpty()

    listContainer.visibility = if (hasItems) VISIBLE else GONE
    stateContainer.visibility = if (hasItems) GONE else VISIBLE
    footerRow.visibility = if (data) VISIBLE else GONE

    styleModeChip(appsChip, mode == Mode.APPS)
    styleModeChip(processesChip, mode == Mode.PROCESSES)
    summaryText.text = summaryLine()

    when (content) {
      Content.LOADING -> showState("Scanning processes\u2026", start = false)
      Content.ERROR -> showState("$stateMessage\nTap Refresh to retry.", start = false)
      Content.UNAVAILABLE -> showState(stateMessage, start = showStart)
      Content.DATA -> {
        if (items.isEmpty()) {
          showState(
            if (mode.apps) "No apps to show" else "No processes to show",
            start = false,
          )
        } else {
          bindRows(slice.items)
        }
      }
    }

    // Refresh answers a tap in every state but the one already scanning. It
    // stays white on its gray fill and only dims, so the label never disappears.
    val refreshable = content != Content.LOADING
    refreshButton.isEnabled = refreshable
    refreshButton.alpha = if (refreshable) 1f else DISABLED_ALPHA

    bindFooter(slice, items.size)
    contentDescription = pageDescription(items.size)
  }

  private fun showState(message: String, start: Boolean) {
    stateText.text = message
    startButton.visibility = if (start) VISIBLE else GONE
    stateContainer.visibility = VISIBLE
  }

  private fun bindRows(items: List<DisplayRow>) {
    rows.forEachIndexed { index, holder ->
      val item = items.getOrNull(index)
      if (item == null) {
        holder.container.visibility = GONE
      } else {
        holder.container.visibility = VISIBLE
        holder.name.text = item.name
        holder.value.text = fmtProcessRam(item.pssKb)
      }
    }
  }

  /**
   * Fills the footer: `PSS · N apps · just now` on the left and the pager on the
   * right. With no rows the pager reads `0 of 0` and both arrows are disabled.
   */
  private fun bindFooter(slice: PageSlice<DisplayRow>, total: Int) {
    footerText.text = "PSS \u00b7 " +
      fmtProcessCount(total, mode.apps) + " \u00b7 " +
      fmtAge(now - capturedAt)
    pageLabel.text = if (total == 0) {
      "0 of 0"
    } else {
      "${slice.firstNumber}\u2013${slice.lastNumber} of $total"
    }
    prevButton.isEnabled = slice.index > 0
    nextButton.isEnabled = slice.index < slice.count - 1
    prevButton.setTextColor(if (prevButton.isEnabled) Palette.white else Palette.gray)
    nextButton.setTextColor(if (nextButton.isEnabled) Palette.white else Palette.gray)
  }

  private fun styleModeChip(chip: TextView, on: Boolean) {
    (chip.background as? GradientDrawable)?.setColor(if (on) Palette.green else Palette.gray)
    chip.setTextColor(if (on) Palette.dark else Palette.white)
    chip.contentDescription = "${chip.text} filter ${if (on) "on" else "off"}"
  }

  private fun summaryLine(): String = "Used " + fmtRam(ram?.usedBytes, ram?.totalBytes)

  /**
   * The page's accessibility text, in the spec's shape:
   * `RAM by process, Apps, 42 apps, updated just now`. Non-data states name
   * themselves so TalkBack never reads a stale count.
   */
  private fun pageDescription(total: Int): String = when (content) {
    Content.LOADING -> "RAM by process, scanning"
    Content.ERROR -> "RAM by process, error, $stateMessage"
    Content.UNAVAILABLE -> "RAM by process, unavailable, $stateMessage"
    Content.DATA -> "RAM by process, ${mode.label}, " +
      "${fmtProcessCount(total, mode.apps)}, updated ${fmtAge(now - capturedAt)}"
  }

  private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  companion object {
    private const val TITLE_SIZE_SP = 10f
    private const val ROW_TEXT_SIZE_SP = 10f
    private const val FOOTER_TEXT_SIZE_SP = 9f
    private const val BUTTON_TEXT_SIZE_SP = 10f

    /** Slight tracking, as the spec asks for a letter-spaced header. */
    private const val TITLE_LETTER_SPACING = 0.08f

    private const val BUTTON_RADIUS_DP = 6f
    private const val BUTTON_PAD_H_DP = 8f
    private const val BUTTON_PAD_V_DP = 2f
    private const val CHIP_GAP_DP = 6f

    private const val ROW_PAD_V_DP = 2f
    private const val VALUE_GAP_DP = 6f
    private const val ROW_GAP_DP = 6f
    private const val ROW_TOP_GAP_DP = 4f
    private const val STATE_TOP_GAP_DP = 8f
    private const val STATE_BUTTON_GAP_DP = 6f

    private const val PAGER_PAD_H_DP = 4f
    private const val PAGER_PAD_V_DP = 1f
    private const val PAGER_GAP_DP = 4f

    /** Dims a disabled control without hiding its label against the same gray. */
    private const val DISABLED_ALPHA = 0.5f
  }
}
