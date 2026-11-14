package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Endpoint measurements stay fixed while placement and clipping follow the motion clock. */
internal class LyricsBodyGeometry(val visibility: State<Float>, val expansion: State<Float>) {
    val fullHeight = mutableIntStateOf(0)
    val artworkHeight = mutableIntStateOf(0)
    var timelineHeight = 0
    var controlsHeight = 0
    var optionsHeight = 0
    var gap = 0
    var focusedGap = 0

    fun bodyHeight(): Int {
        val focus = expansion.value.coerceIn(0f, 1f)
        val options = gap + (optionsHeight - gap) * visibility.value.coerceIn(0f, 1f)
        return (fullHeight.intValue - options - (timelineHeight + controlsHeight) * (1f - focus) - focusedGap * focus)
            .roundToInt().coerceIn(0, fullHeight.intValue)
    }

    fun hiddenHeight(): Int = fullHeight.intValue - bodyHeight()
}

internal val LocalLyricsBodyGeometry = staticCompositionLocalOf<LyricsBodyGeometry?> { null }

/** The outgoing sleeve keeps its original size, including on the first lyrics frame. */
@Composable
internal fun Modifier.lyricsBodyEndpoint(lyrics: Boolean): Modifier {
    val geometry = LocalLyricsBodyGeometry.current ?: return this
    return layout { measurable, constraints ->
        val height = if (lyrics) constraints.maxHeight else geometry.artworkHeight.intValue.coerceIn(0, constraints.maxHeight)
        val child = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
        layout(constraints.maxWidth, constraints.maxHeight) { child.placeRelative(0, 0) }
    }
}

/** Header, body, lyric tools, timeline, transport. No animated value is read during measurement. */
@Composable
internal fun PortraitPlayerExpansion(
    expansion: State<Float>,
    lyricsVisibility: State<Float>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val geometry = remember(expansion, lyricsVisibility) { LyricsBodyGeometry(lyricsVisibility, expansion) }
    CompositionLocalProvider(LocalLyricsBodyGeometry provides geometry) {
        Layout(content = content, modifier = modifier) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val childConstraints = Constraints(minWidth = width, maxWidth = width, maxHeight = height)
            val header = measurables[0].measure(childConstraints)
            val fullHeight = (height - header.height).coerceAtLeast(0)
            val tools = measurables[2].measure(childConstraints.copy(maxHeight = fullHeight))
            val timeline = measurables[3].measure(childConstraints.copy(maxHeight = (fullHeight - tools.height).coerceAtLeast(0)))
            val controls = measurables[4].measure(childConstraints.copy(maxHeight = (fullHeight - tools.height - timeline.height).coerceAtLeast(0)))
            geometry.fullHeight.intValue = fullHeight
            geometry.timelineHeight = timeline.height
            geometry.controlsHeight = controls.height
            geometry.optionsHeight = tools.height
            geometry.gap = 14.dp.roundToPx()
            geometry.focusedGap = 12.dp.roundToPx()
            geometry.artworkHeight.intValue = (fullHeight - timeline.height - controls.height - geometry.gap).coerceAtLeast(0)
            val body = measurables[1].measure(Constraints.fixed(width, fullHeight))
            val lookahead = isLookingAhead
            layout(width, height) {
                val focus = expansion.value.coerceIn(0f, 1f)
                val shown = lyricsVisibility.value.coerceIn(0f, 1f)
                val bodyBottom = header.height + geometry.bodyHeight()
                val toolsHeight = (geometry.gap + (tools.height - geometry.gap) * shown).roundToInt()
                header.placeRelative(0, 0)
                body.placeRelative(0, header.height)
                if (lookahead || shown > 0f) tools.placeRelative(0, bodyBottom)
                if (lookahead || focus < 1f) {
                    timeline.placeRelative(0, bodyBottom + toolsHeight)
                    controls.placeRelative(0, bodyBottom + toolsHeight + timeline.height)
                }
            }
        }
    }
}
