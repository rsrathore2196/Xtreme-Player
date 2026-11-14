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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.util.LocalArtworkRequestsActive
import kotlinx.coroutines.CompletableDeferred

/** The first layout happens before page motion, with collectors/artwork still paused. */
internal class TabDestinationPreparation {
    private val laidOut = List(3) { CompletableDeferred<Unit>() }
    fun prepared(page: Int) { laidOut.getOrNull(page)?.complete(Unit) }
    suspend fun await(page: Int) { laidOut[page].await() }
}

/** Mount requested destinations only, retaining their composition and scroll state after first use. */
@Composable
internal fun MainTabPager(
    state: PagerState,
    pageCovered: Boolean,
    requestedPage: Int = state.targetPage,
    onPagePrepared: (Int) -> Unit = {},
    content: @Composable (Int) -> Unit
) {
    val parentEffectsActive = LocalVisualEffectsActive.current
    var mounted by remember { mutableStateOf(setOf(state.currentPage)) }
    LaunchedEffect(state.currentPage, state.targetPage, requestedPage) {
        if (state.currentPage !in mounted || state.targetPage !in mounted || requestedPage !in mounted) {
            mounted = mounted + state.currentPage + state.targetPage + requestedPage
        }
    }
    HorizontalPager(state = state, beyondViewportPageCount = 2, userScrollEnabled = false,
        key = { it }, modifier = Modifier.fillMaxSize()) { index ->
        val visible by remember(state, index, pageCovered) {
            derivedStateOf { !pageCovered && state.layoutInfo.visiblePagesInfo.any { it.index == index } }
        }
        val settled by remember(state, index, pageCovered) {
            derivedStateOf {
                !pageCovered && state.settledPage == index && !state.isScrollInProgress &&
                    (state.currentPage == index && kotlin.math.abs(state.currentPageOffsetFraction) < 0.001f)
            }
        }
        val parent = LocalLifecycleOwner.current.lifecycle
        val page = remember(parent) { RetainedPageLifecycle(parent) }
        DisposableEffect(page) {
            val observer = LifecycleEventObserver { _, _ -> page.update() }
            parent.addObserver(observer)
            onDispose { parent.removeObserver(observer); page.destroy() }
        }
        SideEffect { page.visible = settled; page.update() }
        CompositionLocalProvider(LocalVisualEffectsActive provides (visible && parentEffectsActive),
            LocalArtworkRequestsActive provides settled, LocalLifecycleOwner provides page) {
            Box(Modifier.fillMaxSize().graphicsLayer { clip = false }
                .onGloballyPositioned { if (index in mounted || index == requestedPage) onPagePrepared(index) }
                .testTag(if (settled) "main_tab_${index}_settled" else "main_tab_${index}_retained")) {
                // Adjacent page containers stay cheap until navigation requests them.
                if (index in mounted || index == requestedPage) content(index)
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
        registry.currentState = when {
            visible -> parent.currentState
            else -> minOf(parent.currentState, Lifecycle.State.CREATED)
        }
    }
    fun destroy() {
        // INITIALIZED cannot transition directly to DESTROYED.
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** Retain the primary page during the Settings slide, with its collectors paused. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun RetainedPrimaryTabHost(
    state: PagerState, transition: androidx.compose.animation.core.Transition<Int>, covered: Boolean,
    inputBlocked: Boolean = covered, requestedPage: Int = state.targetPage,
    onPagePrepared: (Int) -> Unit = {}, content: @Composable (Int) -> Unit
) {
    val settingsCoversContent = transition.currentState == 3 || transition.targetState == 3 || transition.isRunning
    val settingsSettled = transition.currentState == 3 && transition.targetState == 3 && !transition.isRunning
    Box(Modifier.fillMaxSize().drawWithContent { if (!covered && !settingsSettled) drawContent() }
        .then(if (inputBlocked || settingsCoversContent) Modifier.clearAndSetSemantics {
            testTag = "retained_primary_tabs"
            invisibleToUser()
        }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        } else Modifier.testTag("retained_primary_tabs"))) {
        MainTabPager(state, pageCovered = covered || settingsCoversContent,
            requestedPage = requestedPage, onPagePrepared = onPagePrepared, content = content)
    }
}

/** One opaque page slides on a single layer clock, retracing its path on close. */
@Composable
internal fun SettingsNavigationOverlay(
    transition: androidx.compose.animation.core.Transition<Int>,
    content: @Composable () -> Unit
) {
    val progress = transition.animateFloat(transitionSpec = {
        androidx.compose.animation.core.tween(if (targetState == 3) 360 else 320,
            easing = androidx.compose.animation.core.FastOutSlowInEasing)
    }, label = "settings_page_presentation") { if (it == 3) 1f else 0f }
    val fromRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    if (transition.currentState == 3 || transition.targetState == 3 || transition.isRunning) {
        Box(Modifier.fillMaxSize().clipToBounds()
            .testTag("settings_navigation_overlay")) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                // Translate the canvas and its content together, keeping text,
                // glass and borders sharp. No rescaling or transparent preview.
                translationX = size.width * (1f - progress.value.coerceIn(0f, 1f)) * if (fromRight) 1f else -1f
            }.background(com.example.ui.theme.LocalAppColors.current.scaffoldBackground)
                .testTag("settings_navigation_content")) { content() }
        }
    }
}
