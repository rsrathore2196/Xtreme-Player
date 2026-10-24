package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.ui.util.LANDSCAPE_CONTENT_WIDTH_DP

/** Keep the original portrait stack; landscape shares one compact row when it fits. */
@Composable
internal fun AdaptiveBottomControls(landscape: Boolean, modifier: Modifier = Modifier,
    miniPlayer: @Composable ColumnScope.() -> Unit, dock: @Composable ColumnScope.() -> Unit) {
    if (!landscape) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { miniPlayer(); dock() }
        return
    }
    Layout(modifier = modifier.testTag("landscape_bottom_controls"), content = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = miniPlayer)
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = dock)
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val contentWidth = minOf(width, LANDSCAPE_CONTENT_WIDTH_DP.dp.roundToPx())
        val child = Constraints(maxWidth = contentWidth, maxHeight = constraints.maxHeight)
        val bar = measurables[1].measure(child)
        // The mini-player retains its existing 20 dp side padding and full-height controls.
        val miniWidth = ((contentWidth - 40.dp.roundToPx()).coerceAtLeast(0) / 3)
            // Preserve readable metadata and full-size touch controls in narrow windows.
            .coerceAtLeast(220.dp.roundToPx()).plus(40.dp.roundToPx())
            .coerceAtMost(contentWidth)
        val mini = measurables[0].measure(child.copy(maxWidth = miniWidth))
        val gap = 12.dp.roundToPx()
        val horizontal = mini.height > 0 && bar.height > 0 && mini.width + gap + bar.width <= contentWidth
        val height = if (horizontal) maxOf(mini.height, bar.height) else mini.height + bar.height
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            if (horizontal) {
                val start = (width - mini.width - gap - bar.width) / 2
                mini.placeRelative(start, (height - mini.height) / 2)
                bar.placeRelative(start + mini.width + gap, (height - bar.height) / 2)
            } else {
                mini.placeRelative((width - mini.width) / 2, 0)
                bar.placeRelative((width - bar.width) / 2, mini.height)
            }
        }
    }
}
