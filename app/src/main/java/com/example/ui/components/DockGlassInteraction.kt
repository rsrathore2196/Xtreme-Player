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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Touch pressure drives the shared glass/foreground spring. Selection still
 * owns tab widths, while the uniform touch response keeps the capsule in sync.
 * Read progress in layer/draw blocks, never in the Home or navigation composition.
 */
@Stable
class DockGlassInteraction internal constructor(private val scope: CoroutineScope) {
    private val progress = Animatable(0f, 0.001f)
    private val pressSpring = spring<Float>(dampingRatio = 0.48f, stiffness = 400f, visibilityThreshold = 0.001f)
    private val releaseSpring = spring<Float>(dampingRatio = 0.42f, stiffness = 320f, visibilityThreshold = 0.001f)
    private var animation: Job? = null
    private var generation = 0
    private var motionLease: AutoCloseable? = null

    val pressProgress: Float get() = progress.value
    // One uniform spatial response, including the spring's release rebound bounce.
    // Read only from the outer hardware layer, never from measurement/composition.
    val scale: Float get() = 1f + 0.04f * progress.value
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
                val spec = if (value) pressSpring else releaseSpring
                progress.animateTo(if (value) 1f else 0f, spec, initialVelocity = velocity)
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

    internal fun touch(position: Offset, started: Boolean) {
        touchPosition = position
        if (started) pressed(true)
    }

    internal fun releaseTouch() = pressed(false)
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

internal fun Modifier.observeDockGlassTouches(interaction: DockGlassInteraction?,
    onPosition: (Offset, IntSize, Boolean) -> Unit,
    onFinish: () -> Unit): Modifier =
    pointerInput(interaction) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                BackgroundWorkCoordinator.cancelNonEssentialWork()
                var pointerId = down.id
                interaction?.touch(down.position, true)
                try {
                    onPosition(down.position, size, true)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val held = event.changes.firstOrNull { it.id == pointerId && it.pressed }
                            ?: event.changes.firstOrNull { it.pressed }
                        if (held == null) {
                            // Keep the selection guard through child clickables'
                            // up handling, then release it for keyboard/accessibility.
                            awaitPointerEvent(PointerEventPass.Final)
                            break
                        }
                        val transferred = pointerId != held.id
                        pointerId = held.id
                        interaction?.touch(held.position, false)
                        if (transferred || held.position != held.previousPosition)
                            onPosition(held.position, size, false)
                    }
                } finally {
                    interaction?.releaseTouch()
                    onFinish()
                }
            }
        } finally {
            interaction?.reset()
            onFinish()
        }
    }
