package com.example.ui.util

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.model.MusicTrack
import com.example.data.remote.*
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Metadata/decoding belong to visible Trending cards, never the Home feed loader. */
@Composable
internal fun rememberNativeSongArtworkRequest(track: MusicTrack, nativeSongCover: String,
    requestContext: String,
    resolve: suspend (MusicTrack, String) -> String? = ::resolveNativeSongCover,
    cached: (MusicTrack) -> String? = ::cachedNativeSongCover
): ImageRequest {
    val context = LocalContext.current.applicationContext
    val active = LocalArtworkRequestsActive.current
    val pixels = with(LocalDensity.current) { 163.2.dp.roundToPx().coerceIn(1, 1500) }
    val identity = remember(track.id, track.title, track.artist, track.primaryArtists, track.isrc) { playerCoverIdentity(track) }
    val visibility = remember(identity, requestContext) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    var resolved by remember(identity, requestContext) { mutableStateOf(cached(track)) }
    // A source not proven to be a native audio cover never reaches the square painter.
    val initial = resolved ?: nativeSongCover.takeIf(YouTubePlayerArtworkResolver::validSongImageUrl).orEmpty()
    LaunchedEffect(identity, requestContext, nativeSongCover, active) {
        if (!active) return@LaunchedEffect
        withContext(AppDispatchers.Io) {
            try {
                val cover = resolved ?: resolve(track, nativeSongCover) ?: return@withContext
                val request = buildOptimizedImageRequest(context, cover, ImageConfig.RECOMMENDATION_CARD_SIZE, track,
                    decodeSize = IntSize(pixels, pixels), requireSquareSongArtwork = true, visibleThumbnail = true)
                val result = context.imageLoader.execute(request) as? SuccessResult
                ensureActive()
                val bitmap = (result?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                val width = bitmap?.width ?: result?.drawable?.intrinsicWidth ?: 0
                val height = bitmap?.height ?: result?.drawable?.intrinsicHeight ?: 0
                if (width > 0 && width == height) resolved = cover else rejectSongCover(track, cover)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep an existing verified cover or the existing card surface. */ }
        }
    }
    return remember(identity, requestContext, initial, pixels) {
        buildOptimizedImageRequest(context, initial, ImageConfig.RECOMMENDATION_CARD_SIZE, track,
            decodeSize = IntSize(pixels, pixels), requireSquareSongArtwork = true, visibleThumbnail = true, visibility = visibility)
    }
}
