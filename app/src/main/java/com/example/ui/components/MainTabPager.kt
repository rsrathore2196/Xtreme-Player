package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
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
        SideEffect { page.visible = visible; page.update() }
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

/** Retain the primary tree through opaque destinations, with the existing Settings motion. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun RetainedPrimaryTabHost(
    state: PagerState, transition: androidx.compose.animation.core.Transition<Int>, covered: Boolean,
    inputBlocked: Boolean = covered, prepareStartup: Boolean = false, content: @Composable (Int) -> Unit
) {
    val scale = transition.animateFloat(transitionSpec = {
        androidx.compose.animation.core.tween(if (targetState == 3) 320 else 300,
            easing = androidx.compose.animation.core.FastOutSlowInEasing)
    }, label = "retained_primary_scale") { if (it == 3) 0.985f else 1f }
    val opacity = transition.animateFloat(transitionSpec = {
        if (targetState == 3) androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing)
        else androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
    }, label = "retained_primary_alpha") { if (it == 3) 0f else 1f }
    Box(Modifier.fillMaxSize().graphicsLayer {
        scaleX = scale.value; scaleY = scale.value; alpha = opacity.value
    }.drawWithContent { if (!covered) drawContent() }
        .then(if (inputBlocked) Modifier.clearAndSetSemantics {
            testTag = "retained_primary_tabs"
            invisibleToUser()
        }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        } else Modifier.testTag("retained_primary_tabs"))) {
        MainTabPager(state, pageCovered = covered, prepareStartup = prepareStartup, content = content)
    }
}

/** One visibility owner for Settings; no animated empty page above the retained pager. */
@Composable
internal fun SettingsNavigationOverlay(
    transition: androidx.compose.animation.core.Transition<Int>,
    content: @Composable () -> Unit
) {
    val origin = androidx.compose.ui.graphics.TransformOrigin(0.94f, 0.04f)
    transition.AnimatedVisibility(
        visible = { it == 3 },
        enter = androidx.compose.animation.scaleIn(
            initialScale = 0.94f,
            transformOrigin = origin,
            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        ),
        exit = androidx.compose.animation.scaleOut(
            targetScale = 0.94f,
            transformOrigin = origin,
            animationSpec = androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeOut(
            animationSpec = androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutLinearInEasing)
        ),
        modifier = Modifier.fillMaxSize().testTag("settings_navigation_overlay")
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { clip = false }) { content() }
    }
}
