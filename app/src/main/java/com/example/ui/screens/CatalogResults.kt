package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.TrackListItem
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.viewmodel.CatalogSearchState

/** Entity rows retain the artist/album row dimensions and styling already used by Search. */
@Composable
internal fun CatalogEntityRow(title: String, subtitle: String, image: String?, artist: CatalogArtist? = null, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        .padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (artist != null) ArtistPortrait(artist, Modifier.size(40.dp).clip(CircleShape))
        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF163255)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Album, contentDescription = null, tint = XtremeLightBlue, modifier = Modifier.size(22.dp))
            AsyncImage(image, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.textMuted, fontSize = 12.sp)
        }
    }
}
@Composable
internal fun CatalogSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold,
        color = LocalAppColors.current.textPrimary, fontSize = 18.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}
internal fun LazyListScope.catalogSearchResults(
    state: CatalogSearchState, playingId: String?, isPlaying: Boolean, isLoading: Boolean,
    onPlay: (MusicTrack, List<MusicTrack>) -> Unit, onFavorite: (MusicTrack) -> Unit,
    onOpen: (CatalogDestination) -> Unit, onMore: () -> Unit, onRetry: () -> Unit
) {
    val result = state.page
    val display = state.display
    fun song(key: String, track: MusicTrack) {
        item(key, contentType = "track") {
            TrackListItem(track = track, isPlaying = track.id == playingId && isPlaying,
                isLoading = track.id == playingId && isLoading, onClick = { onPlay(track, result.tracks) },
                onToggleFavorite = { onFavorite(track) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
    }
    fun entity(key: String, destination: CatalogDestination) {
        item(key, contentType = "catalog_entity") {
            Box(Modifier.padding(horizontal = 20.dp)) {
                when (destination) {
                    is CatalogDestination.Artist -> CatalogEntityRow(destination.artist.name, "Artist • Tap to view songs", null, destination.artist) { onOpen(destination) }
                    is CatalogDestination.Album -> CatalogEntityRow(destination.album.title, "${destination.album.artists.joinToString { it.name }} • ${destination.album.year}", destination.album.image) { onOpen(destination) }
                    is CatalogDestination.Playlist -> CatalogEntityRow(destination.playlist.title, "Playlist", destination.playlist.image) { onOpen(destination) }
                }
            }
        }
    }
    if (state.type == "All") {
        val top = display.top
        if (top.isNotEmpty()) item("top_header") { CatalogSectionTitle("Top Results") }
        top.forEach { entry -> entry.track?.let { song("top:${entry.key}", it) } ?: entry.destination?.let { entity("top:${entry.key}", it) } }
    }
    val songs = display.songs
    val albums = display.albums
    val artists = display.artists
    if (songs.isNotEmpty()) {
        item("songs_header") { CatalogSectionTitle("Songs") }
        songs.forEach { song("song:${it.id}", it) }
    }
    if (albums.isNotEmpty()) {
        item("albums_header") { CatalogSectionTitle("Albums") }
        albums.forEach { entity("album:${it.id}", CatalogDestination.Album(it)) }
    }
    if (artists.isNotEmpty()) {
        item("artists_header") { CatalogSectionTitle("Artists") }
        artists.forEach { entity("artist:${it.id}", CatalogDestination.Artist(it)) }
    }
    if (result.playlists.isNotEmpty()) {
        item("playlists_header") { CatalogSectionTitle("Playlists") }
        result.playlists.forEach { entity("playlist:${it.id}", CatalogDestination.Playlist(it)) }
    }
    if (!state.loading && state.error == null && result.tracks.isEmpty() && result.albums.isEmpty() && result.artists.isEmpty() && result.playlists.isEmpty()) {
        item("empty") { Text("No results found", color = LocalAppColors.current.textMuted, modifier = Modifier.padding(20.dp)) }
    }
    val message = state.error ?: result.warning
    if (message != null) item("error") { Text(message, color = LocalAppColors.current.textMuted, modifier = Modifier.clickable(onClick = onRetry).padding(20.dp)) }
    if (state.loading || state.loadingMore) item("loading") {
        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = LocalAppColors.current.primaryAccent) }
    }
    else if (result.nextPage != null) item("more") {
        Text("See More", color = LocalAppColors.current.primaryAccent, modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(20.dp))
    }
}
