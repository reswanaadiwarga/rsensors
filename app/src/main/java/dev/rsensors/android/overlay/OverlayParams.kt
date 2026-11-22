package dev.rsensors.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * Geometry and window flags for the single overlay window (ANDROID.md §4, §8 T6).
 *
 * The window is wrap-content in both axes, drawn over the whole screen, never
 * focusing and never stealing touches it does not own. Its origin is the screen
 * top-left, so x/y can be clamped against the full display rather than a
 * parent view.
 *
 * Side effects: none; [create] and [screenBounds] only read display metrics.
 */
object OverlayParams {
  /** Default inset from the left edge. */
  const val DEFAULT_X_DP = 8

  /** Default distance from the top edge; below the status bar, above content. */
  const val DEFAULT_Y_DP = 48

  /**
   * Builds the overlay window parameters.
   *
   * @param context any context; only its display density is read
   * @return fresh params with TOP|START gravity and the default dp offsets
   */
  fun create(context: Context): WindowManager.LayoutParams {
    val density = context.resources.displayMetrics.density
    return WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = (DEFAULT_X_DP * density + 0.5f).toInt()
      y = (DEFAULT_Y_DP * density + 0.5f).toInt()
    }
  }

  /**
   * Full-screen bounds in pixels for drag clamping.
   *
   * On API 30+ this is the real window metrics, which include the status bar
   * and navigation bar areas the overlay can occupy with FLAG_LAYOUT_IN_SCREEN.
   * On older releases the service context's display metrics are the same size.
   */
  fun screenBounds(windowManager: WindowManager, context: Context): Rect =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      windowManager.currentWindowMetrics.bounds
    } else {
      val metrics = context.resources.displayMetrics
      Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

  /**
   * Keeps a dragged coordinate on screen.
   *
   * @param value proposed x or y in pixels
   * @param max highest coordinate at which the whole pill is still visible;
   *   negative values (pill wider than the display) collapse to 0
   */
  fun clamp(value: Int, max: Int): Int = value.coerceIn(0, max.coerceAtLeast(0))

  /** Converts dp to whole pixels, rounding half up. */
  fun dpToPx(context: Context, dp: Float): Int =
    (dp * context.resources.displayMetrics.density + 0.5f).toInt()
}
