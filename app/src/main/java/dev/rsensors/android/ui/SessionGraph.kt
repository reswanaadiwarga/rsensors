package dev.rsensors.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rsensors.android.core.ChartFocus
import dev.rsensors.android.core.ChartSeries
import dev.rsensors.android.core.DASH
import dev.rsensors.android.core.FPS_AXIS_MAX
import dev.rsensors.android.core.SessionLog
import dev.rsensors.android.core.SessionSample
import dev.rsensors.android.core.TEMPERATURE_SERIES
import dev.rsensors.android.core.bounds
import dev.rsensors.android.core.fpsFraction
import dev.rsensors.android.core.hasFpsValues
import dev.rsensors.android.core.nearestIndex
import dev.rsensors.android.core.valueFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Full-session temperature graph with a hover/touch crosshair (ANDROID.md §3,
 * §9, §8 T13, T15d).
 *
 * The whole recorded session is on the x axis and the shared temperature range
 * on the y axis; CPU, GPU, BAT, SOC and SKIN are separate polylines that break
 * at a null poll instead of bridging a sleeping phone or a dropped sensor. T14
 * adds FPS as a sixth line on its own right-hand 0–120 axis, labelled 0/60/120
 * in the FPS colour and included in the tooltip; a v1 session with no FPS
 * values draws neither the line nor the axis. A mouse hover or a touch
 * press/drag selects the nearest sample by time and draws a crosshair, a dot per
 * series and a tooltip with that sample's values. A touch release keeps the
 * selection; a mouse exit clears it. There is no zoom or pan.
 *
 * T15d puts the legend above the canvas as six tappable chips, one per series:
 * a tap dims that line (drawn at low alpha under the highlighted ones) and a
 * second tap re-highlights it on top, using [ChartFocus]'s paint order. Dimming
 * is a display filter only — every line stays drawn and the tooltip always
 * lists every value — so a reader cannot lose data by tapping a chip. T15e
 * drops the FPS chip when the session has no FPS values at all, matching the
 * already-absent axis and line.
 *
 * The graph never consumes pointer changes, so a future scrollable parent keeps
 * working; the chips are a separate composable above the canvas, so tapping
 * them never moves the crosshair. Sessions with no samples or no values still
 * render the frame and a "no data" label rather than crashing or drawing a
 * misleading line.
 *
 * @param log decoded session to plot; empty logs are valid
 * @param modifier layout modifier for the legend plus the 220 dp canvas
 */
@Composable
fun SessionGraph(log: SessionLog, modifier: Modifier = Modifier) {
  val textMeasurer = rememberTextMeasurer()
  var selectedIndex by remember(log) { mutableStateOf<Int?>(null) }
  // Every series starts highlighted; a new log starts a fresh focus.
  var focus by remember(log) { mutableStateOf(ChartFocus()) }
  val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
  // Only sessions with a recorded FPS pay for the right-hand axis gutter.
  val hasFps = hasFpsValues(log.samples)
  val rightGutter = if (hasFps) FPS_AXIS_WIDTH else PLOT_RIGHT

  Column(modifier = modifier.fillMaxWidth()) {
    SeriesLegend(
      hasFps = hasFps,
      focus = focus,
      onToggle = { focus = focus.toggle(it) },
    )

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(GRAPH_HEIGHT)
        .pointerInput(log) {
          if (log.samples.isEmpty()) return@pointerInput
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              if (event.type == PointerEventType.Exit) {
                selectedIndex = null
                continue
              }
              val change = event.changes.firstOrNull() ?: continue
              when (event.type) {
                PointerEventType.Press, PointerEventType.Move, PointerEventType.Enter ->
                  selectedIndex = indexAt(
                    x = change.position.x,
                    plotLeft = PLOT_LEFT.toPx(),
                    plotRight = size.width - rightGutter.toPx(),
                    samples = log.samples,
                  )
                // A touch release deliberately keeps the last selection so the
                // tooltip stays readable after the finger lifts.
                else -> Unit
              }
            }
          }
        },
    ) {
      val left = PLOT_LEFT.toPx()
      val right = size.width - rightGutter.toPx()
      val top = PLOT_TOP.toPx()
      val bottom = size.height - PLOT_BOTTOM.toPx()
      if (right <= left || bottom <= top) return@Canvas

      val axisStyle = TextStyle(
        color = labelColor,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
      )

      if (log.samples.isEmpty()) {
        drawCenteredText(textMeasurer, "no data", left, right, top, bottom, axisStyle)
        return@Canvas
      }

      val seriesBounds = TEMPERATURE_SERIES.mapNotNull { bounds(log.samples, it) }
      val minTemp = seriesBounds.minOfOrNull { it.minTemp }
      val maxTemp = seriesBounds.maxOfOrNull { it.maxTemp }
      if (minTemp == null || maxTemp == null || maxTemp <= minTemp) {
        drawCenteredText(textMeasurer, "no data", left, right, top, bottom, axisStyle)
        return@Canvas
      }

      val startT = log.samples.minOf { it.t }
      val endT = log.samples.maxOf { it.t }
      val spanT = endT - startT

      fun xFor(t: Long): Float =
        if (spanT <= 0L) {
          (left + right) / 2f
        } else {
          left + (t - startT).toFloat() / spanT.toFloat() * (right - left)
        }

      fun yFor(value: Double): Float =
        top + ((maxTemp - value) / (maxTemp - minTemp)).toFloat() * (bottom - top)

      /** FPS grows upward on the fixed 0–120 axis, independent of temperatures. */
      fun yForFps(value: Double): Float = bottom - fpsFraction(value) * (bottom - top)

      // Grid and axes: one horizontal line per labelled temperature, one vertical
      // line per labelled time. Drawn first so the series sit on top.
      val gridColor = Color(Palette.gray).copy(alpha = 0.5f)
      val gridStroke = 1.dp.toPx()
      val midTemp = (minTemp + maxTemp) / 2.0
      listOf(maxTemp, midTemp, minTemp).forEach { value ->
        val y = yFor(value)
        drawLine(gridColor, Offset(left, y), Offset(right, y), gridStroke)
      }
      val midX = (left + right) / 2f
      listOf(left, midX, right).forEach { x ->
        drawLine(gridColor, Offset(x, top), Offset(x, bottom), gridStroke)
      }

      // Temperature labels sit in the left gutter, right-aligned to the plot.
      listOf(maxTemp, midTemp, minTemp).forEach { value ->
        val layout = textMeasurer.measure(tempLabel(value), axisStyle)
        drawText(
          textLayoutResult = layout,
          topLeft = Offset(
            x = left - 4.dp.toPx() - layout.size.width,
            y = yFor(value) - layout.size.height / 2f,
          ),
        )
      }

      // FPS axis, right gutter: 0/60/120 in the FPS colour. It shares the
      // vertical plot but not the temperature grid; v1 sessions skip it entirely.
      if (hasFps) {
        val fpsStyle = TextStyle(
          color = seriesColor(ChartSeries.FPS),
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
        )
        listOf(FPS_AXIS_MAX, FPS_AXIS_MAX / 2.0, 0.0).forEach { value ->
          val layout = textMeasurer.measure(fpsLabel(value), fpsStyle)
          drawText(
            textLayoutResult = layout,
            topLeft = Offset(
              x = right + 4.dp.toPx(),
              y = yForFps(value) - layout.size.height / 2f,
            ),
          )
        }
      }

      // Time labels: start left-aligned, end right-aligned, midpoint centred, so
      // none can spill past the canvas edge.
      if (spanT <= 0L) {
        val layout = textMeasurer.measure(clock(startT), axisStyle)
        drawText(layout, topLeft = Offset(midX - layout.size.width / 2f, bottom + 6.dp.toPx()))
      } else {
        val startLayout = textMeasurer.measure(clock(startT), axisStyle)
        drawText(startLayout, topLeft = Offset(left, bottom + 6.dp.toPx()))
        val endLayout = textMeasurer.measure(clock(endT), axisStyle)
        drawText(
          endLayout,
          topLeft = Offset(right - endLayout.size.width, bottom + 6.dp.toPx()),
        )
        val midLayout = textMeasurer.measure(clock((startT + endT) / 2L), axisStyle)
        drawText(
          midLayout,
          topLeft = Offset(midX - midLayout.size.width / 2f, bottom + 6.dp.toPx()),
        )
      }

      // One polyline per series in ChartFocus's order: dimmed lines first so the
      // highlighted ones always paint over them, and the newest highlight last.
      // A null value ends the current segment and the next value starts a new
      // one, so gaps are visible rather than bridged. FPS uses its own axis.
      val lineStroke = Stroke(
        width = 1.6.dp.toPx(),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
      )
      focus.drawOrder().forEach { series ->
        val path = Path()
        var started = false
        log.samples.forEach { sample ->
          val value = sample.valueFor(series)
          if (value == null) {
            started = false
          } else {
            val x = xFor(sample.t)
            val y = if (series == ChartSeries.FPS) yForFps(value) else yFor(value)
            if (started) path.lineTo(x, y) else path.moveTo(x, y)
            started = true
          }
        }
        drawPath(
          path = path,
          color = seriesColor(series),
          alpha = lineAlpha(focus, series),
          style = lineStroke,
        )
      }

      val sample = selectedIndex?.let { log.samples.getOrNull(it) } ?: return@Canvas
      drawSelection(
        textMeasurer = textMeasurer,
        sample = sample,
        focus = focus,
        cursorX = xFor(sample.t),
        yFor = ::yFor,
        yForFps = ::yForFps,
        top = top,
        bottom = bottom,
      )
    }
  }
}

/**
 * Legend row above the canvas: one chip per series present in this session.
 *
 * Six equal-weight chips fit the 312 dp content width of the target phone, and
 * the row is a separate composable so a chip tap is never seen by the canvas
 * pointer handler below it. A v1 session with no FPS values omits the FPS chip
 * and lets the five temperature chips fill the row (ANDROID.md §9, T15e).
 */
@Composable
private fun SeriesLegend(
  hasFps: Boolean,
  focus: ChartFocus,
  onToggle: (ChartSeries) -> Unit,
) {
  val series = if (hasFps) ChartSeries.entries else TEMPERATURE_SERIES
  Row(
    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    series.forEach { current ->
      SeriesChip(
        series = current,
        dimmed = focus.isDimmed(current),
        onToggle = { onToggle(current) },
      )
    }
  }
}

/**
 * One tappable legend chip: a short line swatch in the series colour and the
 * series label.
 *
 * A highlighted chip shows the full colour and the normal label colour; a
 * dimmed chip fades the swatch to [DIM_SWATCH_ALPHA] and greys the label. The
 * chip reports itself as a checkbox so TalkBack announces the toggle state, and
 * its content description names the series and the current state rather than
 * repeating the label.
 */
@Composable
private fun RowScope.SeriesChip(
  series: ChartSeries,
  dimmed: Boolean,
  onToggle: () -> Unit,
) {
  val highlighted = !dimmed
  val labelColor = if (highlighted) {
    MaterialTheme.colorScheme.onBackground
  } else {
    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
  }
  Row(
    modifier = Modifier
      .weight(1f)
      .heightIn(min = CHIP_HEIGHT)
      .clip(CHIP_SHAPE)
      .background(CHIP_BACKGROUND)
      .toggleable(value = highlighted, role = Role.Checkbox, onValueChange = { onToggle() })
      .semantics(mergeDescendants = true) {
        contentDescription = if (highlighted) {
          "${series.label} line, highlighted"
        } else {
          "${series.label} line, dimmed"
        }
      }
      .padding(horizontal = 3.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Canvas(
      modifier = Modifier
        .width(8.dp)
        .height(3.dp),
    ) {
      drawLine(
        color = seriesColor(series).copy(alpha = if (highlighted) 1f else DIM_SWATCH_ALPHA),
        start = Offset(0f, size.height / 2f),
        end = Offset(size.width, size.height / 2f),
        strokeWidth = size.height,
        cap = StrokeCap.Round,
      )
    }
    Spacer(Modifier.width(3.dp))
    Text(
      text = series.label,
      modifier = Modifier.weight(1f),
      color = labelColor,
      fontFamily = FontFamily.Monospace,
      fontSize = 10.sp,
      maxLines = 1,
    )
  }
}

/** Alpha multiplier for a dimmed series; shared by its line and crosshair dot. */
private fun lineAlpha(focus: ChartFocus, series: ChartSeries): Float =
  if (focus.isDimmed(series)) DIM_LINE_ALPHA else 1f

/**
 * Maps a horizontal pointer position to the nearest sample index.
 *
 * The position is clamped to the plot so a drag past either edge keeps showing
 * the first or last row instead of losing the selection. A single-sample or
 * all-at-one-instant session maps every position to the same instant, and the
 * nearest-index tie rule then chooses the first row.
 */
private fun indexAt(
  x: Float,
  plotLeft: Float,
  plotRight: Float,
  samples: List<SessionSample>,
): Int? {
  if (samples.isEmpty() || plotRight <= plotLeft) return null
  val clamped = x.coerceIn(plotLeft, plotRight)
  val fraction = ((clamped - plotLeft) / (plotRight - plotLeft)).toDouble()
  val startT = samples.minOf { it.t }
  val endT = samples.maxOf { it.t }
  val target = if (endT <= startT) {
    startT
  } else {
    startT + (fraction * (endT - startT)).roundToLong()
  }
  return nearestIndex(samples, target)
}

/**
 * Crosshair, a dot per present series and the clamped tooltip card.
 *
 * Dots follow the line paint order and alpha, so a dimmed series reads as dim in
 * the crosshair too. The tooltip itself still lists every value: dimming
 * filters the drawing, not the data.
 */
private fun DrawScope.drawSelection(
  textMeasurer: TextMeasurer,
  sample: SessionSample,
  focus: ChartFocus,
  cursorX: Float,
  yFor: (Double) -> Float,
  yForFps: (Double) -> Float,
  top: Float,
  bottom: Float,
) {
  drawLine(
    color = Color(Palette.white).copy(alpha = 0.7f),
    start = Offset(cursorX, top),
    end = Offset(cursorX, bottom),
    strokeWidth = 1.dp.toPx(),
  )

  focus.drawOrder().forEach { series ->
    val value = sample.valueFor(series) ?: return@forEach
    // The FPS dot follows its own axis; temperatures keep the shared scale.
    val y = if (series == ChartSeries.FPS) yForFps(value) else yFor(value)
    drawCircle(
      color = seriesColor(series),
      radius = 3.dp.toPx(),
      center = Offset(cursorX, y),
      alpha = lineAlpha(focus, series),
    )
  }

  val text = tooltipText(sample)
  val layout = textMeasurer.measure(
    text,
    TextStyle(
      color = Color(Palette.white),
      fontSize = 10.sp,
      lineHeight = 14.sp,
      fontFamily = FontFamily.Monospace,
    ),
  )
  val pad = 6.dp.toPx()
  val margin = 4.dp.toPx()
  val boxWidth = layout.size.width + pad * 2f
  val boxHeight = layout.size.height + pad * 2f

  // Prefer the right of the crosshair, flip to the left when it would overflow,
  // and clamp in both axes so the card never leaves the canvas.
  val preferredX = cursorX + 10.dp.toPx()
  val flippedX = cursorX - 10.dp.toPx() - boxWidth
  val maxX = (size.width - boxWidth - margin).coerceAtLeast(margin)
  val maxY = (size.height - boxHeight - margin).coerceAtLeast(margin)
  val x = (if (preferredX + boxWidth <= size.width - margin) preferredX else flippedX)
    .coerceIn(margin, maxX)
  val y = (top + 6.dp.toPx()).coerceIn(margin, maxY)

  drawRoundRect(
    color = TOOLTIP_BACKGROUND,
    topLeft = Offset(x, y),
    size = Size(boxWidth, boxHeight),
    cornerRadius = CornerRadius(6.dp.toPx()),
  )
  drawRoundRect(
    color = Color(Palette.gray),
    topLeft = Offset(x, y),
    size = Size(boxWidth, boxHeight),
    cornerRadius = CornerRadius(6.dp.toPx()),
    style = Stroke(1.dp.toPx()),
  )
  drawText(layout, topLeft = Offset(x + pad, y + pad))
}

/** Centred "no data" label used for empty sessions and all-null series. */
private fun DrawScope.drawCenteredText(
  textMeasurer: TextMeasurer,
  text: String,
  left: Float,
  right: Float,
  top: Float,
  bottom: Float,
  style: TextStyle,
) {
  val layout = textMeasurer.measure(text, style)
  drawText(
    layout,
    topLeft = Offset(
      x = (left + right) / 2f - layout.size.width / 2f,
      y = (top + bottom) / 2f - layout.size.height / 2f,
    ),
  )
}

private fun seriesColor(series: ChartSeries): Color = when (series) {
  ChartSeries.CPU -> Color(Palette.green)
  ChartSeries.GPU -> Color(Palette.amber)
  ChartSeries.BATTERY -> Color(Palette.blue)
  ChartSeries.SOC -> Color(Palette.red)
  ChartSeries.SKIN -> Color(Palette.white)
  ChartSeries.FPS -> Color(Palette.fps)
}

/** `52°`; whole degrees are enough for an axis label. */
private fun tempLabel(value: Double): String =
  String.format(Locale.US, "%.0f\u00b0", value)

/** `0`, `60`, `120`; whole frames are enough for the FPS axis. */
private fun fpsLabel(value: Double): String = String.format(Locale.US, "%.0f", value)

/** `HH:mm:ss` in the device zone. */
private fun clock(epochMillis: Long): String =
  SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

/**
 * Four tooltip lines: time, then CPU/GPU, BAT/SOC and SKIN. FPS shares the last
 * line only when this sample recorded a value, so a v1 session's card looks
 * exactly as it did before T14. Every series keeps its line while dimmed.
 */
private fun tooltipText(sample: SessionSample): String {
  fun cell(series: ChartSeries): String =
    sample.valueFor(series)?.let { String.format(Locale.US, "%.1f", it) } ?: DASH

  val lastLine = buildString {
    append("SKIN ${cell(ChartSeries.SKIN)}")
    sample.fps?.let { append("  FPS ${String.format(Locale.US, "%.1f", it)}") }
  }
  return clock(sample.t) + "\n" +
    "CPU ${cell(ChartSeries.CPU)}  GPU ${cell(ChartSeries.GPU)}\n" +
    "BAT ${cell(ChartSeries.BATTERY)}  SOC ${cell(ChartSeries.SOC)}\n" +
    lastLine
}

/** Canvas height; the legend row and the table take the rest of the screen. */
private val GRAPH_HEIGHT = 220.dp

private val PLOT_LEFT = 40.dp
private val PLOT_RIGHT = 10.dp

/** Right gutter when FPS is plotted; wide enough for the `120` label. */
private val FPS_AXIS_WIDTH = 28.dp

/**
 * Top inset. Only the topmost axis label's half height needs clearing now that
 * the legend moved into its own row above the canvas (T15d).
 */
private val PLOT_TOP = 14.dp
private val PLOT_BOTTOM = 26.dp

/** Chip height: enough to read and tap a 10 sp label on a 360 dp screen. */
private val CHIP_HEIGHT = 26.dp
private val CHIP_SHAPE = RoundedCornerShape(5.dp)
private val CHIP_BACKGROUND = Color(Palette.gray).copy(alpha = 0.20f)

/** Dimmed line and crosshair dot: present, readable, clearly behind. */
private const val DIM_LINE_ALPHA = 0.18f

/** Dimmed chip swatch: 35% of the series colour, per §3. */
private const val DIM_SWATCH_ALPHA = 0.35f

private val TOOLTIP_BACKGROUND = Color(0xFF141B24)
