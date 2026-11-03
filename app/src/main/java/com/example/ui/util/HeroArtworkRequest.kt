package com.example.ui.util

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.request.ImageRequest
import coil.size.Precision
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeHeroArtworkResolver
import com.example.data.remote.heroArtworkIdentity
import com.example.performance.AppDispatchers
import com.example.recommendation.DiscoveryRequestCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Only the visible Hero requests its video artwork; no playback metadata is rewritten. */
@Composable
internal fun rememberHeroArtworkRequest(track: MusicTrack, size: IntSize,
    resolve: suspend (MusicTrack) -> String?): ImageRequest {
    val context = LocalContext.current
    val active = LocalArtworkRequestsActive.current
    val identity = heroArtworkIdentity(track)
    val fallback = track.coverUrl
    var source by remember(identity, fallback) { mutableStateOf(YouTubeHeroArtworkResolver.initial(track) ?: fallback) }
    val currentResolver by rememberUpdatedState(resolve)
    LaunchedEffect(identity, fallback, active) {
        if (active) {
            val image = try { loadVisibleHeroArtwork { currentResolver(track) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (!image.isNullOrBlank()) source = image
        }
    }
    val visibility = remember(identity, source) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    return remember(context, source, fallback, size, visibility) {
        val requestedSource = source
        buildHeroArtworkRequest(context, requestedSource, size, visibility).newBuilder()
            .listener(onError = { _, _ ->
                // A removed/unavailable video image must leave the supplied cover
                // filling the card, rather than a failed image or a fabricated frame.
                if (source == requestedSource && fallback.isNotBlank() && requestedSource != fallback) source = fallback
            }).build()
    }
}

/** Optional Hero metadata shares discovery admission; audio resolution keeps priority.
 * Admission precedes the resolver's timeout, so waiting for input/playback is not
 * cached as a missing cover. The visible composition owns cancellation. */
internal suspend fun loadVisibleHeroArtwork(resolve: suspend () -> String?): String? = withContext(AppDispatchers.Io) {
    DiscoveryRequestCoordinator.withPriority(MutableStateFlow(false), awaitFrameQuiet = false) {
        DiscoveryRequestCoordinator.network { resolve() }
    }
}

/** Keep the native frame's aspect ratio when requesting a larger Google thumbnail. */
internal fun heroLandscapeSourceUrl(source: String, size: IntSize): String {
    val host = try { java.net.URI(source).host.orEmpty().lowercase(java.util.Locale.ROOT) } catch (_: Exception) { return source }
    if (!host.endsWith(".googleusercontent.com") && !host.endsWith(".ggpht.com"))
        return providerArtworkUrl(source, maxOf(size.width, size.height))
    val width = Regex("(?:=|-)w(\\d+)").find(source)?.groupValues?.get(1)?.toIntOrNull() ?: return source
    val height = Regex("(?:=|-)h(\\d+)").find(source)?.groupValues?.get(1)?.toIntOrNull() ?: return source
    if (width <= 0 || height <= 0) return source
    val factor = maxOf(1.0, size.width.toDouble() / width, size.height.toDouble() / height)
        .coerceAtMost(3000.0 / maxOf(width, height))
    val targetWidth = kotlin.math.ceil(width * factor).toInt().coerceAtLeast(width)
    val targetHeight = kotlin.math.ceil(height * factor).toInt().coerceAtLeast(height)
    return source.replace(Regex("(=|-)w\\d+")) { "${it.groupValues[1]}w$targetWidth" }
        .replace(Regex("(=|-)h\\d+")) { "${it.groupValues[1]}h$targetHeight" }
}

internal fun buildHeroArtworkRequest(context: Context, source: String, size: IntSize,
    visibility: ArtworkVisibility = ArtworkVisibility(true)): ImageRequest {
    val nativeSource = heroLandscapeSourceUrl(source, size)
    val base = buildOptimizedImageRequest(context, nativeSource, ImageConfig.HERO_BANNER_SIZE, null,
        decodeSize = size, visibility = visibility, crossfadeEnabled = true)
    val metadata = base.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!
    return base.newBuilder()
        .data(nativeSource)
        .setParameter(ARTWORK_METADATA_PARAMETER, metadata.copy(providerUrl = source, landscapeArtwork = true,
            // Use only the native source selected above. Generic video-frame
            // alternatives can reintroduce an audio upload's blurred side panels.
            allowVideoFallback = false), null)
        .memoryCacheKey("hero-landscape:$nativeSource")
        .diskCacheKey(nativeSource)
        .placeholderMemoryCacheKey(null as String?)
        .precision(Precision.INEXACT)
        .allowRgb565(false)
        .build()
}
