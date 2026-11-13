package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import com.example.downloads.observeSavedRecords
import com.example.performance.AppDispatchers
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DownloadsArtwork = Brush.linearGradient(listOf(Color(0xFF087F8C), Color(0xFF2563EB)))
internal fun downloadsArtworkBrush(): Brush = DownloadsArtwork
private val LikedSongsArtwork = Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFDB2777)))
internal fun likedSongsArtworkBrush(): Brush = LikedSongsArtwork

@Composable
internal fun LibraryCollectionArtwork(icon: ImageVector, modifier: Modifier, brush: Brush = DownloadsArtwork) {
    Box(modifier.background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(64.dp).testTag("library_collection_symbol"))
    }
}

/** The saved collection uses the same header, track rows and playback actions as playlists. */
@Composable
fun LibraryDownloadsScreen(
    onBackClick: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    currentPlayingTrackId: String? = null,
    store: OfflineDownloads = OfflineDownloads.get(LocalContext.current),
    listState: LazyListState = rememberLazyListState(),
    likedIds: Set<String> = emptySet()
) {
    val saved by remember(store) { store.observeSavedRecords() }.collectAsStateWithLifecycle(emptyList())
    var loading by remember(store) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        try { store.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Saved downloads could not be loaded." }
        finally { loading = false }
    }
    val collection by produceState<PlaylistWithTracks?>(null, saved, likedIds) {
        val offline = try {
            val tracks = withContext(AppDispatchers.CpuBounded) { saved.map { it.track.copy(isLiked = it.track.id in likedIds) } }
            store.localQueue(tracks)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Saved downloads could not be loaded."; emptyList() }
        value = withContext(AppDispatchers.CpuBounded) {
            PlaylistWithTracks(PlaylistEntity(-2L, "Downloads", "Your music, available offline"),
                offline.map(TrackEntity::fromMusicTrack)).also { it.musicTracks }
        }
    }
    fun playSaved(selected: MusicTrack, queue: List<MusicTrack>) {
        scope.launch {
            try {
                if (store.localTrack(selected) == null) {
                    error = "Saved audio is missing. Download this track again."
                    return@launch
                }
                // Preserve play/shuffle order and offline URIs. Missing downloads
                // are excluded; playback never falls back to an online stream.
                val offline = store.localQueue(queue)
                offline.firstOrNull { it.id == selected.id }?.let { onPlayTrack(it, offline) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Saved audio could not be opened. Try downloading it again." }
        }
    }
    Box(Modifier.fillMaxSize().background(LocalAppColors.current.scaffoldBackground).testTag("library_downloads_page")) {
        val ready = collection
        if (loading || ready == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            PlaylistDetailScreen(ready, currentPlayingTrackId = currentPlayingTrackId,
                onBackClick = onBackClick, onPlayTrack = ::playSaved,
                onToggleFavorite = { track -> onToggleFavorite((store.records.value[track.id]?.track ?: track).copy(isLiked = track.isLiked)) },
                onDeletePlaylist = {}, onRemoveTrack = {}, readOnly = true, listState = listState,
                showDownloadAction = false, artworkIcon = Icons.Default.Download,
                collectionLabel = "OFFLINE COLLECTION", emptyTitle = "No downloads yet",
                emptyDescription = "Download songs from their track menu to listen here without internet.")
        }
        error?.let { message ->
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 160.dp),
                action = { TextButton(onClick = { error = null }) { Text("Dismiss") } }) { Text(message) }
        }
    }
}
