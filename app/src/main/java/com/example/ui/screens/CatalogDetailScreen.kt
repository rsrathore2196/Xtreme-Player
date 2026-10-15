package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogDestination
import com.example.playback.PlayerUiState
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.TrackListItem
import com.example.ui.components.bouncyOverscroll
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.bouncyClickable
import com.example.ui.viewmodel.CatalogDetailState
import kotlinx.coroutines.flow.distinctUntilChanged

/** Shared artist/album destination using the existing track rows, typography and theme. */
@Composable
fun CatalogDetailScreen(state: CatalogDetailState, player: PlayerUiState, onBack: () -> Unit,
    onMore: () -> Unit, onPlay: (MusicTrack, List<MusicTrack>) -> Unit, onFavorite: (MusicTrack) -> Unit,
    onScroll: (Int, Int) -> Unit, handleBack: Boolean = true) {
    val colors = LocalAppColors.current
    val list = rememberLazyListState(state.scrollIndex, state.scrollOffset)
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        .distinctUntilChanged().collect { (index, offset) -> onScroll(index, offset) } }
    BackHandler(enabled = handleBack, onBack = onBack)
    if (state.destination is CatalogDestination.Playlist) {
        val playlist = state.destination.playlist
        val content = state.playlistContent ?: remember(playlist) {
            com.example.data.local.PlaylistWithTracks(
                com.example.data.local.PlaylistEntity(playlistId = playlist.id.toLongOrNull() ?: 0,
                    title = playlist.title, coverUrl = playlist.image),
                emptyList())
        }
        PlaylistDetailScreen(playlistWithTracks = content, playerUiState = player, onBackClick = onBack,
            onPlayTrack = onPlay, onToggleFavorite = onFavorite, onDeletePlaylist = {}, onRemoveTrack = {},
            readOnly = true, listState = list, footer = {
                if (state.loading) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = colors.primaryAccent)
                }
                else if (state.error != null || state.nextPage != null || state.pending.isNotEmpty()) {
                    Text(state.error ?: "See More", color = colors.primaryAccent,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(20.dp))
                }
            })
        return
    }
    val title = when (val target = state.destination) {
        is CatalogDestination.Artist -> target.artist.name
        is CatalogDestination.Album -> target.album.title
        is CatalogDestination.Playlist -> target.playlist.title
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().background(colors.scaffoldBackground)
        .statusBarsPadding().bouncyOverscroll(), contentPadding = PaddingValues(bottom = 180.dp)) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).liquidGlassButton(colors, shape = CircleShape, elevation = 4.dp)
                    .bouncyClickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.textPrimary, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            }
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (val target = state.destination) {
                    is CatalogDestination.Artist -> ArtistPortrait(target.artist, Modifier.size(180.dp).clip(CircleShape))
                    is CatalogDestination.Album -> {
                        AsyncImage(target.album.image, target.album.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(180.dp).clip(RoundedCornerShape(18.dp)))
                        Spacer(Modifier.height(12.dp))
                        Text(target.album.artists.joinToString { it.name }, color = colors.textMuted)
                        Text(target.album.year, color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    is CatalogDestination.Playlist -> AsyncImage(target.playlist.image, target.playlist.title,
                        contentScale = ContentScale.Crop, modifier = Modifier.size(180.dp).clip(RoundedCornerShape(18.dp)))
                }
            }
        }
        items(state.tracks, key = { it.id }, contentType = { "track" }) { song ->
            TrackListItem(track = song, isPlaying = player.currentTrack?.id == song.id && player.isPlaying,
                isLoading = player.currentTrack?.id == song.id && player.isLoading,
                onClick = { onPlay(song, state.tracks) }, onToggleFavorite = { onFavorite(song) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (state.loading) item("loading") { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = colors.primaryAccent)
        } }
        else if (state.error != null) item("error") {
            Text(state.error, color = colors.textMuted, modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(20.dp))
        }
        else {
            if (state.tracks.isEmpty()) item("empty") { Text("No songs available", color = colors.textMuted, modifier = Modifier.padding(20.dp)) }
            if (state.nextPage != null || state.pending.isNotEmpty()) item("more") {
                Text("See More", color = colors.primaryAccent, modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(20.dp))
            }
        }
        state.warning?.let { warning -> item("warning") { Text(warning, color = colors.textMuted, modifier = Modifier.padding(20.dp)) } }
    }
}
