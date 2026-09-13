package com.example.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val homeShelves by viewModel.homeShelves.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var isQueueOpen by remember { mutableStateOf(false) }
    var isEqualizerOpen by remember { mutableStateOf(false) }
    var isCreatePlaylistOpen by remember { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<MusicTrack?>(null) }

    // Request notification permission for Android 13+ (API 33)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Handle Back Press gracefully
    BackHandler(enabled = isPlayerExpanded || selectedPlaylistWithTracks != null) {
        if (isPlayerExpanded) {
            isPlayerExpanded = false
        } else if (selectedPlaylistWithTracks != null) {
            viewModel.closePlaylist()
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
                    // Floating MiniPlayer (Animated smoothly in and out)
                    AnimatedVisibility(
                        visible = playerUiState.currentTrack != null && !isPlayerExpanded,
                        enter = slideInVertically(
                            animationSpec = spring(
                                dampingRatio = 0.8f,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            initialOffsetY = { it }
                        ) + fadeIn(animationSpec = tween(260)) + expandVertically(),
                        exit = slideOutVertically(
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            targetOffsetY = { it }
                        ) + fadeOut(animationSpec = tween(200)) + shrinkVertically()
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

                    NavigationBar(
                        containerColor = appColors.bottomBarBackground,
                        tonalElevation = 8.dp,
                        modifier = Modifier.testTag("bottom_navigation_bar")
                    ) {
                        NavigationBarItem(
                            selected = selectedTabIndex == 0,
                            onClick = {
                                selectedTabIndex = 0
                                viewModel.closePlaylist()
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 0) Icons.Default.Home else Icons.Outlined.Home,
                                    contentDescription = "Home"
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
                                selectedTabIndex = 1
                                viewModel.closePlaylist()
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 1) Icons.Default.Search else Icons.Outlined.Search,
                                    contentDescription = "Search"
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
                                selectedTabIndex = 2
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 2) Icons.Default.LibraryMusic else Icons.Outlined.LibraryMusic,
                                    contentDescription = "Library"
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
                                selectedTabIndex = 3
                                viewModel.closePlaylist()
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTabIndex == 3) Icons.Default.Settings else Icons.Outlined.Settings,
                                    contentDescription = "Settings"
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
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
                    ) + fadeIn(animationSpec = tween(260)),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)
                    ) + fadeOut(animationSpec = tween(200))
                ) {
                    currentPlaylist?.let { playlist ->
                        PlaylistDetailScreen(
                            playlistWithTracks = playlist,
                            playerUiState = playerUiState,
                            onBackClick = { viewModel.closePlaylist() },
                            onPlayTrack = { track, queue -> viewModel.playTrack(track, queue) },
                            onToggleFavorite = { track -> viewModel.toggleLike(track) },
                            onDeletePlaylist = { viewModel.deletePlaylist(playlist.playlist.playlistId) },
                            onRemoveTrack = { trackId ->
                                viewModel.removeTrackFromPlaylist(playlist.playlist.playlistId, trackId)
                            }
                        )
                    }
                }

                if (currentPlaylist == null) {
                    AnimatedContent(
                        targetState = selectedTabIndex,
                        transitionSpec = {
                            val isForward = targetState > initialState
                            val slideDistanceFraction = 0.15f

                            val enterTransition = slideInHorizontally(
                                animationSpec = tween(360, easing = FastOutSlowInEasing),
                                initialOffsetX = { width -> if (isForward) (width * slideDistanceFraction).toInt() else -(width * slideDistanceFraction).toInt() }
                            ) + scaleIn(
                                initialScale = 0.94f,
                                animationSpec = tween(360, easing = FastOutSlowInEasing)
                            ) + fadeIn(
                                animationSpec = tween(280)
                            )

                            val exitTransition = slideOutHorizontally(
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                targetOffsetX = { width -> if (isForward) -(width * slideDistanceFraction).toInt() else (width * slideDistanceFraction).toInt() }
                            ) + scaleOut(
                                targetScale = 0.97f,
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeOut(
                                animationSpec = tween(220)
                            )

                            enterTransition.togetherWith(exitTransition)
                        },
                        label = "tab_navigation_transition",
                        modifier = Modifier.fillMaxSize()
                    ) { tabIndex ->
                        when (tabIndex) {
                            0 -> HomeScreen(
                                catalogTracks = catalogTracks,
                                recentlyPlayed = recentlyPlayed,
                                playerUiState = playerUiState,
                                shelves = homeShelves,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) }
                            )
                            1 -> SearchScreen(
                                searchState = searchState,
                                playerUiState = playerUiState,
                                onQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                                onSelectGenre = { g -> viewModel.selectGenre(g) },
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) }
                            )
                            2 -> LibraryScreen(
                                favoriteTracks = favoriteTracks,
                                playlists = playlists,
                                playerUiState = playerUiState,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                onOpenPlaylist = { id -> viewModel.openPlaylist(id) },
                                onCreatePlaylistClick = { isCreatePlaylistOpen = true }
                            )
                            3 -> SettingsScreen(
                                playerUiState = playerUiState,
                                effectsState = effectsState,
                                isDarkMode = isDarkMode,
                                onToggleDarkMode = { viewModel.toggleDarkMode() },
                                onAudioQualitySelected = { quality -> viewModel.setAudioQuality(quality) },
                                onCrystalClarityToggle = { enabled -> viewModel.setCrystalClarityEnabled(enabled) },
                                onToggleEqualizer = { enabled -> viewModel.setEqualizerEnabled(enabled) },
                                onOpenEqualizer = { isEqualizerOpen = true },
                                onSelectPreset = { preset -> viewModel.setEqualizerPreset(preset) }
                            )
                        }
                    }
                }
            }
        }

        // FULL SCREEN EXPANDED PLAYER (Animates vertically with smooth spring)
        AnimatedVisibility(
            visible = isPlayerExpanded && playerUiState.currentTrack != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeOut(),
            modifier = Modifier.fillMaxSize()
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
                onAddToPlaylist = { track -> trackToAddToPlaylist = track }
            )
        }

        // UP-NEXT QUEUE BOTTOM SHEET
        if (isQueueOpen) {
            QueueBottomSheet(
                queue = playerUiState.queue,
                currentTrack = playerUiState.currentTrack,
                onTrackClick = { track -> viewModel.playTrack(track, playerUiState.queue) },
                onMoveTrack = { from, to -> viewModel.reorderQueue(from, to) },
                onDismiss = { isQueueOpen = false }
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
    }
}
