package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Both surfaces read the very same motion on their graphics layers. Binding a
 * newly mounted overlay changes the reference once, not Compose state per frame. */
internal class PlayerExpansionMotion {
    private var animation by mutableStateOf<State<Float>>(mutableStateOf(1f))
    val fraction: Float get() = animation.value
    fun bind(value: State<Float>) { animation = value }
}

private fun collapsedViewport(value: Float): Float {
    // Easing belongs to the shared clock, not each surface's viewport.
    return value.coerceIn(0f, 1f)
}

private fun miniReveal(viewport: Float): Float {
    // Complete the handoff at the actual mini-player bounds. Finishing the
    // fade before the geometry lands leaves a visible gap at the destination.
    val unit = ((viewport - .88f) / .12f).coerceIn(0f, 1f)
    return unit * unit * (3f - 2f * unit)
}

internal fun Modifier.playerMiniTransition(motion: PlayerExpansionMotion): Modifier = graphicsLayer {
    alpha = miniReveal(collapsedViewport(motion.fraction))
}

/** Retain dock geometry through the handoff and fade on the same clock. */
internal fun Modifier.playerDockTransition(motion: PlayerExpansionMotion): Modifier = graphicsLayer {
    alpha = miniReveal(collapsedViewport(motion.fraction))
}

/** Animate the existing player layer into the real mini-player viewport. Its
 * content scales uniformly; only its rounded viewport contracts vertically.
 * No screen snapshots, bitmap work or per-frame layout/recomposition are needed. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun PlayerExpansionOverlay(
    visibleState: MutableTransitionState<Boolean>,
    motion: PlayerExpansionMotion,
    miniPlayerBounds: () -> Rect?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Dock layout may start expanding after its compact player is tapped. Keep
    // the opening origin fixed at the actual tapped card, not its fading layout.
    val openingBounds = remember(visibleState.targetState) { miniPlayerBounds() }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        // One visibility transition owns both directions, including reversals.
        // It cannot dispose the surface before the minimize motion finishes.
        val collapse = transition.animateFloat(
            transitionSpec = {
                tween(durationMillis = if (targetState == EnterExitState.Visible) 440 else 560,
                    easing = FastOutSlowInEasing)
            },
            label = "player_expansion_bounds"
        ) { if (it == EnterExitState.Visible) 0f else 1f }
        SideEffect { motion.bind(collapse) }
        val keepExpandedContent by remember(visibleState, collapse) {
            derivedStateOf {
                visibleState.targetState || miniReveal(collapsedViewport(collapse.value)) < 1f
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            // Both directions zoom along the same bounded path, with no
            // overshoot, elastic landing or second mini-player transform.
            val progress = collapsedViewport(collapse.value)
            if (size.width > 0f && size.height > 0f) {
                val measured = (if (visibleState.targetState) openingBounds else miniPlayerBounds())?.translate(-origin)?.takeIf {
                    it.width > 0f && it.height > 0f && it.left >= 0f && it.top >= 0f &&
                        it.right <= size.width + 1f && it.bottom <= size.height + 1f
                }
                // A restored expanded player can close before the mini player's
                // first placement. The measured destination takes over on layout.
                val target = measured ?: Rect(size.width * .08f, size.height - minOf(152.dp.toPx(), size.height * .4f),
                    size.width * .92f, size.height - minOf(80.dp.toPx(), size.height * .2f))
                val width = size.width + (target.width - size.width) * progress
                val height = size.height + (target.height - size.height) * progress
                val scale = width / size.width
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = target.left * progress
                translationY = target.top * progress
                clip = progress > 0f
                shape = PlayerMinimizeClip(height / scale, target.height * .5f * progress / scale)
                alpha = 1f - miniReveal(progress)
            }
        }.testTag("expanded_player_transition")) {
            // Once it is fully transparent, release the expanded screen's touch
            // handlers while the shared zoom completes its handoff.
            if (keepExpandedContent) content()
        }
    }
}

private class PlayerMinimizeClip(private val height: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, size.width, height.coerceAtMost(size.height), CornerRadius(radius)))
}
