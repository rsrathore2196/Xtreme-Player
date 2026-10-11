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

/** One synchronous drag position and one spring own each boundary gesture. */
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

    private fun settle(velocity: Float = 0f) {
        animation?.cancel()
        val target = if (refreshing) refreshRest else 0f
        animation = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                initialVelocity = velocity.coerceIn(-maxBottom * 18f, maxTop * 18f),
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)
            ) { value, _ -> offset = value.coerceIn(-maxBottom, maxTop) }
            offset = target
        }
    }

    fun updateRefreshing(value: Boolean) {
        if (refreshing == value) return
        refreshing = value
        if (!dragging) settle()
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            animation?.cancel()
            dragging = true
            edgeDirection = 0f
            if (offset * available.y < 0f) {
                val consumed = if (offset > 0f) available.y.coerceAtLeast(-offset)
                    else available.y.coerceAtMost(-offset)
                offset += consumed
                return Offset(0f, consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y == 0f) return Offset.Zero
            if (source != NestedScrollSource.UserInput) {
                // Leave fling distance unconsumed so the child stops at the boundary.
                // onPostFling receives its residual velocity and starts exactly one bounce.
                edgeDirection = if (available.y > 0f) 1f else -1f
                return Offset.Zero
            }
            if (refreshing) return Offset.Zero
            dragging = true
            animation?.cancel()
            val max = if (available.y > 0f) maxTop else maxBottom
            val resistance = 0.5f / (1f + abs(offset) / max * 2f)
            offset = (offset + available.y * resistance).coerceIn(-maxBottom, maxTop)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val shouldRefresh = dragging && offset >= refreshThreshold && !refreshing
            dragging = false
            if (shouldRefresh) {
                // Parent owns refresh state; the pull cannot fire twice on this release.
                onRefresh()
            }
            if (offset != 0f) {
                settle(if (shouldRefresh || offset > 0f) 0f else available.y * 0.25f)
                return Velocity(0f, available.y)
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            dragging = false
            if (animation?.isActive != true) {
                if (!refreshing && edgeDirection != 0f && abs(available.y) > 1f) {
                    settle(available.y * 0.25f)
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
