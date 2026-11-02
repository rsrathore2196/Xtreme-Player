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
import kotlinx.coroutines.flow.first

@Composable
fun ArtistPortrait(artist: CatalogArtist, modifier: Modifier = Modifier,
    deferEnrichment: Boolean = false, targetSize: Int = 400,
    backgroundColor: Color = Color(0xFF163255), enrichmentAllowed: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val active = LocalArtworkRequestsActive.current
    val mayEnrich by rememberUpdatedState(enrichmentAllowed)
    val currentArtist by rememberUpdatedState(artist)
    val started = remember(artist.id, artist.name, artist.musicBrainzId) { android.os.SystemClock.elapsedRealtime() }
    var retries by remember(artist.name, artist.id, artist.musicBrainzId) { mutableIntStateOf(0) }
    var failedUrls by remember(artist.name, artist.id, artist.musicBrainzId) { mutableStateOf(emptySet<String>()) }
    var photo by remember(artist.id, artist.name, artist.musicBrainzId) {
        mutableStateOf(ArtistPhotoRepository.initialPhoto(artist))
    }
    LaunchedEffect(artist.id, artist.name, artist.musicBrainzId, retries, active) {
        photo = ArtistPhotoRepository.initialPhoto(artist) ?: photo
        if (!active) return@LaunchedEffect
        coroutineScope {
            // An enriched detail identity can improve a Home portrait while Home
            // remains composed underneath the destination. Observe that same cache.
            launch { ArtistPhotoRepository.observePhoto(artist).collect { resolved ->
                photo = resolved ?: ArtistPhotoRepository.initialPhoto(currentArtist) ?: photo
            } }
            // The repository serves saved portraits first, resolves visible misses
            // with bounded IO, and schedules stale-image upgrades independently.
            ArtistPhotoRepository.restoreSaved(context, listOf(currentArtist))
            photo = ArtistPhotoRepository.initialPhoto(currentArtist) ?: photo
            snapshotFlow { mayEnrich }.first { it }
            val resolved = try { ArtistPhotoRepository.photo(context, currentArtist, refreshInBackground = !deferEnrichment) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { ArtistPhotoRepository.cachedPhoto(artist) }
            photo = ArtistPhotoRepository.initialPhoto(currentArtist) ?: resolved ?: photo
            awaitCancellation()
        }
    }
    val displayed = photo?.takeUnless { it in failedUrls }
    Box(modifier.background(backgroundColor), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, contentDescription = null, tint = XtremeLightBlue, modifier = Modifier.fillMaxSize(0.55f))
        AsyncImage(model = rememberOptimizedImageRequest(url = displayed.orEmpty(), targetSize = targetSize,
            crossfadeEnabled = false, preferStableArtwork = deferEnrichment),
            contentDescription = artist.name, contentScale = ContentScale.Crop,
            onSuccess = { android.util.Log.d("ArtistPortrait", "coil-ready key=${artist.name.hashCode()} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}") },
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
