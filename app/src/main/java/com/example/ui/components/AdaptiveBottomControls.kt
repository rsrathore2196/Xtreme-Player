package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.ui.util.LANDSCAPE_CONTENT_WIDTH_DP
import kotlin.math.roundToInt

/** One set of controls moves between the original layout and a compact browsing row. */
@Composable
internal fun AdaptiveBottomControls(landscape: Boolean, modifier: Modifier = Modifier,
    collapseProgress: State<Float>? = null,
    compactSearch: @Composable ColumnScope.() -> Unit = {},
    miniPlayer: @Composable ColumnScope.() -> Unit, dock: @Composable ColumnScope.() -> Unit) {
    if (!landscape && collapseProgress == null) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { miniPlayer(); dock() }
        return
    }
    Layout(modifier = modifier.then(if (landscape) Modifier.testTag("landscape_bottom_controls") else Modifier), content = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = miniPlayer)
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = dock)
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = compactSearch)
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val progress = (collapseProgress?.value ?: 0f).coerceIn(0f, 1f)
        val contentWidth = if (landscape) minOf(width, LANDSCAPE_CONTENT_WIDTH_DP.dp.roundToPx()) else width
        val child = Constraints(maxWidth = contentWidth, maxHeight = constraints.maxHeight)
        val bar = measurables[1].measure(child)
        val gutter = 16.dp.roundToPx()
        val circle = 64.dp.roundToPx().coerceAtMost(contentWidth / 3)
        val compactGap = 8.dp.roundToPx()
        val playerPadding = 40.dp.roundToPx()
        val fullMiniWidth = if (!landscape) contentWidth else ((contentWidth - playerPadding).coerceAtLeast(0) / 3)
            // Preserve readable metadata and full-size touch controls in narrow windows.
            .coerceAtLeast(220.dp.roundToPx()).plus(playerPadding)
            .coerceAtMost(contentWidth)
        val compactMiniWidth = minOf((fullMiniWidth - playerPadding).coerceAtLeast(0),
            (contentWidth - gutter * 2 - circle * 2 - compactGap * 2).coerceAtLeast(0))
        val miniWidth = interpolate(fullMiniWidth, compactMiniWidth + playerPadding, progress)
        val mini = measurables[0].measure(child.copy(maxWidth = miniWidth))
        val search = measurables[2].measure(child.copy(maxWidth = circle))
        val gap = 12.dp.roundToPx()
        val horizontal = landscape && mini.height > 0 && bar.height > 0 && fullMiniWidth + gap + bar.width <= contentWidth
        val fullHeight = if (horizontal) maxOf(mini.height, bar.height) else mini.height + bar.height
        val compactHeight = maxOf(mini.height, bar.height, search.height)
        val height = interpolate(fullHeight, compactHeight, progress)
        val miniPresent = mini.height > 0
        val rowWidth = circle + compactGap + (if (miniPresent) compactMiniWidth + compactGap else 0) + circle
        val rowStart = (width - rowWidth) / 2
        val fullStart = (width - mini.width - gap - bar.width) / 2
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            mini.placeRelative(
                interpolate(if (horizontal) fullStart else (width - mini.width) / 2,
                    rowStart + circle + compactGap - playerPadding / 2, progress),
                interpolate(if (horizontal) (fullHeight - mini.height) / 2 else 0,
                    (compactHeight - mini.height) / 2, progress))
            bar.placeRelative(
                interpolate(if (horizontal) fullStart + mini.width + gap else (width - bar.width) / 2,
                    rowStart + (circle - bar.width) / 2, progress),
                interpolate(if (horizontal) (fullHeight - bar.height) / 2 else mini.height,
                    (compactHeight - bar.height) / 2, progress))
            if (progress > 0f && bar.height > 0) {
                search.placeRelativeWithLayer(rowStart + rowWidth - circle,
                    (height - search.height) / 2) { alpha = progress }
            }
        }
    }
}

private fun interpolate(start: Int, end: Int, progress: Float): Int =
    (start + (end - start) * progress).roundToInt()
