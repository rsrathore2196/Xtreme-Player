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

    // Hold progress from 0f to 1f while holding past threshold for 1 full second
    var holdProgress by mutableStateOf(0f)
        private set

    // True only when the user has completed the heavy pull and 1 full second hold
    var isHoldSatisfied by mutableStateOf(false)
        private set

    private var thresholdCrossedTimeMs = 0L
    private var holdTimerJob: Job? = null
    private var hasFiredThresholdHaptic = false
    private var springJob: Job? = null

    // Physically tuned spring specs: stiffness = 340f, dampingRatio = 0.76f for smooth bounce
    private val returnSpringSpec = spring<Float>(
        stiffness = 340f,
        dampingRatio = 0.76f
    )

    private val refreshHoldSpringSpec = spring<Float>(
        stiffness = 380f,
        dampingRatio = 0.82f
    )

    private fun cancelHoldTimer() {
        holdTimerJob?.cancel()
        holdTimerJob = null
        thresholdCrossedTimeMs = 0L
        holdProgress = 0f
        isHoldSatisfied = false
    }

    private fun startHoldTimer() {
        if (holdTimerJob?.isActive == true || isHoldSatisfied) return
        thresholdCrossedTimeMs = System.currentTimeMillis()
        holdTimerJob = scope.launch {
            val requiredHoldDurationMs = 1000L
            val intervalMs = 16L
            while (true) {
                val elapsed = System.currentTimeMillis() - thresholdCrossedTimeMs
                val progress = (elapsed.toFloat() / requiredHoldDurationMs).coerceIn(0f, 1f)
                holdProgress = progress
                if (progress >= 1f) {
                    isHoldSatisfied = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    break
                }
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }

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
                    val nextOffset = current + consumedY
                    scope.launch { offset.snapTo(nextOffset) }

                    // If user moves back below refresh threshold, reset hold countdown
                    if (nextOffset < refreshThresholdPx) {
                        cancelHoldTimer()
                        hasFiredThresholdHaptic = false
                    }
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
                    // Pulling down at top boundary -> Pull-To-Refresh gesture (heavy pull with graduated resistance)
                    if (!isRefreshing) {
                        val posCurrent = current.coerceAtLeast(0f)
                        val resistance = (1f - (posCurrent / maxTopStretchPx).pow(1.8f)).coerceIn(0.10f, 0.50f)
                        val delta = available.y * resistance
                        val nextOffset = (posCurrent + delta).coerceIn(0f, maxTopStretchPx)

                        if (nextOffset >= refreshThresholdPx) {
                            if (!hasFiredThresholdHaptic) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                hasFiredThresholdHaptic = true
                            }
                            // Start or continue 1-second hold requirement
                            startHoldTimer()
                        } else {
                            if (hasFiredThresholdHaptic) {
                                hasFiredThresholdHaptic = false
                            }
                            cancelHoldTimer()
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
                // Fling reached boundary! Immediately absorb unconsumed fling motion into spring bounce
                if (available.y < 0f) {
                    // Fling hit bottom boundary
                    val negCurrent = current.coerceAtMost(0f)
                    val absCurrent = abs(negCurrent)
                    val factor = 0.40f / (1f + (absCurrent / maxBottomStretchPx) * 2f)
                    val delta = (available.y * factor).coerceIn(-28f, 0f)
                    val nextOffset = (negCurrent + delta).coerceIn(-maxBottomStretchPx * 0.70f, 0f)

                    scope.launch { offset.snapTo(nextOffset) }

                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.animateTo(0f, returnSpringSpec)
                        offset.snapTo(0f)
                    }
                    return Offset(0f, available.y)
                } else if (available.y > 0f && !isRefreshing) {
                    // Fling hit top boundary (absorb fling bounce without triggering refresh)
                    val posCurrent = current.coerceAtLeast(0f)
                    val factor = 0.35f / (1f + (posCurrent / maxTopStretchPx) * 2f)
                    val delta = (available.y * factor).coerceIn(0f, 24f)
                    val nextOffset = (posCurrent + delta).coerceIn(0f, refreshThresholdPx * 0.55f)

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
            val satisfied = isHoldSatisfied
            cancelHoldTimer()
            hasFiredThresholdHaptic = false
            val current = offset.value

            if (current > 0f) {
                // Drag ended at top
                if (current >= refreshThresholdPx && satisfied && !isRefreshing) {
                    // Heavy pull AND 1-second hold fulfilled! Trigger refresh
                    onRefresh()
                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.animateTo(refreshRestingHeightPx, refreshHoldSpringSpec)
                    }
                } else {
                    // Released below threshold or without 1s hold: smoothly bounce back to 0 without refreshing
                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.animateTo(if (isRefreshing) refreshRestingHeightPx else 0f, returnSpringSpec)
                        if (!isRefreshing) offset.snapTo(0f)
                    }
                }
                return Velocity.Zero
            } else if (current < 0f) {
                // Drag ended at bottom: return to resting 0 immediately with fling velocity continuity and elastic bounce
                springJob?.cancel()
                val initVelocity = available.y.coerceIn(-2500f, 2500f)
                springJob = scope.launch {
                    offset.animateTo(0f, returnSpringSpec, initialVelocity = initVelocity)
                    offset.snapTo(0f)
                }
                return available
            }

            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            cancelHoldTimer()
            hasFiredThresholdHaptic = false
            // Absorb any leftover fling velocity hitting bottom boundary into an elastic bounce
            if (available.y < 0f && offset.value == 0f) {
                val flingBounce = (available.y * 0.045f).coerceIn(-maxBottomStretchPx * 0.60f, 0f)
                if (abs(flingBounce) > 1.5f) {
                    springJob?.cancel()
                    springJob = scope.launch {
                        offset.snapTo(flingBounce)
                        offset.animateTo(0f, returnSpringSpec)
                        offset.snapTo(0f)
                    }
                }
            } else if (available.y > 0f && offset.value == 0f && !isRefreshing) {
                // Fling hitting top boundary bounce
                val flingBounce = (available.y * 0.035f).coerceIn(0f, maxTopStretchPx * 0.40f)
                if (flingBounce > 1.5f) {
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

        if (!refreshing) {
            cancelHoldTimer()
        }

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
 * Requires a deliberate heavy pull and a 1-second hold before triggering a refresh.
 */
@Composable
fun HomePullRefreshOverscrollLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshThresholdDp: Dp = 120.dp,
    maxTopStretchDp: Dp = 165.dp,
    maxBottomStretchDp: Dp = 95.dp,
    refreshRestingHeightDp: Dp = 60.dp,
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
    val holdProgress = coordinator.holdProgress
    val isHoldSatisfied = coordinator.isHoldSatisfied
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

        val showIndicator = currentOffset > 10f || isRefreshing

        if (showIndicator) {
            val indicatorY = with(density) {
                // Progressive reveal starting from off-screen
                val restingY = refreshRestingHeightDp.toPx() * 0.45f
                val clampedOffset = currentOffset.coerceAtMost(maxTopStretchPx)
                (-45.dp.toPx() + clampedOffset * 0.70f).coerceAtMost(restingY + 30.dp.toPx())
            }

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
                // Polished Liquid-Glass Refresh Pill with 1s hold indicator
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (isDark) Color(0xDD18181B) else Color(0xEEFFFFFF),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isHoldSatisfied || isRefreshing) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        } else if (pullProgress >= 1f) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
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
                        } else if (isHoldSatisfied) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Release to refresh",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Release to refresh",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        } else if (pullProgress >= 1f) {
                            // User reached heavy threshold, showing 1-second hold timer progress
                            CircularProgressIndicator(
                                progress = { holdProgress },
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Hold 1s to refresh",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
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
                                tint = if (isDark) Color.White else Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Pull down & hold",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDark) Color(0xFFE4E4E7) else Color(0xFF27272A)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
