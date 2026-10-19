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
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect

@Composable
fun ArtistPortrait(artist: CatalogArtist, modifier: Modifier = Modifier, fallbackArtwork: String? = null,
    deferEnrichment: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val active = LocalArtworkRequestsActive.current && LocalVisualEffectsActive.current
    var retries by remember(artist.name, artist.id, artist.musicBrainzId) { mutableIntStateOf(0) }
    var failedUrls by remember(artist.name, artist.id, artist.musicBrainzId) { mutableStateOf(emptySet<String>()) }
    val photo by produceState(ArtistPhotoRepository.initialPhoto(artist), artist, fallbackArtwork, retries, active, deferEnrichment) {
        value = ArtistPhotoRepository.initialPhoto(artist)
        if (!active) return@produceState
        coroutineScope {
            // An enriched detail identity can improve a Home portrait while Home
            // remains composed underneath the destination. Observe that same cache.
            launch { ArtistPhotoRepository.observePhoto(artist).collect { resolved ->
                if (resolved != null) value = resolved
            } }
            val resolved = if (deferEnrichment) {
                // Admission precedes repository identity/photo locks and permits.
                BackgroundWorkCoordinator.awaitInitialPresentation()
                BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                    label = "Home artist portrait") {
                    // Repository identity locks must be released before optional
                    // CPU continuations park in response to a navigation gesture.
                    BackgroundWorkCoordinator.finishBoundedBatch {
                        ArtistPhotoRepository.photo(context, artist, fallbackArtwork)
                    }
                }
            } else ArtistPhotoRepository.photo(context, artist, fallbackArtwork)
            value = ArtistPhotoRepository.cachedPhoto(artist) ?: resolved
            awaitCancellation()
        }
    }
    val displayed = photo?.takeUnless { it in failedUrls } ?: fallbackArtwork?.takeUnless { it in failedUrls }
    Box(modifier.background(Color(0xFF163255)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, contentDescription = null, tint = XtremeLightBlue, modifier = Modifier.fillMaxSize(0.55f))
        AsyncImage(model = rememberOptimizedImageRequest(url = displayed.orEmpty(), targetSize = 400,
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
