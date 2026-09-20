package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class AppThemeColors(
    val isDark: Boolean,
    val isAmoled: Boolean = false,
    val screenBackground: Brush,
    val scaffoldBackground: Color,
    val cardBackground: Color,
    val cardBackgroundElevated: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val bottomBarBackground: Color,
    val bottomBarIndicator: Color,
    val bottomBarSelectedIcon: Color,
    val bottomBarUnselectedIcon: Color,
    val miniPlayerBackground: Brush,
    val miniPlayerBorder: Color,
    val inputBackground: Color,
    val chipBackground: Color,
    val chipBorder: Color,
    val dividerColor: Color
) {
    val heroGradient: Brush
        get() = Brush.linearGradient(listOf(primaryAccent, secondaryAccent))

    val onPrimaryAccent: Color
        get() {
            val lum = (0.299 * primaryAccent.red + 0.587 * primaryAccent.green + 0.114 * primaryAccent.blue)
            return if (lum > 0.55) Color.Black else Color.White
        }

    val bottomSheetBackground: Color
        get() = if (isAmoled) Color.Black else if (isDark) cardBackground else scaffoldBackground
}

// Night / Dark Theme: Black and Night Blue Combination
val DarkAppColors = AppThemeColors(
    isDark = true,
    screenBackground = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF081426), // Night Blue
            Color(0xFF040A14), // Midnight Navy
            Color(0xFF000206)  // Pure Black
        )
    ),
    scaffoldBackground = Color(0xFF020610),
    cardBackground = Color(0xFF0B1728),
    cardBackgroundElevated = Color(0xFF102038),
    cardBorder = Color(0xFF162F4D),
    textPrimary = Color(0xFFF0F9FF), // Crisp ice white
    textSecondary = Color(0xFF93C5FD), // Soft sky blue
    textMuted = Color(0xFF64748B),
    primaryAccent = Color(0xFF38BDF8), // Electric Light Blue
    secondaryAccent = Color(0xFF00E5FF), // Cyan
    bottomBarBackground = Color(0xFF040A14),
    bottomBarIndicator = Color(0xFF122C4A),
    bottomBarSelectedIcon = Color(0xFF38BDF8),
    bottomBarUnselectedIcon = Color(0xFF64748B),
    miniPlayerBackground = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF0E1F36),
            Color(0xFF091424)
        )
    ),
    miniPlayerBorder = Color(0xFF1A385C),
    inputBackground = Color(0xFF091424),
    chipBackground = Color(0xFF0E1E33),
    chipBorder = Color(0xFF1B3552),
    dividerColor = Color(0xFF122842)
)

// Light Theme: Beautiful White Background with Blue Color Combination
val LightAppColors = AppThemeColors(
    isDark = false,
    screenBackground = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFFFFFF), // Pure Crisp White
            Color(0xFFF1F6FD), // Soft Frosted Blue
            Color(0xFFE2EDFB)  // Sky Pastel Blue
        )
    ),
    scaffoldBackground = Color(0xFFF8FAFD),
    cardBackground = Color(0xFFFFFFFF),
    cardBackgroundElevated = Color(0xFFF0F6FF),
    cardBorder = Color(0xFFCBD5E1), // Visible, crisp border for light mode shapes
    textPrimary = Color(0xFF0F172A), // Deep slate / navy high-contrast
    textSecondary = Color(0xFF1E40AF), // Rich royal blue
    textMuted = Color(0xFF64748B), // Slate muted
    primaryAccent = Color(0xFF0284C7), // Vibrant Ocean Blue
    secondaryAccent = Color(0xFF2563EB), // Deep Royal Blue
    bottomBarBackground = Color(0xFFFFFFFF),
    bottomBarIndicator = Color(0xFFDBEAFE),
    bottomBarSelectedIcon = Color(0xFF0284C7),
    bottomBarUnselectedIcon = Color(0xFF64748B),
    miniPlayerBackground = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFFFFFFFF),
            Color(0xFFEFF6FF)
        )
    ),
    miniPlayerBorder = Color(0xFF94A3B8),
    inputBackground = Color(0xFFF1F5F9),
    chipBackground = Color(0xFFF0F6FF),
    chipBorder = Color(0xFFCBD5E1),
    dividerColor = Color(0xFFE2E8F0)
)

val LocalAppColors = compositionLocalOf { DarkAppColors }

// Base static constants for themes & non-composable scopes
val StaticDarkBackground = Color(0xFF070F1E)
val StaticDarkSurface = Color(0xFF0D182A)
val StaticDarkCard = Color(0xFF132238)
val StaticDarkBorder = Color(0xFF1E3554)
val StaticTextPrimary = Color(0xFFF0F9FF)

// Dynamic Composable Color Accessors
val TextPrimary: Color
    @Composable
    get() = LocalAppColors.current.textPrimary

val TextSecondary: Color
    @Composable
    get() = LocalAppColors.current.textSecondary

val TextMuted: Color
    @Composable
    get() = LocalAppColors.current.textMuted

val XtremeBackground: Color
    @Composable
    get() = LocalAppColors.current.scaffoldBackground

val XtremeSurface: Color
    @Composable
    get() = LocalAppColors.current.cardBackground

val XtremeCard: Color
    @Composable
    get() = LocalAppColors.current.cardBackground

val XtremeCardElevated: Color
    @Composable
    get() = LocalAppColors.current.cardBackgroundElevated

val XtremeBorder: Color
    @Composable
    get() = LocalAppColors.current.cardBorder

val XtremeBorderGlow: Color
    @Composable
    get() = LocalAppColors.current.primaryAccent.copy(alpha = if (LocalAppColors.current.isDark) 0.40f else 0.25f)

// Signature Accent Tones - Dynamically adapt to active theme preset & custom colors
val XtremeLightBlue: Color
    @Composable
    get() = LocalAppColors.current.primaryAccent

val XtremeCyan: Color
    @Composable
    get() = LocalAppColors.current.secondaryAccent

val XtremeDeepBlue: Color
    @Composable
    get() = LocalAppColors.current.primaryAccent

val XtremeIce: Color
    @Composable
    get() = LocalAppColors.current.primaryAccent.copy(alpha = 0.25f)

val XtremeGreen: Color
    @Composable
    get() = LocalAppColors.current.primaryAccent

val XtremePurple: Color
    @Composable
    get() = LocalAppColors.current.secondaryAccent

val XtremeRose: Color
    @Composable
    get() = Color(0xFFF43F5E)

val SliderTrackColor: Color
    @Composable
    get() = if (LocalAppColors.current.isDark) Color(0xFF1C3454) else Color(0xFFCBD5E1)

val SliderThumbColor: Color
    @Composable
    get() = LocalAppColors.current.primaryAccent

val SliderBufferedColor: Color
    @Composable
    get() = LocalAppColors.current.primaryAccent.copy(alpha = 0.4f)

// Eye-catching Signature Gradients matching the theme
object XtremeGradients {
    val LogoGradient: Brush
        @Composable
        get() = Brush.linearGradient(
            colors = listOf(
                LocalAppColors.current.secondaryAccent,
                LocalAppColors.current.primaryAccent,
                LocalAppColors.current.primaryAccent
            )
        )

    val LightBlueGradient: Brush
        @Composable
        get() = Brush.horizontalGradient(
            colors = listOf(
                LocalAppColors.current.primaryAccent,
                LocalAppColors.current.secondaryAccent
            )
        )

    val ScreenBackground: Brush
        @Composable
        get() = LocalAppColors.current.screenBackground

    val PlayerAmbient: Brush
        @Composable
        get() = if (LocalAppColors.current.isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    LocalAppColors.current.cardBackgroundElevated,
                    LocalAppColors.current.cardBackground,
                    LocalAppColors.current.scaffoldBackground
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    LocalAppColors.current.primaryAccent.copy(alpha = 0.12f),
                    LocalAppColors.current.scaffoldBackground,
                    Color.White
                )
            )
        }

    val CardGradient: Brush
        @Composable
        get() = if (LocalAppColors.current.isDark) {
            Brush.linearGradient(
                colors = listOf(
                    LocalAppColors.current.cardBackgroundElevated,
                    LocalAppColors.current.cardBackground
                )
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    LocalAppColors.current.scaffoldBackground
                )
            )
        }

    val ButtonGradient: Brush
        @Composable
        get() = Brush.horizontalGradient(
            colors = listOf(
                LocalAppColors.current.primaryAccent,
                LocalAppColors.current.secondaryAccent
            )
        )

    val ChipGradient: Brush
        @Composable
        get() = if (LocalAppColors.current.isDark) {
            Brush.linearGradient(
                colors = listOf(
                    LocalAppColors.current.cardBackgroundElevated,
                    LocalAppColors.current.cardBackground
                )
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    LocalAppColors.current.chipBackground,
                    LocalAppColors.current.scaffoldBackground
                )
            )
        }
}

