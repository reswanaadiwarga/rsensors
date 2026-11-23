package dev.rsensors.android.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.rsensors.android.core.PROCESS_PAGE_SIZE
import dev.rsensors.android.core.PageSlice
import dev.rsensors.android.core.ProcessMemory
import dev.rsensors.android.core.ProcessMetric
import dev.rsensors.android.core.RamUsage
import dev.rsensors.android.core.StopDecision
import dev.rsensors.android.core.StopRisk
import dev.rsensors.android.core.StopTarget
import dev.rsensors.android.core.fmtAge
import dev.rsensors.android.core.fmtProcessCount
import dev.rsensors.android.core.fmtProcessRam
import dev.rsensors.android.core.fmtRam
import dev.rsensors.android.core.groupApps
import dev.rsensors.android.core.pageSlice
import dev.rsensors.android.core.sortProcesses
import dev.rsensors.android.core.stopDecision
import dev.rsensors.android.ui.Palette

/**
 * The process-memory page of the expanded overlay card (ANDROID.md §3, §9, §8
 * T17, T18).
 *
 * Replaces the card's normal content while open: a `Back` / `RAM BY PROCESS` /
 * `Refresh` header, the card's `Used …` summary with an `Apps` / `Processes`
 * filter, eight memory rows, and a footer naming the metric (`PSS` or `RSS`),
 * the count, the snapshot age and a pager. The list itself is a snapshot
 * supplied by the service; this view owns only the presentation state (filter,
 * page, labels, selection) and never scans or stops by itself.
 *
 * T18 turns each row into a selection target. A tap selects the row (blue tint
 * and left accent) and replaces the footer with a selection bar that names the
 * item, its memory, its package and the `StopPolicy` tier. Stop always asks once
 * more before calling [Callbacks.onStopRequested]; protected rows never show
 * Stop. The page keeps its selection while a stop is reported or fails and
 * clears it on Cancel, Back, a filter or page change, Refresh and any new
 * loading/data/error/unavailable state.
 *
 * The page is clickable with a no-op listener so taps on its background are
 * consumed instead of reaching the panel's collapse gesture and never clear the
 * selection. The header buttons, filter chips, rows, selection buttons and
 * pager consume their own taps.
 *
 * Threading: constructed and mutated on the main thread only, like
 * [OverlayView]. App labels, [ApplicationInfo] and the launcher/IME package sets
 * are resolved through [PackageManager] and cached on this view; package
 * visibility is deliberately not broadened.
 */
class ProcessPageView(
  context: Context,
  private val callbacks: Callbacks,
) : LinearLayout(context) {
  /** Page actions the panel forwards; all arrive on the main thread. */
  interface Callbacks {
    /** The header's Back button was pressed while no row was selected. */
    fun onBack()

    /** The header's Refresh button was pressed. */
    fun onRefresh()

    /** The unavailable state's Start Shizuku button was pressed. */
    fun onStartShizuku()

    /**
     * The user confirmed Stop for [target]. The service re-checks the policy,
     * runs the shell stop off the main thread and reports the outcome back
     * through [setStopError] or [setLoading].
     */
    fun onStopRequested(target: StopTarget)
  }

  /** The two filter modes; [apps] also selects the footer's noun. */
  private enum class Mode(val label: String, val apps: Boolean) {
    APPS("Apps", true),
    PROCESSES("Processes", false),
  }

  /** What the state area is currently reporting, if anything. */
  private enum class Content { LOADING, DATA, ERROR, UNAVAILABLE }

  /**
   * One display row for the current filter.
   *
   * [label] is the resolved app label (Apps) or the full process name
   * (Processes). [groups] are the raw OOM groups the policy must consider: all
   * groups of the package for an Apps row, the row's own group for a Process
   * row. A Process row carries [packageName] only when its base name resolves
   * to an installed package; otherwise the policy sees a native/unknown target.
   * [pid] is null for Apps rows, and [processCount] is the grouped app's process
   * count or the number of rows sharing a Process row's base name. [uid] is the
   * shell-reported UID attached by the scan (T19c-3a): it lets a row protect a
   * hidden core process even when `ApplicationInfo` is unavailable.
   */
  private data class DisplayRow(
    val label: String,
    val memoryKb: Long,
    val packageName: String?,
    val groups: Set<String>,
    val pid: Int?,
    val processCount: Int,
    val uid: Int?,
  )

  /**
   * Fixed row views recycled for each page; the page size is always eight.
   *
   * [accent] is the 2 dp blue left bar that is INVISIBLE until the row is
   * selected; keeping it laid out (rather than GONE) keeps the label column
   * aligned across rows.
   */
  private class RowHolder(
    val container: LinearLayout,
    val accent: View,
    val name: TextView,
    val value: TextView,
  )

  private var mode = Mode.APPS
  private var content = Content.LOADING

  /**
   * Last successful snapshot. Kept while a refresh is loading so a cancelled
   * scan can restore it instead of leaving the page on the loading state.
   */
  private var processes: List<ProcessMemory> = emptyList()
  private var capturedAt = 0L

  /** Metric of the current snapshot; the footer labels PSS vs RSS. */
  private var metric = ProcessMetric.PSS

  /** Message shown for the loading state; set by the service per scan. */
  private var loadingMessage = DEFAULT_LOADING_MESSAGE

  /** Message shown for the error and unavailable states. */
  private var stateMessage = ""

  /** Whether the unavailable state offers the Start Shizuku button. */
  private var showStart = false

  private var ram: RamUsage? = null
  private var now = System.currentTimeMillis()

  private var pageIndex = 0

  /** The selected row and its policy verdict, or null when nothing is selected. */
  private var selected: DisplayRow? = null
  private var selectedTarget: StopTarget? = null
  private var selectedDecision: StopDecision? = null

  /** True after the first Stop tap: the next Stop tap actually requests a stop. */
  private var confirming = false

  /** True between the confirmed Stop tap and the service's result. */
  private var stopping = false

  /** The service's failure message, shown in place of the reason while retrying. */
  private var stopError: String? = null

  /** Resolved app labels keyed by package; the fallback package name is cached too. */
  private val labelCache = HashMap<String, String>()

  /** Resolved [ApplicationInfo] keyed by package; a miss is cached as null too. */
  private val infoCache = HashMap<String, ApplicationInfo?>()

  /** Launcher packages, resolved on first use from the HOME intent. */
  private var launcherPackages: Set<String>? = null

  /** Current default IME package, read once from secure settings. */
  private var imePackage: String? = null
  private var imeLoaded = false

  private val backButton = pageButton("\u2039 Back", "Back to card") { onBackTapped() }
  private val refreshButton = pageButton("Refresh", "Refresh process memory") {
    clearSelection()
    callbacks.onRefresh()
  }

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

  private val selectionTitle = TextView(context).apply {
    setTextColor(Palette.white)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, SELECTION_TITLE_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  /**
   * The selected row's package, shown under the title for Apps rows. MIDDLE
   * truncation keeps both the leading and trailing package segments readable
   * at the card's narrow width. Processes rows hide it because their title
   * already carries the package/pid.
   */
  private val selectionPackage = TextView(context).apply {
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, SELECTION_META_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.MIDDLE
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private val selectionMeta = TextView(context).apply {
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, SELECTION_META_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    // Up to three lines so the whole-app confirmation sentence is not dropped
    // at the card's width.
    maxLines = 3
    ellipsize = TextUtils.TruncateAt.END
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private val stopButton = selectionButton("Stop", Palette.red) { onStopTapped() }
  private val cancelButton = selectionButton("Cancel", Palette.gray) { onCancelTapped() }

  private val selectionButtons = LinearLayout(context).apply {
    orientation = HORIZONTAL
    gravity = Gravity.END
    addView(stopButton)
    addView(
      cancelButton,
      LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(SELECTION_BUTTON_GAP_DP)
      },
    )
  }

  /**
   * Replaces the footer while a row is selected; its height is part of the
   * page, so the window grows instead of the bar overlapping the list.
   */
  private val selectionBar = LinearLayout(context).apply {
    orientation = VERTICAL
    visibility = GONE
    addView(selectionTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    addView(
      selectionPackage,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(SELECTION_META_GAP_DP)
      },
    )
    addView(
      selectionMeta,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(SELECTION_META_GAP_DP)
      },
    )
    addView(
      selectionButtons,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(SELECTION_BUTTON_TOP_GAP_DP)
      },
    )
  }

  init {
    orientation = VERTICAL
    isClickable = true
    isFocusable = false
    // Consume background taps so they never reach the panel's collapse gesture
    // and never clear the selection.
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
    addView(
      selectionBar,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(ROW_GAP_DP)
      },
    )
    render()
  }

  /**
   * Shows the scanning state and disables Refresh until a result arrives.
   *
   * Clears any selection: a new scan is a new snapshot, and a stop's success
   * path uses [message] to report `Stop requested — refreshing…` in the state
   * area.
   */
  fun setLoading(message: String = DEFAULT_LOADING_MESSAGE) {
    clearSelection()
    loadingMessage = message
    content = Content.LOADING
    render()
  }

  /**
   * Installs a fresh snapshot.
   *
   * The current page is kept when it still exists in the new list and clamped
   * otherwise; the filter is untouched. A new snapshot clears the selection,
   * because its rows may no longer describe the same process.
   *
   * @param processes parsed rows, already in dump order
   * @param capturedAt epoch millis the dump finished, the footer's age origin
   * @param metric the metric the rows carry; the footer shows its label
   */
  fun setData(processes: List<ProcessMemory>, capturedAt: Long, metric: ProcessMetric) {
    clearSelection()
    this.processes = processes
    this.capturedAt = capturedAt
    this.metric = metric
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
    clearSelection()
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
    clearSelection()
    stateMessage = message
    this.showStart = showStart
    content = Content.UNAVAILABLE
    render()
  }

  /**
   * Keeps the selection while reporting why a stop failed.
   *
   * The bar switches to `Couldn't stop <label>`, the service's message and the
   * tier reason, with Stop (a retry, still confirmed) and Cancel. Called by the
   * service on the main thread; the selection is kept only if one still exists
   * (a stale result must not resurrect a cancelled selection).
   */
  fun setStopError(message: String) {
    if (selected == null) return
    stopError = message
    confirming = false
    stopping = false
    render()
  }

  /**
   * Updates the summary line and the snapshot age from the normal poll.
   *
   * The process rows stay a snapshot; only the `Used …` figures and `updated …`
   * text change. Called on the main thread every poll while the page is open. A
   * selection survives the poll: the summary keeps updating and the selection
   * bar stays in place of the hidden age/pager.
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
   * One list row: a 2 dp selection accent, the name with weight 1 and the memory
   * value flush right. The row itself is the touch and accessibility target; at
   * the 7 dp vertical padding the target is about 26 dp tall, which is the
   * point of T18's larger row (small-target mis-taps were the user's concern).
   */
  private fun buildRow(): RowHolder {
    val accent = View(context).apply {
      setBackgroundColor(Palette.blue)
      visibility = INVISIBLE
      isFocusable = false
      isClickable = false
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
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
      isFocusable = true
      isClickable = true
      addView(
        accent,
        LayoutParams(dp(ACCENT_WIDTH_DP), dp(ACCENT_HEIGHT_DP)).apply {
          marginEnd = dp(ACCENT_GAP_DP)
        },
      )
      addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    return RowHolder(container, accent, name, value)
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

  /**
   * One selection-bar button: rounded [fill], white 10 sp monospace text.
   *
   * @param text visible label, also the base of the content description
   * @param fill `Stop` red or `Cancel` gray
   */
  private fun selectionButton(text: String, fill: Int, onClick: () -> Unit): TextView =
    TextView(context).apply {
      this.text = text
      contentDescription = text
      setTextColor(Palette.white)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP)
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      gravity = Gravity.CENTER
      background = GradientDrawable().apply {
        cornerRadius = BUTTON_RADIUS_DP * resources.displayMetrics.density
        setColor(fill)
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

  /**
   * Switches the filter, resets to the first page and drops the selection.
   *
   * A selection belongs to a row in one filter; the other list may not contain
   * it, so it must not survive the switch.
   */
  private fun selectMode(next: Mode) {
    if (mode == next) return
    mode = next
    pageIndex = 0
    clearSelection()
    render()
  }

  /** Moves one page and drops the selection; the target is clamped by [render]. */
  private fun goToPage(target: Int) {
    if (content != Content.DATA) return
    clearSelection()
    val count = pageSlice(displayItems(), 0).count
    pageIndex = target.coerceIn(0, (count - 1).coerceAtLeast(0))
    render()
  }

  /**
   * The header's Back: a selection is cancelled first, and only a page with no
   * selection returns to the card. A stop in flight cannot be cancelled, so
   * Back leaves the page and lets the service drop the late result.
   */
  private fun onBackTapped() {
    if (selected != null && !stopping) {
      clearSelection()
      render()
      return
    }
    callbacks.onBack()
  }

  /** Selects one row and shows its tier; nothing is stopped on this tap. */
  private fun selectRow(row: DisplayRow) {
    if (stopping) return
    val target = stopTargetFor(row)
    selected = row
    selectedTarget = target
    selectedDecision = stopDecision(target)
    confirming = false
    stopError = null
    render()
  }

  /** Default selection state: a tier and, for warning rows, its reason. */
  private fun onStopTapped() {
    val decision = selectedDecision ?: return
    if (stopping || decision.risk == StopRisk.PROTECTED) return
    if (!confirming) {
      // A retry after a failure goes through the same confirmation again.
      stopError = null
      confirming = true
      render()
      return
    }
    stopping = true
    stopError = null
    render()
    selectedTarget?.let { callbacks.onStopRequested(it) }
  }

  /** Cancel clears the selection and restores the age/pager footer. */
  private fun onCancelTapped() {
    clearSelection()
    render()
  }

  /** Drops every selection field; the caller repaints. */
  private fun clearSelection() {
    selected = null
    selectedTarget = null
    selectedDecision = null
    confirming = false
    stopping = false
    stopError = null
  }

  /**
   * The current filter's rows in display order.
   *
   * Apps groups by base package (the pure [groupApps], which also hides
   * `Native`/`System`), carries every raw OOM group of that package and keeps
   * `pid` null because the stop acts on the package. Processes shows the sorted
   * full process name and resolves its base package for the policy; an
   * unresolved base (a native binary or a package hidden from this app) stays
   * null and therefore protected.
   */
  private fun displayItems(): List<DisplayRow> = when (mode) {
    Mode.APPS -> {
      val groupsByPackage = processes
        .groupBy({ it.name.substringBefore(':') }, { it.group })
        .mapValues { (_, groups) -> groups.toSet() }
      groupApps(sortProcesses(processes)).map { app ->
        DisplayRow(
          label = appLabel(app.packageName),
          memoryKb = app.memoryKb,
          packageName = app.packageName,
          groups = groupsByPackage[app.packageName].orEmpty(),
          pid = null,
          processCount = app.processCount,
          uid = app.uid,
        )
      }
    }

    Mode.PROCESSES -> {
      val counts = processes.groupingBy { it.name.substringBefore(':') }.eachCount()
      sortProcesses(processes).map { process ->
        val base = process.name.substringBefore(':')
        DisplayRow(
          label = process.name,
          memoryKb = process.memoryKb,
          packageName = if (resolveApplicationInfo(base) != null) base else null,
          groups = setOf(process.group),
          pid = process.pid,
          processCount = (counts[base] ?: 1).coerceAtLeast(1),
          uid = process.uid,
        )
      }
    }
  }

  /**
   * Builds the policy input for [row] (ANDROID.md §3, §9 T18).
   *
   * Apps rows are known apps even when package visibility hides their
   * [ApplicationInfo]; the policy then sees [StopTarget.detailsHidden] and
   * keeps them stoppable with a warning. Processes rows are known only when
   * their base package resolves, so native/unknown names are protected. All
   * Android flags and the launcher/IME/self/Shizuku identities come from the
   * same cached lookups the list uses. The UID is the resolved
   * `ApplicationInfo` UID when visible, otherwise the shell-reported row UID
   * (T19c-3a), so a hidden core process is still protected by UID < 10000
   * instead of being downgraded to a warning.
   */
  private fun stopTargetFor(row: DisplayRow): StopTarget {
    val appRow = mode == Mode.APPS
    val packageName = row.packageName
    val info = packageName?.let { resolveApplicationInfo(it) }
    return StopTarget(
      packageName = packageName,
      uid = info?.uid ?: row.uid,
      groups = row.groups,
      isKnownApp = appRow || info != null,
      isSystemApp = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
      isUpdatedSystemApp = info != null &&
        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
      isPersistent = info != null && (info.flags and ApplicationInfo.FLAG_PERSISTENT) != 0,
      detailsHidden = appRow && info == null,
      isLauncher = packageName != null && launcherPackages().contains(packageName),
      isIme = packageName != null && packageName == currentImePackage(),
      isSelf = packageName != null && packageName == context.packageName,
      isShizuku = packageName == SHIZUKU_PACKAGE,
    )
  }

  /**
   * Resolves and caches one package's [ApplicationInfo].
   *
   * A hidden or missing package caches as null so the list, the stop target and
   * the label never disagree for the same snapshot. Package visibility is not
   * broadened: the lookup sees exactly what the app is allowed to see.
   */
  private fun resolveApplicationInfo(packageName: String): ApplicationInfo? {
    if (infoCache.containsKey(packageName)) return infoCache[packageName]
    val info = try {
      context.packageManager.getApplicationInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
      null
    } catch (e: Exception) {
      null
    }
    infoCache[packageName] = info
    return info
  }

  /** Package label if the package is installed, otherwise the package name. */
  private fun appLabel(packageName: String): String {
    labelCache[packageName]?.let { return it }
    val info = resolveApplicationInfo(packageName)
    val label = if (info != null) {
      try {
        context.packageManager.getApplicationLabel(info).toString()
      } catch (e: Exception) {
        packageName
      }
    } else {
      packageName
    }
    labelCache[packageName] = label
    return label
  }

  /** Launcher packages, resolved once from the HOME intent and cached. */
  private fun launcherPackages(): Set<String> {
    launcherPackages?.let { return it }
    val packages = try {
      val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      context.packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()
    } catch (e: Exception) {
      emptySet()
    }
    launcherPackages = packages
    return packages
  }

  /** The current default IME package, read once from secure settings. */
  private fun currentImePackage(): String? {
    if (imeLoaded) return imePackage
    imeLoaded = true
    imePackage = try {
      Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.substringBefore('/')
        ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
      null
    }
    return imePackage
  }

  /** Repaints every region from the current state; the single render path. */
  private fun render() {
    val items = displayItems()
    val slice = pageSlice(items, pageIndex)
    pageIndex = slice.index
    val data = content == Content.DATA
    val hasItems = data && items.isNotEmpty()
    val hasSelection = selected != null

    listContainer.visibility = if (hasItems) VISIBLE else GONE
    stateContainer.visibility = if (hasItems) GONE else VISIBLE
    footerRow.visibility = if (data && !hasSelection) VISIBLE else GONE
    selectionBar.visibility = if (hasSelection) VISIBLE else GONE

    styleModeChip(appsChip, mode == Mode.APPS)
    styleModeChip(processesChip, mode == Mode.PROCESSES)
    summaryText.text = summaryLine()

    when (content) {
      Content.LOADING -> showState(loadingMessage, start = false)
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

    if (hasSelection) bindSelectionBar()

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

  /**
   * Binds the visible rows, including their selection state.
   *
   * The container is the clickable/focusable target and carries the accessible
   * name; the child texts stay out of the accessibility tree so TalkBack reads
   * one row, not three fragments.
   */
  private fun bindRows(items: List<DisplayRow>) {
    rows.forEachIndexed { index, holder ->
      val item = items.getOrNull(index)
      if (item == null) {
        holder.container.visibility = GONE
      } else {
        val selected = isSelected(item)
        holder.container.visibility = VISIBLE
        holder.name.text = item.label
        holder.value.text = fmtProcessRam(item.memoryKb)
        holder.accent.visibility = if (selected) VISIBLE else INVISIBLE
        holder.container.background = rowBackground(selected)
        holder.container.contentDescription = rowDescription(item, selected)
        holder.container.setOnClickListener { selectRow(item) }
      }
    }
  }

  /** True when [row] is the current selection; rows are value-equal. */
  private fun isSelected(row: DisplayRow): Boolean = selected == row

  /** The selected tint, or a transparent fill that keeps the row tappable. */
  private fun rowBackground(selected: Boolean): Drawable =
    GradientDrawable().apply {
      cornerRadius = ROW_RADIUS_DP * resources.displayMetrics.density
      setColor(if (selected) SELECTED_ROW_TINT else Color.TRANSPARENT)
    }

  /** `AICore 956M, select for details`-style row text with the selected state. */
  private fun rowDescription(row: DisplayRow, selected: Boolean): String {
    val count = if (mode == Mode.APPS && row.processCount > 1) {
      ", ${row.processCount} processes"
    } else {
      ""
    }
    // Neutral wording: a protected row must not promise a Stop.
    val state = if (selected) "Selected." else "Select for details."
    return "${row.label} ${fmtProcessRam(row.memoryKb)}$count. $state"
  }

  /**
   * Fills the selection bar for the current state.
   *
   * Initial: the name and memory as the title, the package underneath for Apps
   * rows, and the tier as the meta — a padlock glyph for PROTECTED, the reason
   * for WARNING, and no meta line for NORMAL (the title and package suffice).
   * After the first Stop tap it becomes `Stop <name>?` plus the whole-app
   * sentence; while the stop runs both buttons are hidden; after a failure it
   * becomes `Couldn't stop <name>` plus the message and the reason, with
   * Stop/Cancel so the user can retry or cancel. Protected rows never receive
   * a Stop button.
   */
  private fun bindSelectionBar() {
    val row = selected ?: return
    val decision = selectedDecision ?: return
    val name = selectionName(row)
    val error = stopError
    val risk = decision.risk

    selectionTitle.text = when {
      stopping -> "Stopping $name\u2026"
      error != null -> "Couldn't stop $name"
      confirming -> "Stop $name?"
      else -> when (mode) {
        Mode.APPS -> "$name \u00b7 ${fmtProcessRam(row.memoryKb)}"
        Mode.PROCESSES ->
          "$name (pid ${row.pid}) \u00b7 ${fmtProcessRam(row.memoryKb)}"
      }
    }

    // Apps rows always show the package here; Processes rows keep it GONE
    // because their title already contains the package/pid.
    selectionPackage.text = packageText(row)
    selectionPackage.visibility = if (mode == Mode.APPS) VISIBLE else GONE

    val meta = when {
      error != null -> listOfNotNull(error, decision.reason).joinToString(" \u00b7 ")
      confirming -> confirmationMeta(decision)
      risk == StopRisk.PROTECTED -> "\uD83D\uDD12 PROTECTED \u00b7 ${decision.reason}"
      risk == StopRisk.WARNING -> "WARNING \u00b7 ${decision.reason}"
      else -> null
    }
    selectionMeta.text = meta.orEmpty()
    selectionMeta.visibility = if (meta != null) VISIBLE else GONE
    selectionMeta.setTextColor(
      when {
        error != null -> Palette.red
        risk == StopRisk.WARNING -> Palette.amber
        else -> Palette.gray
      },
    )

    val stoppable = risk != StopRisk.PROTECTED
    stopButton.visibility = if (stoppable && !stopping) VISIBLE else GONE
    cancelButton.visibility = if (stopping) GONE else VISIBLE
    stopButton.isEnabled = !stopping
    cancelButton.isEnabled = !stopping
    val label = if (mode == Mode.PROCESSES) "Stop app" else "Stop"
    stopButton.text = label
    stopButton.contentDescription = if (mode == Mode.PROCESSES) {
      "Stop $name and all of its processes"
    } else {
      "Stop $name"
    }
    cancelButton.contentDescription = "Cancel selection"
  }

  /** The short name used in the bar and its titles. */
  private fun selectionName(row: DisplayRow): String = when (mode) {
    Mode.APPS -> row.label
    Mode.PROCESSES -> row.packageName ?: row.label
  }

  /** The package line under the title, with the row name for an unresolved package. */
  private fun packageText(row: DisplayRow): String =
    row.packageName?.takeIf { it.isNotEmpty() } ?: selectionName(row)

  /** `WARNING · reason · This stops every process of the app.` or the sentence alone. */
  private fun confirmationMeta(decision: StopDecision): String = when (decision.risk) {
    StopRisk.WARNING ->
      "WARNING \u00b7 ${decision.reason} \u00b7 This stops every process of the app."
    else -> "This stops every process of the app."
  }

  /**
   * Fills the footer: `PSS · N apps · just now` or `RSS · N processes · …` on
   * the left and the pager on the right. The metric label follows the snapshot,
   * so a `ps` fallback is never shown as PSS. With no rows the pager reads
   * `0 of 0` and both arrows are disabled. Hidden while a selection bar
   * replaces it.
   */
  private fun bindFooter(slice: PageSlice<DisplayRow>, total: Int) {
    footerText.text = metric.label + " \u00b7 " +
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
   * `RAM by process, Apps, 42 apps, updated just now`, plus the selected row,
   * its package, tier and tier reason (and a stop failure) while the selection
   * bar is visible. Non-data states name themselves so TalkBack never reads a
   * stale count.
   */
  private fun pageDescription(total: Int): String {
    val base = when (content) {
      Content.LOADING -> "RAM by process, scanning"
      Content.ERROR -> "RAM by process, error, $stateMessage"
      Content.UNAVAILABLE -> "RAM by process, unavailable, $stateMessage"
      Content.DATA -> "RAM by process, ${metric.label}, ${mode.label}, " +
        "${fmtProcessCount(total, mode.apps)}, updated ${fmtAge(now - capturedAt)}"
    }
    val row = selected ?: return base
    val decision = selectedDecision ?: return base
    val riskWord = when (decision.risk) {
      StopRisk.PROTECTED -> "protected"
      StopRisk.WARNING -> "warning"
      StopRisk.NORMAL -> "normal"
    }
    val reason = decision.reason?.let { ", $it" }.orEmpty()
    val state = when {
      stopping -> ", stopping"
      stopError != null -> ", stop failed"
      confirming -> ", confirm stop"
      else -> ""
    }
    return "$base, selected ${selectionName(row)}, package ${packageText(row)}, " +
      "$riskWord$reason$state"
  }

  private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  companion object {
    private const val TITLE_SIZE_SP = 10f
    private const val ROW_TEXT_SIZE_SP = 10f
    private const val FOOTER_TEXT_SIZE_SP = 9f
    private const val BUTTON_TEXT_SIZE_SP = 10f

    /** Selection bar: title / meta / button text sizes from the spec. */
    private const val SELECTION_TITLE_SIZE_SP = 10f
    private const val SELECTION_META_SIZE_SP = 9f

    /** Slight tracking, as the spec asks for a letter-spaced header. */
    private const val TITLE_LETTER_SPACING = 0.08f

    private const val BUTTON_RADIUS_DP = 6f
    private const val BUTTON_PAD_H_DP = 8f
    private const val BUTTON_PAD_V_DP = 2f
    private const val CHIP_GAP_DP = 6f

    /** ~26 dp rows: 7 dp padding above and below the 10 sp line (T18). */
    private const val ROW_PAD_V_DP = 7f
    private const val VALUE_GAP_DP = 6f
    private const val ROW_GAP_DP = 6f
    private const val ROW_TOP_GAP_DP = 4f
    private const val STATE_TOP_GAP_DP = 8f
    private const val STATE_BUTTON_GAP_DP = 6f

    /** Selected row: 2 dp blue accent and the blue-tinted `0x332F81F7` fill. */
    private const val ACCENT_WIDTH_DP = 2f
    private const val ACCENT_HEIGHT_DP = 18f
    private const val ACCENT_GAP_DP = 4f
    private const val ROW_RADIUS_DP = 4f
    private const val SELECTED_ROW_TINT = 0x332F81F7

    private const val SELECTION_META_GAP_DP = 2f
    private const val SELECTION_BUTTON_TOP_GAP_DP = 4f
    private const val SELECTION_BUTTON_GAP_DP = 6f

    private const val PAGER_PAD_H_DP = 4f
    private const val PAGER_PAD_V_DP = 1f
    private const val PAGER_GAP_DP = 4f

    /** Dims a disabled control without hiding its label against the same gray. */
    private const val DISABLED_ALPHA = 0.5f

    /** Default state text; the service overrides it after a successful stop. */
    const val DEFAULT_LOADING_MESSAGE = "Scanning processes\u2026"

    /** Shell provider package; stopping it would remove the stop route. */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
  }
}
