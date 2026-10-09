package com.example.ui.components

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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.XtremeMusicApp
import com.example.data.model.MusicTrack
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LocalAppColors
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.PlayerViewModel
import com.example.util.AppHaptics

enum class NavigationTab(val title: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library"),
    SETTINGS("Settings")
}

@Composable
fun MainNavigationScaffold(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val playerUiState by viewModel.playerUiState.collectAsState()
    val selectedQuality by viewModel.selectedQuality.collectAsState()
    val currentPlayingTrackId by viewModel.currentPlayingTrackId.collectAsState()
    val catalogTracks by viewModel.catalogTracks.collectAsState()
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val selectedPlaylistWithTracks by viewModel.selectedPlaylistTracks.collectAsState()
    val effectsState by viewModel.effectsState.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val customThemeState by viewModel.customThemeState.collectAsState()
    val homeShelves by viewModel.homeShelves.collectAsState()
    val currentLyrics by viewModel.currentLyrics.collectAsState()
    val availableAudioDevices by viewModel.availableAudioDevices.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val isDynamicGlassEnabled by viewModel.isDynamicGlassEnabled.collectAsState()

    val context = LocalContext.current
    val app = context.applicationContext as XtremeMusicApp
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.provideFactory(
            repository = app.repository,
            playbackManager = app.playbackManager,
            application = app
        )
    )

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var isQueueOpen by remember { mutableStateOf(false) }
    var isEqualizerOpen by remember { mutableStateOf(false) }
    var isCreatePlaylistOpen by remember { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<MusicTrack?>(null) }
    var isSettingsSubpageOpen by remember { mutableStateOf(false) }

    // Request notification permission for Android 13+ (API 33)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        com.example.updater.AppUpdateManager.checkForUpdate(context = context)
    }

    // Handle Back Press gracefully across the app
    val isSearchSubPage = selectedTabIndex == 1 && (searchState.selectedGenre != "All" || searchState.query.isNotBlank())
    BackHandler(enabled = isPlayerExpanded || selectedPlaylistWithTracks != null || isSearchSubPage || selectedTabIndex != 0) {
        if (isPlayerExpanded) {
            isPlayerExpanded = false
        } else if (selectedPlaylistWithTracks != null) {
            viewModel.closePlaylist()
        } else if (isSearchSubPage) {
            viewModel.selectGenre("All")
            if (searchState.query.isNotBlank()) {
                viewModel.onSearchQueryChange("")
            }
        } else if (selectedTabIndex != 0) {
            selectedTabIndex = 0
        }
    }

    val appColors = LocalAppColors.current

    CompositionLocalProvider(LocalDynamicGlassEnabled provides isDynamicGlassEnabled) {
        Box(modifier = modifier.fillMaxSize().background(appColors.scaffoldBackground)) {
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                containerColor = appColors.scaffoldBackground
            ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
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
                        PlaylistDetailScreen(
                            playlistWithTracks = playlist,
                            playerUiState = playerUiState,
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

                if (currentPlaylist == null) {
                    AnimatedContent(
                        targetState = selectedTabIndex,
                        transitionSpec = {
                            if (targetState == 3) {
                                // Zoom in fast snappy transition to open settings tab with kinetic spring
                                (scaleIn(
                                    initialScale = 0.88f,
                                    animationSpec = spring(dampingRatio = 0.80f, stiffness = 850f)
                                ) + fadeIn(
                                    animationSpec = tween(140, easing = LinearOutSlowInEasing)
                                )).togetherWith(
                                    scaleOut(
                                        targetScale = 1.04f,
                                        animationSpec = tween(130, easing = FastOutLinearInEasing)
                                    ) + fadeOut(
                                        animationSpec = tween(100, easing = FastOutLinearInEasing)
                                    )
                                )
                            } else if (initialState == 3) {
                                // Modern, fast, and smooth zoom out closing animation to return from settings
                                (scaleIn(
                                    initialScale = 1.04f,
                                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 850f)
                                ) + fadeIn(
                                    animationSpec = tween(140, easing = LinearOutSlowInEasing)
                                )).togetherWith(
                                    scaleOut(
                                        targetScale = 0.88f,
                                        animationSpec = tween(130, easing = FastOutLinearInEasing)
                                    ) + fadeOut(
                                        animationSpec = tween(100, easing = FastOutLinearInEasing)
                                    )
                                )
                            } else if (targetState > initialState) {
                                // Kinetic directional spring slide & scale forward between tabs (120 FPS tuned)
                                (slideInHorizontally(
                                    initialOffsetX = { (it * 0.12f).toInt() },
                                    animationSpec = spring(dampingRatio = 0.84f, stiffness = 950f)
                                ) + fadeIn(
                                    animationSpec = tween(130, easing = LinearOutSlowInEasing)
                                )).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { (-it * 0.12f).toInt() },
                                        animationSpec = spring(dampingRatio = 0.84f, stiffness = 950f)
                                    ) + fadeOut(
                                        animationSpec = tween(90, easing = FastOutLinearInEasing)
                                    )
                                )
                            } else {
                                // Kinetic directional spring slide & scale backward between tabs (120 FPS tuned)
                                (slideInHorizontally(
                                    initialOffsetX = { (-it * 0.12f).toInt() },
                                    animationSpec = spring(dampingRatio = 0.84f, stiffness = 950f)
                                ) + fadeIn(
                                    animationSpec = tween(130, easing = LinearOutSlowInEasing)
                                )).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { (it * 0.12f).toInt() },
                                        animationSpec = spring(dampingRatio = 0.84f, stiffness = 950f)
                                    ) + fadeOut(
                                        animationSpec = tween(90, easing = FastOutLinearInEasing)
                                    )
                                )
                            }
                        },
                        label = "tab_navigation_transition",
                        modifier = Modifier
                            .fillMaxSize()
                            .xtremeSharedBackdropSource(enabled = isDynamicGlassEnabled)
                    ) { tabIndex ->
                        when (tabIndex) {
                            0 -> HomeScreen(
                                homeViewModel = homeViewModel,
                                currentPlayingTrackId = currentPlayingTrackId,
                                isPlaying = playerUiState.isPlaying,
                                isAutoplayEnabled = playerUiState.isAutoplayEnabled,
                                currentTrack = playerUiState.currentTrack,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                onOpenSettings = { selectedTabIndex = 3 },
                                onPlayInfiniteRadio = { seed -> viewModel.playInfiniteRadio(seed) },
                                onAddToQueue = { track -> viewModel.addToQueue(track) }
                            )
                            1 -> SearchScreen(
                                searchState = searchState,
                                currentPlayingTrackId = currentPlayingTrackId,
                                isPlaying = playerUiState.isPlaying,
                                isLoading = playerUiState.isLoading,
                                onQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                                onSelectGenre = { g -> viewModel.selectGenre(g) },
                                onSelectSource = { s -> viewModel.selectSource(s) },
                                onTrackClick = { track, queue -> viewModel.playFromSearch(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) }
                            )
                            2 -> LibraryScreen(
                                favoriteTracks = favoriteTracks,
                                playlists = playlists,
                                currentPlayingTrackId = currentPlayingTrackId,
                                isDarkMode = isDarkMode,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                onOpenPlaylist = { id -> viewModel.openPlaylist(id) },
                                onCreatePlaylistClick = { isCreatePlaylistOpen = true },
                                onImportPlaylistClick = { viewModel.openImportDialog() }
                            )
                            3 -> SettingsScreen(
                                selectedQuality = selectedQuality,
                                effectsState = effectsState,
                                isDarkMode = isDarkMode,
                                themeMode = themeMode,
                                userProfile = userProfile,
                                isDynamicGlassEnabled = isDynamicGlassEnabled,
                                onToggleDynamicGlass = { viewModel.setDynamicGlassEnabled(it) },
                                onSubpageStateChanged = { isOpen -> isSettingsSubpageOpen = isOpen },
                                onNavigateBackToHome = { selectedTabIndex = 0 },
                                onUpdateProfile = { updated -> viewModel.updateUserProfile(updated) },
                                onSelectThemeMode = { mode -> viewModel.setThemeMode(mode) },
                                onToggleDarkMode = { viewModel.toggleDarkMode() },
                                onAudioQualitySelected = { quality -> viewModel.setAudioQuality(quality) },
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
                                selectedTabIndex = index
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

        // FULL SCREEN EXPANDED PLAYER (Smooth, responsive push/pop spring transitions from LastWave-Native)
        AnimatedVisibility(
            visible = isPlayerExpanded && playerUiState.currentTrack != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = LiquidGlass.PushPopIntOffsetSpring
            ) + scaleIn(
                initialScale = 0.92f,
                animationSpec = LiquidGlass.PushPopSpring
            ) + fadeIn(animationSpec = tween(190)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = LiquidGlass.PushPopIntOffsetSpring
            ) + scaleOut(
                targetScale = 0.92f,
                animationSpec = LiquidGlass.PushPopSpring
            ) + fadeOut(animationSpec = tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            ExpandedPlayerScreen(
                uiState = playerUiState,
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
                availableAudioDevices = availableAudioDevices,
                onSelectAudioDevice = { deviceId -> viewModel.selectAudioOutputDevice(deviceId) },
                currentPositionProvider = { viewModel.currentPosition },
                trackDurationProvider = { viewModel.trackDuration.value },
                bufferedPositionProvider = { viewModel.bufferedPosition.value }
            )
        }

        // QUEUE BOTTOM SHEET
        if (isQueueOpen) {
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

        // EQUALIZER DIALOG
        if (isEqualizerOpen) {
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
        if (importState.isDialogOpen) {
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
                onOpenPlaylist = { playlistId -> viewModel.openPlaylist(playlistId) }
            )
        }
    }
}
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
