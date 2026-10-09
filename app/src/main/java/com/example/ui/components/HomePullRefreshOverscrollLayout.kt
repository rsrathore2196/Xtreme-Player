package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Coordinated Single-Owner Manager for Home Screen Vertical Boundary Displacements:
 *
 * 1. True Top Boundary: Deliberate downward drag smoothly reveals the liquid-glass refresh indicator.
 *    - Progressively reveals indicator with smooth progress tracking.
 *    - Clear "ready to refresh" state once the threshold is crossed (with soft haptic feedback).
 *    - Releasing beyond threshold triggers onRefresh().
 *    - Releasing below threshold smoothly springs back without fetching.
 *    - Holding the gesture alone does not trigger repeated requests.
 *    - While refreshing, indicator stays visible and spinning until real refresh operation completes.
 *    - Existing cards remain visible and interactive throughout.
 *
 * 2. Bottom Boundary (and Top Fling): Elastic Rubber-Band Overscroll with Spring Physics.
 *    - Immediate proportional movement with quadratic resistance and capped displacement.
 *    - If finger is still down, displacement tracks responsive to gesture; returns immediately when released.
 *    - Fling reaching boundary responds immediately to unconsumed velocity without idle timeout delay.
 *    - Continuous, physically coherent spring return to exact resting position (0f).
 *    - Seamless continuity: touching again during return interrupts the animation immediately.
 *    - Normal scrolling away from boundaries does NOT bounce.
 */
class HomeBoundaryCoordinator(
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val refreshThresholdPx: Float,
    private val maxTopStretchPx: Float,
    private val maxBottomStretchPx: Float,
    private val refreshRestingHeightPx: Float,
    private val onRefresh: () -> Unit
) {
    val offset = Animatable(0f)
    var isRefreshing by mutableStateOf(false)
        internal set

    private var hasFiredThresholdHaptic = false
    private var springJob: Job? = null

    // Physically tuned spring specs: stiffness = 380f, dampingRatio = 0.78f
    private val returnSpringSpec = spring<Float>(
        stiffness = 380f,
        dampingRatio = 0.78f
    )

    private val refreshHoldSpringSpec = spring<Float>(
        stiffness = 400f,
        dampingRatio = 0.85f
    )

    val nestedScrollConnection = object : NestedScrollConnection {

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // When user touches again during spring return, immediately cancel animation and restore touch continuity
            if (source == NestedScrollSource.UserInput && springJob?.isActive == true) {
                springJob?.cancel()
                springJob = null
            }

            val current = offset.value

            // If displaced and user drags back toward center, consume reverse delta to collapse smoothly
            if (current != 0f && source == NestedScrollSource.UserInput) {
                if (current > 0f && available.y < 0f) {
                    // Moving back up from top pull
                    val consumedY = available.y.coerceAtLeast(-current)
                    scope.launch { offset.snapTo(current + consumedY) }
                    return Offset(0f, consumedY)
                } else if (current < 0f && available.y > 0f) {
                    // Moving back down from bottom overscroll
                    val consumedY = available.y.coerceAtMost(-current)
                    scope.launch { offset.snapTo(current + consumedY) }
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

            if (source == NestedScrollSource.UserInput) {
                if (available.y > 0f) {
                    // Pulling down at top boundary -> Pull-To-Refresh gesture
                    if (!isRefreshing) {
                        val posCurrent = current.coerceAtLeast(0f)
                        val resistance = (1f - (posCurrent / maxTopStretchPx).pow(1.5f)).coerceIn(0.15f, 0.65f)
                        val delta = available.y * resistance
                        val nextOffset = (posCurrent + delta).coerceIn(0f, maxTopStretchPx)

                        if (!hasFiredThresholdHaptic && nextOffset >= refreshThresholdPx) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            hasFiredThresholdHaptic = true
                        } else if (hasFiredThresholdHaptic && nextOffset < refreshThresholdPx) {
                            hasFiredThresholdHaptic = false
                        }

                        springJob?.cancel()
                        scope.launch { offset.snapTo(nextOffset) }
                        return Offset(0f, available.y)
                    }
                } else if (available.y < 0f) {
                    // Pulling up at bottom boundary -> Elastic Overscroll
                    val negCurrent = current.coerceAtMost(0f)
                    val absCurrent = abs(negCurrent)
                    val resistance = (1f - (absCurrent / maxBottomStretchPx).pow(1.5f)).coerceIn(0.12f, 0.60f)
                    val delta = available.y * resistance
                    val nextOffset = (negCurrent + delta).coerceIn(-maxBottomStretchPx, 0f)

                    springJob?.cancel()
                    scope.launch { offset.snapTo(nextOffset) }
                    return Offset(0f, available.y)
                }
            } else if (source == NestedScrollSource.SideEffect) {
                // Fling reached boundary! Immediately absorb unconsumed fling motion
                if (available.y < 0f) {
                    // Fling hit bottom boundary
                    val negCurrent = current.coerceAtMost(0f)
                    val absCurrent = abs(negCurrent)
                    val factor = 0.35f / (1f + (absCurrent / maxBottomStretchPx) * 2f)
                    val delta = (available.y * factor).coerceIn(-18f, 0f)
                    val nextOffset = (negCurrent + delta).coerceIn(-maxBottomStretchPx * 0.55f, 0f)

                    scope.launch { offset.snapTo(nextOffset) }

                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.animateTo(0f, returnSpringSpec)
                        offset.snapTo(0f)
                    }
                    return Offset(0f, available.y)
                } else if (available.y > 0f && !isRefreshing) {
                    // Fling hit top boundary (absorb gentle bounce without triggering refresh)
                    val posCurrent = current.coerceAtLeast(0f)
                    val factor = 0.25f / (1f + (posCurrent / maxTopStretchPx) * 2f)
                    val delta = (available.y * factor).coerceIn(0f, 15f)
                    val nextOffset = (posCurrent + delta).coerceIn(0f, refreshThresholdPx * 0.6f)

                    scope.launch { offset.snapTo(nextOffset) }

                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.animateTo(0f, returnSpringSpec)
                        offset.snapTo(0f)
                    }
                    return Offset(0f, available.y)
                }
            }

            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            hasFiredThresholdHaptic = false
            val current = offset.value

            if (current > 0f) {
                // Drag ended at top
                if (current >= refreshThresholdPx && !isRefreshing) {
                    // Crossed threshold! Trigger exactly one refresh
                    onRefresh()
                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.animateTo(refreshRestingHeightPx, refreshHoldSpringSpec)
                    }
                } else {
                    // Released below threshold: return to resting 0 smoothly without fetching
                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.animateTo(if (isRefreshing) refreshRestingHeightPx else 0f, returnSpringSpec)
                        if (!isRefreshing) offset.snapTo(0f)
                    }
                }
                return Velocity.Zero
            } else if (current < 0f) {
                // Drag ended at bottom: return to resting 0 immediately with fling velocity continuity
                springJob?.cancel()
                val initVelocity = available.y.coerceIn(-2000f, 2000f)
                springJob = scope.launch {
                    offset.animateTo(0f, returnSpringSpec, initialVelocity = initVelocity)
                    offset.snapTo(0f)
                }
                return available
            }

            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            hasFiredThresholdHaptic = false
            // Absorb any leftover fling velocity hitting bottom
            if (available.y < 0f && offset.value == 0f) {
                val flingBounce = (available.y * 0.035f).coerceIn(-maxBottomStretchPx * 0.45f, 0f)
                if (abs(flingBounce) > 2f) {
                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.snapTo(flingBounce)
                        offset.animateTo(0f, returnSpringSpec)
                        offset.snapTo(0f)
                    }
                }
            } else if (offset.value != 0f && springJob?.isActive != true) {
                springJob = scope.launch {
                    offset.animateTo(if (isRefreshing) refreshRestingHeightPx else 0f, returnSpringSpec)
                    if (!isRefreshing) offset.snapTo(0f)
                }
            }
            return Velocity.Zero
        }
    }

    fun onRefreshingChanged(refreshing: Boolean) {
        if (isRefreshing == refreshing) return
        isRefreshing = refreshing

        springJob?.cancel()
        springJob = scope.launch {
            if (refreshing) {
                if (offset.value < refreshRestingHeightPx) {
                    offset.animateTo(refreshRestingHeightPx, refreshHoldSpringSpec)
                }
            } else {
                offset.animateTo(0f, returnSpringSpec)
                offset.snapTo(0f)
            }
        }
    }
}

/**
 * Coordinated Container for Home Screen handling both smooth Material 3 Liquid-Glass Pull-To-Refresh
 * and 120Hz Elastic Edge Spring Overscroll without competing nested scroll connections.
 */
@Composable
fun HomePullRefreshOverscrollLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshThresholdDp: Dp = 72.dp,
    maxTopStretchDp: Dp = 110.dp,
    maxBottomStretchDp: Dp = 85.dp,
    refreshRestingHeightDp: Dp = 56.dp,
    content: @Composable BoxScope.(contentOffset: Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val refreshThresholdPx = with(density) { refreshThresholdDp.toPx() }
    val maxTopStretchPx = with(density) { maxTopStretchDp.toPx() }
    val maxBottomStretchPx = with(density) { maxBottomStretchDp.toPx() }
    val refreshRestingHeightPx = with(density) { refreshRestingHeightDp.toPx() }

    val coordinator = remember(refreshThresholdPx, maxTopStretchPx, maxBottomStretchPx) {
        HomeBoundaryCoordinator(
            scope = scope,
            haptic = haptic,
            refreshThresholdPx = refreshThresholdPx,
            maxTopStretchPx = maxTopStretchPx,
            maxBottomStretchPx = maxBottomStretchPx,
            refreshRestingHeightPx = refreshRestingHeightPx,
            onRefresh = onRefresh
        )
    }

    LaunchedEffect(isRefreshing) {
        coordinator.onRefreshingChanged(isRefreshing)
    }

    val currentOffset = coordinator.offset.value
    val isDark = isSystemInDarkTheme()

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(coordinator.nestedScrollConnection)
    ) {
        // Main Content (displaced smoothly via 120Hz graphicsLayer translationY)
        content(currentOffset)

        // Top Pull-To-Refresh Indicator
        val pullProgress = if (currentOffset > 0f) {
            (currentOffset / refreshThresholdPx).coerceIn(0f, 1f)
        } else {
            0f
        }

        val showIndicator = currentOffset > 6f || isRefreshing

        if (showIndicator) {
            val indicatorY = with(density) {
                // Progressive reveal starting from off-screen
                val restingY = refreshRestingHeightDp.toPx() * 0.45f
                val clampedOffset = currentOffset.coerceAtMost(maxTopStretchPx)
                (-40.dp.toPx() + clampedOffset * 0.75f).coerceAtMost(restingY + 24.dp.toPx())
            }

            val isReady = pullProgress >= 1f && !isRefreshing

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .offset { IntOffset(0, indicatorY.roundToInt()) }
                    .padding(top = 10.dp)
                    .graphicsLayer {
                        alpha = if (isRefreshing) 1f else (pullProgress * 1.3f).coerceIn(0f, 1f)
                        scaleX = if (isRefreshing) 1f else (0.8f + pullProgress * 0.2f).coerceIn(0.8f, 1f)
                        scaleY = scaleX
                    },
                contentAlignment = Alignment.Center
            ) {
                // Polished Liquid-Glass Refresh Pill
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (isDark) Color(0xDD18181B) else Color(0xEEFFFFFF),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isReady || isRefreshing) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        } else {
                            if (isDark) Color(0x33FFFFFF) else Color(0x22000000)
                        }
                    ),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Refreshing music...",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color.White else Color.Black
                                )
                            )
                        } else {
                            val rotation = pullProgress * 180f
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Pull to refresh",
                                modifier = Modifier
                                    .size(17.dp)
                                    .rotate(rotation),
                                tint = if (isReady) MaterialTheme.colorScheme.primary else (if (isDark) Color.White else Color.Black)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isReady) "Release to refresh" else "Pull to refresh",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isReady) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isReady) MaterialTheme.colorScheme.primary else (if (isDark) Color(0xFFE4E4E7) else Color(0xFF27272A))
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
