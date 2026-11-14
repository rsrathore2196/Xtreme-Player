package com.example.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** The portrait host publishes a stable measurement bound; only the visible window animates. */
internal val LocalStableLyricsHeight = staticCompositionLocalOf<State<Int>?> { null }

internal fun Modifier.lyricsViewportWindow(padding: LyricsViewportPadding, stableHeight: State<Int>?): Modifier =
    layout { measurable, constraints ->
        val visibleHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val contentHeight = maxOf(visibleHeight, stableHeight?.value ?: visibleHeight)
        padding.heightPx = contentHeight
        val child = measurable.measure(constraints.copy(minHeight = contentHeight, maxHeight = contentHeight))
        layout(child.width, visibleHeight) { child.placeRelative(0, (visibleHeight - contentHeight) / 2) }
    }

/** Updated and read during measurement; animated heights never invalidate composition. */
internal class LyricsViewportPadding(private val density: Density) : PaddingValues {
    var heightPx: Int = 0
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateTopPadding(): Dp = with(density) { heightPx.toDp() / 2 }
    override fun calculateBottomPadding(): Dp = calculateTopPadding()
}
