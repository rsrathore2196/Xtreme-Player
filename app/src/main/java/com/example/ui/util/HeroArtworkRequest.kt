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
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Only the visible Hero requests its video artwork; no playback metadata is rewritten. */
@Composable
internal fun rememberHeroArtworkRequest(track: MusicTrack, size: IntSize,
    resolve: suspend (MusicTrack) -> String?): ImageRequest {
    val context = LocalContext.current
    val active = LocalArtworkRequestsActive.current
    val identity = heroArtworkIdentity(track)
    var source by remember(identity) { mutableStateOf(YouTubeHeroArtworkResolver.initial(track) ?: track.coverUrl) }
    val currentResolver by rememberUpdatedState(resolve)
    LaunchedEffect(identity, active) {
        if (active) {
            val image = try { withContext(AppDispatchers.Io) {
                BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) {
                    BackgroundWorkCoordinator.yieldToInteraction(awaitFrameQuiet = false)
                    currentResolver(track)
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (!image.isNullOrBlank()) source = image
        }
    }
    val visibility = remember(identity, source) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    return remember(context, source, size, visibility) { buildHeroArtworkRequest(context, source, size, visibility) }
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
        .setParameter(ARTWORK_METADATA_PARAMETER, metadata.copy(providerUrl = source, landscapeArtwork = true), null)
        .memoryCacheKey("hero-landscape:$nativeSource")
        .diskCacheKey(nativeSource)
        .placeholderMemoryCacheKey(null as String?)
        .precision(Precision.INEXACT)
        .allowRgb565(false)
        .build()
}
