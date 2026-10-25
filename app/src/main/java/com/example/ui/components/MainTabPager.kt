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
    val opacity = transition.animateFloat(transitionSpec = {
        if (targetState == 3) androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutLinearInEasing)
        else androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
    }, label = "retained_primary_alpha") { if (it == 3) 0f else 1f }
    val parallaxX = transition.animateFloat(transitionSpec = {
        androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    }, label = "retained_primary_parallax") { if (it == 3) -100f else 0f }
    Box(Modifier.fillMaxSize().graphicsLayer {
        translationX = parallaxX.value
        alpha = opacity.value
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

/** Transform the actual Settings layer from its first frame; never flash a laid-out preview. */
@Composable
internal fun SettingsNavigationOverlay(
    transition: androidx.compose.animation.core.Transition<Int>,
    content: @Composable () -> Unit
) {
    val slide = transition.animateFloat(transitionSpec = {
        if (targetState == 3) androidx.compose.animation.core.tween(320,
            easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))
        else androidx.compose.animation.core.tween(260,
            easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))
    }, label = "settings_layer_position") { if (it == 3) 1f else 0f }
    val opacity = transition.animateFloat(transitionSpec = {
        if (targetState == 3) androidx.compose.animation.core.tween(240,
            easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        else androidx.compose.animation.core.tween(180,
            easing = androidx.compose.animation.core.FastOutLinearInEasing)
    }, label = "settings_layer_alpha") { if (it == 3) 1f else 0f }
    if (transition.currentState == 3 || transition.targetState == 3 || transition.isRunning) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * (1f - slide.value)
            // Enter as one opaque page: a translucent preview over Home looks
            // like a second opening before the shared-axis slide has completed.
            alpha = if (transition.targetState == 3) 1f else opacity.value
            clip = false
        }.drawWithContent {
            // Includes the initial unmeasured frame: no ghost page can be captured
            // in the shared backdrop before the real entrance has begun.
            if (slide.value > 0f && (transition.targetState == 3 || opacity.value > 0f)) drawContent()
        }.testTag("settings_navigation_overlay")) { content() }
    }
}
