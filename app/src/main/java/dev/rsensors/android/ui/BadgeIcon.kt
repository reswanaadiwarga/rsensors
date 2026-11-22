package dev.rsensors.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import dev.rsensors.android.core.IconSpec
import dev.rsensors.android.core.Level

/**
 * Rasterises the tray badge as a notification large icon (ANDROID.md §3).
 *
 * The badge is a rounded square in the level colour with a thin dark border and
 * the [IconSpec.label] centred on it. It is drawn into an ARGB_8888 bitmap so
 * the notification layer can wrap it in an `IconCompat` without needing a
 * drawable resource or an activity context.
 *
 * @param sizePx edge length of the square bitmap in pixels; must be positive
 * @param spec label and colour grade from [dev.rsensors.android.core.iconSpec]
 * @return a new mutable bitmap; the caller owns it and the notification layer
 *   may recycle it once posted
 * @throws IllegalArgumentException when [sizePx] is not positive
 */
fun badgeBitmap(sizePx: Int, spec: IconSpec): Bitmap {
  require(sizePx > 0) { "sizePx must be positive, was $sizePx" }

  val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val size = sizePx.toFloat()
  val radius = size / 4f

  val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = levelColor(spec.level)
    style = Paint.Style.FILL
  }
  canvas.drawRoundRect(RectF(0f, 0f, size, size), radius, radius, background)

  val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Palette.dark
    style = Paint.Style.STROKE
    strokeWidth = (size / 32f).coerceAtLeast(1f)
  }
  // Inset by half the stroke so the border sits inside the bitmap edge.
  val inset = borderPaint.strokeWidth / 2f
  val innerRadius = (radius - inset).coerceAtLeast(0f)
  canvas.drawRoundRect(
    RectF(inset, inset, size - inset, size - inset),
    innerRadius,
    innerRadius,
    borderPaint,
  )

  val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = if (spec.level == Level.WARN) Palette.amberText else Palette.white
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
    // ~55% of the badge reads well for one digit and still fits two.
    textSize = size * 0.55f
  }
  // Long labels (e.g. "100") would overflow the rounded corners; keep the
  // height-derived size unless the glyphs would clip.
  val maxWidth = size * 0.8f
  val measuredWidth = text.measureText(spec.label)
  if (measuredWidth > maxWidth) {
    text.textSize *= maxWidth / measuredWidth
  }
  // Centre on the glyph box, not the em box, so "?" sits level with digits.
  val metrics = text.fontMetrics
  val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
  canvas.drawText(spec.label, size / 2f, baseline, text)

  return bitmap
}

/** Maps a colour grade onto the app palette. */
private fun levelColor(level: Level): Int = when (level) {
  Level.OK -> Palette.green
  Level.WARN -> Palette.amber
  Level.HOT -> Palette.red
  Level.UNKNOWN -> Palette.gray
}
