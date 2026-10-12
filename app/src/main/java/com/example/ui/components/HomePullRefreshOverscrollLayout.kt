package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A deliberate distance threshold triggers refresh only on release, never on a fling. */
@Composable
fun HomePullRefreshOverscrollLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshThresholdDp: Dp = 80.dp,
    maxTopStretchDp: Dp = 135.dp,
    maxBottomStretchDp: Dp = 80.dp,
    refreshRestingHeightDp: Dp = 60.dp,
    content: @Composable BoxScope.(contentOffset: () -> Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestRefresh by rememberUpdatedState(onRefresh)
    val threshold = with(density) { refreshThresholdDp.toPx() }
    val maxTop = with(density) { maxTopStretchDp.toPx() }
    val maxBottom = with(density) { maxBottomStretchDp.toPx() }
    val rest = with(density) { refreshRestingHeightDp.toPx() }
    val state = remember(scope, threshold, maxTop, maxBottom, rest) {
        ElasticBoundaryState(scope, maxTop, maxBottom, threshold, rest) { latestRefresh() }
    }
    LaunchedEffect(isRefreshing) { state.updateRefreshing(isRefreshing) }
    val armed by remember(state, threshold) { derivedStateOf { state.dragging && state.offset >= threshold } }
    val showIndicator by remember(state, threshold) { derivedStateOf { state.dragging && state.offset >= threshold * 0.55f } }
    val contentOffset = remember(state) { { state.offset } }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(armed) {
        if (armed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    Box(modifier.fillMaxSize().nestedScroll(state.connection)) {
        content(contentOffset)
        // Tiny pulls and edge flings never reveal refresh UI. Hide it immediately on release.
        if (isRefreshing || showIndicator) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (isRefreshing) "Refreshing music…" else if (armed) "Release to refresh" else "Pull to refresh",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
