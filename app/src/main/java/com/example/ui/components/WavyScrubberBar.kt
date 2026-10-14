package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAppColors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/** Draw-phase scratch storage. Geometry changes with progress; phase changes reuse all allocations. */
private class WaveformDrawCache(private val colors: List<Color>) {
    private val path = Path()
    private var width = Float.NaN
    private var wavelength = Float.NaN
    private var startFade = Float.NaN
    private var endFade = Float.NaN
    private var count = 0
    private var sine = DoubleArray(0)
    private var cosine = DoubleArray(0)
    private var damping = FloatArray(0)
    private var brushWidth = Float.NaN
    private var brush: Brush? = null

    fun brush(activeWidth: Float): Brush {
        if (brush == null || brushWidth != activeWidth) {
            brushWidth = activeWidth
            brush = Brush.horizontalGradient(colors, startX = 0f, endX = activeWidth.coerceAtLeast(1f))
        }
        return brush!!
    }

    fun path(activeWidth: Float, phase: Float, centerY: Float, amplitude: Float,
        waveLength: Float, startDistance: Float, endDistance: Float): Path {
        if (width != activeWidth || wavelength != waveLength || startFade != startDistance || endFade != endDistance) {
            width = activeWidth; wavelength = waveLength; startFade = startDistance; endFade = endDistance
            count = (activeWidth / 3f).toInt() + 1
            if (sine.size < count) {
                val capacity = maxOf(count, sine.size * 2)
                sine = DoubleArray(capacity); cosine = DoubleArray(capacity); damping = FloatArray(capacity)
            }
            for (index in 0 until count) {
                val x = index * 3f
                val angle = (x / waveLength) * 2 * PI
                sine[index] = sin(angle); cosine[index] = cos(angle)
                damping[index] = (x / startDistance).coerceIn(0f, 1f) *
                    ((activeWidth - x) / endDistance).coerceIn(0f, 1f)
            }
        }
        val phaseSine = sin(phase.toDouble())
        val phaseCosine = cos(phase.toDouble())
        path.reset()
        path.moveTo(0f, centerY)
        for (index in 0 until count) {
            val value = (sine[index] * phaseCosine + cosine[index] * phaseSine).toFloat()
            path.lineTo(index * 3f, centerY + value * amplitude * damping[index])
        }
        return path
    }
}

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
    modifier: Modifier = Modifier,
    barColor: Color? = null,
    inactiveBarColor: Color? = null
) {
    val haptic = LocalHapticFeedback.current
    val glassEnabled = LocalDynamicGlassEnabled.current

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(progress) }

    val currentFraction = if (isDragging) dragFraction else progress.coerceIn(0f, 1f)

    // Wave amplitude: Flattens gracefully to 0 when paused or scrubbing
    val targetWaveAmplitude = if (isPlaying && !isDragging) 4.2f else 0f
    val animatedAmplitude by animateFloatAsState(
        targetValue = targetWaveAmplitude,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 380f),
        label = "wave_amplitude"
    )

    // Dynamic wave phase running infinitely during playback
    val wavePhase by rememberVisibleInfiniteFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase",
        active = isPlaying || animatedAmplitude != 0f
    )

    // Thumb bounce & expansion on touch/scrub
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.35f else 1.0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "thumb_scale"
    )

    val resolvedActiveColor = barColor ?: if (isDark) Color.White else Color.Black
    val resolvedInactiveColor = inactiveBarColor ?: if (isDark) {
        Color.White.copy(alpha = 0.22f)
    } else {
        Color.Black.copy(alpha = 0.16f)
    }

    val drawCache = remember(resolvedActiveColor) { WaveformDrawCache(listOf(resolvedActiveColor, resolvedActiveColor)) }
    val density = LocalDensity.current
    val trackStrokeWidth = with(density) { 4.dp.toPx() }
    val waveLengthPx = with(density) { 22.dp.toPx() }
    val startFadePx = with(density) { 24.dp.toPx() }
    val endFadePx = with(density) { 20.dp.toPx() }
    val waveStroke = remember(trackStrokeWidth) { Stroke(width = trackStrokeWidth, cap = StrokeCap.Round) }
    val rimWidth = with(density) { 1.5.dp.toPx() }
    val rimStroke = remember(rimWidth) { Stroke(width = rimWidth) }

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

            val waveAmplitudePx = animatedAmplitude.dp.toPx()

            // 1. Draw inactive / remaining track (flat background line)
            if (activeWidth < width) {
                val inactiveStart = (activeWidth + (if (animatedAmplitude > 0.5f) 0f else thumbRadius)).coerceAtMost(width)
                drawLine(
                    color = resolvedInactiveColor,
                    start = Offset(inactiveStart, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = trackStrokeWidth,
                    cap = StrokeCap.Round
                )
            }

            // 2. Draw active track (Animated Wavy Path or Smooth Line)
            if (activeWidth > 0f) {
                val activeBrush = drawCache.brush(activeWidth)

                if (waveAmplitudePx > 0.8f && activeWidth > 12f) {
                    val wavePath = drawCache.path(activeWidth, wavePhase, centerY, waveAmplitudePx,
                        waveLengthPx, startFadePx, endFadePx)

                    drawPath(
                        path = wavePath,
                        brush = activeBrush,
                        style = waveStroke
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
                val isDarkBar = (0.299 * resolvedActiveColor.red + 0.587 * resolvedActiveColor.green + 0.114 * resolvedActiveColor.blue) > 0.50f

                // Outer ambient glow ring
                val glowColor = if (isDarkBar) {
                    Color.White.copy(alpha = if (isDragging) 0.35f else 0.18f)
                } else {
                    Color.Black.copy(alpha = if (isDragging) 0.25f else 0.12f)
                }
                if (glassEnabled) drawCircle(
                    color = glowColor,
                    radius = thumbRadius + 5.dp.toPx(),
                    center = Offset(activeWidth, centerY)
                )

                // Main thumb circle
                val thumbCircleColor = if (isDarkBar) Color.White else Color(0xFF0F172A)
                drawCircle(
                    color = thumbCircleColor,
                    radius = thumbRadius,
                    center = Offset(activeWidth, centerY)
                )

                // Contrasting core dot
                val coreDotColor = if (isDarkBar) Color(0xFF18181B) else Color.White
                drawCircle(
                    color = coreDotColor,
                    radius = thumbRadius * 0.50f,
                    center = Offset(activeWidth, centerY)
                )

                // Specular rim reflection
                val rimColor = if (isDarkBar) Color.White.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.35f)
                if (glassEnabled) drawCircle(
                    color = rimColor,
                    radius = thumbRadius,
                    center = Offset(activeWidth, centerY),
                    style = rimStroke
                )
            }
        }
    }
}
