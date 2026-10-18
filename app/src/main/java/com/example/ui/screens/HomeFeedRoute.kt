package com.example.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.XtremeMusicApp
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogArtist
import com.example.playback.PlayerUiState
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.awaitCancellation

/**
 * Feed ownership stays inside the Home destination. The pager and dock have no
 * dependency on feed loading, cache restoration, or recommendation publication.
 * The Activity's ViewModelStore retains prepared feed data when this page leaves composition.
 */
@Composable
internal fun HomeFeedRoute(
    player: PlayerUiState,
    listState: LazyListState,
    isVisible: Boolean,
    onOpenArtist: (CatalogArtist) -> Unit,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenSettings: () -> Unit,
    onPlayInfiniteRadio: (MusicTrack?) -> Unit,
    onAddToQueue: (MusicTrack) -> Unit
) {
    val app = LocalContext.current.applicationContext as XtremeMusicApp
    val factory = remember(app) {
        HomeViewModel.provideFactory(app.repository, app.playbackManager, app)
    }
    val feed: HomeViewModel = viewModel(factory = factory)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(feed, lifecycle, isVisible) {
        if (isVisible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            feed.setHomeVisible(true)
            try { awaitCancellation() } finally { feed.setHomeVisible(false) }
        }
    }
    HomeScreen(
        homeViewModel = feed,
        listState = listState,
        onOpenArtist = onOpenArtist,
        currentPlayingTrackId = player.currentTrack?.id,
        isPlaying = player.isPlaying,
        isAutoplayEnabled = player.isAutoplayEnabled,
        currentTrack = player.currentTrack,
        onTrackClick = onTrackClick,
        onToggleFavorite = onToggleFavorite,
        onOpenSettings = onOpenSettings,
        onPlayInfiniteRadio = onPlayInfiniteRadio,
        onAddToQueue = onAddToQueue
    )
}
