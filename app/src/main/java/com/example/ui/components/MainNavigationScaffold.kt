package com.example.ui.components

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration

import androidx.lifecycle.repeatOnLifecycle

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
    val playerUiState by viewModel.navigationPlayerUiState.collectAsStateWithLifecycle()
    val currentPlayingTrackId = playerUiState.currentTrack?.id
    val catalogDetail by viewModel.catalogBrowser.detail.collectAsStateWithLifecycle()
    val selectedPlaylistWithTracks by viewModel.selectedPlaylistTracks.collectAsStateWithLifecycle()
    val isDarkMode = LocalAppColors.current.isDark
    val importDialogOpen by viewModel.isImportDialogOpen.collectAsStateWithLifecycle()
    val isDynamicGlassEnabled by viewModel.isDynamicGlassEnabled.collectAsStateWithLifecycle()
    val liquidGlassProfileSetting by viewModel.liquidGlassProfile.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val effectiveGlassProfile = rememberEffectiveLiquidGlassProfile(liquidGlassProfileSetting)
    val messageLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, context, messageLifecycle) {
        messageLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            viewModel.libraryMessages.collect { message ->
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    // One lazy pager owns the page position. Selection changes on
    // tap, never from currentPage halfway through a slide (which retriggers pills).
    val tabPagerState = rememberPagerState(
        initialPage = selectedTabIndex.coerceIn(0, 2),
        pageCount = { 3 }
    )
    // The three primary pages stay mounted. Visibility still controls optional
    // data preparation and effects; tab motion does not reconstruct their trees.
    val previousTabIndex = remember { mutableIntStateOf(selectedTabIndex) }
    val selectTab: (Int) -> Unit = remember(viewModel) {
        { index ->
            if (index == 2) viewModel.retryLocalLibrary()
            if (selectedTabIndex != index) {
                com.example.performance.BackgroundWorkCoordinator.navigationInteraction()
                selectedTabIndex = index
            }
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
                    // Settings owns its page reveal; prepare the destination
                    // behind it without adding an unrelated horizontal animation.
                    tabPagerState.scrollToPage(selectedTabIndex)
                } else {
                    // Pager's default critically damped spring gives a smooth
                    // full-width page slide, without overlapping slide/fade clocks.
                    tabPagerState.animateScrollToPage(selectedTabIndex)
                }
            }

        } finally { navigation.close() }
    }
    val tabState = rememberSaveableStateHolder()
    // Allocate these outside the Settings overlay; tab disposal never serializes or
    // reconstructs the visible lists' scroll state during a slide.
    val homeListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    // Settings has one overlay transition. Retained primary tabs share the pager's
    // position/velocity and keep their prepared content across navigation.
    val tabTransition = updateTransition(if (selectedTabIndex == 3) 3 else 0, label = "settings_navigation_transition")
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel, selectedTabIndex, catalogDetail != null, isPlayerExpanded) {
        viewModel.prepareCatalogUi(searchVisible = selectedTabIndex == 1 && !isPlayerExpanded && catalogDetail == null,
            detailVisible = catalogDetail != null && !isPlayerExpanded,
            libraryVisible = selectedTabIndex == 2 && !isPlayerExpanded)
    }
    val playerVisibility = remember {
        androidx.compose.animation.core.MutableTransitionState(isPlayerExpanded && playerUiState.currentTrack != null)
    }
    playerVisibility.targetState = isPlayerExpanded && playerUiState.currentTrack != null
    val playerMotion = remember { PlayerExpansionMotion() }
    // Keep the visible recording coherent for the short handoff; playback/queue
    // state continues live and its newly prefetched artwork is used at the endpoint.
    val playerVisualTrack = rememberPlayerHandoff(playerUiState.currentTrack, playerVisibility)
    SideEffect { playerMotion.trackId = playerVisualTrack?.id.orEmpty() }
    var isQueueOpen by rememberSaveable { mutableStateOf(false) }
    var isEqualizerOpen by rememberSaveable { mutableStateOf(false) }
    var isCreatePlaylistOpen by rememberSaveable { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<MusicTrack?>(null) }
    var isSettingsSubpageOpen by rememberSaveable { mutableStateOf(false) }
    val scrollDock = rememberScrollDockState()
    val dockProgress = rememberScrollDockProgress(scrollDock)
    SideEffect {
        if (isPlayerExpanded || playerVisibility.currentState || !playerVisibility.isIdle) scrollDock.suspendForPlayer()
        else scrollDock.enabled = !isSettingsSubpageOpen && !isEqualizerOpen
    }
    LaunchedEffect(selectedTabIndex, catalogDetail?.destination?.key, selectedPlaylistWithTracks?.playlist?.playlistId,
        isSettingsSubpageOpen) { scrollDock.expand() }

    // Data admission follows real Search/Library drags and flings as well as
    // Home's nested rows. Observe here without reading scroll state in composition.
    val tabScrollOwner = remember { Any() }
    val tabCovered = isPlayerExpanded || catalogDetail != null || selectedPlaylistWithTracks != null
    LaunchedEffect(selectedTabIndex, tabCovered, homeListState, searchListState, libraryListState) {
        try {
            androidx.compose.runtime.snapshotFlow {
                !tabCovered && when (selectedTabIndex) {
                    0 -> homeListState.isScrollInProgress
                    1 -> searchListState.isScrollInProgress
                    2 -> libraryListState.isScrollInProgress
                    else -> false
                }
            }.collect { scrolling ->
                com.example.performance.BackgroundWorkCoordinator.scrolling(tabScrollOwner, scrolling)
                com.example.recommendation.DiscoveryRequestCoordinator.homeScrolling(scrolling)
            }
        } finally {
            com.example.performance.BackgroundWorkCoordinator.scrolling(tabScrollOwner, false)
            com.example.recommendation.DiscoveryRequestCoordinator.homeScrolling(false)
        }
    }

    // Request notification permission for Android 13+ (API 33)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.finishNotificationPermissionRequest() }

    val startupLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val startupView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(viewModel, startupLifecycle, startupView, selectedTabIndex) {
      startupLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
        if (selectedTabIndex != 0) return@repeatOnLifecycle
        // Ask as soon as Home has actually drawn, rather than waiting behind
        // recommendation loading or the optional background-work idle budget.
        startupView.awaitContentDraw { }
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
      }
    }

    AutomaticAppUpdateCheck(viewModel, viewModel::beginAutomaticUpdateCheck) {
        // The manager owns IO, maintenance admission and manual-check promotion.
        com.example.updater.AppUpdateManager.checkForUpdate(context = context)
    }

    // Handle Back Press gracefully across the app
    BackHandler(enabled = catalogDetail != null || isPlayerExpanded || playerVisibility.currentState ||
        !playerVisibility.isIdle || selectedPlaylistWithTracks != null || selectedTabIndex != 0) {
        if (isPlayerExpanded || playerVisibility.currentState || !playerVisibility.isIdle) {
            isPlayerExpanded = false
        } else if (catalogDetail != null) {
            viewModel.catalogBrowser.back()
        } else if (selectedPlaylistWithTracks != null) {
            viewModel.closePlaylist()
        } else if (selectedTabIndex == 1 && viewModel.hasActiveSearch()) {
            viewModel.resetSearchNavigation()
        } else if (selectedTabIndex != 0) {
            selectTab(0)
        }
    }

    var miniPlayerBounds by remember { mutableStateOf<Rect?>(null) }
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
    val pendingCollectionPlayback by viewModel.pendingCollectionPlayback.collectAsStateWithLifecycle()
    val collectionPlaybackActions = remember(viewModel, pendingCollectionPlayback) {
        CollectionPlaybackActions(pendingCollectionPlayback, viewModel::playCollectionFromCard)
    }
    val appColors = remember(baseAppColors, isDynamicGlassEnabled) {
        baseAppColors.copy(dynamicGlassEnabled = isDynamicGlassEnabled)
    }

    // These screens own their edge spring; disable Android's competing stretch effect.
    val platformOverscroll = LocalOverscrollConfiguration.current
    CompositionLocalProvider(
        LocalCollectionPlayback provides collectionPlaybackActions,
        LocalDynamicGlassEnabled provides isDynamicGlassEnabled,
        LocalLiquidGlassProfile provides effectiveGlassProfile,
        LocalPlayerExpansionMotion provides playerMotion,
        LocalAppColors provides appColors,
        LocalLiquidGlassBackdrop provides rememberLiquidGlassBackdrop(captureVisibleRegions = true),
        LocalOverscrollConfiguration provides
            if (selectedTabIndex == 3 && selectedPlaylistWithTracks == null) platformOverscroll else null
    ) {
        Box(modifier = modifier.fillMaxSize().background(appColors.scaffoldBackground)) {
            Scaffold(
                modifier = Modifier,
                contentWindowInsets = WindowInsets(0.dp),
                containerColor = appColors.scaffoldBackground
            ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().nestedScroll(scrollDock).drawWithContent { if (!playerCoversContent) drawContent() }
                    .xtremeSharedBackdropSource(enabled = isDynamicGlassEnabled && !playerCoversContent)) {
                CompositionLocalProvider(LocalVisualEffectsActive provides (!playerCoversContent && catalogDetail == null)) {
                // If playlist detail is open, show it with a smooth animated transition
                val currentPlaylist = selectedPlaylistWithTracks
                val onHomeOpenArtist = remember(viewModel) { { artist: com.example.data.repository.CatalogArtist -> viewModel.catalogBrowser.open(com.example.data.repository.CatalogDestination.Artist(artist)) } }
                val onHomeTrackClick = remember(viewModel) { { track: com.example.data.model.MusicTrack, queue: List<com.example.data.model.MusicTrack> -> viewModel.playTrack(track, queue) } }
                val onHomeToggleFavorite = remember(viewModel) { { track: com.example.data.model.MusicTrack -> viewModel.toggleLike(track) } }
                val onHomeOpenSettings = remember(selectTab) { { selectTab(3) } }
                val onHomePlayInfiniteRadio = remember(viewModel) { { seed: com.example.data.model.MusicTrack? -> viewModel.playInfiniteRadio(seed) } }
                val onHomeAddToQueue = remember(viewModel) { { track: com.example.data.model.MusicTrack -> viewModel.addToQueue(track) } }

                run {
                    val tabContent: @Composable (Int) -> Unit = { tabIndex ->
                      tabState.SaveableStateProvider(tabIndex) {
                        when (tabIndex) {
                            0 -> HomeFeedRoute(
                                player = playerUiState,
                                isVisible = homeIsVisible,
                                listState = homeListState,
                                onOpenArtist = onHomeOpenArtist,
                                onOpenCatalog = viewModel.catalogBrowser::open,
                                onTrackClick = onHomeTrackClick,
                                onToggleFavorite = onHomeToggleFavorite,
                                onOpenSettings = onHomeOpenSettings,
                                onPlayInfiniteRadio = onHomePlayInfiniteRadio,
                                onAddToQueue = onHomeAddToQueue,
                                prepareCoreTabs = viewModel::prepareStartupTabs
                            )
                            1 -> {
                                val searchState by viewModel.searchState.collectAsStateWithLifecycle()
                                val catalogSearch by viewModel.catalogBrowser.search.collectAsStateWithLifecycle()
                                SearchScreen(
                                    searchState = searchState,
                                    isScreenActive = selectedTabIndex == 1 && !isPlayerExpanded && catalogDetail == null,
                                    listState = searchListState,
                                    catalogState = catalogSearch,
                                    onSelectType = viewModel.catalogBrowser::selectType,
                                    onLoadMore = viewModel.catalogBrowser::loadMoreSearch,
                                    onRetrySearch = { if (catalogSearch.page.nextPage != null && catalogSearch.error != null) viewModel.catalogBrowser.loadMoreSearch() else viewModel.catalogBrowser.retrySearch() },
                                    onOpenCatalog = viewModel.catalogBrowser::open,
                                    onOpenAlbumByName = viewModel::openAlbumByName,
                                    onRememberSearch = viewModel::rememberSearch,
                                    onRemoveRecentSearch = viewModel::removeRecentSearch,
                                    onClearRecentSearches = viewModel::clearRecentSearches,
                                    currentPlayingTrackId = currentPlayingTrackId,
                                    isPlaying = playerUiState.isPlaying,
                                    isLoading = playerUiState.isLoading,
                                    onQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                                    onSelectGenre = { g -> viewModel.selectGenre(g) },
                                    onExploreVisibilityChanged = viewModel::setExploreVisible,
                                    onSelectExploreCategory = { category, force -> viewModel.selectExploreCategory(category, force) },
                                    onSelectSource = { s -> viewModel.selectSource(s) },
                                    onTrackClick = { track, queue -> viewModel.playFromSearch(track, queue) },
                                    onToggleFavorite = { track -> viewModel.toggleLike(track) }
                                )
                            }
                            2 -> {
                                val favoriteCount by viewModel.favoriteTrackCount.collectAsStateWithLifecycle()
                                val playlists by viewModel.playlists.collectAsStateWithLifecycle()
                                LibraryScreen(
                                    favoriteTracks = emptyList(),
                                    favoriteTracksState = viewModel.favoriteTracks,
                                    favoriteCount = favoriteCount,
                                    onPlayLikedSongs = { viewModel.playLikedSongs() },
                                    onPrepareDownloads = { store -> viewModel.playDownloads(store) },
                                    onPlayDownloadedTrack = { track, queue -> viewModel.playPlaylistTrack(track, queue) },
                                    listState = libraryListState,
                                    playlists = playlists,
                                    currentPlayingTrackId = currentPlayingTrackId,
                                    isDarkMode = isDarkMode,
                                    onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                    onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                    onOpenPlaylist = { id -> viewModel.openPlaylist(id) },
                                    onCreatePlaylistClick = { isCreatePlaylistOpen = true },
                                    onOpenArtist = { viewModel.catalogBrowser.open(com.example.data.repository.CatalogDestination.Artist(it)) },
                                    onImportPlaylistClick = { viewModel.openImportDialog() }
                                )
                            }
                            3 -> {
                                val effectsState by viewModel.effectsState.collectAsStateWithLifecycle()
                                val themeSelection by viewModel.themeSelection.collectAsStateWithLifecycle()
                                val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
                                SettingsScreen(
                                    effectsState = effectsState,
                                    isDarkMode = isDarkMode,
                                    themeMode = themeSelection.mode,
                                    userProfile = userProfile,
                                    isDynamicGlassEnabled = isDynamicGlassEnabled,
                                    onToggleDynamicGlass = { viewModel.setDynamicGlassEnabled(it) },
                                    liquidGlassProfile = liquidGlassProfileSetting,
                                    onSelectLiquidGlassProfile = { viewModel.setLiquidGlassProfile(it) },
                                    onSubpageStateChanged = { isOpen -> isSettingsSubpageOpen = isOpen },
                                    onNavigateBackToHome = { selectTab(0) },
                                    onUpdateProfile = { updated -> viewModel.updateUserProfile(updated) },
                                    onSelectThemeMode = { mode -> viewModel.setThemeMode(mode) },
                                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                                    onCrystalClarityToggle = { enabled -> viewModel.setCrystalClarityEnabled(enabled) },
                                    onToggleEqualizer = { enabled -> viewModel.setEqualizerEnabled(enabled) },
                                    onOpenEqualizer = { isEqualizerOpen = true },
                                    onSelectPreset = { preset -> viewModel.setEqualizerPreset(preset) },
                                    customThemeState = themeSelection.palette,
                                    onUpdateCustomThemeState = { updatedState -> viewModel.updateCustomThemeState(updatedState) },
                                    onApplyPreset = { preset -> viewModel.applyThemePreset(preset) },
                                    onTextScaleChanged = { index -> viewModel.setTextScaleIndex(index) },
                                    onUiScaleChanged = { index -> viewModel.setUiScaleIndex(index) }
                                )
                            }
                        }
                      }
                    }
                    val primaryCovered = playerCoversContent || catalogDetail != null || currentPlaylist != null
                    RetainedPrimaryTabHost(state = tabPagerState, transition = tabTransition,
                        covered = primaryCovered, inputBlocked = primaryCovered || selectedTabIndex == 3) { tabIndex -> tabContent(tabIndex) }
                    SettingsNavigationOverlay(tabTransition) { tabContent(3) }

                }

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
                            onRemoveEntry = { track -> viewModel.removePlaylistEntry(playlist.playlist.playlistId, track) },
                            onRemoveTrack = { trackId ->
                                viewModel.removeTrackFromPlaylist(playlist.playlist.playlistId, trackId)
                            },
                            onRenamePlaylist = { newTitle ->
                                viewModel.renamePlaylist(playlist.playlist.playlistId, newTitle)
                            }
                        )
                    }
                }

                }
                CompositionLocalProvider(LocalVisualEffectsActive provides !playerCoversContent) {
                catalogDetail?.let { detail ->
                    androidx.compose.runtime.key(detail.destination.key) {
                        val detailPlayer by viewModel.playbackPresentationState.collectAsStateWithLifecycle()
                        com.example.ui.screens.CatalogDetailScreen(
                            state = detail, player = detailPlayer,
                            onBack = viewModel.catalogBrowser::back,
                            onMore = viewModel.catalogBrowser::loadMoreDetail,
                            onPlay = { track, queue ->
                                if (detail.destination is com.example.data.repository.CatalogDestination.Album ||
                                    detail.destination is com.example.data.repository.CatalogDestination.Playlist)
                                    viewModel.playCatalogCollection(track, detail)
                                else viewModel.playTrack(track, queue)
                            },
                            onFavorite = { viewModel.toggleLike(it) },
                            onScroll = viewModel.catalogBrowser::rememberScroll,
                            handleBack = !isPlayerExpanded,
                            onOpenAlbum = { viewModel.catalogBrowser.open(com.example.data.repository.CatalogDestination.Album(it)) },
                            onOpenArtist = { viewModel.catalogBrowser.open(com.example.data.repository.CatalogDestination.Artist(it)) }
                        )
                    }
                }

                }
                } // Only page content belongs to the shared backdrop; overlays never sample themselves.

                // Floating Overlaid Container (MiniPlayer + Floating Pill Bottom Bar)
                // Merges with the tab screen seamlessly and floats elevated above the content
                val onDockTabSelected: (Int) -> Unit = remember(selectTab, viewModel, scrollDock, context) {
                    { index ->
                        AppHaptics.performTap(context)
                        scrollDock.expand()
                        isSettingsSubpageOpen = false
                        selectTab(index)
                        viewModel.catalogBrowser.close()
                        viewModel.closePlaylist()
                    }
                }
                val onMiniPlayPause = remember(viewModel) { { viewModel.togglePlayPause() } }
                val onMiniSkipNext = remember(viewModel) { { viewModel.skipNext() } }
                val onMiniSkipPrevious = remember(viewModel) { { viewModel.skipPrevious() } }
                val onMiniClick = remember { { isPlayerExpanded = true } }
                val miniPositionProvider = remember(viewModel) { { viewModel.currentPosition } }

                AdaptiveBottomControls(
                    landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                    collapseProgress = dockProgress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                    miniPlayer = {
                    // Retain the mini surface through the handoff, then stop its
                    // hidden marquee/effects while the expanded player is idle.
                    val isMiniPlayerVisible = playerVisualTrack != null && !isSettingsSubpageOpen && !isEqualizerOpen
                    val playerHandoff = isPlayerExpanded || playerVisibility.currentState || !playerVisibility.isIdle

                    AnimatedVisibility(
                        visible = isMiniPlayerVisible,
                        enter = if (playerHandoff) EnterTransition.None else slideInVertically(
                            animationSpec = tween(170, easing = FastOutSlowInEasing),
                            initialOffsetY = { it }
                        ) + fadeIn(animationSpec = tween(140, easing = LinearOutSlowInEasing)),
                        exit = if (playerHandoff) ExitTransition.None else slideOutVertically(
                            animationSpec = tween(140, easing = FastOutSlowInEasing),
                            targetOffsetY = { it }
                        ) + fadeOut(animationSpec = tween(100, easing = FastOutLinearInEasing)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .onGloballyPositioned { coordinates ->
                                if (coordinates.size.width > 0 && coordinates.size.height > 0)
                                    miniPlayerBounds = coordinates.boundsInRoot()
                            }
                            .then(if (isPlayerExpanded) Modifier.clearAndSetSemantics { } else Modifier)
                    ) {
                        CompositionLocalProvider(LocalVisualEffectsActive provides
                            (LocalVisualEffectsActive.current && !playerCoversContent)) {
                            MiniPlayer(
                                modifier = Modifier.playerMiniTransition(playerMotion),
                                uiState = playerUiState.copy(currentTrack = playerVisualTrack),
                                onPlayPauseClick = onMiniPlayPause,
                                onSkipNext = onMiniSkipNext,
                                onSkipPrevious = onMiniSkipPrevious,
                                onClick = onMiniClick,
                                currentPositionProvider = miniPositionProvider,
                                compactProgress = dockProgress,
                                compact = scrollDock.isCompact
                            )
                        }
                    }

                    }, dock = {
                        val playerHandoff = isPlayerExpanded || playerVisibility.currentState || !playerVisibility.isIdle
                        AnimatedVisibility(
                            visible = !isSettingsSubpageOpen,
                            enter = if (playerHandoff) EnterTransition.None else fadeIn(animationSpec = tween(180)),
                            exit = if (playerHandoff) ExitTransition.None else fadeOut(animationSpec = tween(140)),
                            modifier = Modifier.playerDockTransition(playerMotion)
                                .then(if (playerHandoff) Modifier.clearAndSetSemantics { } else Modifier)
                        ) {
                            CompositionLocalProvider(LocalVisualEffectsActive provides
                                (LocalVisualEffectsActive.current && !playerCoversContent)) {
                                FloatingPillBottomBar(
                                    selectedTabIndex = selectedTabIndex,
                                    onTabSelected = onDockTabSelected,
                                    compactProgress = dockProgress,
                                    compact = scrollDock.isCompact
                                )
                            }
                        }
                    }
                )

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

        // One retained scene and measured shared elements follow the same path in both directions.
        PlayerExpansionOverlay(
            visibleState = playerVisibility,
            motion = playerMotion,
            miniPlayerBounds = { miniPlayerBounds },
            onReopen = { isPlayerExpanded = true },
            modifier = Modifier.fillMaxSize()
        ) {
            val currentLyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
            val availableAudioDevices by viewModel.availableAudioDevices.collectAsStateWithLifecycle()
            val expandedPlayer by viewModel.playbackPresentationState.collectAsStateWithLifecycle()
            val onExpandedMinimize = remember { { isPlayerExpanded = false } }
            val onExpandedPlayPause = remember(viewModel) { { viewModel.togglePlayPause() } }
            val onExpandedSeekTo = remember(viewModel) { { pos: Long -> viewModel.seekTo(pos) } }
            val onExpandedSkipNext = remember(viewModel) { { viewModel.skipNext() } }
            val onExpandedSkipPrevious = remember(viewModel) { { viewModel.skipPrevious() } }
            val onExpandedToggleShuffle = remember(viewModel) { { viewModel.toggleShuffle() } }
            val onExpandedToggleRepeat = remember(viewModel) { { viewModel.toggleRepeat() } }
            val onExpandedToggleFavorite = remember(viewModel) { { track: com.example.data.model.MusicTrack -> viewModel.toggleLike(track) } }
            val onExpandedOpenQueue = remember { { isQueueOpen = true } }
            val onExpandedOpenEqualizer = remember { { isEqualizerOpen = true } }
            val onExpandedAddToPlaylist = remember { { track: com.example.data.model.MusicTrack -> trackToAddToPlaylist = track } }
            val onExpandedAddToQueue = remember(viewModel) { { track: com.example.data.model.MusicTrack -> viewModel.addToQueue(track) } }
            val onExpandedRetryLyrics = remember(viewModel) { { viewModel.retryLyrics() } }
            val onExpandedSelectAudioDevice = remember(viewModel) { { deviceId: Int -> viewModel.selectAudioOutputDevice(deviceId) } }
            val expandedPositionProvider = remember(viewModel) { { viewModel.currentPosition } }
            val expandedTrackDurationProvider = remember(viewModel) { { viewModel.trackDuration.value } }
            val expandedBufferedPositionProvider = remember(viewModel) { { viewModel.bufferedPosition.value } }

            ExpandedPlayerScreen(
                uiState = expandedPlayer.copy(currentTrack = playerVisualTrack ?: expandedPlayer.currentTrack),
                onMinimize = onExpandedMinimize,
                onPlayPause = onExpandedPlayPause,
                onSeekTo = onExpandedSeekTo,
                onSkipNext = onExpandedSkipNext,
                onSkipPrevious = onExpandedSkipPrevious,
                onToggleShuffle = onExpandedToggleShuffle,
                onToggleRepeat = onExpandedToggleRepeat,
                onToggleFavorite = onExpandedToggleFavorite,
                onOpenQueue = onExpandedOpenQueue,
                onOpenEqualizer = onExpandedOpenEqualizer,
                onAddToPlaylist = onExpandedAddToPlaylist,
                onAddToQueue = onExpandedAddToQueue,
                lyrics = currentLyrics,
                onRetryLyrics = onExpandedRetryLyrics,
                onLyricsVisibilityChanged = viewModel::setLyricsVisible,
                isScreenVisible = isPlayerExpanded,
                availableAudioDevices = availableAudioDevices,
                onSelectAudioDevice = onExpandedSelectAudioDevice,
                playbackPositionFlow = viewModel.playbackPosition,
                currentPositionProvider = expandedPositionProvider,
                trackDurationProvider = expandedTrackDurationProvider,
                bufferedPositionProvider = expandedBufferedPositionProvider
            )
        }

        // QUEUE BOTTOM SHEET
        if (isQueueOpen) {
          PlayerPresentationHost(viewModel) { playerUiState ->
            QueueBottomSheet(
                queue = playerUiState.queue,
                currentTrack = playerUiState.currentTrack,
                onTrackClick = viewModel::playQueuedTrack,
                onMoveTrack = { from, to -> viewModel.reorderQueue(from, to) },
                onDismiss = { isQueueOpen = false },
                isAutoplayEnabled = playerUiState.isAutoplayEnabled,
                nextRecommendedTrack = playerUiState.nextRecommendedTrack,
                onToggleAutoplay = { viewModel.toggleAutoplay() }
            )
          }
        }

        // EQUALIZER DIALOG
        if (isEqualizerOpen) {
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
        trackToAddToPlaylist?.let { track ->
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
        if (isCreatePlaylistOpen) {
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
        if (importDialogOpen) ImportDialogHost(viewModel, isDarkMode)
    }
}
}

@Composable
private fun PlayerPresentationHost(viewModel: PlayerViewModel,
    content: @Composable (com.example.playback.PlayerUiState) -> Unit) {
    val state by viewModel.playbackPresentationState.collectAsStateWithLifecycle()
    content(state)
}

private val DEFAULT_BOTTOM_BAR_TABS = listOf(
    AnimatedBottomBarItem(0, "Home", Icons.Default.Home, Icons.Outlined.Home),
    AnimatedBottomBarItem(1, "Search", Icons.Default.Search, Icons.Outlined.Search),
    AnimatedBottomBarItem(2, "Library", Icons.Default.LibraryMusic, Icons.Outlined.LibraryMusic)
)

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
    ambientGlowColor: Color = LocalAppColors.current.primaryAccent,
    compactProgress: State<Float>? = null,
    compact: Boolean = false
) {
    AnimatedBottomBar(
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        items = DEFAULT_BOTTOM_BAR_TABS,
        modifier = modifier,
        ambientGlowColor = ambientGlowColor,
        compactProgress = compactProgress,
        compact = compact,
        compactTabIndex = 0,
        compactCompanionTabIndex = 1
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


/**
 * Re-evaluates AUTO rendering capability when the runtime conditions that influence it change.
 * Explicit user-selected profiles remain stable and do not register unnecessary observers.
 */
@Composable
private fun rememberEffectiveLiquidGlassProfile(
    setting: com.example.performance.LiquidGlassProfileSetting
): com.example.performance.LiquidGlassProfile {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var capabilityRevision by remember(context, lifecycleOwner) { mutableIntStateOf(0) }

    DisposableEffect(context, lifecycleOwner, setting) {
        if (setting != com.example.performance.LiquidGlassProfileSetting.AUTO) {
            onDispose { }
        } else {
            val appContext = context.applicationContext
            val powerReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: android.content.Context?, intent: android.content.Intent?) {
                    capabilityRevision++
                }
            }
            var receiverRegistered = false
            try {
                ContextCompat.registerReceiver(
                    appContext,
                    powerReceiver,
                    android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            } catch (_: SecurityException) {
                // Lifecycle and display callbacks still trigger a refresh if broadcasts are unavailable.
            }

            val displayManager = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) { capabilityRevision++ }
                override fun onDisplayRemoved(displayId: Int) { capabilityRevision++ }
                override fun onDisplayChanged(displayId: Int) { capabilityRevision++ }
            }
            displayManager?.registerDisplayListener(
                displayListener,
                android.os.Handler(android.os.Looper.getMainLooper())
            )

            val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) capabilityRevision++
            }
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                if (receiverRegistered) appContext.unregisterReceiver(powerReceiver)
                displayManager?.unregisterDisplayListener(displayListener)
            }
        }
    }

    return remember(context, setting, capabilityRevision) {
        com.example.performance.LiquidGlassDeviceCapability.resolveEffectiveProfile(context, setting)
    }
}
