package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAppColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dynamic Wavy Scrubber Bar with Real-time Audio Visualizer Motion:
 * - Active progress renders as an animated traveling sine wave during playback.
 * - Gracefully flattens into a sleek smooth line when paused or scrubbing.
 * - Interactive thumb with tactile spring expansion and specular liquid glass glow.
 * - Full tap & drag seeking support with haptic feedback.
 */
@Composable
fun WavyScrubberBar(
    progress: Float, // 0.0f .. 1.0f
    isPlaying: Boolean,
    onSeekStarted: () -> Unit,
    onSeekProgress: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val haptic = LocalHapticFeedback.current

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(progress) }

    val currentFraction = if (isDragging) dragFraction else progress.coerceIn(0f, 1f)

    // Dynamic wave phase running infinitely during playback
    val infiniteTransition = rememberInfiniteTransition(label = "wavy_scrubber_phase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    // Wave amplitude: Flattens gracefully to 0 when paused or scrubbing
    val targetWaveAmplitude = if (isPlaying && !isDragging) 4.2f else 0f
    val animatedAmplitude by animateFloatAsState(
        targetValue = targetWaveAmplitude,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 380f),
        label = "wave_amplitude"
    )

    // Thumb bounce & expansion on touch/scrub
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.35f else 1.0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "thumb_scale"
    )

    val primaryAccent = appColors.primaryAccent
    val secondaryAccent = appColors.secondaryAccent
    val inactiveTrackColor = if (isDark) {
        appColors.cardBorder.copy(alpha = 0.8f)
    } else {
        Color(0xFFCBD5E1)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        isDragging = true
                        onSeekStarted()
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragFraction = fraction
                        onSeekProgress(fraction)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                        val released = tryAwaitRelease()
                        if (released) {
                            onSeekFinished(dragFraction)
                        }
                        isDragging = false
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        onSeekStarted()
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragFraction = fraction
                        onSeekProgress(fraction)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDragEnd = {
                        onSeekFinished(dragFraction)
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragFraction = fraction
                        onSeekProgress(fraction)
                    }
                )
            }
            .testTag("wavy_scrubber_bar")
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            val thumbRadius = 7.dp.toPx() * thumbScale
            val activeWidth = (width * currentFraction).coerceIn(0f, width)

            val trackStrokeWidth = 4.dp.toPx()
            val waveAmplitudePx = animatedAmplitude.dp.toPx()
            val waveLengthPx = 22.dp.toPx()

            // 1. Draw inactive / remaining track (flat background line)
            if (activeWidth < width) {
                val inactiveStart = (activeWidth + (if (animatedAmplitude > 0.5f) 0f else thumbRadius)).coerceAtMost(width)
                drawLine(
                    color = inactiveTrackColor,
                    start = Offset(inactiveStart, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = trackStrokeWidth,
                    cap = StrokeCap.Round
                )
            }

            // 2. Draw active track (Animated Wavy Path or Smooth Line)
            if (activeWidth > 0f) {
                val activeBrush = Brush.horizontalGradient(
                    colors = listOf(primaryAccent, secondaryAccent),
                    startX = 0f,
                    endX = activeWidth.coerceAtLeast(1f)
                )

                if (waveAmplitudePx > 0.8f && activeWidth > 12f) {
                    // Draw sinusoidal wave path
                    val wavePath = Path()
                    wavePath.moveTo(0f, centerY)

                    val stepPx = 3f
                    var x = 0f
                    while (x <= activeWidth) {
                        // Fade wave amplitude smoothly near start (0f) and thumb end (activeWidth)
                        val startDamping = (x / 24.dp.toPx()).coerceIn(0f, 1f)
                        val endDamping = ((activeWidth - x) / 20.dp.toPx()).coerceIn(0f, 1f)
                        val damping = startDamping * endDamping

                        val angle = (x / waveLengthPx) * 2 * PI + wavePhase
                        val y = centerY + (sin(angle).toFloat() * waveAmplitudePx * damping)
                        wavePath.lineTo(x, y)
                        x += stepPx
                    }

                    drawPath(
                        path = wavePath,
                        brush = activeBrush,
                        style = Stroke(
                            width = trackStrokeWidth,
                            cap = StrokeCap.Round
                        )
                    )
                } else {
                    // Draw smooth flat line
                    drawLine(
                        brush = activeBrush,
                        start = Offset(0f, centerY),
                        end = Offset(activeWidth, centerY),
                        strokeWidth = trackStrokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                // 3. Draw Scrubber Thumb with specular liquid glass glow
                // Outer ambient glow ring
                drawCircle(
                    color = primaryAccent.copy(alpha = if (isDragging) 0.35f else 0.20f),
                    radius = thumbRadius + 5.dp.toPx(),
                    center = Offset(activeWidth, centerY)
                )

                // Main thumb circle
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = Offset(activeWidth, centerY)
                )

                // Accent core dot
                drawCircle(
                    color = primaryAccent,
                    radius = thumbRadius * 0.55f,
                    center = Offset(activeWidth, centerY)
                )

                // Specular rim reflection
                drawCircle(
                    color = primaryAccent.copy(alpha = 0.5f),
                    radius = thumbRadius,
                    center = Offset(activeWidth, centerY),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}
