package com.example.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Updated and read during measurement; animated heights never invalidate composition. */
internal class LyricsViewportPadding(private val density: Density) : PaddingValues {
    var heightPx: Int = 0
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateTopPadding(): Dp = with(density) { heightPx.toDp() / 2 }
    override fun calculateBottomPadding(): Dp = calculateTopPadding()
}
