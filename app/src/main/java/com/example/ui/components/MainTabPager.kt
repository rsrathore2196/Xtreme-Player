package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    var preparingPage by remember { mutableIntStateOf(-1) }
    val measured = remember { List(3) { CompletableDeferred<Unit>() } }
    // Visible navigation always wins over optional hidden-tab preparation.
    LaunchedEffect(state.currentPage, state.targetPage) {
        mounted = mounted + state.currentPage + state.targetPage
    }
    LaunchedEffect(view, lifecycle, prepareStartup) {
        if (!prepareStartup) {
            // A preparation timeout must not mount all unfinished screens on the
            // first interactive frame. User navigation still mounts its target immediately.
            for (index in 0..2) {
                if (index in mounted) continue
                BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                    label = "Retained tab preparation $index") {
                    try {
                        preparingPage = index
                        mounted = mounted + index
                        measured[index].await()
                        view.awaitStartupContentDraw { }
                    } finally { preparingPage = -1 }
                }
            }
            return@LaunchedEffect
        }
        BackgroundWorkCoordinator.beginTabPreparation()
        if (BackgroundWorkCoordinator.startupReadiness.value.complete) return@LaunchedEffect
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
                    // Measure the cached Home shell without waiting on remote shelves.
                    // The final presentation fence still waits for the populated draw.
                    else if (index == 0) readiness.tasks.getValue(StartupTask.HOME_CACHE).terminal
                    else readiness.tasks.getValue(StartupTask.LOCAL_TABS).terminal
                }
                if (coordinator.startupReadiness.value.presentationGeneration != generation) return@repeatOnLifecycle
                coordinator.startupTask(task, StartupStatus.RUNNING, generation)
                withContext(AppDispatchers.Io) {
                    coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI,
                        boundToInitialPresentation = true, label = "Initial tab layout $index") {
                        withContext(AppDispatchers.Main) {
                            try {
                                preparingPage = index
                                mounted = mounted + index
                                measured[index].await()
                                // Bind cached flows while preparing, not on the first
                                // slide into the page. Hidden effects stay inactive.
                                view.awaitStartupContentDraw { }
                            } finally { preparingPage = -1 }
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
        val settled by remember(state, index, pageCovered) {
            derivedStateOf { !pageCovered && state.settledPage == index && !state.isScrollInProgress &&
                kotlin.math.abs(state.currentPageOffsetFraction) < 0.001f }
        }
        val parent = LocalLifecycleOwner.current.lifecycle
        val page = remember(parent) { RetainedPageLifecycle(parent) }
        DisposableEffect(page) {
            val observer = LifecycleEventObserver { _, _ -> page.update() }
            parent.addObserver(observer)
            onDispose { parent.removeObserver(observer); page.destroy() }
        }
        val collectVisibleData = settled
        val collectPreparedData = preparingPage == index && !state.isScrollInProgress
        SideEffect {
            // Keep the retained pixels moving, without publishing data into an
            // incoming/outgoing page during the animation itself.
            page.visible = collectVisibleData
            page.preparing = collectPreparedData
            page.update()
        }
        CompositionLocalProvider(LocalVisualEffectsActive provides (visible && parentEffectsActive),
            LocalArtworkRequestsActive provides visible, LocalLifecycleOwner provides page) {
            Box(Modifier.fillMaxSize().graphicsLayer { clip = false }
                .testTag(if (settled) "main_tab_${index}_settled" else "main_tab_${index}_retained")) {
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
    var preparing = false
    override val lifecycle: Lifecycle get() = registry
    fun update() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (parent.currentState == Lifecycle.State.DESTROYED) { destroy(); return }
        registry.currentState = when {
            visible -> parent.currentState
            preparing -> minOf(parent.currentState, Lifecycle.State.STARTED)
            else -> minOf(parent.currentState, Lifecycle.State.CREATED)
        }
    }
    fun destroy() {
        // INITIALIZED cannot transition directly to DESTROYED.
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** Retain the primary tree without drawing a second page beneath Settings. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun RetainedPrimaryTabHost(
    state: PagerState, transition: androidx.compose.animation.core.Transition<Int>, covered: Boolean,
    inputBlocked: Boolean = covered, prepareStartup: Boolean = false, content: @Composable (Int) -> Unit
) {
    val settingsCoversContent = transition.currentState == 3 || transition.targetState == 3 || transition.isRunning
    Box(Modifier.fillMaxSize().drawWithContent { if (!covered && !settingsCoversContent) drawContent() }
        .then(if (inputBlocked) Modifier.clearAndSetSemantics {
            testTag = "retained_primary_tabs"
            invisibleToUser()
        }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        } else Modifier.testTag("retained_primary_tabs"))) {
        MainTabPager(state, pageCovered = covered || settingsCoversContent, prepareStartup = prepareStartup, content = content)
    }
}

/** One opaque page, with a short rise and settle; no translucent preview or outgoing-page fade. */
@Composable
internal fun SettingsNavigationOverlay(
    transition: androidx.compose.animation.core.Transition<Int>,
    content: @Composable () -> Unit
) {
    val progress = transition.animateFloat(transitionSpec = {
        androidx.compose.animation.core.tween(if (targetState == 3) 240 else 180,
            easing = androidx.compose.animation.core.FastOutSlowInEasing)
    }, label = "settings_page_presentation") { if (it == 3) 1f else 0f }
    if (transition.currentState == 3 || transition.targetState == 3 || transition.isRunning) {
        Box(Modifier.fillMaxSize().clipToBounds()
            .background(com.example.ui.theme.LocalAppColors.current.scaffoldBackground)
            .testTag("settings_navigation_overlay")) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val remaining = 1f - progress.value
                scaleX = 1f - 0.015f * remaining
                scaleY = scaleX
                translationY = 12.dp.toPx() * remaining
                clip = true
            }.testTag("settings_navigation_content")) { content() }
        }
    }
}
