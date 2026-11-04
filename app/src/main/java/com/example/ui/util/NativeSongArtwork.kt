package com.example.ui.util

import androidx.compose.runtime.*
import androidx.compose.ui.layout.ContentScale
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
    cached: (MusicTrack) -> String? = ::cachedNativeSongCover,
    landscapeThumbnail: String = ""
): ImageRequest {
    val context = LocalContext.current.applicationContext
    val active = LocalArtworkRequestsActive.current
    val pixels = with(LocalDensity.current) { 163.2.dp.roundToPx().coerceIn(1, 1500) }
    val identity = remember(track) { playerCoverLookupIdentity(track) }
    val visibility = remember(identity, requestContext) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    var resolved by remember(identity, requestContext) { mutableStateOf(cached(track)) }
    var rejected by remember(identity, requestContext) { mutableStateOf(emptySet<String>()) }
    val fallbacks = remember(track.id, track.coverUrl, landscapeThumbnail) {
        nativeSongArtworkFallbacks(track, landscapeThumbnail)
    }
    var fallbackIndex by remember(identity, requestContext, fallbacks) { mutableIntStateOf(0) }
    val square = (resolved ?: nativeSongCover.takeIf(YouTubePlayerArtworkResolver::validSongImageUrl))
        ?.takeUnless { it in rejected }
    val initial = square ?: fallbacks.getOrNull(fallbackIndex).orEmpty()
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
                if (width > 0 && width == height && cover !in rejected) resolved = cover else {
                    rejected = rejected + cover
                    if (resolved == cover) resolved = null
                    rejectSongCover(track, cover)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The independent native-thumbnail request remains usable. */ }
        }
    }
    return remember(identity, requestContext, initial, square, pixels, fallbackIndex, fallbacks) {
        buildOptimizedImageRequest(context, initial, ImageConfig.RECOMMENDATION_CARD_SIZE, track,
            decodeSize = IntSize(pixels, pixels), requireSquareSongArtwork = square != null,
            preserveAspectRatio = square == null, allowVideoFallback = false,
            visibleThumbnail = true, visibility = visibility).newBuilder()
            .listener(onError = { _, _ ->
                if (square != null) {
                    rejected = rejected + square
                    if (resolved == square) resolved = null
                } else if (fallbacks.getOrNull(fallbackIndex) == initial && fallbackIndex < fallbacks.size) {
                    fallbackIndex++
                }
            }).build()
    }
}

/** These URLs are presentation fallbacks, never evidence for a verified album cover. */
internal fun nativeSongArtworkFallbacks(track: MusicTrack, landscapeThumbnail: String): List<String> =
    (listOf(landscapeThumbnail, track.coverUrl) + youtubeArtworkAlternatives(track.id, emptyList()))
        .filter { value -> runCatching {
            val uri = java.net.URI(value)
            uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()
        }.getOrDefault(false) }.distinct()

internal fun nativeSongArtworkContentScale(request: ImageRequest): ContentScale =
    if (request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)?.preserveAspectRatio == true)
        ContentScale.Fit else ContentScale.Crop
