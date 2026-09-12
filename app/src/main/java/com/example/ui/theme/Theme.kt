package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme =
  darkColorScheme(
    primary = XtremeLightBlue,
    onPrimary = Color(0xFF031428),
    primaryContainer = Color(0xFF0C2B4E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = XtremeCyan,
    onSecondary = Color(0xFF021A2A),
    secondaryContainer = Color(0xFF083344),
    onSecondaryContainer = Color(0xFF67E8F9),
    tertiary = XtremeDeepBlue,
    onTertiary = Color.White,
    background = XtremeBackground,
    onBackground = TextPrimary,
    surface = XtremeSurface,
    onSurface = TextPrimary,
    surfaceVariant = XtremeCard,
    onSurfaceVariant = TextSecondary,
    outline = XtremeBorder
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      window.statusBarColor = XtremeBackground.toArgb()
      window.navigationBarColor = XtremeBackground.toArgb()
      val windowInsetsController = WindowCompat.getInsetsController(window, view)
      windowInsetsController.isAppearanceLightStatusBars = false
      windowInsetsController.isAppearanceLightNavigationBars = false
    }
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
