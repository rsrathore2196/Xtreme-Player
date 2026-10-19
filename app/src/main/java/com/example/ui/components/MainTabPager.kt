package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.StartupStatus
import com.example.performance.StartupTask
import com.example.ui.util.LocalArtworkRequestsActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Prepare each retained destination once; feed updates never drive pager/dock state. */
@Composable
internal fun MainTabPager(
    state: PagerState,
    pageCovered: Boolean,
    prepareStartup: Boolean = false,
    content: @Composable (Int) -> Unit
) {
    val parentEffectsActive = LocalVisualEffectsActive.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mounted by remember { mutableStateOf(if (prepareStartup) setOf(state.currentPage) else setOf(0, 1, 2)) }
    val measured = remember { List(3) { CompletableDeferred<Unit>() } }
    // Visible navigation always wins over optional hidden-tab preparation.
    LaunchedEffect(state.currentPage, state.targetPage) {
        mounted = mounted + state.currentPage + state.targetPage
    }
    LaunchedEffect(view, lifecycle, prepareStartup) {
        if (!prepareStartup) {
            mounted = setOf(0, 1, 2)
            return@LaunchedEffect
        }
        BackgroundWorkCoordinator.beginTabPreparation()
        if (BackgroundWorkCoordinator.startupReadiness.value.complete) {
            mounted = setOf(0, 1, 2)
            return@LaunchedEffect
        }
        val generation = BackgroundWorkCoordinator.startupReadiness.value.presentationGeneration
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val coordinator = BackgroundWorkCoordinator
            for (index in (listOf(state.currentPage) + (0..2)).distinct()) {
                val task = when (index) {
                    0 -> StartupTask.HOME_LAYOUT
                    1 -> StartupTask.SEARCH_LAYOUT
                    else -> StartupTask.LIBRARY_LAYOUT
                }
                if (coordinator.startupReadiness.value.tasks.getValue(task).terminal) continue
                // Do not hold an admission lease while waiting for another owner.
                coordinator.startupReadiness.first { readiness ->
                    if (readiness.presentationGeneration != generation) true
                    else if (index == 0) listOf(StartupTask.HOME_CACHE, StartupTask.HERO,
                        StartupTask.QUICK, StartupTask.ERA, StartupTask.CHART, StartupTask.PERSONALIZED,
                        StartupTask.SIMILAR, StartupTask.INSPIRED).all { readiness.tasks.getValue(it).terminal }
                    else readiness.tasks.getValue(StartupTask.LOCAL_TABS).terminal
                }
                if (coordinator.startupReadiness.value.presentationGeneration != generation) return@repeatOnLifecycle
                coordinator.startupTask(task, StartupStatus.RUNNING, generation)
                withContext(AppDispatchers.Io) {
                    coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI,
                        label = "Initial tab layout $index") {
                        withContext(AppDispatchers.Main) {
                            mounted = mounted + index
                            measured[index].await()
                            // Retain this lease through the real traversal. The next
                            // tab cannot compete with composition/measurement here.
                            view.awaitStartupContentDraw { }
                        }
                    }
                }
                coordinator.startupTask(task, StartupStatus.READY, generation)
            }
        }
    }
    HorizontalPager(
        state = state,
        // Keep all three primary destinations mounted, including Home's carousels
        // and painters. Hidden effects remain gated by the visibility local below.
        // Retain cheap page containers immediately; mount their real content one
        // at a time during background preparation. A requested tab mounts immediately.
        beyondViewportPageCount = 2,
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
        CompositionLocalProvider(LocalVisualEffectsActive provides (visible && parentEffectsActive),
            LocalArtworkRequestsActive provides visible, LocalLifecycleOwner provides page) {
            Box(Modifier.fillMaxSize().graphicsLayer { clip = false }) {
                if (index in mounted) Box(Modifier.fillMaxSize().onGloballyPositioned {
                    if (it.size.width > 0 && it.size.height > 0) measured[index].complete(Unit)
                }) { content(index) }
            }
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
