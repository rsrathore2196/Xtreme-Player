package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme =
  darkColorScheme(
    primary = XtremeGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0F3D20),
    onPrimaryContainer = Color(0xFF6CF89B),
    secondary = XtremeCyan,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF003844),
    onSecondaryContainer = Color(0xFF7DEFFF),
    tertiary = XtremePurple,
    onTertiary = Color.White,
    background = XtremeBackground,
    onBackground = TextPrimary,
    surface = XtremeSurface,
    onSurface = TextPrimary,
    surfaceVariant = XtremeCard,
    onSurfaceVariant = TextSecondary,
    outline = XtremeBorder
  )

private val LightColorScheme = DarkColorScheme

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
