package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The navigation host only knows pages and position; it never observes feed readiness. */
@Composable
internal fun MainTabPager(
    state: PagerState,
    pageCovered: Boolean,
    retainInactivePages: Boolean = true,
    content: @Composable (Int) -> Unit
) {
    val parentEffectsActive = LocalVisualEffectsActive.current
    HorizontalPager(
        state = state,
        // Keep all three primary destinations mounted, including Home's carousels
        // and painters. Hidden effects remain gated by the visibility local below.
        beyondViewportPageCount = if (retainInactivePages) 2 else 0,
        userScrollEnabled = false,
        key = { it },
        modifier = Modifier.fillMaxSize()
    ) { index ->
        val visible by remember(state, index, pageCovered) {
            derivedStateOf { !pageCovered && state.layoutInfo.visiblePagesInfo.any { it.index == index } }
        }
        val parent = LocalLifecycleOwner.current.lifecycle
        val page = remember(parent) { RetainedPageLifecycle(parent) }
        DisposableEffect(page) {
            val observer = LifecycleEventObserver { _, _ -> page.update() }
            parent.addObserver(observer)
            onDispose { parent.removeObserver(observer); page.destroy() }
        }
        SideEffect { page.visible = visible; page.update() }
        CompositionLocalProvider(LocalVisualEffectsActive provides (visible && parentEffectsActive), LocalLifecycleOwner provides page) {
            Box(Modifier.fillMaxSize().graphicsLayer { clip = false }) { content(index) }
        }
    }
}

/** Retain composition/scroll state, without starting hidden Room collectors or screen effects. */
private class RetainedPageLifecycle(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    var visible = false
    override val lifecycle: Lifecycle get() = registry
    fun update() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (parent.currentState == Lifecycle.State.DESTROYED) { destroy(); return }
        registry.currentState = if (visible) parent.currentState else
            minOf(parent.currentState, Lifecycle.State.CREATED)
    }
    fun destroy() {
        // INITIALIZED cannot transition directly to DESTROYED.
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
