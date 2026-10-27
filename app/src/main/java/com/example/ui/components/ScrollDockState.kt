package com.example.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.performance.BackgroundWorkCoordinator
import kotlin.math.abs

/** Observes real vertical scrolling without consuming any of the page's motion. */
@Stable
internal class ScrollDockState(private val thresholdPx: Float) : NestedScrollConnection {
    var isCompact by mutableStateOf(false)
        private set
    var enabled = true
        set(value) {
            field = value
            if (!value) expand()
        }
    private var accumulated = 0f
    private var userFling = false

    fun expand() {
        isCompact = false
        accumulated = 0f
        userFling = false
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || (source != NestedScrollSource.UserInput && !userFling)) return Offset.Zero
        // Ignore horizontal shelves/paging, unconsumed overscroll and programmatic restores.
        if (abs(consumed.y) <= abs(consumed.x)) return Offset.Zero
        val delta = consumed.y
        if (delta * accumulated < 0f) accumulated = 0f
        accumulated = (accumulated + delta).coerceIn(-thresholdPx, thresholdPx)
        if (accumulated <= -thresholdPx && !isCompact) {
            isCompact = true
            accumulated = 0f
        } else if (accumulated >= thresholdPx && isCompact) {
            expand()
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        userFling = enabled && abs(available.y) > abs(available.x)
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        userFling = false
        return Velocity.Zero
    }
}

@Composable
internal fun rememberScrollDockState(): ScrollDockState {
    val threshold = with(LocalDensity.current) { 50.dp.toPx() }
    return remember(threshold) { ScrollDockState(threshold) }
}

@Composable
internal fun rememberScrollDockProgress(state: ScrollDockState): State<Float> {
    val transition = updateTransition(state.isCompact, label = "scroll_dock_morph")
    val progress = transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 1f, stiffness = 120f) }, label = "dock_bounds"
    ) { compact -> if (compact) 1f else 0f }
    DisposableEffect(transition.isRunning) {
        val work = if (transition.isRunning) BackgroundWorkCoordinator.navigationBusy() else null
        onDispose { work?.close() }
    }
    return progress
}
