package com.example.ui.components

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

/** Visibility of retained UI beneath an opaque destination. Playback never reads this value. */
val LocalVisualEffectsActive = compositionLocalOf { true }

/** Same animation spec and elapsed phase, but no frames while hidden or backgrounded. */
@Composable
fun rememberVisibleInfiniteFloat(
    initialValue: Float,
    targetValue: Float,
    animationSpec: InfiniteRepeatableSpec<Float>,
    label: String,
    active: Boolean = true
): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible = LocalVisualEffectsActive.current && active
    val animation = remember(initialValue, targetValue, animationSpec) {
        TargetBasedAnimation(animationSpec, Float.VectorConverter, initialValue, targetValue)
    }
    val value = remember(animation) { mutableFloatStateOf(initialValue) }
    val elapsed = remember(animation) { longArrayOf(0L) }
    LaunchedEffect(animation, lifecycle, visible) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previousFrame: Long? = null
            val durationScale = coroutineContext[MotionDurationScale]
            while (isActive) {
                if (durationScale?.scaleFactor == 0f) {
                    value.floatValue = targetValue
                    snapshotFlow { durationScale.scaleFactor }.first { it > 0f }
                    previousFrame = null
                    continue
                }
                withFrameNanos { frame ->
                    val scale = (durationScale?.scaleFactor ?: 1f).coerceAtLeast(0.0001f)
                    previousFrame?.let { elapsed[0] += ((frame - it).coerceAtLeast(0L) / scale).toLong() }
                    previousFrame = frame
                    value.floatValue = animation.getValueFromNanos(elapsed[0])
                }
            }
        }
    }
    return value
}

/** Retained pager pages keep their layout but must not schedule invisible spinner frames. */
@Composable
internal fun VisibleCircularProgressIndicator(
    modifier: androidx.compose.ui.Modifier,
    color: androidx.compose.ui.graphics.Color,
    strokeWidth: androidx.compose.ui.unit.Dp = androidx.compose.material3.ProgressIndicatorDefaults.CircularStrokeWidth
) {
    if (LocalVisualEffectsActive.current) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = modifier, color = color, strokeWidth = strokeWidth)
    } else {
        androidx.compose.foundation.layout.Spacer(modifier)
    }
}
