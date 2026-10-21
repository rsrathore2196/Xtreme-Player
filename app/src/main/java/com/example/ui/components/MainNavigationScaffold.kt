package com.example.ui.components

import androidx.lifecycle.repeatOnLifecycle

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.updateTransition
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.liquidGlass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MusicTrack
import com.example.ui.screens.HomeFeedRoute
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LocalAppColors
import com.example.ui.viewmodel.PlayerViewModel
import com.example.util.AppHaptics
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.clearAndSetSemantics

enum class NavigationTab(val title: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainNavigationScaffold(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    // Preparation is process-retained; the lightweight modal observes progress separately.
    remember { BackgroundWorkCoordinator.beginInitialPresentation() }
    val preparingStartup by remember {
        BackgroundWorkCoordinator.startupReadiness.map { !it.complete }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = !BackgroundWorkCoordinator.startupReadiness.value.complete)
    if (preparingStartup) com.example.ui.screens.PrepareInitialHome()
    ObserveInitialContentReadiness()
    val playerUiState by viewModel.navigationPlayerUiState.collectAsStateWithLifecycle()
    val currentPlayingTrackId = playerUiState.currentTrack?.id
    val catalogDetail by viewModel.catalogBrowser.detail.collectAsStateWithLifecycle()
    val selectedPlaylistWithTracks by viewModel.selectedPlaylistTracks.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val importDialogOpen by viewModel.isImportDialogOpen.collectAsStateWithLifecycle()
    val isDynamicGlassEnabled by viewModel.isDynamicGlassEnabled.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    // Like LastWave, one lazy pager owns the page position. Selection changes on
    // tap, never from currentPage halfway through a slide (which retriggers pills).
    val tabPagerState = rememberPagerState(
        initialPage = selectedTabIndex.coerceIn(0, 2),
        pageCount = { 3 }
    )
    // The three primary pages stay mounted. Visibility still controls optional
    // data preparation and effects; tab motion does not reconstruct their trees.
    val previousTabIndex = remember { mutableIntStateOf(selectedTabIndex) }
    val selectTab: (Int) -> Unit = { index ->
        if (selectedTabIndex != index) {
            com.example.performance.BackgroundWorkCoordinator.navigationInteraction()
            selectedTabIndex = index
        }
    }
    // Retarget a single cancellable page animation on rapid taps. Keep optional
    // work out of the whole slide, including the incoming page's first layout.
    LaunchedEffect(selectedTabIndex) {
        val returningFromSettings = previousTabIndex.intValue == 3
        previousTabIndex.intValue = selectedTabIndex
        val navigation = com.example.performance.BackgroundWorkCoordinator.navigationBusy()
        try {
            if (selectedTabIndex in 0..2 &&
                (tabPagerState.currentPage != selectedTabIndex || tabPagerState.currentPageOffsetFraction != 0f)) {
                if (returningFromSettings) {
                    // Settings keeps its existing zoom; reveal the destination
                    // behind it without adding an unrelated horizontal animation.
                    tabPagerState.scrollToPage(selectedTabIndex)
                } else {
                    // Pager's default critically damped spring matches LastWave's
                    // full-width page slide, without overlapping slide/fade clocks.
                    tabPagerState.animateScrollToPage(selectedTabIndex)
                }
            }
            if (selectedTabIndex != 0) {
                // A restored Search/Library/Settings page must not need to create
                // a hidden Home ViewModel to release application maintenance.
                com.example.performance.BackgroundWorkCoordinator.awaitFirstFrame()
                com.example.performance.BackgroundWorkCoordinator.nonHomeContentReady()
            }
        } finally { navigation.close() }
    }
    LaunchedEffect(viewModel, selectedTabIndex, catalogDetail != null, preparingStartup) {
        viewModel.prepareCatalogUi(searchVisible = !preparingStartup && selectedTabIndex == 1,
            detailVisible = !preparingStartup && catalogDetail != null)
    }
    val tabState = rememberSaveableStateHolder()
    // Allocate these outside AnimatedContent; tab disposal never serializes or
    // reconstructs the visible lists' scroll state during a slide.
    val homeListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    // Only Settings uses AnimatedContent. Primary tabs share the pager's one
    // position/velocity and are composed lazily as they enter the viewport.
    val tabTransition = updateTransition(if (selectedTabIndex == 3 && !preparingStartup) 3 else 0, label = "settings_navigation_transition")
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    var isQueueOpen by rememberSaveable { mutableStateOf(false) }
    var isEqualizerOpen by rememberSaveable { mutableStateOf(false) }
    var isCreatePlaylistOpen by rememberSaveable { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<MusicTrack?>(null) }
    var isSettingsSubpageOpen by rememberSaveable { mutableStateOf(false) }

    // Data admission follows real Search/Library drags and flings as well as
    // Home's nested rows. Observe here without reading scroll state in composition.
    val tabScrollOwner = remember { Any() }
    val tabCovered = isPlayerExpanded || catalogDetail != null || selectedPlaylistWithTracks != null
    LaunchedEffect(selectedTabIndex, tabCovered, searchListState, libraryListState) {
        try {
            androidx.compose.runtime.snapshotFlow {
                !tabCovered && when (selectedTabIndex) {
                    1 -> searchListState.isScrollInProgress
                    2 -> libraryListState.isScrollInProgress
                    else -> false
                }
            }.collect { com.example.performance.BackgroundWorkCoordinator.scrolling(tabScrollOwner, it) }
        } finally { com.example.performance.BackgroundWorkCoordinator.scrolling(tabScrollOwner, false) }
    }

    // Request notification permission for Android 13+ (API 33)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.finishNotificationPermissionRequest() }

    val startupLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val startupView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(viewModel, startupLifecycle, startupView, selectedTabIndex, preparingStartup) {
      startupLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
        if (preparingStartup || selectedTabIndex != 0) return@repeatOnLifecycle
        // Ask as soon as Home has actually drawn, rather than waiting behind
        // recommendation loading or the optional background-work idle budget.
        startupView.awaitStartupContentDraw { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && viewModel.canRequestNotificationPermission &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (viewModel.beginNotificationPermissionRequest()) {
                try { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                catch (cancelled: kotlinx.coroutines.CancellationException) {
                    viewModel.finishNotificationPermissionRequest()
                    throw cancelled
                } catch (_: Exception) { viewModel.finishNotificationPermissionRequest() }
            }
        }
        // The OS dialog may pause this lifecycle. Its result is retained by the VM
        // so the next resumed pass continues instead of requesting permission again.
        viewModel.awaitNotificationPermissionResult()
        // An automatic release check is optional. A fixed startup delay alone
        // allowed it to compete with the first artwork/Room/recommendation wave.
        // AppUpdateManager owns serialized MAINTENANCE admission and promotion
        // for manual checks; do not add a competing readiness/timer gate here.
        if (viewModel.beginAutomaticUpdateCheck())
            com.example.updater.AppUpdateManager.checkForUpdate(context = context)
      }
    }

    // Handle Back Press gracefully across the app
    BackHandler(enabled = catalogDetail != null || isPlayerExpanded || selectedPlaylistWithTracks != null || selectedTabIndex != 0) {
        if (isPlayerExpanded) {
            isPlayerExpanded = false
        } else if (catalogDetail != null) {
            viewModel.catalogBrowser.close()
        } else if (selectedPlaylistWithTracks != null) {
            viewModel.closePlaylist()
        } else if (selectedTabIndex == 1 && viewModel.hasActiveSearch()) {
            viewModel.resetSearchNavigation()
        } else if (selectedTabIndex != 0) {
            selectTab(0)
        }
    }

    val playerVisibility = remember {
        androidx.compose.animation.core.MutableTransitionState(isPlayerExpanded && playerUiState.currentTrack != null)
    }
    playerVisibility.targetState = isPlayerExpanded && playerUiState.currentTrack != null
    val playerCoversContent = playerVisibility.isIdle && playerVisibility.currentState && playerVisibility.targetState
    val transitionRunning = !playerVisibility.isIdle || tabTransition.isRunning || tabPagerState.isScrollInProgress
    DisposableEffect(transitionRunning) {
        if (transitionRunning) com.example.performance.BackgroundWorkCoordinator.interaction()
        val work = if (transitionRunning) com.example.performance.BackgroundWorkCoordinator.navigationBusy() else null
        onDispose { work?.close() }
    }
    val homeLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Visibility is a scheduling hint only. Home keeps its Room observers and
    // retained state alive while another tab or the expanded player is visible.
    val homeIsVisible = selectedTabIndex == 0 && !isPlayerExpanded && playerVisibility.isIdle &&
        !playerVisibility.currentState && catalogDetail == null && selectedPlaylistWithTracks == null

    val baseAppColors = LocalAppColors.current
    val appColors = remember(baseAppColors, isDynamicGlassEnabled) {
        baseAppColors.copy(dynamicGlassEnabled = isDynamicGlassEnabled)
    }

    // These screens own their edge spring; disable Android's competing stretch effect.
    val platformOverscroll = LocalOverscrollConfiguration.current
    CompositionLocalProvider(
        LocalDynamicGlassEnabled provides isDynamicGlassEnabled,
        LocalAppColors provides appColors,
        LocalLiquidGlassBackdrop provides rememberLiquidGlassBackdrop(captureVisibleRegions = true),
        LocalOverscrollConfiguration provides
            if (selectedTabIndex == 3 && selectedPlaylistWithTracks == null) platformOverscroll else null
    ) {
        Box(modifier = modifier.fillMaxSize().background(appColors.scaffoldBackground)) {
            Scaffold(
                modifier = if (preparingStartup) Modifier.clearAndSetSemantics { } else Modifier,
                contentWindowInsets = WindowInsets(0.dp),
                containerColor = appColors.scaffoldBackground
            ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().drawWithContent { if (!playerCoversContent) drawContent() }
                    .xtremeSharedBackdropSource(enabled = isDynamicGlassEnabled && !playerCoversContent)) {
                CompositionLocalProvider(LocalVisualEffectsActive provides (!playerCoversContent && catalogDetail == null)) {
                // If playlist detail is open, show it with a smooth animated transition
                val currentPlaylist = selectedPlaylistWithTracks
                AnimatedVisibility(
                    visible = currentPlaylist != null,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing)),
                    modifier = Modifier.graphicsLayer { clip = false }
                ) {
                    currentPlaylist?.let { playlist ->
                        val detailPlayer by viewModel.playbackPresentationState.collectAsStateWithLifecycle()
                        PlaylistDetailScreen(
                            playlistWithTracks = playlist,
                            playerUiState = detailPlayer,
                            currentPlayingTrackId = currentPlayingTrackId,
                            onBackClick = { viewModel.closePlaylist() },
                            onPlayTrack = { track, queue -> viewModel.playPlaylistTrack(track, queue) },
                            onToggleFavorite = { track -> viewModel.toggleLike(track) },
                            onDeletePlaylist = { viewModel.deletePlaylist(playlist.playlist.playlistId) },
                            onRemoveTrack = { trackId ->
                                viewModel.removeTrackFromPlaylist(playlist.playlist.playlistId, trackId)
                            },
                            onRenamePlaylist = { newTitle ->
                                viewModel.renamePlaylist(playlist.playlist.playlistId, newTitle)
                            }
                        )
                    }
                }

                if (currentPlaylist == null || preparingStartup) {
                    val tabContent: @Composable (Int) -> Unit = { tabIndex ->
                      tabState.SaveableStateProvider(tabIndex) {
                        when (tabIndex) {
                            0 -> HomeFeedRoute(
                                player = playerUiState,
                                isVisible = homeIsVisible,
                                listState = homeListState,
                                onOpenArtist = { viewModel.catalogBrowser.open(com.example.data.repository.CatalogDestination.Artist(it)) },
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                onOpenSettings = { selectTab(3) },
                                onPlayInfiniteRadio = { seed -> viewModel.playInfiniteRadio(seed) },
                                onAddToQueue = { track -> viewModel.addToQueue(track) }
                            )
                            1 -> {
                                val searchState by viewModel.searchState.collectAsStateWithLifecycle()
                                val catalogSearch by viewModel.catalogBrowser.search.collectAsStateWithLifecycle()
                                SearchScreen(
                                    searchState = searchState,
                                    isScreenActive = !preparingStartup && selectedTabIndex == 1 && !isPlayerExpanded && catalogDetail == null,
                                    listState = searchListState,
                                    catalogState = catalogSearch,
                                    onSelectType = viewModel.catalogBrowser::selectType,
                                    onLoadMore = viewModel.catalogBrowser::loadMoreSearch,
                                    onRetrySearch = { if (catalogSearch.page.nextPage != null && catalogSearch.error != null) viewModel.catalogBrowser.loadMoreSearch() else viewModel.catalogBrowser.retrySearch() },
                                    onOpenCatalog = viewModel.catalogBrowser::open,
                                    onRememberSearch = viewModel::rememberSearch,
                                    onRemoveRecentSearch = viewModel::removeRecentSearch,
                                    onClearRecentSearches = viewModel::clearRecentSearches,
                                    currentPlayingTrackId = currentPlayingTrackId,
                                    isPlaying = playerUiState.isPlaying,
                                    isLoading = playerUiState.isLoading,
                                    onQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                                    onSelectGenre = { g -> viewModel.selectGenre(g) },
                                    onSelectSource = { s -> viewModel.selectSource(s) },
                                    onTrackClick = { track, queue -> viewModel.playFromSearch(track, queue) },
                                    onToggleFavorite = { track -> viewModel.toggleLike(track) }
                                )
                            }
                            2 -> {
                                val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()
                                val playlists by viewModel.playlists.collectAsStateWithLifecycle()
                                LibraryScreen(
                                    favoriteTracks = favoriteTracks,
                                    listState = libraryListState,
                                    playlists = playlists,
                                    currentPlayingTrackId = currentPlayingTrackId,
                                    isDarkMode = isDarkMode,
                                    onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                    onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                    onOpenPlaylist = { id -> viewModel.openPlaylist(id) },
                                    onCreatePlaylistClick = { isCreatePlaylistOpen = true },
                                    onImportPlaylistClick = { viewModel.openImportDialog() }
                                )
                            }
                            3 -> {
                                val effectsState by viewModel.effectsState.collectAsStateWithLifecycle()
                                val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
                                val customThemeState by viewModel.customThemeState.collectAsStateWithLifecycle()
                                val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
                                SettingsScreen(
                                    effectsState = effectsState,
                                    isDarkMode = isDarkMode,
                                    themeMode = themeMode,
                                    userProfile = userProfile,
                                    isDynamicGlassEnabled = isDynamicGlassEnabled,
                                    onToggleDynamicGlass = { viewModel.setDynamicGlassEnabled(it) },
                                    onSubpageStateChanged = { isOpen -> isSettingsSubpageOpen = isOpen },
                                    onNavigateBackToHome = { selectTab(0) },
                                    onUpdateProfile = { updated -> viewModel.updateUserProfile(updated) },
                                    onSelectThemeMode = { mode -> viewModel.setThemeMode(mode) },
                                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                                    onCrystalClarityToggle = { enabled -> viewModel.setCrystalClarityEnabled(enabled) },
                                    onToggleEqualizer = { enabled -> viewModel.setEqualizerEnabled(enabled) },
                                    onOpenEqualizer = { isEqualizerOpen = true },
                                    onSelectPreset = { preset -> viewModel.setEqualizerPreset(preset) },
                                    customThemeState = customThemeState,
                                    onUpdateCustomThemeState = { updatedState -> viewModel.updateCustomThemeState(updatedState) },
                                    onApplyPreset = { preset -> viewModel.applyThemePreset(preset) },
                                    onTextScaleChanged = { index -> viewModel.setTextScaleIndex(index) },
                                    onUiScaleChanged = { index -> viewModel.setUiScaleIndex(index) }
                                )
                            }
                        }
                      }
                    }
                    tabTransition.AnimatedContent(
                        transitionSpec = {
                            // Expand from the Home settings button with one coordinated
                            // easing curve. Primary-tab paging is entirely unchanged.
                            val origin = TransformOrigin(0.94f, 0.04f)
                            (if (targetState == 3) {
                                (scaleIn(initialScale = 0.94f, transformOrigin = origin,
                                    animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                                    slideInVertically(initialOffsetY = { -it / 40 },
                                        animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                                    fadeIn(tween(240, easing = LinearOutSlowInEasing))).togetherWith(
                                    scaleOut(targetScale = 0.985f,
                                        animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                                    fadeOut(tween(240, easing = FastOutLinearInEasing)))
                            } else {
                                (scaleIn(initialScale = 0.985f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                    fadeIn(tween(240, easing = LinearOutSlowInEasing))).togetherWith(
                                    scaleOut(targetScale = 0.94f, transformOrigin = origin,
                                        animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                    slideOutVertically(targetOffsetY = { -it / 40 },
                                        animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                    fadeOut(tween(220, easing = FastOutLinearInEasing)))
                            }).using(null)
                        },
                        modifier = Modifier.fillMaxSize()
                    ) { tabHost ->
                        if (tabHost == 3) {
                            Box(Modifier.fillMaxSize().graphicsLayer { clip = false }) {
                                tabContent(3)
                            }
                        } else {
                            MainTabPager(
                                state = tabPagerState,
                                pageCovered = !preparingStartup && (playerCoversContent || catalogDetail != null),
                                prepareStartup = preparingStartup
                            ) { tabIndex ->
                                tabContent(tabIndex)
                            }
                        }
                    }
                }

                }
                CompositionLocalProvider(LocalVisualEffectsActive provides !playerCoversContent) {
                catalogDetail?.let { detail ->
                    androidx.compose.runtime.key(detail.destination.key) {
                        val detailPlayer by viewModel.playbackPresentationState.collectAsStateWithLifecycle()
                        com.example.ui.screens.CatalogDetailScreen(
                            state = detail, player = detailPlayer,
                            onBack = viewModel.catalogBrowser::close,
                            onMore = viewModel.catalogBrowser::loadMoreDetail,
                            onPlay = { track, queue ->
                                if (detail.destination is com.example.data.repository.CatalogDestination.Album ||
                                    detail.destination is com.example.data.repository.CatalogDestination.Playlist)
                                    viewModel.playCatalogCollection(track, detail)
                                else viewModel.playTrack(track, queue)
                            },
                            onFavorite = { viewModel.toggleLike(it) },
                            onScroll = viewModel.catalogBrowser::rememberScroll,
                            handleBack = !isPlayerExpanded
                        )
                    }
                }

                }
                } // Only page content belongs to the shared backdrop; overlays never sample themselves.

                // Floating Overlaid Container (MiniPlayer + Floating Pill Bottom Bar)
                // Merges with the tab screen seamlessly and floats elevated above the content
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Floating MiniPlayer: Sleek, attractive, non-bouncy transition
                    val isMiniPlayerVisible = playerUiState.currentTrack != null && !isPlayerExpanded && !isSettingsSubpageOpen && !isEqualizerOpen

                    AnimatedVisibility(
                        visible = isMiniPlayerVisible,
                        enter = slideInVertically(
                            animationSpec = tween(170, easing = FastOutSlowInEasing),
                            initialOffsetY = { it }
                        ) + fadeIn(animationSpec = tween(140, easing = LinearOutSlowInEasing)),
                        exit = slideOutVertically(
                            animationSpec = tween(140, easing = FastOutSlowInEasing),
                            targetOffsetY = { it }
                        ) + fadeOut(animationSpec = tween(100, easing = FastOutLinearInEasing)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        MiniPlayer(
                            uiState = playerUiState,
                            onPlayPauseClick = { viewModel.togglePlayPause() },
                            onSkipNext = { viewModel.skipNext() },
                            onSkipPrevious = { viewModel.skipPrevious() },
                            onClick = { isPlayerExpanded = true },
                            currentPositionProvider = { viewModel.currentPosition }
                        )
                    }

                    AnimatedVisibility(
                        visible = !isSettingsSubpageOpen && !isPlayerExpanded,
                        enter = fadeIn(animationSpec = tween(180)),
                        exit = fadeOut(animationSpec = tween(140))
                    ) {
                        FloatingPillBottomBar(
                            selectedTabIndex = selectedTabIndex,
                            onTabSelected = { index ->
                                AppHaptics.performTap(context)
                                if (index != 3) {
                                    isSettingsSubpageOpen = false
                                }
                                selectTab(index)
                                viewModel.catalogBrowser.close()
                                viewModel.closePlaylist()
                            }
                        )
                    }
                }

                // Floating 3D Liquid Glass Auto-Update Pop-up Bar at top of main screen
                if (!isPlayerExpanded && !isSettingsSubpageOpen) {
                    UpdateNotificationBar(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        // FULL SCREEN EXPANDED PLAYER (Smooth, responsive push/pop spring transitions)
        AnimatedVisibility(
            visibleState = playerVisibility,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(150, easing = LinearOutSlowInEasing)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ) + scaleOut(
                targetScale = 0.92f,
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(120, easing = FastOutLinearInEasing)),
            modifier = Modifier.fillMaxSize()
        ) {
            val currentLyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
            val availableAudioDevices by viewModel.availableAudioDevices.collectAsStateWithLifecycle()
            val expandedPlayer by viewModel.playbackPresentationState.collectAsStateWithLifecycle()
            ExpandedPlayerScreen(
                uiState = expandedPlayer,
                onMinimize = { isPlayerExpanded = false },
                onPlayPause = { viewModel.togglePlayPause() },
                onSeekTo = { viewModel.seekTo(it) },
                onSkipNext = { viewModel.skipNext() },
                onSkipPrevious = { viewModel.skipPrevious() },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onToggleRepeat = { viewModel.toggleRepeat() },
                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                onOpenQueue = { isQueueOpen = true },
                onOpenEqualizer = { isEqualizerOpen = true },
                onAddToPlaylist = { track -> trackToAddToPlaylist = track },
                onAddToQueue = { track -> viewModel.addToQueue(track) },
                lyrics = currentLyrics,
                onRetryLyrics = { viewModel.retryLyrics() },
                onLyricsVisibilityChanged = viewModel::setLyricsVisible,
                isScreenVisible = isPlayerExpanded && !preparingStartup,
                availableAudioDevices = availableAudioDevices,
                onSelectAudioDevice = { deviceId -> viewModel.selectAudioOutputDevice(deviceId) },
                playbackPositionFlow = viewModel.playbackPosition,
                currentPositionProvider = { viewModel.currentPosition },
                trackDurationProvider = { viewModel.trackDuration.value },
                bufferedPositionProvider = { viewModel.bufferedPosition.value }
            )
        }

        // QUEUE BOTTOM SHEET
        if (isQueueOpen && !preparingStartup) {
          PlayerPresentationHost(viewModel) { playerUiState ->
            QueueBottomSheet(
                queue = playerUiState.queue,
                currentTrack = playerUiState.currentTrack,
                onTrackClick = { track -> viewModel.playTrack(track, playerUiState.queue) },
                onMoveTrack = { from, to -> viewModel.reorderQueue(from, to) },
                onDismiss = { isQueueOpen = false },
                isAutoplayEnabled = playerUiState.isAutoplayEnabled,
                nextRecommendedTrack = playerUiState.nextRecommendedTrack,
                onToggleAutoplay = { viewModel.toggleAutoplay() }
            )
          }
        }

        // EQUALIZER DIALOG
        if (isEqualizerOpen && !preparingStartup) {
            val effectsState by viewModel.effectsState.collectAsStateWithLifecycle()
            EqualizerDialog(
                effectsState = effectsState,
                onEnableChanged = { viewModel.setEqualizerEnabled(it) },
                onPresetSelected = { viewModel.setEqualizerPreset(it) },
                onBandLevelChanged = { band, level -> viewModel.setBandLevel(band, level) },
                onBassBoostChanged = { viewModel.setBassBoost(it) },
                onVirtualizerChanged = { viewModel.setVirtualizer(it) },
                onReset = { viewModel.resetEqualizer() },
                onDismiss = { isEqualizerOpen = false }
            )
        }

        // ADD TO PLAYLIST DIALOG
        trackToAddToPlaylist?.takeUnless { preparingStartup }?.let { track ->
            val playlists by viewModel.playlists.collectAsStateWithLifecycle()
            AddToPlaylistDialog(
                track = track,
                playlists = playlists,
                isDarkMode = isDarkMode,
                onPlaylistSelected = { playlistId ->
                    viewModel.addTrackToPlaylist(playlistId, track)
                    trackToAddToPlaylist = null
                },
                onCreateNewPlaylist = {
                    trackToAddToPlaylist = null
                    isCreatePlaylistOpen = true
                },
                onDismiss = { trackToAddToPlaylist = null }
            )
        }

        // CREATE PLAYLIST DIALOG
        if (isCreatePlaylistOpen && !preparingStartup) {
            CreatePlaylistDialog(
                isDarkMode = isDarkMode,
                onDismiss = { isCreatePlaylistOpen = false },
                onConfirm = { title, description ->
                    viewModel.createPlaylist(title, description)
                    isCreatePlaylistOpen = false
                }
            )
        }

        // PLAYLIST IMPORT DIALOG (Spotify, YouTube Music, Apple Music, CSV)
        if (importDialogOpen && !preparingStartup) ImportDialogHost(viewModel, isDarkMode)
        StartupInitializationOverlay()
    }
}
}

@Composable
private fun PlayerPresentationHost(viewModel: PlayerViewModel,
    content: @Composable (com.example.playback.PlayerUiState) -> Unit) {
    val state by viewModel.playbackPresentationState.collectAsStateWithLifecycle()
    content(state)
}

/**
 * Floating Pill Navigation Bar
 * - Rounded from left and right sides (CircleShape / Pill).
 * - Floats above the tab screens with generous size and shadow.
 * - Only the icon/logo is shown when unselected.
 * - Expanding pill shows both full text and logo when selected.
 * - Tabs: Home, Search, Library.
 */
@Composable
fun FloatingPillBottomBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    ambientGlowColor: Color = LocalAppColors.current.primaryAccent
) {
    val items = remember {
        listOf(
            AnimatedBottomBarItem(0, "Home", Icons.Default.Home, Icons.Outlined.Home),
            AnimatedBottomBarItem(1, "Search", Icons.Default.Search, Icons.Outlined.Search),
            AnimatedBottomBarItem(2, "Library", Icons.Default.LibraryMusic, Icons.Outlined.LibraryMusic)
        )
    }

    AnimatedBottomBar(
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        items = items,
        modifier = modifier,
        ambientGlowColor = ambientGlowColor
    )
}

@Composable
private fun ImportDialogHost(viewModel: PlayerViewModel, isDarkMode: Boolean) {
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    PlaylistImportDialog(
        state = importState,
        isDarkMode = isDarkMode,
        onDismiss = { viewModel.closeImportDialog() },
        onInputChanged = { viewModel.onImportInputChanged(it) },
        onPlatformChanged = { viewModel.onImportPlatformChanged(it) },
        onTokenChanged = { viewModel.onImportTokenChanged(it) },
        onCustomTitleChanged = { viewModel.onCustomTitleChanged(it) },
        onCustomDescriptionChanged = { viewModel.onCustomDescriptionChanged(it) },
        onLoadSample = { viewModel.loadSamplePlaylist(it) },
        onStartImport = { viewModel.startPlaylistImport() },
        onSavePlaylist = { onSaved -> viewModel.saveImportedPlaylist(onSaved) },
        onResetStep = { viewModel.resetImportStep() },
        onOpenPlaylist = { playlistId -> viewModel.openPlaylist(playlistId) },
        onRetryUnmatched = viewModel::retryUnmatchedOnYouTube
    )
}
