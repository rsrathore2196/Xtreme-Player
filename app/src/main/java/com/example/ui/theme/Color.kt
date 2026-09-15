package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class AppThemeColors(
    val isDark: Boolean,
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
)

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
    get() = if (LocalAppColors.current.isDark) Color(0x6638BDF8) else Color(0x440284C7)

// Signature Accent Tones
val XtremeLightBlue = Color(0xFF38BDF8)        // Primary vibrant light blue (Sky 400)
val XtremeCyan = Color(0xFF00E5FF)             // Electric luminous cyan
val XtremeDeepBlue = Color(0xFF0284C7)         // Deep electric blue (Sky 600)
val XtremeIce = Color(0xFFBAE6FD)              // Ice light blue highlight (Sky 200)
val XtremeGreen = Color(0xFF38BDF8)            // Mapped to signature light blue
val XtremePurple = Color(0xFF818CF8)           // Indigo/sky harmony
val XtremeRose = Color(0xFFF43F5E)             // Favorite heart accent

val SliderTrackColor = Color(0xFF1E3554)
val SliderThumbColor = Color(0xFF38BDF8)
val SliderBufferedColor = Color(0xFF2E4E75)

// Eye-catching Signature Gradients matching the theme
object XtremeGradients {
    val LogoGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF00E5FF),
            Color(0xFF38BDF8),
            Color(0xFF0284C7)
        )
    )

    val LightBlueGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF38BDF8),
            Color(0xFF00E5FF)
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
                    Color(0xFF0E2545),
                    Color(0xFF091424),
                    Color(0xFF050B14)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE0F2FE),
                    Color(0xFFF0F9FF),
                    Color(0xFFFFFFFF)
                )
            )
        }

    val CardGradient: Brush
        @Composable
        get() = if (LocalAppColors.current.isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF14243B),
                    Color(0xFF0F1B2D)
                )
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF8FAFC)
                )
            )
        }

    val ButtonGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF0284C7),
            Color(0xFF38BDF8),
            Color(0xFF00E5FF)
        )
    )

    val ChipGradient: Brush
        @Composable
        get() = if (LocalAppColors.current.isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF172B46),
                    Color(0xFF101E31)
                )
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFFF0F6FF),
                    Color(0xFFE2E8F0)
                )
            )
        }
}

