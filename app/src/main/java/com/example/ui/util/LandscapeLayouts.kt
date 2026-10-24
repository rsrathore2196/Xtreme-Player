package com.example.ui.util

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal const val LANDSCAPE_CONTENT_WIDTH_DP = 1120

/** Portrait is unchanged. A null maximum lets a landscape tab fill its actual window. */
internal fun Modifier.landscapeContentBounds(landscape: Boolean, maxWidthDp: Int? = LANDSCAPE_CONTENT_WIDTH_DP): Modifier =
    if (landscape) wrapContentSize(Alignment.TopCenter)
        .then(if (maxWidthDp == null) Modifier else Modifier.widthIn(max = maxWidthDp.dp))
        .fillMaxSize() else this

internal fun landscapeHeroArtworkWidthDp(widthDp: Float): Float =
    (widthDp - 56f).coerceAtLeast(0f) / 3f

internal fun landscapePlaylistColumns(widthDp: Float): Int {
    val contentWidth = (widthDp - 40f).coerceAtLeast(0f)
    val fittingColumns = ((contentWidth + 14f) / 134f).toInt().coerceAtLeast(2)
    // Keep the compact phone grid; add columns instead of enlarging tablet/desktop covers.
    val preferredColumns = kotlin.math.ceil((contentWidth + 14f) / 274f).toInt().coerceAtLeast(4)
    return preferredColumns.coerceAtMost(fittingColumns)
}

/** Covers remain between about 120 and 260 dp as the landscape window resizes. */
internal fun landscapePlaylistTileWidth(widthDp: Float): Float =
    ((widthDp - 40f -
        14f * (landscapePlaylistColumns(widthDp) - 1)) / landscapePlaylistColumns(widthDp)).coerceAtLeast(48f)
