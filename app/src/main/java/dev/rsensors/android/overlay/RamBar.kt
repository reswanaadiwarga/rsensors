package dev.rsensors.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.rsensors.android.ui.Palette

/**
 * The expanded card's compact RAM usage bar.
 *
 * A plain [View] drawing a rounded track and fill, because the fill is a data
 * value (used / total) rather than a static drawable. The track is the palette's
 * grey at low alpha and the fill is the palette's blue; both stay subordinate to
 * the RAM digits above it.
 *
 * Purely decorative: the RAM row's text carries the value, so the bar hides
 * itself from accessibility and takes no focus or clicks.
 *
 * Threading: constructed, mutated and drawn on the main thread only.
 */
class RamBar(context: Context) : View(context) {
  private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = (Palette.gray and 0x00FFFFFF) or (TRACK_ALPHA shl 24)
  }
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Palette.blue
  }
  private val bounds = RectF()

  /**
   * Filled share of the bar, clamped to `0f..1f`; 0 draws an empty track.
   *
   * Setting an unchanged value does not invalidate, so the poll's steady RAM
   * figure does not force a redraw every tick.
   */
  var fraction: Float = 0f
    set(value) {
      val next = value.coerceIn(0f, 1f)
      if (next == field) return
      field = next
      invalidate()
    }

  init {
    isFocusable = false
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  override fun onDraw(canvas: Canvas) {
    val h = height.toFloat()
    if (h <= 0f) return
    val radius = h / 2f
    bounds.set(0f, 0f, width.toFloat(), h)
    canvas.drawRoundRect(bounds, radius, radius, trackPaint)
    val filled = width * fraction
    if (filled <= 0f) return
    bounds.right = filled
    canvas.drawRoundRect(bounds, radius, radius, fillPaint)
  }

  private companion object {
    /** Track alpha: visible against the dark card without competing with the fill. */
    const val TRACK_ALPHA = 0x40
  }
}
