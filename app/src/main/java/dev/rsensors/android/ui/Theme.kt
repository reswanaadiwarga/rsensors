package dev.rsensors.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark Rsensors theme (ANDROID.md §3, §4).
 *
 * The app is always dark: the overlay pill and the tray badge are built from
 * the same dark palette, and a light activity around them would look like a
 * different product. Colors are taken from [Palette] rather than a separate
 * Material palette so the overlay and the activity cannot drift apart.
 */
private val RsensorsColors = darkColorScheme(
  primary = Color(Palette.green),
  onPrimary = Color(Palette.dark),
  primaryContainer = Color(Palette.green),
  onPrimaryContainer = Color(Palette.dark),
  secondary = Color(Palette.amber),
  onSecondary = Color(Palette.dark),
  background = Color(Palette.dark),
  onBackground = Color(Palette.white),
  surface = Color(Palette.dark),
  onSurface = Color(Palette.white),
  surfaceVariant = Color(Palette.gray),
  onSurfaceVariant = Color(Palette.white),
  outline = Color(Palette.gray),
  error = Color(Palette.red),
)

/** Wraps [content] in the dark Rsensors colour scheme. */
@Composable
fun RsensorsTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = RsensorsColors, content = content)
}
