package com.example.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** Rubber-band translation: artwork and layout stay rigid; only the list's graphics layer moves. */
internal class ElasticBoundaryState(
    private val scope: CoroutineScope,
    private val maxTop: Float,
    private val maxBottom: Float,
    private val refreshThreshold: Float = Float.POSITIVE_INFINITY,
    private val refreshRest: Float = 0f,
    private val onRefresh: () -> Unit = {}
) {
    var offset by mutableFloatStateOf(0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var edgeDirection = 0f
    private var dragDistance = 0f
    private var animationVelocity = 0f

    // An asymptote slows longer pulls continuously, instead of hitting a hard position clamp.
    private fun rubberBand(distance: Float): Float {
        val limit = (if (distance >= 0f) maxTop else maxBottom).coerceAtLeast(1f)
        val resisted = abs(distance) * 0.55f
        return sign(distance) * limit * resisted / (limit + resisted)
    }

    private fun catchAnimation() {
        if (animation?.isActive == true) {
            animation?.cancel()
            animationVelocity = 0f
            // Reconstruct drag distance so touching a returning list cannot make it jump.
            val limit = (if (offset >= 0f) maxTop else maxBottom).coerceAtLeast(1f)
            val distance = abs(offset).coerceAtMost(limit * 0.99f)
            dragDistance = sign(offset) * distance * limit / (0.55f * (limit - distance))
        }
    }

    private fun settle(velocity: Float = 0f, target: Float = if (refreshing) refreshRest else 0f) {
        animation?.cancel()
        if (abs(offset - target) < 0.25f && abs(velocity) < 1f) {
            offset = target
            dragDistance = 0f
            animationVelocity = 0f
            return
        }
        // A critically damped spring has one rounded excursion and no oscillating/jittery tail.
        // Bound energy up front, not position on each frame (which would flatten the bounce peak).
        val limit = if (velocity >= 0f) maxTop else maxBottom
        val velocityLimit = (limit - abs(offset - target)).coerceAtLeast(0f) * sqrt(260f) * 2f
        animation = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                initialVelocity = velocity.coerceIn(-velocityLimit, velocityLimit),
                animationSpec = spring(dampingRatio = 1f, stiffness = 260f, visibilityThreshold = 0.25f)
            ) { value, speed ->
                offset = value
                animationVelocity = speed
            }
            offset = target
            dragDistance = 0f
            animationVelocity = 0f
            // A refresh already in flight may decline a new request. Never leave its header held open.
            if (target != 0f && !refreshing && !dragging) settle()
        }
    }

    fun updateRefreshing(value: Boolean) {
        if (refreshing == value) return
        refreshing = value
        if (!dragging) settle(animationVelocity)
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            catchAnimation()
            dragging = true
            edgeDirection = 0f
            if (offset * available.y < 0f && !refreshing) {
                val consumed = if (dragDistance > 0f) available.y.coerceAtLeast(-dragDistance)
                    else available.y.coerceAtMost(-dragDistance)
                dragDistance += consumed
                offset = rubberBand(dragDistance)
                return Offset(0f, consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y == 0f || refreshing) return Offset.Zero
            if (source != NestedScrollSource.UserInput) {
                // Let the child finish its fling and deliver the actual residual edge velocity.
                // Starting springs here would restart them on every animation frame.
                edgeDirection = sign(available.y)
                return Offset.Zero
            }
            catchAnimation()
            dragging = true
            dragDistance += available.y
            offset = rubberBand(dragDistance)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val shouldRefresh = dragging && offset >= refreshThreshold && !refreshing
            dragging = false
            if (shouldRefresh) {
                // Start towards the refresh position immediately, without first springing to zero.
                settle(target = refreshRest)
                onRefresh()
                return Velocity(0f, available.y)
            }
            if (offset != 0f) {
                settle()
                return Velocity(0f, available.y)
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            dragging = false
            if (animation?.isActive != true) {
                if (!refreshing && edgeDirection != 0f && available.y * edgeDirection > 1f) {
                    // Keep the incoming momentum: a fast fling produces a deeper, rounded bounce.
                    settle(available.y * 0.45f)
                } else if (offset != 0f) settle()
            }
            edgeDirection = 0f
            return Velocity(0f, available.y)
        }
    }
}

@Composable
fun Modifier.bouncyOverscroll(enabled: Boolean = true, maxStretchDp: Dp = 90.dp): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val max = with(LocalDensity.current) { maxStretchDp.toPx() }
    val state = remember(scope, max) { ElasticBoundaryState(scope, max, max) }
    return this.nestedScroll(state.connection).graphicsLayer { translationY = state.offset }
}

@Composable
fun BouncyScrollView(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxStretchDp: Dp = 90.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.bouncyOverscroll(enabled, maxStretchDp), content = content)
}
