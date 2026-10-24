package com.example.ui.util

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal const val LANDSCAPE_CONTENT_WIDTH_DP = 1120

/** Portrait returns the original modifier, including on large portrait tablets. */
internal fun Modifier.landscapeContentBounds(landscape: Boolean, maxWidthDp: Int = LANDSCAPE_CONTENT_WIDTH_DP): Modifier =
    if (landscape) wrapContentSize(Alignment.TopCenter).widthIn(max = maxWidthDp.dp).fillMaxSize() else this

internal fun landscapeHeroArtworkWidthDp(widthDp: Float): Float =
    (widthDp.coerceAtMost(LANDSCAPE_CONTENT_WIDTH_DP.toFloat()) - 56f).coerceAtLeast(0f) / 3f

internal fun landscapePlaylistColumns(widthDp: Float): Int =
    (((widthDp.coerceAtMost(LANDSCAPE_CONTENT_WIDTH_DP.toFloat()) - 40f).coerceAtLeast(0f) + 14f) / 134f)
        .toInt().coerceIn(2, 4)

/** Four columns halve the old two-column covers in both dimensions: about one-quarter area. */
internal fun landscapePlaylistTileWidth(widthDp: Float): Float =
    ((widthDp.coerceAtMost(LANDSCAPE_CONTENT_WIDTH_DP.toFloat()) - 40f -
        14f * (landscapePlaylistColumns(widthDp) - 1)) / landscapePlaylistColumns(widthDp)).coerceAtLeast(48f)
