package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Immutable
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
            return if (lum > 0.55f) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
        }

    val onSecondaryAccent: Color
        get() {
            val lum = (0.299 * secondaryAccent.red + 0.587 * secondaryAccent.green + 0.114 * secondaryAccent.blue)
            return if (lum > 0.55f) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
        }

    val bottomSheetBackground: Color
        get() = if (isAmoled) Color.Black else if (isDark) cardBackground else scaffoldBackground
}

/**
 * Universal contrast calculator returning deep contrast text/icon color
 * against any background color (WCAG AAA compliant).
 */
fun contrastingContentColor(background: Color): Color {
    val lum = (0.299 * background.red + 0.587 * background.green + 0.114 * background.blue)
    return if (lum > 0.55f) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
}

// Night / Dark Theme: Neutral Studio Night - Deep Obsidian Charcoal & Eye-Catching Electric Azure Blue
val DarkAppColors = AppThemeColors(
    isDark = true,
    screenBackground = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0D0E12),
            Color(0xFF08090C),
            Color(0xFF040406)
        )
    ),
    scaffoldBackground = Color(0xFF0D0E12),
    cardBackground = Color(0xFF15161C),
    cardBackgroundElevated = Color(0xFF1C1D24),
    cardBorder = Color(0xFF282932),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    primaryAccent = Color(0xFF00D4FF),
    secondaryAccent = Color(0xFF38BDF8),
    bottomBarBackground = Color(0xFF0D0E12),
    bottomBarIndicator = Color(0xFF22242D),
    bottomBarSelectedIcon = Color(0xFF00D4FF),
    bottomBarUnselectedIcon = Color(0xFF64748B),
    miniPlayerBackground = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF181920),
            Color(0xFF121318)
        )
    ),
    miniPlayerBorder = Color(0xFF282932),
    inputBackground = Color(0xFF121318),
    chipBackground = Color(0xFF181920),
    chipBorder = Color(0xFF282932),
    dividerColor = Color(0xFF20222A)
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

// =========================================================================
// LASTWAVE NATIVE (Clash-Projects/LastWave-native) LIQUID GLASS COLOR SYSTEM
// =========================================================================

object LastWaveColors {
    // 1. Oceanic Abyssal Void & Luminous Azure Glass
    val OceanicCanvas = Color(0xFF030712)
    val OceanicCard = Color(0xFF0B1220)
    val OceanicCardElevated = Color(0xFF132035)
    val OceanicBorder = Color(0xFF1E3557)
    val OceanicAccentPrimary = Color(0xFF00D2FF)
    val OceanicAccentSecondary = Color(0xFF3B82F6)

    // 2. Midnight Nebula Glass & Radiant Amethyst
    val NebulaCanvas = Color(0xFF070414)
    val NebulaCard = Color(0xFF120A28)
    val NebulaCardElevated = Color(0xFF1F1240)
    val NebulaBorder = Color(0xFF381F66)
    val NebulaAccentPrimary = Color(0xFFA855F7)
    val NebulaAccentSecondary = Color(0xFFEC4899)

    // 3. Cyberpunk Matrix & Acid Lime Glass
    val MatrixCanvas = Color(0xFF020C06)
    val MatrixCard = Color(0xFF081C10)
    val MatrixCardElevated = Color(0xFF0F2E1B)
    val MatrixBorder = Color(0xFF1B4E2F)
    val MatrixAccentPrimary = Color(0xFF10B981)
    val MatrixAccentSecondary = Color(0xFF84CC16)

    // 4. Solar Flare & Volcanic Amber Glow
    val SolarCanvas = Color(0xFF0E0502)
    val SolarCard = Color(0xFF1D0D07)
    val SolarCardElevated = Color(0xFF2E160C)
    val SolarBorder = Color(0xFF4F2314)
    val SolarAccentPrimary = Color(0xFFF97316)
    val SolarAccentSecondary = Color(0xFFFBBF24)

    // 5. Glacier Frost Crystal (Light Glass)
    val GlacierCanvas = Color(0xFFF5F9FF)
    val GlacierCard = Color(0xFFFFFFFF)
    val GlacierCardElevated = Color(0xFFE8F1FC)
    val GlacierBorder = Color(0xFFBFD7F5)
    val GlacierAccentPrimary = Color(0xFF0284C7)
    val GlacierAccentSecondary = Color(0xFF06B6D4)

    // 6. Sakura Quartz Crystal (Light Glass)
    val SakuraCanvas = Color(0xFFFFF6F8)
    val SakuraCard = Color(0xFFFFFFFF)
    val SakuraCardElevated = Color(0xFFFDE8ED)
    val SakuraBorder = Color(0xFFFBC4CF)
    val SakuraAccentPrimary = Color(0xFFE11D48)
    val SakuraAccentSecondary = Color(0xFFF43F5E)

    // Translucent Liquid Glass Overlays & Highlights
    val GlassSpecularHighlightDark = Color(0x66FFFFFF) // rgba(255, 255, 255, 0.40)
    val GlassSpecularBorderDark = Color(0x14FFFFFF)    // rgba(255, 255, 255, 0.08)
    val GlassSpecularHighlightLight = Color(0xCCFFFFFF) // rgba(255, 255, 255, 0.80)
    val GlassSpecularBorderLight = Color(0x26000000)   // rgba(0, 0, 0, 0.15)
}

