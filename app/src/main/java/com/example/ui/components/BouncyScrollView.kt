package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * 120Hz-Ready Spring / Bouncy Overscroll Effect (Rubber-band physics).
 *
 * Physics & Architecture:
 * - Runs 100% on the UI / Render thread via [Modifier.graphicsLayer] translationY,
 *   completely bypassing Compose recomposition for locked 60Hz/120Hz execution.
 * - Applies quadratic resistance stretch at top (y = 0) and bottom (y = maxExtent) scroll boundaries.
 * - Releasing the gesture triggers immediate return to baseline using precision spring physics:
 *   stiffness: 380f, dampingRatio: 0.78f.
 * - Seamless gesture continuity: touching or dragging during a spring return immediately cancels the animation
 *   and restores direct gesture control without jumps or glitches.
 * - Immediate response on fling reaching boundaries (no 1-2 second delay or waiting for idle timeouts).
 * - Fires soft haptic feedback upon crossing the overscroll threshold.
 */
class BouncyOverscrollState(
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val maxStretchPx: Float,
    private val thresholdPx: Float
) {
    val offset = Animatable(0f)
    private var hasFiredHaptic = false
    private var springJob: Job? = null

    // Precision spring tuning: stiffness = 380f, dampingRatio = 0.78f
    private val springSpec = spring<Float>(
        stiffness = 380f,
        dampingRatio = 0.78f
    )

    val nestedScrollConnection = object : NestedScrollConnection {

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // When user touches or moves finger again, immediately interrupt any active spring animation
            if (source == NestedScrollSource.UserInput && springJob?.isActive == true) {
                springJob?.cancel()
                springJob = null
            }

            val current = offset.value
            // If already stretched and user moves back toward center, consume to reduce stretch smoothly
            if (current != 0f && source == NestedScrollSource.UserInput) {
                if ((current > 0f && available.y < 0f) || (current < 0f && available.y > 0f)) {
                    val remaining = current + available.y
                    val consumedY = if (current > 0f && remaining < 0f) {
                        -current
                    } else if (current < 0f && remaining > 0f) {
                        -current
                    } else {
                        available.y
                    }

                    scope.launch {
                        offset.snapTo(current + consumedY)
                    }
                    return Offset(0f, consumedY)
                }
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (available.y == 0f) return Offset.Zero

            val current = offset.value
            val absCurrent = abs(current)

            if (source == NestedScrollSource.UserInput) {
                // Direct touch drag past boundary: quadratic rubber-band resistance
                val resistanceFactor = (1f - (absCurrent / maxStretchPx).pow(1.5f)).coerceIn(0.12f, 0.65f)
                val delta = available.y * resistanceFactor
                val nextOffset = (current + delta).coerceIn(-maxStretchPx, maxStretchPx)

                // Soft haptic feedback crossing threshold
                if (!hasFiredHaptic && abs(nextOffset) >= thresholdPx) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    hasFiredHaptic = true
                }

                springJob?.cancel()
                scope.launch {
                    offset.snapTo(nextOffset)
                }
                return Offset(0f, available.y)
            } else if (source == NestedScrollSource.SideEffect) {
                // Fling reached boundary! Immediately absorb unconsumed fling motion without waiting for idle timeout
                val resistanceFactor = 0.35f / (1f + (absCurrent / maxStretchPx) * 2f)
                val delta = (available.y * resistanceFactor).coerceIn(-18f, 18f)
                val nextOffset = (current + delta).coerceIn(-maxStretchPx * 0.55f, maxStretchPx * 0.55f)

                scope.launch {
                    offset.snapTo(nextOffset)
                }

                // Immediately spring back without any delay
                springJob?.cancel()
                springJob = scope.launch {
                    offset.animateTo(0f, springSpec)
                    offset.snapTo(0f)
                }
                return Offset(0f, available.y)
            }

            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            hasFiredHaptic = false
            if (offset.value != 0f) {
                springJob?.cancel()
                val initVelocity = available.y.coerceIn(-2000f, 2000f)
                springJob = scope.launch {
                    offset.animateTo(0f, springSpec, initialVelocity = initVelocity)
                    offset.snapTo(0f)
                }
                return available
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            hasFiredHaptic = false
            // If there is residual fling velocity hitting the boundary, absorb a fraction into an elastic bounce
            if (available.y != 0f && offset.value == 0f) {
                val flingBounce = (available.y * 0.035f).coerceIn(-maxStretchPx * 0.45f, maxStretchPx * 0.45f)
                if (abs(flingBounce) > 2f) {
                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.snapTo(flingBounce)
                        offset.animateTo(0f, springSpec)
                        offset.snapTo(0f)
                    }
                }
            } else if (offset.value != 0f && springJob?.isActive != true) {
                springJob = scope.launch {
                    offset.animateTo(0f, springSpec)
                    offset.snapTo(0f)
                }
            }
            return Velocity.Zero
        }
    }
}

/**
 * Modifier extension adding 120Hz-ready bouncy overscroll physics to any scrollable layout.
 */
@Composable
fun Modifier.bouncyOverscroll(
    enabled: Boolean = true,
    maxStretchDp: Dp = 90.dp,
    thresholdDp: Dp = 8.dp
): Modifier {
    if (!enabled) return this

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val maxStretchPx = with(density) { maxStretchDp.toPx() }
    val thresholdPx = with(density) { thresholdDp.toPx() }

    val state = remember(maxStretchPx, thresholdPx) {
        BouncyOverscrollState(scope, haptic, maxStretchPx, thresholdPx)
    }

    return this
        .nestedScroll(state.nestedScrollConnection)
        .graphicsLayer {
            translationY = state.offset.value
        }
}

/**
 * Container component wrapping any scrollable content with bouncy spring overscroll.
 */
@Composable
fun BouncyScrollView(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxStretchDp: Dp = 90.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.bouncyOverscroll(enabled = enabled, maxStretchDp = maxStretchDp),
        content = content
    )
}
