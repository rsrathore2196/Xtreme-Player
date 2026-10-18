package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** The navigation host only knows pages and position; it never observes feed readiness. */
@Composable
internal fun MainTabPager(
    state: PagerState,
    pageCovered: Boolean,
    content: @Composable (Int) -> Unit
) {
    HorizontalPager(
        state = state,
        beyondViewportPageCount = 1,
        userScrollEnabled = false,
        key = { it },
        modifier = Modifier.fillMaxSize()
    ) { index ->
        val visible by remember(state, index, pageCovered) {
            derivedStateOf { !pageCovered && state.layoutInfo.visiblePagesInfo.any { it.index == index } }
        }
        CompositionLocalProvider(LocalVisualEffectsActive provides visible) {
            Box(Modifier.fillMaxSize().graphicsLayer { clip = false }) { content(index) }
        }
    }
}
