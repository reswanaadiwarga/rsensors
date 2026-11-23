package dev.rsensors.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import dev.rsensors.android.core.GraphSeries
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.fpsAxisMax
import dev.rsensors.android.core.fpsFraction
import dev.rsensors.android.ui.Palette

/**
 * 2-minute history sparkline for the expanded overlay card (ANDROID.md §3, §9).
 *
 * Draws one or two antialiased polylines over one time axis at a fixed 36 dp
 * height. The graph is a shape cue, not a chart: there are no axes, labels or
 * grid, and every value it hints at is written above it in the card.
 *
 * [setGraphSeries] chooses what the graph draws, independently of the card's
 * CPU / GPU / FPS chips (T15b, T15c):
 * - [GraphSeries.CPU_GPU]: CPU in [Palette.green] and GPU in [Palette.amber],
 *   sharing one padded temperature scale so the two stay comparable.
 * - [GraphSeries.CPU] / [GraphSeries.GPU]: only that line, scaled from its own
 *   values.
 * - [GraphSeries.BAT]: battery temperature in [Palette.blue], scaled from the
 *   visible battery values with the same padding as the other temperature
 *   modes. Battery is drawn only in this mode; CPU+GPU stays CPU and GPU.
 * - [GraphSeries.FPS]: one [Palette.fps] line on a 0–120 baseline scale that
 *   rises to the next 30 fps step when the visible window records faster
 *   frames ([fpsAxisMax] + [fpsFraction]), with no temperature scaling.
 *
 * The card always passes the full history, so neither the selector nor the
 * chips filter what is recorded; changing series only changes what is drawn.
 *
 * Scaling: x is linear in [Reading.t] over [WINDOW_MS] ending at the `now`
 * passed to [setHistory]; a temperature y is linear over the combined min/max
 * of the drawn series, padded so the extremes never touch the edges. A missing
 * value breaks its series rather than drawing through the gap, so a dropped
 * sensor never looks like a flat line.
 *
 * Threading: main thread only, like the rest of the overlay.
 */
class SparklineView(context: Context) : View(context) {
  private val density = resources.displayMetrics.density

  private val cpuPaint = strokePaint(Palette.green)
  private val gpuPaint = strokePaint(Palette.amber)
  private val batteryPaint = strokePaint(Palette.blue)
  private val fpsPaint = strokePaint(Palette.fps)

  private var cpuPath = Path()
  private var gpuPath = Path()
  private var batteryPath = Path()
  private var fpsPath = Path()

  /** Last input, replayed when the view is first measured (see [onSizeChanged]). */
  private var lastSamples: List<Reading> = emptyList()
  private var lastNow = 0L

  /** Selected series; the combined temperature graph is the default. */
  private var graphSeries = GraphSeries.CPU_GPU

  init {
    layoutParams = ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      (HEIGHT_DP * density + 0.5f).toInt(),
    )
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  /**
   * Chooses which series the graph draws (T15b).
   *
   * Called by the card's selector, not by the pill chips. Rebuilds the paths in
   * the new mode; the history, time window and geometry are unaffected.
   */
  fun setGraphSeries(series: GraphSeries) {
    if (graphSeries == series) return
    graphSeries = series
    rebuild()
    invalidate()
  }

  /**
   * Replaces the graph contents.
   *
   * @param samples readings oldest first, as returned by
   *   [dev.rsensors.android.core.ReadingHistory.snapshot]; samples outside the
   *   window are ignored rather than clipped
   * @param now right edge of the time axis in epoch millis
   */
  fun setHistory(samples: List<Reading>, now: Long) {
    lastSamples = samples
    lastNow = now
    rebuild()
    invalidate()
  }

  /**
   * Paths depend on the measured size, which is zero until the window is laid
   * out; the first poll can land before that, so replay the last input.
   */
  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    rebuild()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    when (graphSeries) {
      GraphSeries.CPU_GPU -> {
        canvas.drawPath(cpuPath, cpuPaint)
        canvas.drawPath(gpuPath, gpuPaint)
      }

      GraphSeries.CPU -> canvas.drawPath(cpuPath, cpuPaint)
      GraphSeries.GPU -> canvas.drawPath(gpuPath, gpuPaint)
      GraphSeries.BAT -> canvas.drawPath(batteryPath, batteryPaint)
      GraphSeries.FPS -> canvas.drawPath(fpsPath, fpsPaint)
    }
  }

  private fun rebuild() {
    if (width <= 0 || height <= 0) return
    val start = lastNow - WINDOW_MS
    val visible = lastSamples.filter { it.t in start..lastNow }
    if (graphSeries == GraphSeries.FPS) {
      buildFpsPath(visible, start, lastNow)
    } else {
      buildTemperaturePaths(visible, start, lastNow)
    }
  }

  /**
   * Builds the temperature path(s); a series that is not selected leaves its
   * path empty so nothing is drawn.
   *
   * The y-range comes from the visible samples of the *drawn* series only,
   * which keeps a single-series graph from being squashed by values it does not
   * draw; with both on it keeps CPU and GPU comparable on one graph instead of
   * two independent scales.
   */
  private fun buildTemperaturePaths(samples: List<Reading>, start: Long, now: Long) {
    cpuPath = Path()
    gpuPath = Path()
    batteryPath = Path()
    fpsPath = Path()

    val showCpu = graphSeries == GraphSeries.CPU_GPU || graphSeries == GraphSeries.CPU
    val showGpu = graphSeries == GraphSeries.CPU_GPU || graphSeries == GraphSeries.GPU
    val showBattery = graphSeries == GraphSeries.BAT

    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    samples.forEach { reading ->
      if (showCpu) {
        reading.cpu?.let {
          if (it < min) min = it
          if (it > max) max = it
        }
      }
      if (showGpu) {
        reading.gpu?.let {
          if (it < min) min = it
          if (it > max) max = it
        }
      }
      if (showBattery) {
        reading.battery?.let {
          if (it < min) min = it
          if (it > max) max = it
        }
      }
    }
    if (min > max) return

    // Pad by 10% of the range, at least 1 °C, so a flat series sits mid-band
    // and the extremes stay clear of the view edges. The battery line uses the
    // same padding: its 30–45 °C band would be flattened by the CPU range.
    val pad = ((max - min) * 0.1).coerceAtLeast(1.0)
    val low = min - pad
    val high = max + pad
    val span = high - low

    val inset = STROKE_DP * density / 2f
    val usableHeight = height - inset * 2f

    fun append(path: Path, reading: Reading, value: Double, started: Boolean): Boolean {
      val x = xFor(reading, start, now)
      val y = inset + ((high - value) / span * usableHeight).toFloat()
      if (started) path.lineTo(x, y) else path.moveTo(x, y)
      return true
    }

    var cpuStarted = false
    var gpuStarted = false
    var batteryStarted = false
    samples.forEach { reading ->
      if (showCpu) {
        val cpu = reading.cpu
        cpuStarted = if (cpu == null) {
          false
        } else {
          append(cpuPath, reading, cpu, cpuStarted)
        }
      }
      if (showGpu) {
        val gpu = reading.gpu
        gpuStarted = if (gpu == null) {
          false
        } else {
          append(gpuPath, reading, gpu, gpuStarted)
        }
      }
      if (showBattery) {
        val battery = reading.battery
        batteryStarted = if (battery == null) {
          false
        } else {
          append(batteryPath, reading, battery, batteryStarted)
        }
      }
    }
  }

  /**
   * Builds the FPS path on a dynamic 0–120 baseline axis; a null FPS breaks the
   * line.
   *
   * The ceiling comes from the visible window's own peak ([fpsAxisMax]), so a
   * 144 Hz or 165 Hz phone is not clipped at 120. 0 sits at the bottom and
   * [fpsFraction]'s 1.0 at the top, independent of any temperature range
   * (ANDROID.md §3, §9).
   */
  private fun buildFpsPath(samples: List<Reading>, start: Long, now: Long) {
    cpuPath = Path()
    gpuPath = Path()
    batteryPath = Path()
    val path = Path()
    fpsPath = path

    val fpsMax = fpsAxisMax(samples.mapNotNull { it.fps }.maxOrNull())
    val inset = STROKE_DP * density / 2f
    val usableHeight = height - inset * 2f

    var started = false
    samples.forEach { reading ->
      val fps = reading.fps
      started = if (fps == null) {
        false
      } else {
        val x = xFor(reading, start, now)
        val y = inset + (1f - fpsFraction(fps, fpsMax)) * usableHeight
        if (started) path.lineTo(x, y) else path.moveTo(x, y)
        true
      }
    }
  }

  /** x for [reading] on the fixed window, shared by every series. */
  private fun xFor(reading: Reading, start: Long, now: Long): Float =
    (reading.t - start) / (now - start).toFloat() * width

  private fun strokePaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = STROKE_DP * density
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }

  companion object {
    /** View height; the card's other rows are text, so the graph stays small. */
    private const val HEIGHT_DP = 36f

    private const val STROKE_DP = 1.5f

    /** Graph window in milliseconds (ANDROID.md §9: 2 minutes). */
    private const val WINDOW_MS = 120_000L
  }
}
