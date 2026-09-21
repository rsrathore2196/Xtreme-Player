package com.example.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.util.AppHaptics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Application
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.XtremeMusicApp
import com.example.data.model.MusicTrack
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.PlayerViewModel

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

    Box(modifier = modifier.fillMaxSize().background(appColors.scaffoldBackground)) {
        Scaffold(
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // Floating MiniPlayer (Animated smoothly in and out with 120Hz high refresh rate response)
                    // Automatically hides when a specific settings option/subpage is open in the settings tab
                    val isMiniPlayerVisible = playerUiState.currentTrack != null &&
                        !isPlayerExpanded &&
                        !(selectedTabIndex == 3 && isSettingsSubpageOpen)

                    AnimatedVisibility(
                        visible = isMiniPlayerVisible,
                        enter = slideInVertically(
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = 650f
                            ),
                            initialOffsetY = { it }
                        ) + fadeIn(animationSpec = tween(150, easing = LinearOutSlowInEasing)) + expandVertically(
                            animationSpec = spring(dampingRatio = 0.84f, stiffness = 650f)
                        ),
                        exit = slideOutVertically(
                            animationSpec = spring(
                                dampingRatio = 0.84f,
                                stiffness = 650f
                            ),
                            targetOffsetY = { it }
                        ) + fadeOut(animationSpec = tween(110, easing = FastOutLinearInEasing)) + shrinkVertically(
                            animationSpec = spring(dampingRatio = 0.84f, stiffness = 650f)
                        ),
                        modifier = Modifier.graphicsLayer { clip = false }
                    ) {
                        MiniPlayer(
                            uiState = playerUiState,
                            onPlayPauseClick = { viewModel.togglePlayPause() },
                            onSkipNext = { viewModel.skipNext() },
                            onSkipPrevious = { viewModel.skipPrevious() },
                            onClick = { isPlayerExpanded = true }
                        )
                    }

                    // Bottom Navigation Bar
                    val tabItemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = appColors.bottomBarSelectedIcon,
                        selectedTextColor = appColors.bottomBarSelectedIcon,
                        unselectedIconColor = appColors.bottomBarUnselectedIcon,
                        unselectedTextColor = appColors.bottomBarUnselectedIcon,
                        indicatorColor = appColors.bottomBarIndicator
                    )

                    val homeScale by animateFloatAsState(
                        targetValue = if (selectedTabIndex == 0) 1.12f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = 850f),
                        label = "tab_home_scale"
                    )
                    val searchScale by animateFloatAsState(
                        targetValue = if (selectedTabIndex == 1) 1.12f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = 850f),
                        label = "tab_search_scale"
                    )
                    val libraryScale by animateFloatAsState(
                        targetValue = if (selectedTabIndex == 2) 1.12f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = 850f),
                        label = "tab_library_scale"
                    )
                    val settingsScale by animateFloatAsState(
                        targetValue = if (selectedTabIndex == 3) 1.12f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = 850f),
                        label = "tab_settings_scale"
                    )

                    NavigationBar(
                        containerColor = appColors.bottomBarBackground,
                        tonalElevation = 8.dp,
                        modifier = Modifier
                            .testTag("bottom_navigation_bar")
                            .graphicsLayer { clip = false }
                    ) {
                        NavigationBarItem(
                            selected = selectedTabIndex == 0,
                            onClick = {
                                AppHaptics.performTap(context)
                                selectedTabIndex = 0
                                viewModel.closePlaylist()
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 0) Icons.Default.Home else Icons.Outlined.Home,
                                    contentDescription = "Home",
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = homeScale
                                        scaleY = homeScale
                                    }
                                )
                            },
                            label = {
                                Text(
                                    text = "Home",
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = tabItemColors
                        )

                        NavigationBarItem(
                            selected = selectedTabIndex == 1,
                            onClick = {
                                AppHaptics.performTap(context)
                                selectedTabIndex = 1
                                viewModel.closePlaylist()
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 1) Icons.Default.Search else Icons.Outlined.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = searchScale
                                        scaleY = searchScale
                                    }
                                )
                            },
                            label = {
                                Text(
                                    text = "Search",
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = tabItemColors
                        )

                        NavigationBarItem(
                            selected = selectedTabIndex == 2,
                            onClick = {
                                AppHaptics.performTap(context)
                                selectedTabIndex = 2
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 2) Icons.Default.LibraryMusic else Icons.Outlined.LibraryMusic,
                                    contentDescription = "Library",
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = libraryScale
                                        scaleY = libraryScale
                                    }
                                )
                            },
                            label = {
                                Text(
                                    text = "Library",
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = tabItemColors
                        )

                        NavigationBarItem(
                            selected = selectedTabIndex == 3,
                            onClick = {
                                AppHaptics.performTap(context)
                                selectedTabIndex = 3
                                viewModel.closePlaylist()
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 3) Icons.Default.Settings else Icons.Outlined.Settings,
                                    contentDescription = "Settings",
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = settingsScale
                                        scaleY = settingsScale
                                    }
                                )
                            },
                            label = {
                                Text(
                                    text = "Settings",
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTabIndex == 3) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = tabItemColors
                        )
                    }
                }
            },
            contentWindowInsets = WindowInsets(0.dp),
            containerColor = appColors.scaffoldBackground
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // If playlist detail is open, show it with a smooth animated transition
                val currentPlaylist = selectedPlaylistWithTracks
                AnimatedVisibility(
                    visible = currentPlaylist != null,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = spring(dampingRatio = 0.84f, stiffness = 650f)
                    ) + fadeIn(animationSpec = tween(170, easing = LinearOutSlowInEasing)),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 650f)
                    ) + fadeOut(animationSpec = tween(130, easing = FastOutLinearInEasing)),
                    modifier = Modifier.graphicsLayer { clip = false }
                ) {
                    currentPlaylist?.let { playlist ->
                        PlaylistDetailScreen(
                            playlistWithTracks = playlist,
                            playerUiState = playerUiState,
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
                            // High-performance 120Hz zero-jank crossfade transition
                            // Eliminates layout re-measurement and frame drops across screens
                            val enterTransition = fadeIn(
                                animationSpec = tween(120, easing = LinearOutSlowInEasing)
                            )
                            val exitTransition = fadeOut(
                                animationSpec = tween(80, easing = FastOutLinearInEasing)
                            )

                            enterTransition.togetherWith(exitTransition)
                        },
                        label = "tab_navigation_transition",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // Hardware acceleration compositing layer for 120Hz rendering
                                clip = false
                            }
                    ) { tabIndex ->
                        when (tabIndex) {
                            0 -> HomeScreen(
                                homeViewModel = homeViewModel,
                                playerUiState = playerUiState,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) }
                            )
                            1 -> SearchScreen(
                                searchState = searchState,
                                playerUiState = playerUiState,
                                onQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                                onSelectGenre = { g -> viewModel.selectGenre(g) },
                                onSelectSource = { s -> viewModel.selectSource(s) },
                                onTrackClick = { track, queue -> viewModel.playFromSearch(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) }
                            )
                            2 -> LibraryScreen(
                                favoriteTracks = favoriteTracks,
                                playlists = playlists,
                                playerUiState = playerUiState,
                                isDarkMode = isDarkMode,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                onOpenPlaylist = { id -> viewModel.openPlaylist(id) },
                                onCreatePlaylistClick = { isCreatePlaylistOpen = true },
                                onImportPlaylistClick = { viewModel.openImportDialog() }
                            )
                            3 -> SettingsScreen(
                                playerUiState = playerUiState,
                                effectsState = effectsState,
                                isDarkMode = isDarkMode,
                                themeMode = themeMode,
                                userProfile = userProfile,
                                onSubpageStateChanged = { isOpen -> isSettingsSubpageOpen = isOpen },
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
            }
        }

        // FULL SCREEN EXPANDED PLAYER (Animates vertically with smooth 120Hz high-frame-rate spring)
        AnimatedVisibility(
            visible = isPlayerExpanded && playerUiState.currentTrack != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.84f,
                    stiffness = 620f
                )
            ) + fadeIn(
                animationSpec = tween(170, easing = LinearOutSlowInEasing)
            ) + scaleIn(
                initialScale = 0.96f,
                animationSpec = spring(
                    dampingRatio = 0.84f,
                    stiffness = 620f
                )
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.84f,
                    stiffness = 620f
                )
            ) + fadeOut(
                animationSpec = tween(130, easing = FastOutLinearInEasing)
            ) + scaleOut(
                targetScale = 0.97f,
                animationSpec = spring(
                    dampingRatio = 0.84f,
                    stiffness = 620f
                )
            ),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { clip = true }
        ) {
            ExpandedPlayerScreen(
                uiState = playerUiState,
                onMinimize = { isPlayerExpanded = false },
                onPlayPause = { viewModel.togglePlayPause() },
                onSeekTo = { pos -> viewModel.seekTo(pos) },
                onSkipNext = { viewModel.skipNext() },
                onSkipPrevious = { viewModel.skipPrevious() },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onToggleRepeat = { viewModel.toggleRepeat() },
                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                onOpenQueue = { isQueueOpen = true },
                onAddToPlaylist = { track -> trackToAddToPlaylist = track },
                lyrics = currentLyrics,
                onRetryLyrics = { viewModel.retryLyrics() },
                availableAudioDevices = availableAudioDevices,
                onSelectAudioDevice = { deviceId -> viewModel.selectAudioOutputDevice(deviceId) },
                isDark = isDarkMode
            )
        }

        // UP-NEXT QUEUE BOTTOM SHEET
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

        // EQUALIZER MODAL
        if (isEqualizerOpen) {
            EqualizerDialog(
                effectsState = effectsState,
                onEnableChanged = { viewModel.setEqualizerEnabled(it) },
                onPresetSelected = { preset -> viewModel.setEqualizerPreset(preset) },
                onBandLevelChanged = { band, level -> viewModel.setBandLevel(band, level) },
                onBassBoostChanged = { viewModel.setBassBoost(it) },
                onVirtualizerChanged = { viewModel.setVirtualizer(it) },
                onReset = { viewModel.resetEqualizer() },
                onDismiss = { isEqualizerOpen = false }
            )
        }

        // CREATE PLAYLIST DIALOG
        if (isCreatePlaylistOpen) {
            CreatePlaylistDialog(
                isDarkMode = isDarkMode,
                onDismiss = { isCreatePlaylistOpen = false },
                onConfirm = { title, desc ->
                    viewModel.createPlaylist(title, desc)
                    isCreatePlaylistOpen = false
                }
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
