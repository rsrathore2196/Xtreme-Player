package com.example.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF060D17))) {
        Scaffold(
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // Floating MiniPlayer (Visible if a track is active and player is not expanded)
                    if (playerUiState.currentTrack != null && !isPlayerExpanded) {
                        MiniPlayer(
                            uiState = playerUiState,
                            onPlayPauseClick = { viewModel.togglePlayPause() },
                            onSkipNext = { viewModel.skipNext() },
                            onSkipPrevious = { viewModel.skipPrevious() },
                            onClick = { isPlayerExpanded = true }
                        )
                    }

                    // Bottom Navigation Bar
                    NavigationBar(
                        containerColor = Color(0xFF071220),
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
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = XtremeLightBlue,
                                selectedTextColor = XtremeLightBlue,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Color(0xFF163255)
                            )
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
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = XtremeLightBlue,
                                selectedTextColor = XtremeLightBlue,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Color(0xFF163255)
                            )
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
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = XtremeLightBlue,
                                selectedTextColor = XtremeLightBlue,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Color(0xFF163255)
                            )
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
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = XtremeLightBlue,
                                selectedTextColor = XtremeLightBlue,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Color(0xFF163255)
                            )
                        )
                    }
                }
            },
            containerColor = Color(0xFF060D17)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // If playlist detail is open, show it
                val currentPlaylist = selectedPlaylistWithTracks
                if (currentPlaylist != null) {
                    PlaylistDetailScreen(
                        playlistWithTracks = currentPlaylist,
                        playerUiState = playerUiState,
                        onBackClick = { viewModel.closePlaylist() },
                        onPlayTrack = { track, queue -> viewModel.playTrack(track, queue) },
                        onToggleFavorite = { track -> viewModel.toggleLike(track) },
                        onDeletePlaylist = { viewModel.deletePlaylist(currentPlaylist.playlist.playlistId) },
                        onRemoveTrack = { trackId ->
                            viewModel.removeTrackFromPlaylist(currentPlaylist.playlist.playlistId, trackId)
                        }
                    )
                } else {
                    AnimatedContent(
                        targetState = selectedTabIndex,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally { width -> width / 4 } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> -width / 4 } + fadeOut())
                            } else {
                                (slideInHorizontally { width -> -width / 4 } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> width / 4 } + fadeOut())
                            }
                        },
                        label = "tab_navigation_transition",
                        modifier = Modifier.fillMaxSize()
                    ) { tabIndex ->
                        when (tabIndex) {
                            0 -> HomeScreen(
                                catalogTracks = catalogTracks,
                                recentlyPlayed = recentlyPlayed,
                                playerUiState = playerUiState,
                                onTrackClick = { track, queue -> viewModel.playTrack(track, queue) },
                                onToggleFavorite = { track -> viewModel.toggleLike(track) },
                                onOpenEqualizer = { isEqualizerOpen = true }
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
                                onAudioQualitySelected = { quality -> viewModel.setAudioQuality(quality) },
                                onCrystalClarityToggle = { enabled -> viewModel.setCrystalClarityEnabled(enabled) },
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
                onOpenEqualizer = { isEqualizerOpen = true },
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
