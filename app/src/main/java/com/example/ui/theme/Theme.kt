package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    background = StaticDarkBackground,
    onBackground = StaticTextPrimary,
    surface = StaticDarkSurface,
    onSurface = StaticTextPrimary,
    surfaceVariant = StaticDarkCard,
    onSurfaceVariant = Color(0xFF93C5FD),
    outline = StaticDarkBorder
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E40AF),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    background = Color(0xFFF8FAFD),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F6FD),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFDBEAFE)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val appColors = if (darkTheme) DarkAppColors else LightAppColors

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      val bgColor = if (darkTheme) Color(0xFF040A14) else Color(0xFFFFFFFF)
      window.statusBarColor = bgColor.toArgb()
      window.navigationBarColor = bgColor.toArgb()
      val windowInsetsController = WindowCompat.getInsetsController(window, view)
      windowInsetsController.isAppearanceLightStatusBars = !darkTheme
      windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
    }
  }

  CompositionLocalProvider(LocalAppColors provides appColors) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}

