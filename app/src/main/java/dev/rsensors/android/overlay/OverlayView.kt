package dev.rsensors.android.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.rsensors.android.core.BATTERY_HOT
import dev.rsensors.android.core.BATTERY_WARN
import dev.rsensors.android.core.CPU_HOT
import dev.rsensors.android.core.CPU_WARN
import dev.rsensors.android.core.DASH
import dev.rsensors.android.core.GPU_HOT
import dev.rsensors.android.core.GPU_WARN
import dev.rsensors.android.core.GraphSeries
import dev.rsensors.android.core.Level
import dev.rsensors.android.core.PillVisibility
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.Segment
import dev.rsensors.android.core.SegmentKind
import dev.rsensors.android.core.Strip
import dev.rsensors.android.core.ThermalStatus
import dev.rsensors.android.core.ValueRange
import dev.rsensors.android.core.VisibilityKind
import dev.rsensors.android.core.fmtFps
import dev.rsensors.android.core.fmtTemp
import dev.rsensors.android.core.fpsLevel
import dev.rsensors.android.core.level
import dev.rsensors.android.core.thermalLevel
import dev.rsensors.android.core.valueRange
import dev.rsensors.android.ui.Palette
import kotlin.math.abs

/**
 * The overlay panel: a collapsed pill row plus an expanded card, stacked in one
 * dark rounded window (ANDROID.md §3, §4, §8 T9, T15).
 *
 * Classic Views, not Compose: the window is owned by a service, so there is no
 * activity lifecycle or saved-state owner for a composition to hold onto.
 *
 * Behaviour:
 * - The pill row is always visible and carries the same segments as before,
 *   plus the 6dp source dot.
 * - The card is GONE while collapsed. It shows individual CPU, GPU and BAT
 *   rows, each with the 2-minute `max`/`min`, the thermal status word, the FPS,
 *   the per-core line, an optional board line, the `GRAPH · 2 MIN` sparkline
 *   with its own series selector, the CPU/GPU / BAT / FPS chips and Refresh /
 *   Diagnostics / Stop monitoring buttons (ANDROID.md §3, §8 T7, T15, T15b,
 *   T15i). The wordy source/age row is gone: the source lives in Diagnostics.
 *   The chips drive the pill and notification only; the graph selector is
 *   independent of them.
 * - Tap toggles the card, long-press opens the app, drag moves the window and
 *   snaps it to the nearer side edge on release. A gesture that turned into a
 *   drag or a long-press never toggles; the buttons and chips consume their own
 *   taps before the panel sees them.
 *
 * The window itself stays `FLAG_NOT_FOCUSABLE` (it must never take input focus
 * from the app underneath); the buttons and chips are still clickable, focusable
 * views for touch and accessibility.
 *
 * Threading: constructed, mutated and drawn on the main thread only.
 */
class OverlayView(
  context: Context,
  private val windowManager: WindowManager,
  private val params: WindowManager.LayoutParams,
  private val callbacks: Callbacks,
) : LinearLayout(context) {
  /** Gestures and state changes the panel reports; implemented by the service. */
  interface Callbacks {
    /** Tap without drag or long-press: show or hide the card. */
    fun onToggleExpanded()

    /** Delayed long-press: bring the app to the front. */
    fun onLongPress()

    /** The card's Refresh button was pressed. */
    fun onRefresh()

    /** The card's Diagnostics button was pressed. */
    fun onDiagnostics()

    /** A CPU/GPU / BAT / FPS chip was pressed; the service owns the state. */
    fun onVisibilityToggled(kind: VisibilityKind)

    /** The card's graph selector was pressed; the argument is already cycled. */
    fun onGraphSeriesChanged(series: GraphSeries)

    /** The card's Stop monitoring button was pressed. */
    fun onStopOverlay()

    /** The card's Start Shizuku button was pressed (server down, T15g). */
    fun onStartShizuku()

    /** The window settled at a new position (after snap); persist it. */
    fun onPositionChanged(x: Int, y: Int, expanded: Boolean)
  }

  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

  private val pillRow = LinearLayout(context).apply {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
  }

  private val card = LinearLayout(context).apply {
    orientation = VERTICAL
    visibility = GONE
  }

  private val cpuValue = makeValue()
  private val cpuDetail = makeDetail()
  private val gpuValue = makeValue()
  private val gpuDetail = makeDetail()
  private val batteryValue = makeValue()
  private val batteryDetail = makeDetail()
  private val thermalValue = makeValue()
  private val fpsValue = makeValue()
  private val coresLine = makeDetail()
  private val boardLine = makeDetail()
  private val sparkline = SparklineView(context)

  /** Chip state last handed in by the service; also drives the card's text. */
  private var chipState = PillVisibility()

  private val cpuGpuChip = makeChip("CPU/GPU", VisibilityKind.CPUGPU)
  private val batChip = makeChip("BAT", VisibilityKind.BAT)
  private val fpsChip = makeChip("FPS", VisibilityKind.FPS)

  /** Sparkline selector state; the header button shows its label. */
  private var graphSeries = GraphSeries.CPU_GPU
  private val graphButton = makeButton(GraphSeries.CPU_GPU.label) {
    callbacks.onGraphSeriesChanged(graphSeries.next())
  }
  private val refreshButton = makeButton("Refresh") { callbacks.onRefresh() }
  private val diagnosticsButton = makeButton("Diagnostics") { callbacks.onDiagnostics() }
  private val stopButton = makeButton("Stop monitoring") { callbacks.onStopOverlay() }

  /**
   * Shown only while the Shizuku server is down (T15g). It sits in its own row
   * so adding it never reflows the Refresh / Diagnostics / Stop strip.
   */
  private val startShizukuButton = makeButton("Start Shizuku") { callbacks.onStartShizuku() }

  private val startShizukuRow = LinearLayout(context).apply {
    orientation = HORIZONTAL
    visibility = GONE
    addView(startShizukuButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(BUTTON_GAP_DP)
    }
  }

  /**
   * Last Shizuku state the service reported. Starts true, i.e. hidden, so the
   * card never claims the server is down before it has been checked.
   */
  private var shizukuRunning = true

  private var expanded = false

  private var downRawX = 0f
  private var downRawY = 0f
  private var startX = 0
  private var startY = 0
  private var dragging = false
  private var longPressFired = false

  /** Accessibility text for each state; the root announces the visible one. */
  private var pillDescription = "Rsensors"
  private var cardDescription = "Rsensors"

  /** Last collapsed strip, kept so the REC marker can rebuild the pill. */
  private var lastStrip: Strip? = null
  private var lastDotColor = Palette.gray

  /** Last sample, kept so a chip toggle can rebuild the card's description. */
  private var lastReading: Reading? = null

  /** Last 2-minute window, kept so the description can repeat the card's ranges. */
  private var lastHistory: List<Reading> = emptyList()

  /** True while the service records a session; draws the red REC marker. */
  private var recording = false

  private val longPressRunnable = Runnable {
    if (!dragging) {
      longPressFired = true
      callbacks.onLongPress()
    }
  }

  init {
    orientation = VERTICAL
    val density = resources.displayMetrics.density
    setPadding(
      dp(PAD_H_DP),
      dp(PAD_V_DP),
      dp(PAD_H_DP),
      dp(PAD_V_DP),
    )
    background = GradientDrawable().apply {
      cornerRadius = CORNER_RADIUS_DP * density
      setColor(Palette.dark)
      // 1px border, not dp: a hairline outline.
      setStroke(1, Palette.gray)
    }
    isClickable = true
    isFocusable = false
    setOnClickListener { callbacks.onToggleExpanded() }

    addView(pillRow)
    addView(card)
    buildCard()
    contentDescription = pillDescription
  }

  /** Builds the collapsed rows plus the source dot (ANDROID.md §3). */
  fun setStrip(strip: Strip, dotColor: Int) {
    lastStrip = strip
    lastDotColor = dotColor
    alpha = if (strip.dim) DIM_ALPHA else 1f
    pillDescription = strip.segments.joinToString(" ") { it.text }
    renderPill()
  }

  /**
   * Shows or hides the small red REC marker in the pill (ANDROID.md §3).
   *
   * Main thread only, like the rest of the view. The strip is re-rendered from
   * the cached last reading so the marker appears in the same frame as the
   * toggle instead of waiting for the next poll.
   */
  fun setRecording(recording: Boolean) {
    if (this.recording == recording) return
    this.recording = recording
    renderPill()
  }

  /** Rebuilds the pill row from the cached strip, dot and recording state. */
  private fun renderPill() {
    val strip = lastStrip ?: return
    pillRow.removeAllViews()
    strip.segments.forEachIndexed { index, segment ->
      pillRow.addView(makeSegmentView(segment, trailingSpace = index < strip.segments.lastIndex))
    }
    pillRow.addView(makeDotView(lastDotColor))
    if (recording) pillRow.addView(makeRecView())
    contentDescription = descriptionText()
  }

  /**
   * Repaints the card from the latest sample and the sparkline history.
   *
   * @param reading latest sample, or null before the first poll; every missing
   *   value renders as an em dash rather than a guess
   * @param history readings inside the sparkline window, oldest first
   * @param now epoch millis, the right edge of the graph's time axis
   */
  fun setReading(reading: Reading?, history: List<Reading>, now: Long) {
    lastReading = reading
    lastHistory = history
    val cpu = reading?.cpu
    setRow(
      cpuValue,
      cpuDetail,
      fmtTemp(cpu),
      level(cpu, CPU_WARN, CPU_HOT),
      rangeText(history.valueRange { it.cpu }),
    )

    val gpu = reading?.gpu
    setRow(
      gpuValue,
      gpuDetail,
      fmtTemp(gpu),
      level(gpu, GPU_WARN, GPU_HOT),
      rangeText(history.valueRange { it.gpu }),
    )

    val battery = reading?.battery
    setRow(
      batteryValue,
      batteryDetail,
      fmtTemp(battery),
      level(battery, BATTERY_WARN, BATTERY_HOT),
      rangeText(history.valueRange { it.battery }),
    )

    val status = reading?.thermalStatus ?: ThermalStatus.UNKNOWN
    thermalValue.text = if (status == ThermalStatus.UNKNOWN) DASH else status.name
    thermalValue.setTextColor(levelColor(thermalLevel(status)))

    // An idle foreground app reports 0, which is a real measurement and grades
    // red; only a missing shell route is a dash (ANDROID.md §3, §9).
    val fps = reading?.fps
    fpsValue.text = fmtFps(fps)
    fpsValue.setTextColor(levelColor(fpsLevel(fps)))

    val cores = reading?.cores.orEmpty()
    coresLine.text = if (cores.isEmpty()) {
      "per-core n/a"
    } else {
      "cores " + cores.joinToString(" ") { fmtTemp(it) }
    }
    coresLine.setTextColor(if (cores.isEmpty()) Palette.gray else Palette.white)

    val board = compactBoard(reading?.board.orEmpty())
    boardLine.visibility = if (board.isEmpty()) GONE else VISIBLE
    boardLine.text = board.joinToString(" \u00b7 ") { (name, value) ->
      "${shortBoardName(name)} ${fmtTemp(value)}"
    }

    sparkline.setHistory(history, now)

    cardDescription = buildCardDescription(reading)
    contentDescription = descriptionText()
  }

  /**
   * Applies the pill's chip state (T15).
   *
   * Called by the service with the persisted preference on every poll, so the
   * chips follow a toggle even if a poll never completes. The chips drive only
   * the pill and its notification; the card's graph has its own selector
   * ([setGraphSeries]) and always sees the full recorded history (T15b).
   *
   * @param visibility enabled kinds; normalised so the last chip cannot be
   *   cleared by a corrupted preference
   */
  fun setVisibility(visibility: PillVisibility) {
    val next = visibility.normalised()
    if (chipState == next) return
    chipState = next
    styleChip(cpuGpuChip, next.cpuGpu)
    styleChip(batChip, next.bat)
    styleChip(fpsChip, next.fps)
    cardDescription = buildCardDescription(lastReading)
    contentDescription = descriptionText()
  }

  /**
   * Applies the sparkline's selected series (T15b).
   *
   * Updates the selector button text and forwards the choice to the graph. The
   * pill chips are deliberately untouched: they choose what the pill and
   * notification show, not what the graph draws.
   *
   * @param series the series to draw; the service owns persistence
   */
  fun setGraphSeries(series: GraphSeries) {
    graphSeries = series
    graphButton.text = series.label
    graphButton.contentDescription = "Graph series ${series.label}"
    sparkline.setGraphSeries(series)
    cardDescription = buildCardDescription(lastReading)
    contentDescription = descriptionText()
  }

  /**
   * Shows or hides the card's full-width Start Shizuku button (T15g).
   *
   * Fed from every poll's `Shizuku.pingBinder()` result, so the button appears
   * within one tick of the server dying and disappears when it comes back. The
   * card changes size, so the window is re-clamped to stay on screen.
   *
   * @param running true when the Shizuku server answers; the button is hidden
   */
  fun setShizukuRunning(running: Boolean) {
    if (shizukuRunning == running) return
    shizukuRunning = running
    startShizukuRow.visibility = if (running) GONE else VISIBLE
    contentDescription = descriptionText()
    post { ensureVisible() }
  }

  /**
   * Shows or hides the card.
   *
   * The height changes, so the window is re-clamped once laid out to keep the
   * card from hanging off the bottom of the display.
   */
  fun setExpanded(expanded: Boolean) {
    this.expanded = expanded
    card.visibility = if (expanded) VISIBLE else GONE
    contentDescription = descriptionText()
    post { ensureVisible() }
  }

  /**
   * Drag to move, tap to toggle, long-press to open the app (ANDROID.md §9).
   *
   * Dragging starts only after the scaled touch slop is exceeded, so a tap with
   * a little finger movement still counts as a tap and the long-press timer is
   * only cancelled once the finger really travels.
   */
  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downRawX = event.rawX
        downRawY = event.rawY
        startX = params.x
        startY = params.y
        dragging = false
        longPressFired = false
        removeCallbacks(longPressRunnable)
        postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
          dragging = true
          removeCallbacks(longPressRunnable)
        }
        if (dragging) {
          val bounds = OverlayParams.screenBounds(windowManager, context)
          params.x = OverlayParams.clamp(startX + dx.toInt(), bounds.width() - width)
          params.y = OverlayParams.clamp(startY + dy.toInt(), bounds.height() - height)
          windowManager.updateViewLayout(this, params)
        }
        return true
      }

      MotionEvent.ACTION_UP -> {
        removeCallbacks(longPressRunnable)
        when {
          dragging -> {
            snapToEdge()
            callbacks.onPositionChanged(params.x, params.y, expanded)
          }

          !longPressFired -> performClick()
        }
        dragging = false
        longPressFired = false
        return true
      }

      MotionEvent.ACTION_CANCEL -> {
        removeCallbacks(longPressRunnable)
        dragging = false
        longPressFired = false
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  /**
   * Accessibility entry point for a tap: the framework calls this instead of
   * synthesising touches, and our custom path calls it for a real tap.
   */
  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  override fun onDetachedFromWindow() {
    removeCallbacks(longPressRunnable)
    super.onDetachedFromWindow()
  }

  /** Re-clamps after the first layout and whenever the card changes the size. */
  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    post { ensureVisible() }
  }

  /**
   * Snaps the window to the nearer side edge, keeping a small margin so the
   * rounded corner is not flush with the screen edge, then clamps vertically.
   */
  private fun snapToEdge() {
    val bounds = OverlayParams.screenBounds(windowManager, context)
    val margin = dp(SNAP_MARGIN_DP)
    val maxX = bounds.width() - width
    params.x = if (params.x + width / 2 < bounds.width() / 2) {
      margin
    } else {
      (maxX - margin).coerceAtLeast(0)
    }
    params.x = OverlayParams.clamp(params.x, maxX)
    params.y = OverlayParams.clamp(params.y, bounds.height() - height)
    windowManager.updateViewLayout(this, params)
  }

  /** Pulls the window fully back on screen; no-op until the view is measured. */
  private fun ensureVisible() {
    if (width <= 0 || height <= 0) return
    val bounds = OverlayParams.screenBounds(windowManager, context)
    val x = OverlayParams.clamp(params.x, bounds.width() - width)
    val y = OverlayParams.clamp(params.y, bounds.height() - height)
    if (x == params.x && y == params.y) return
    params.x = x
    params.y = y
    windowManager.updateViewLayout(this, params)
  }

  private fun buildCard() {
    card.layoutParams = LayoutParams(dp(CARD_WIDTH_DP), LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(CARD_TOP_MARGIN_DP)
    }
    card.addView(makeRow("CPU", cpuValue, cpuDetail))
    card.addView(makeRow("GPU", gpuValue, gpuDetail))
    card.addView(makeRow("BAT", batteryValue, batteryDetail))
    card.addView(makeRow("THRM", thermalValue, null))
    card.addView(makeRow("FPS", fpsValue, null))
    coresLine.setPadding(0, 0, 0, 0)
    boardLine.setPadding(0, 0, 0, 0)
    card.addView(coresLine)
    card.addView(boardLine)
    card.addView(makeGraphHeader())
    card.addView(sparkline)
    // The sparkline fixes its own 36dp height; only the gap is ours to set. The
    // header sits directly above it, so this gap stays tighter than a row gap.
    (sparkline.layoutParams as LayoutParams).topMargin = dp(GRAPH_GAP_DP)
    card.addView(makeChipRow())
    card.addView(makeActionRow())
    card.addView(startShizukuRow)
    card.addView(makeStopRow())
  }

  /**
   * The graph's header row: `GRAPH · 2 MIN` on the left, the series selector on
   * the right (ANDROID.md §3, §9 T15b).
   *
   * The label takes the remaining width with weight 1, so the selector stays
   * flush right and both fit the 220 dp card without shrinking the 10 sp text.
   */
  private fun makeGraphHeader(): LinearLayout =
    LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(makeLabel(GRAPH_LABEL), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      addView(graphButton)
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(ROW_GAP_DP)
      }
    }

  /**
   * The CPU/GPU, BAT and FPS chips, above the action row (ANDROID.md §3, §9
   * T15i).
   *
   * Three chips at 10 sp with 8 dp padding need ~140 dp of the card's 200 dp
   * content width even with the wider `CPU/GPU` label, so one row is enough and
   * nothing has to shrink.
   */
  private fun makeChipRow(): LinearLayout =
    LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(cpuGpuChip)
      addView(
        batChip,
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
          marginStart = dp(BUTTON_GAP_DP)
        },
      )
      addView(
        fpsChip,
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
          marginStart = dp(BUTTON_GAP_DP)
        },
      )
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(ROW_GAP_DP)
      }
    }

  /** Refresh and Diagnostics share the compact row they have had since T9. */
  private fun makeActionRow(): LinearLayout =
    LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.END
      addView(refreshButton)
      addView(
        diagnosticsButton,
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
          marginStart = dp(BUTTON_GAP_DP)
        },
      )
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(ROW_GAP_DP)
      }
    }

  /**
   * Stop monitoring gets its own full-width row.
   *
   * Three buttons at 10 sp / 8 dp padding need roughly 228 dp, more than the
   * card's 200 dp content width, so the third one moves to a second row at the
   * spec's size instead of dropping below the 9 sp legibility floor.
   */
  private fun makeStopRow(): LinearLayout =
    LinearLayout(context).apply {
      orientation = HORIZONTAL
      addView(
        stopButton,
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
      )
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(BUTTON_GAP_DP)
      }
    }

  /**
   * One `LABEL  value detail` row.
   *
   * @param label fixed left-hand caption, dimmed
   * @param value coloured reading
   * @param detail optional grey suffix such as `max 42°`; hidden when null
   */
  private fun makeRow(label: String, value: TextView, detail: TextView?): LinearLayout =
    LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(makeLabel(label))
      addView(value)
      detail?.let { addView(it) }
    }

  private fun makeLabel(text: String): TextView = TextView(context).apply {
    this.text = text
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    setPadding(0, 0, dp(LABEL_GAP_DP), 0)
  }

  private fun makeValue(): TextView = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private fun makeDetail(): TextView = TextView(context).apply {
    setTextColor(Palette.gray)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    setPadding(dp(LABEL_GAP_DP), 0, 0, 0)
  }

  /**
   * A compact card action button.
   *
   * Shared by Refresh, Diagnostics and Stop monitoring so they read as one control
   * strip. Focusable and clickable for accessibility even though the overlay
   * window is not focusable, matching the card's touch behaviour.
   *
   * @param text visible label and content description
   * @param onClick invoked on a tap; the service decides what the action means
   */
  private fun makeButton(text: String, onClick: () -> Unit): Button = Button(context).apply {
    this.text = text
    contentDescription = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP)
    setTextColor(Palette.white)
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

  /**
   * One visibility chip.
   *
   * A clickable [TextView] rather than a [Button] so the selected state can
   * swap both fill and text colour inside the same rounded shape; clickable for
   * the same reason the action buttons are, so a tap is consumed here and never
   * reaches the panel's toggle gesture.
   *
   * @param text chip label, also the base of its content description
   * @param kind which pill kind this chip switches
   */
  private fun makeChip(text: String, kind: VisibilityKind): TextView = TextView(context).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    gravity = Gravity.CENTER
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    setPadding(dp(BUTTON_PAD_H_DP), dp(BUTTON_PAD_V_DP), dp(BUTTON_PAD_H_DP), dp(BUTTON_PAD_V_DP))
    isFocusable = true
    isClickable = true
    setOnClickListener { callbacks.onVisibilityToggled(kind) }
    background = GradientDrawable().apply {
      cornerRadius = BUTTON_RADIUS_DP * resources.displayMetrics.density
      setColor(Palette.green)
    }
    // The field defaults to PillVisibility(), i.e. every chip starts selected.
    styleChip(this, true)
  }

  /**
   * Paints a chip's selected / unselected state (T15).
   *
   * Selected is the palette's OK green with dark text, so "on" reads at a
   * glance; unselected is the action buttons' gray with white text. The content
   * description repeats the state for TalkBack, because colour alone is not a
   * label.
   */
  private fun styleChip(chip: TextView, on: Boolean) {
    (chip.background as? GradientDrawable)?.setColor(if (on) Palette.green else Palette.gray)
    chip.setTextColor(if (on) Palette.dark else Palette.white)
    chip.contentDescription = "${chip.text} ${if (on) "on" else "off"}"
  }

  /**
   * Sets one row's value and optional grey suffix.
   *
   * @param detailText the 2-minute suffix such as `max 45° min 38°`; a missing
   *   sensor lands here as `max — min —` because every card field is drawn,
   *   never omitted (ANDROID.md §2.2). A null detail hides the suffix for rows
   *   that have none.
   */
  private fun setRow(
    value: TextView,
    detail: TextView?,
    text: String,
    level: Level,
    detailText: String?,
  ) {
    value.text = text
    value.setTextColor(levelColor(level))
    detail?.let {
      it.visibility = if (detailText == null) GONE else VISIBLE
      it.text = detailText.orEmpty()
    }
  }

  /**
   * One row's 2-minute detail, `max 45° min 38°` (ANDROID.md §3, §9 T15i).
   *
   * [ValueRange] is built over the same history list the sparkline draws, so
   * the numbers and the graph describe the same window. An empty window yields
   * `max — min —` rather than dropping the detail.
   */
  private fun rangeText(range: ValueRange): String =
    "max ${fmtTemp(range.max)} min ${fmtTemp(range.min)}"

  private fun buildCardDescription(reading: Reading?): String {
    if (reading == null) {
      return "Rsensors waiting for a reading, graph ${graphSeries.label}, 2 minutes, showing ${kindsSummary()}"
    }
    val cpuRange = lastHistory.valueRange { it.cpu }
    val gpuRange = lastHistory.valueRange { it.gpu }
    val batteryRange = lastHistory.valueRange { it.battery }
    val parts = mutableListOf(
      "CPU ${fmtTemp(reading.cpu)}",
      "CPU max ${fmtTemp(cpuRange.max)}",
      "CPU min ${fmtTemp(cpuRange.min)}",
      "GPU ${fmtTemp(reading.gpu)}",
      "GPU max ${fmtTemp(gpuRange.max)}",
      "GPU min ${fmtTemp(gpuRange.min)}",
      "battery ${fmtTemp(reading.battery)}",
      "battery max ${fmtTemp(batteryRange.max)}",
      "battery min ${fmtTemp(batteryRange.min)}",
      "thermal ${reading.thermalStatus.name}",
      "FPS ${fmtFps(reading.fps)}",
    )
    parts += if (reading.cores.isEmpty()) {
      "per-core not available"
    } else {
      "cores " + reading.cores.joinToString(" ") { fmtTemp(it) }
    }
    parts += "graph ${graphSeries.label}, 2 minutes"
    parts += "showing ${kindsSummary()}"
    return parts.joinToString(", ")
  }

  /** The enabled kinds in pill order, for the card's accessibility text. */
  private fun kindsSummary(): String = buildList {
    if (chipState.cpuGpu) add("CPU/GPU")
    if (chipState.bat) add("BAT")
    if (chipState.fps) add("FPS")
  }.joinToString(", ").ifEmpty { "CPU/GPU" }

  private fun levelColor(level: Level): Int = when (level) {
    Level.OK -> Palette.green
    Level.WARN -> Palette.amber
    Level.HOT -> Palette.red
    Level.UNKNOWN -> Palette.gray
  }

  /** The card's text when expanded, otherwise the pill plus recording state. */
  private fun descriptionText(): String {
    val base = when {
      expanded -> cardDescription
      recording -> "$pillDescription, recording"
      else -> pillDescription
    }
    // Only the card shows the button, so only its description names the state.
    return if (expanded && !shizukuRunning) "$base, Shizuku stopped" else base
  }

  private fun makeSegmentView(segment: Segment, trailingSpace: Boolean): TextView =
    TextView(context).apply {
      text = if (trailingSpace) "${segment.text} " else segment.text
      setTextColor(colorFor(segment.kind))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_TEXT_SIZE_SP)
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      gravity = Gravity.CENTER_VERTICAL
      isFocusable = false
      isClickable = false
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

  /**
   * The source dot: a small filled circle after the last segment.
   *
   * A plain [View] with an oval [GradientDrawable] rather than an image
   * resource, because the colour is data (green/amber/grey), not a theme value.
   * Margin, not padding, so it cannot stretch the panel's corner radius.
   */
  private fun makeDotView(color: Int): View {
    val size = dp(DOT_SIZE_DP)
    return View(context).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
      }
      layoutParams = LayoutParams(size, size).apply {
        marginStart = dp(DOT_MARGIN_DP)
      }
      isFocusable = false
      isClickable = false
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
  }

  /**
   * The recording marker: a compact red `REC` after the source dot.
   *
   * Same monospace face as the readings so it reads as part of the instrument,
   * and small enough not to widen the pill noticeably.
   */
  private fun makeRecView(): TextView = TextView(context).apply {
    text = "REC"
    setTextColor(Palette.red)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, REC_TEXT_SIZE_SP)
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    gravity = Gravity.CENTER_VERTICAL
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    setPadding(dp(REC_GAP_DP), 0, 0, 0)
  }

  private fun colorFor(kind: SegmentKind): Int = when (kind) {
    SegmentKind.LABEL -> Palette.white
    SegmentKind.OK -> Palette.green
    SegmentKind.WARN -> Palette.amber
    SegmentKind.HOT -> Palette.red
    SegmentKind.UNKNOWN -> Palette.gray
  }

  private fun shortBoardName(name: String): String =
    if (name.startsWith("POWER_AMPLIFIER")) "PA" else name

  /**
   * Picks the compact board line (ANDROID.md §3): the spec names SOC, SKIN and
   * the power amplifier, so those come first when the HAL reports them; any
   * remaining slots go to the other sensors in parser order.
   */
  private fun compactBoard(board: List<Pair<String, Double>>): List<Pair<String, Double>> {
    val preferred = board.filter { (name, _) -> name in BOARD_PREFERRED_ORDER }
      .sortedBy { (name, _) -> BOARD_PREFERRED_ORDER.indexOf(name) }
    val rest = board.filter { (name, _) -> name !in BOARD_PREFERRED_ORDER }
    return (preferred + rest).take(BOARD_MAX_ENTRIES)
  }

  private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  companion object {
    private const val PAD_H_DP = 10f
    private const val PAD_V_DP = 5f
    private const val PILL_TEXT_SIZE_SP = 14f
    private const val CARD_TEXT_SIZE_SP = 12f
    private const val LABEL_SIZE_SP = 10f
    private const val CORNER_RADIUS_DP = 8f
    private const val DIM_ALPHA = 0.45f

    /** Card width: wide enough for the sparkline without spanning the screen. */
    private const val CARD_WIDTH_DP = 220f
    private const val CARD_TOP_MARGIN_DP = 4f
    private const val ROW_GAP_DP = 6f
    private const val LABEL_GAP_DP = 6f

    /** Graph header caption and the tight gap before the sparkline (T15b). */
    private const val GRAPH_LABEL = "GRAPH \u00b7 2 MIN"
    private const val GRAPH_GAP_DP = 2f

    /** Source dot diameter and its gap from the last segment (ANDROID.md §3). */
    private const val DOT_SIZE_DP = 6f
    private const val DOT_MARGIN_DP = 6f

    /** Red REC marker: small enough to sit inside the pill without widening it. */
    private const val REC_TEXT_SIZE_SP = 9f
    private const val REC_GAP_DP = 5f

    /** Distance kept from the side edge after a snap. */
    private const val SNAP_MARGIN_DP = 8f

    private const val BUTTON_RADIUS_DP = 6f
    private const val BUTTON_PAD_H_DP = 8f
    private const val BUTTON_PAD_V_DP = 2f

    /** Gap between the card's Refresh and Diagnostics buttons. */
    private const val BUTTON_GAP_DP = 6f

    /** Board sensors shown on the compact line; the rest stay in Diagnostics. */
    private const val BOARD_MAX_ENTRIES = 3

    /** Card board-line preference, in display order (ANDROID.md §3). */
    private val BOARD_PREFERRED_ORDER = listOf("SOC", "SKIN", "POWER_AMPLIFIER")
  }
}
