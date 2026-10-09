package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAppColors

/**
 * AmbientBlurredBackground:
 * Renders full-screen ambient background with dynamic palette swatches,
 * multi-layered Gaussian blur (48dp-60dp), floating radial orbs, and contrast protection vignette.
 * Transitions between songs smoothly using crossfade and animateColorAsState without per-frame bitmap blurs.
 */
@Composable
fun AmbientBlurredBackground(
    dominantColor: Color,
    accentColor: Color,
    vibrantColor: Color = accentColor,
    isDark: Boolean = true,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current

    // Smooth crossfade transitions between track color changes
    val animatedDominant by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "ambient_dominant"
    )
    val animatedAccent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "ambient_accent"
    )
    val animatedVibrant by animateColorAsState(
        targetValue = vibrantColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "ambient_vibrant"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ambient_bokeh_anim")
    val floatAnim1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "ambient_float_1"
    )
    val floatAnim2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "ambient_float_2"
    )
    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = FastOutSlowInEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "ambient_pulse"
    )

    val isAmoled = appColors.isAmoled

    Box(modifier = modifier.fillMaxSize()) {
        // Base foundation color
        val baseColor = if (isAmoled) Color(0xFF000000) else if (isDark) appColors.scaffoldBackground else Color(0xFFF8FAFC)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(baseColor)
        )

        // Scaled, heavily blurred ambient background layer (60.dp blur)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp)
                .graphicsLayer { alpha = 0.88f }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                if (isAmoled) {
                    val orbCenter = Offset(w * 0.5f, h * 0.35f)
                    val orbRadius = w * 0.78f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                animatedAccent.copy(alpha = 0.25f * pulseAnim),
                                animatedVibrant.copy(alpha = 0.15f * pulseAnim),
                                Color.Transparent
                            ),
                            center = orbCenter,
                            radius = orbRadius
                        ),
                        center = orbCenter,
                        radius = orbRadius
                    )
                    return@Canvas
                }

                val domAlpha = if (isDark) 0.85f else 0.35f
                val accAlpha = if (isDark) 0.90f else 0.40f
                val vibAlpha = if (isDark) 0.75f else 0.30f

                // Central bloom
                val centerOffset = Offset(
                    x = w * (0.50f + 0.08f * (floatAnim1 - 0.5f)),
                    y = h * (0.36f + 0.06f * (floatAnim2 - 0.5f))
                )
                val centerRadius = w * 0.90f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = accAlpha * pulseAnim),
                            animatedVibrant.copy(alpha = (vibAlpha * 0.75f) * pulseAnim),
                            animatedDominant.copy(alpha = (domAlpha * 0.50f) * pulseAnim),
                            Color.Transparent
                        ),
                        center = centerOffset,
                        radius = centerRadius
                    ),
                    center = centerOffset,
                    radius = centerRadius
                )

                // Top-left floating orb
                val orb1Center = Offset(
                    x = w * (0.24f + 0.10f * (floatAnim1 - 0.5f)),
                    y = h * (0.22f + 0.08f * (floatAnim2 - 0.5f))
                )
                val orb1Radius = w * 0.74f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedDominant.copy(alpha = domAlpha * pulseAnim),
                            appColors.secondaryAccent.copy(alpha = (domAlpha * 0.40f) * pulseAnim),
                            Color.Transparent
                        ),
                        center = orb1Center,
                        radius = orb1Radius
                    ),
                    center = orb1Center,
                    radius = orb1Radius
                )

                // Mid-right floating orb
                val orb2Center = Offset(
                    x = w * (0.78f - 0.12f * (floatAnim2 - 0.5f)),
                    y = h * (0.48f + 0.10f * (floatAnim1 - 0.5f))
                )
                val orb2Radius = w * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedVibrant.copy(alpha = vibAlpha * pulseAnim),
                            animatedAccent.copy(alpha = (accAlpha * 0.50f) * pulseAnim),
                            Color.Transparent
                        ),
                        center = orb2Center,
                        radius = orb2Radius
                    ),
                    center = orb2Center,
                    radius = orb2Radius
                )
            }
        }

        // Overlay a dark contrast protection layer (luminance gradient)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val h = size.height

            val glassCausticBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDark) 0.15f else 0.30f),
                    Color.White.copy(alpha = if (isDark) 0.04f else 0.08f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h * 0.45f
            )
            drawRect(glassCausticBrush)

            val darkBase = if (isAmoled) Color(0xFF000000) else appColors.scaffoldBackground
            val vignetteColors = if (isDark) {
                listOf(
                    darkBase.copy(alpha = 0.30f),
                    Color.Transparent,
                    darkBase.copy(alpha = 0.55f),
                    darkBase.copy(alpha = 0.90f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.25f),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.85f)
                )
            }
            drawRect(brush = Brush.verticalGradient(colors = vignetteColors))
        }
    }
}
