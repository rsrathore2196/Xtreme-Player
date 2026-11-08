package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.example.ui.util.landscapeContentBounds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogArtist
import com.example.ui.util.rememberOptimizedImageRequest
import androidx.compose.ui.text.style.TextOverflow
import com.example.playback.PlayerUiState
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.playerArtworkEdgeBlend
import com.example.ui.components.headerArtworkEdgeBlend
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
    onScroll: (Int, Int) -> Unit, handleBack: Boolean = true,
    onOpenAlbum: (CatalogAlbum) -> Unit = {}, onOpenArtist: (CatalogArtist) -> Unit = {}) {
    val colors = LocalAppColors.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("catalog_detail_list").background(colors.scaffoldBackground)
        .then(if (state.destination is CatalogDestination.Artist || state.destination is CatalogDestination.Album) Modifier
            else Modifier.landscapeContentBounds(landscape).statusBarsPadding()).bouncyOverscroll(),
        contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 180.dp)) {
        item("header") {
            when (val dest = state.destination) {
                is CatalogDestination.Artist -> {
                    ArtistProfileHeader(state.artistProfile?.artist ?: dest.artist, onBack)
                }
                is CatalogDestination.Album -> {
                    AlbumProfileHeader(dest.album, onBack)
                }
                is CatalogDestination.Playlist -> Unit
            }
        }
        state.artistProfile?.let { profile ->
            item("artist_stats") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArtistStatistic("Subscribers", profile.subscriberCount, Modifier.weight(1f))
                    ArtistStatistic(if (profile.monthlyListeners.contains("audience", true)) "Monthly audience" else "Monthly listeners",
                        profile.monthlyListeners, Modifier.weight(1f))
                }
            }
            if (profile.description.isNotBlank()) item("artist_about") {
                var expanded by remember(profile.artist.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("About", color = colors.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(profile.description, color = colors.textSecondary, maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis)
                    Text(if (expanded) "Show less" else "Read more", color = colors.primaryAccent,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 8.dp))
                }
            }
            profile.releases.forEachIndexed { index, section -> item("artist_releases_$index") {
                Text(section.title, color = colors.textPrimary, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(section.albums, key = { it.id }, contentType = { "artist_release" }) { album ->
                        Column(Modifier.width(136.dp).clickable { onOpenAlbum(album) }.testTag("artist_release_${album.id}")) {
                            AsyncImage(rememberOptimizedImageRequest(album.image, targetSize = 600), album.title,
                                contentScale = ContentScale.Crop, modifier = Modifier.size(136.dp).clip(RoundedCornerShape(18.dp)))
                            Text(album.title, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            } }
            if (profile.relatedArtists.isNotEmpty()) item("related_artists") {
                Text("Related Artists", color = colors.textPrimary, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(profile.relatedArtists, key = { it.id }, contentType = { "related_artist" }) { artist ->
                        Column(Modifier.width(112.dp).clickable { onOpenArtist(artist) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistPortrait(artist, Modifier.size(112.dp).clip(CircleShape))
                            Text(artist.name, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
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

/** Full-bleed official artist photography; alpha edges reveal the same page canvas.
 * The small Home/related portraits retain their existing size, shape and requests. */
@Composable
private fun ArtistProfileHeader(artist: CatalogArtist, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val height = if (landscape) (configuration.screenHeightDp * .72f).dp.coerceIn(220.dp, 360.dp)
        else (configuration.screenHeightDp * .54f).dp.coerceIn(320.dp, 520.dp)
    Box(Modifier.fillMaxWidth().height(height).testTag("artist_profile_header")) {
        ArtistPortrait(artist, Modifier.matchParentSize().playerArtworkEdgeBlend(true), targetSize = 1200,
            backgroundColor = colors.scaffoldBackground)
        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)
            .size(38.dp).liquidGlassButton(colors, shape = CircleShape, elevation = 4.dp)
            .bouncyClickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.textPrimary, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("ARTIST", color = colors.textMuted, style = MaterialTheme.typography.labelMedium)
            Text(artist.name, color = colors.textPrimary, style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("artist_profile_name"))
        }
    }
}

@Composable
private fun ArtistStatistic(label: String, rawValue: String, modifier: Modifier) {
    val colors = LocalAppColors.current
    // Some API variants return bare counts; others include the unit. The heading
    // always identifies it, and a missing provider value is never invented as zero.
    val value = remember(rawValue) { rawValue.replace(Regex("(?i)\\s*(?:subscribers?|monthly (?:listeners?|audience))\\s*$"), "").trim() }
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(colors.cardBackgroundElevated)
        .border(BorderStroke(1.dp, colors.cardBorder), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = colors.textMuted, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { "Not provided" }, color = colors.textPrimary,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Full-bleed official album artwork; alpha edges reveal the same page canvas. */
@Composable
private fun AlbumProfileHeader(album: CatalogAlbum, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val height = if (landscape) (configuration.screenHeightDp * .72f).dp.coerceIn(220.dp, 360.dp)
        else (configuration.screenHeightDp * .50f).dp.coerceIn(320.dp, 460.dp)
    val topSpacing = if (landscape) 8.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 20.dp
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(colors.scaffoldBackground)
                .testTag("album_profile_header")
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(album.image, targetSize = 1200),
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .padding(top = topSpacing)
                    .headerArtworkEdgeBlend(topFade = 0.22f, bottomFade = 0.28f, topMargin = 0.01f, bottomMargin = 0.02f)
            )
            Box(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)
                    .size(38.dp).liquidGlassButton(colors, shape = CircleShape, elevation = 4.dp)
                    .bouncyClickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.textPrimary, modifier = Modifier.size(19.dp))
            }
        }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text("ALBUM", color = colors.textMuted, style = MaterialTheme.typography.labelMedium)
            Text(
                album.title,
                color = colors.textPrimary,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("album_profile_name")
            )
            val artistNames = album.artists.joinToString { it.name }
            if (artistNames.isNotBlank() || album.year.isNotBlank()) {
                val subtitle = buildString {
                    if (artistNames.isNotBlank()) append(artistNames)
                    if (artistNames.isNotBlank() && album.year.isNotBlank()) append(" • ")
                    if (album.year.isNotBlank()) append(album.year)
                }
                Text(
                    subtitle,
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
