package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePreferences
import com.example.data.local.ThemePresets

private fun parseColorSafe(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Resolves the complete high-performance [AppThemeColors] dynamically from the active [CustomThemeState].
 * Supports instant, smooth transitions across all 10 presets and customized palettes.
 */
fun resolveAppThemeColors(darkTheme: Boolean, custom: CustomThemeState): AppThemeColors {
    val isDark = darkTheme
    val effectiveCustom = custom
    val isBrightnessMismatch = (isDark != custom.isPresetDark)

    val primaryAccent = parseColorSafe(
        effectiveCustom.accentColorHex,
        if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    )

    val secondaryAccent = parseColorSafe(
        effectiveCustom.secondaryAccentHex,
        primaryAccent
    )

    val isAmoled = isDark && (effectiveCustom.isAmoled || effectiveCustom.canvasColorHex == "#000000")

    val scaffoldBg = if (isAmoled) {
        Color(0xFF000000)
    } else if (isBrightnessMismatch) {
        if (isDark) Color(0xFF101014) else Color(0xFFFAFAFC)
    } else {
        parseColorSafe(effectiveCustom.canvasColorHex, if (isDark) Color(0xFF101014) else Color(0xFFFAFAFC))
    }

    val cardBg = if (isAmoled) {
        Color(0xFF000000)
    } else if (isBrightnessMismatch) {
        if (isDark) Color(0xFF18181E) else Color(0xFFFFFFFF)
    } else {
        parseColorSafe(effectiveCustom.cardColorHex, if (isDark) Color(0xFF18181E) else Color(0xFFFFFFFF))
    }

    val cardBorder = if (isAmoled) {
        Color(0xFF242424)
    } else if (isBrightnessMismatch) {
        if (isDark) Color(0xFF282832) else Color(0xFFE2E8F0)
    } else {
        parseColorSafe(effectiveCustom.cardBorderHex, if (isDark) Color(0xFF282832) else Color(0xFFE2E8F0))
    }

    val textPrimary = if (isBrightnessMismatch) {
        if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    } else {
        parseColorSafe(effectiveCustom.textPrimaryHex, if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A))
    }

    val textSecondary = if (isBrightnessMismatch) {
        if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
    } else {
        parseColorSafe(effectiveCustom.textSecondaryHex, if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569))
    }

    val textMuted = parseColorSafe(
        effectiveCustom.textMutedHex,
        Color(0xFF64748B)
    )

    val bgGradientColors = if (isAmoled) {
        listOf(Color(0xFF000000), Color(0xFF000000))
    } else if (isBrightnessMismatch) {
        if (isDark) listOf(Color(0xFF121218), Color(0xFF0A0A0E), Color(0xFF050508))
        else listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC), Color(0xFFF1F5F9))
    } else {
        effectiveCustom.bgGradientColorsHex.mapNotNull { hex ->
            try {
                Color(android.graphics.Color.parseColor(hex.trim()))
            } catch (_: Exception) {
                null
            }
        }.ifEmpty {
            if (isDark) listOf(Color(0xFF121218), Color(0xFF0A0A0E), Color(0xFF050508))
            else listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC), Color(0xFFF1F5F9))
        }
    }

    val cardElevated = if (isDark) {
        if (isAmoled) Color(0xFF0D0D0D)
        else try {
            Color(android.graphics.Color.parseColor(effectiveCustom.cardColorHex)).copy(alpha = 0.95f)
        } catch (_: Exception) {
            Color(0xFF202028)
        }
    } else {
        Color(0xFFF1F5F9)
    }

    val bottomBarBg = if (isDark) {
        if (isAmoled) Color(0xFF000000) else scaffoldBg
    } else {
        Color(0xFFFFFFFF)
    }

    val chipBg = if (isDark) {
        if (isAmoled) Color(0xFF121212) else cardElevated
    } else {
        Color(0xFFF1F5F9)
    }

    return AppThemeColors(
        isDark = isDark,
        isAmoled = isAmoled,
        screenBackground = Brush.verticalGradient(bgGradientColors),
        scaffoldBackground = scaffoldBg,
        cardBackground = cardBg,
        cardBackgroundElevated = cardElevated,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
        primaryAccent = primaryAccent,
        secondaryAccent = secondaryAccent,
        bottomBarBackground = bottomBarBg,
        bottomBarIndicator = primaryAccent.copy(alpha = if (isDark) 0.22f else 0.15f),
        bottomBarSelectedIcon = primaryAccent,
        bottomBarUnselectedIcon = textMuted,
        miniPlayerBackground = if (isDark) {
            if (isAmoled) Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFF080808)))
            else Brush.horizontalGradient(listOf(cardBg, scaffoldBg))
        } else {
            Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9)))
        },
        miniPlayerBorder = cardBorder,
        inputBackground = if (isDark) (if (isAmoled) Color(0xFF0A0A0A) else cardBg) else Color(0xFFF1F5F9),
        chipBackground = chipBg,
        chipBorder = cardBorder,
        dividerColor = if (isAmoled) Color(0xFF1A1A1A) else cardBorder.copy(alpha = 0.5f)
    )
}

/**
 * Builds Material 3 ColorScheme from the dynamic AppThemeColors.
 */
fun resolveColorScheme(darkTheme: Boolean, appColors: AppThemeColors): ColorScheme {
    return if (darkTheme) {
        darkColorScheme(
            primary = appColors.primaryAccent,
            onPrimary = if (appColors.primaryAccent == Color.White) Color.Black else Color.White,
            primaryContainer = appColors.cardBackgroundElevated,
            onPrimaryContainer = appColors.primaryAccent,
            secondary = appColors.secondaryAccent,
            onSecondary = Color.White,
            secondaryContainer = appColors.cardBackground,
            onSecondaryContainer = appColors.textSecondary,
            tertiary = appColors.secondaryAccent,
            onTertiary = Color.White,
            background = appColors.scaffoldBackground,
            onBackground = appColors.textPrimary,
            surface = appColors.cardBackground,
            onSurface = appColors.textPrimary,
            surfaceVariant = appColors.cardBackgroundElevated,
            onSurfaceVariant = appColors.textSecondary,
            outline = appColors.cardBorder
        )
    } else {
        lightColorScheme(
            primary = appColors.primaryAccent,
            onPrimary = Color.White,
            primaryContainer = appColors.primaryAccent.copy(alpha = 0.12f),
            onPrimaryContainer = appColors.primaryAccent,
            secondary = appColors.secondaryAccent,
            onSecondary = Color.White,
            secondaryContainer = appColors.secondaryAccent.copy(alpha = 0.12f),
            onSecondaryContainer = appColors.secondaryAccent,
            tertiary = appColors.secondaryAccent,
            onTertiary = Color.White,
            background = appColors.scaffoldBackground,
            onBackground = appColors.textPrimary,
            surface = appColors.cardBackground,
            onSurface = appColors.textPrimary,
            surfaceVariant = appColors.cardBackgroundElevated,
            onSurfaceVariant = appColors.textSecondary,
            outline = appColors.cardBorder
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    customThemeState: CustomThemeState? = null,
    dynamicColor: Boolean = false,
    textScale: Float = 0.95f,
    uiScale: Float = 0.94f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val custom = customThemeState ?: remember { ThemePreferences.getCustomThemeState(context) }
    val appColors = remember(darkTheme, custom) {
        resolveAppThemeColors(darkTheme, custom)
    }
    val colorScheme = remember(darkTheme, appColors) {
        resolveColorScheme(darkTheme, appColors)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val statusBarBg = appColors.scaffoldBackground
            val navBarBg = appColors.bottomBarBackground
            window.statusBarColor = statusBarBg.toArgb()
            window.navigationBarColor = navBarBg.toArgb()
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val currentDensity = androidx.compose.ui.platform.LocalDensity.current
    val customDensity = remember(currentDensity, textScale, uiScale) {
        androidx.compose.ui.unit.Density(
            density = currentDensity.density * uiScale,
            fontScale = currentDensity.fontScale * textScale
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        androidx.compose.ui.platform.LocalDensity provides customDensity
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}
