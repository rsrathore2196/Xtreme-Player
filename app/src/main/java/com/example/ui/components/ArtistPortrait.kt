package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.CatalogArtist
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.util.rememberOptimizedImageRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect

@Composable
fun ArtistPortrait(artist: CatalogArtist, modifier: Modifier = Modifier,
    deferEnrichment: Boolean = false, targetSize: Int = 400,
    backgroundColor: Color = Color(0xFF163255)) {
    val context = LocalContext.current.applicationContext
    val active = LocalArtworkRequestsActive.current && LocalVisualEffectsActive.current
    var retries by remember(artist.name, artist.id, artist.musicBrainzId) { mutableIntStateOf(0) }
    var failedUrls by remember(artist.name, artist.id, artist.musicBrainzId) { mutableStateOf(emptySet<String>()) }
    val photo by produceState(ArtistPhotoRepository.initialPhoto(artist), artist, retries, active) {
        value = ArtistPhotoRepository.initialPhoto(artist)
        if (!active) return@produceState
        coroutineScope {
            // An enriched detail identity can improve a Home portrait while Home
            // remains composed underneath the destination. Observe that same cache.
            launch { ArtistPhotoRepository.observePhoto(artist).collect { resolved ->
                value = resolved ?: ArtistPhotoRepository.initialPhoto(artist)
            } }
            // The repository serves saved portraits first, resolves visible misses
            // with bounded IO, and schedules stale-image upgrades independently.
            val resolved = try { ArtistPhotoRepository.photo(context, artist) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { ArtistPhotoRepository.cachedPhoto(artist) }
            value = ArtistPhotoRepository.initialPhoto(artist) ?: resolved
            awaitCancellation()
        }
    }
    val displayed = photo?.takeUnless { it in failedUrls }
    Box(modifier.background(backgroundColor), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, contentDescription = null, tint = XtremeLightBlue, modifier = Modifier.fillMaxSize(0.55f))
        AsyncImage(model = rememberOptimizedImageRequest(url = displayed.orEmpty(), targetSize = targetSize,
            crossfadeEnabled = false, preferStableArtwork = deferEnrichment),
            contentDescription = artist.name, contentScale = ContentScale.Crop,
            onError = {
                if (displayed != null) {
                    failedUrls = failedUrls + displayed
                    if (displayed == ArtistPhotoRepository.cachedPhoto(artist)) {
                        ArtistPhotoRepository.rejectPhoto(displayed)
                        if (retries < 2) retries++
                    }
                }
            }, modifier = Modifier.fillMaxSize())
    }
}
