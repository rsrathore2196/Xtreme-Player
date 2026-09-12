package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Xtreme Player - Signature Light Blue & Electric Navy Palette (Matched with 3D Logo)
val XtremeBackground = Color(0xFF070F1E)       // Deep atmospheric midnight navy
val XtremeSurface = Color(0xFF0D182A)          // Surface navy
val XtremeCard = Color(0xFF132238)             // Elevated card navy
val XtremeCardElevated = Color(0xFF192B45)     // Higher elevated surface
val XtremeBorder = Color(0xFF1E3554)           // Crisp card outline
val XtremeBorderGlow = Color(0x6638BDF8)       // Light blue glowing border

// Signature Accent Tones from Logo
val XtremeLightBlue = Color(0xFF38BDF8)        // Primary vibrant light blue (Sky 400)
val XtremeCyan = Color(0xFF00E5FF)             // Electric luminous cyan
val XtremeDeepBlue = Color(0xFF0284C7)         // Deep electric blue (Sky 600)
val XtremeIce = Color(0xFFBAE6FD)              // Ice light blue highlight (Sky 200)
val XtremeGreen = Color(0xFF38BDF8)            // Mapped to signature light blue for seamless integration across all views!
val XtremePurple = Color(0xFF818CF8)           // Indigo/sky harmony
val XtremeRose = Color(0xFFF43F5E)             // Favorite heart accent

val TextPrimary = Color(0xFFF0F9FF)            // Crisp ice white (high contrast)
val TextSecondary = Color(0xFF93C5FD)          // Soft sky blue (readable secondary)
val TextMuted = Color(0xFF627D98)              // Muted cool slate

val SliderTrackColor = Color(0xFF1E3554)
val SliderThumbColor = Color(0xFF38BDF8)
val SliderBufferedColor = Color(0xFF2E4E75)

// Eye-catching Signature Gradients matching the 3D Light Blue Logo
object XtremeGradients {
    // Primary Vibrant Logo Gradient (Cyan -> Sky Blue -> Electric Blue)
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

    // Full Screen Background Gradient
    val ScreenBackground = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0C192E), // Top subtle ambient light blue tint
            Color(0xFF070F1E),
            Color(0xFF040812)
        )
    )

    // Player Ambient Gradient
    val PlayerAmbient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0E2545), // Luminous top blue glow
            Color(0xFF091424),
            Color(0xFF050B14)
        )
    )

    // Card Glass / Depth Gradient
    val CardGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF14243B),
            Color(0xFF0F1B2D)
        )
    )

    // Button & Active Indicator Gradient
    val ButtonGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF0284C7),
            Color(0xFF38BDF8),
            Color(0xFF00E5FF)
        )
    )

    // Subtle Chip Gradient
    val ChipGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF172B46),
            Color(0xFF101E31)
        )
    )
}
