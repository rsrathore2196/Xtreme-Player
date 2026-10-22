package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Two independent motions: selection controls pill width, while a
 * touch springs the entire glass dock to 1.12x and back (0.5 damping / 300 stiffness).
 * Read progress in layer/draw blocks, never in the Home or navigation composition.
 */
@Stable
class DockGlassInteraction internal constructor(private val scope: CoroutineScope) {
    private val progress = Animatable(0f, 0.001f)
    private val pressSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)
    private var animation: Job? = null
    private var generation = 0
    private var motionLease: AutoCloseable? = null

    val pressProgress: Float get() = progress.value
    val scale: Float get() = 1f + 0.12f * pressProgress
    var touchPosition: Offset by mutableStateOf(Offset.Unspecified)
        private set

    private fun pressed(value: Boolean) {
        val request = ++generation
        val velocity = progress.velocity
        animation?.cancel()
        if (value && motionLease == null) motionLease = BackgroundWorkCoordinator.navigationBusy()
        animation = scope.launch {
            try {
                // Retarget the same Animatable, retaining velocity on rapid touches.
                progress.animateTo(if (value) 1f else 0f, pressSpring, initialVelocity = velocity)
            } finally {
                if (generation == request && !value) releaseLease()
            }
        }
    }

    private fun releaseLease() {
        motionLease?.close()
        motionLease = null
    }

    internal fun reset() {
        ++generation
        animation?.cancel()
        animation = null
        releaseLease()
        // snapTo does not need a frame, so lifecycle cancellation cannot leave a
        // background-only navigation lease or a pressed dock on the next resume.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(0f) }
    }

    internal suspend fun observeTouches(pointer: PointerInputScope) = with(pointer) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                BackgroundWorkCoordinator.cancelNonEssentialWork()
                var pointerId = down.id
                touchPosition = down.position
                pressed(true)
                try {
                    while (true) {
                        // Observe before child clickables. Never consume down,
                        // move or up: their normal cancellation/click rules own navigation.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val current = event.changes.firstOrNull { it.id == pointerId }
                        val held = current?.takeIf { it.pressed }
                            ?: event.changes.firstOrNull { it.pressed }
                            ?: break
                        pointerId = held.id
                        touchPosition = held.position
                    }
                } finally {
                    pressed(false)
                }
            }
        } finally {
            reset()
        }
    }
}

@Composable
internal fun rememberDockGlassInteraction(): DockGlassInteraction {
    val scope = rememberCoroutineScope()
    val interaction = remember(scope) { DockGlassInteraction(scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, interaction) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) interaction.reset()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            interaction.reset()
        }
    }
    return interaction
}

internal fun Modifier.observeDockGlassTouches(interaction: DockGlassInteraction?): Modifier =
    pointerInput(interaction) {
        if (interaction != null) interaction.observeTouches(this)
        else awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            BackgroundWorkCoordinator.cancelNonEssentialWork()
            // Observe only; child clickables retain press, cancellation and navigation.
            do { val event = awaitPointerEvent(PointerEventPass.Initial) }
            while (event.changes.any { it.pressed })
        }
    }
