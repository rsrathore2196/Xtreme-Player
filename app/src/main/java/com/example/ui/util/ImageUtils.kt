package com.example.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import java.util.concurrent.ConcurrentHashMap

object ImageConfig {
    const val LIST_ITEM_SIZE = 100 // 100dp x 100dp for track list items, playlist rows, search result items, and queue items
    const val LIST_THUMBNAIL_SIZE = 100 // alias
    const val MINI_PLAYER_SIZE = 200 // 200dp x 200dp for the mini player
    const val PLAYLIST_HEADER_SIZE = 200 // 200dp x 200dp for playlist header cover
    const val RECOMMENDATION_CARD_SIZE = 300 // 300dp x 300dp for recommendation cards on Home screen
    const val HERO_BANNER_SIZE = 300 // 300dp x 300dp for Now Playing hero banners
    const val PLAYER_SCREEN_SIZE = -1 // Full High Quality image (Size.ORIGINAL) for expanded music player screen album art
    const val PLAYER_COVER_SIZE = -1 // alias
    const val PALETTE_THUMBNAIL_SIZE = 100 // 100dp x 100dp for fast palette extraction
}

/**
 * In-memory thread-safe cache for extracted palette color schemes by track ID.
 * Once a track's colors are extracted, re-playing that track never re-triggers processing.
 */
data class ExtractedTrackColors(
    val dominantColor: Color,
    val accentColor: Color,
    val vibrantColor: Color = accentColor
)

object TrackPaletteCache {
    private val memoryCache = ConcurrentHashMap<String, ExtractedTrackColors>(128)

    fun get(trackId: String): ExtractedTrackColors? = memoryCache[trackId]

    fun put(trackId: String, colors: ExtractedTrackColors) {
        if (trackId.isNotBlank()) {
            memoryCache[trackId] = colors
        }
    }

    fun clear() {
        memoryCache.clear()
    }
}

/**
 * Shared high-performance Coil ImageRequest with explicit hardware downscaling,
 * GPU hardware acceleration, and full disk & memory caching to eliminate memory leaks
 * and frame drops.
 */
@Composable
fun rememberOptimizedImageRequest(
    url: String,
    targetSize: Int = ImageConfig.LIST_ITEM_SIZE
): ImageRequest {
    val context = LocalContext.current
    return remember(url, targetSize) {
        val upgradedUrl = when {
            url.contains("150x150.jpg") -> url.replace("150x150.jpg", "500x500.jpg")
            url.contains("50x50.jpg") -> url.replace("50x50.jpg", "500x500.jpg")
            url.contains("hqdefault.jpg") -> url.replace("hqdefault.jpg", "maxresdefault.jpg")
            url.contains("mqdefault.jpg") -> url.replace("mqdefault.jpg", "maxresdefault.jpg")
            url.contains("default.jpg") && !url.contains("maxresdefault.jpg") -> url.replace("default.jpg", "maxresdefault.jpg")
            else -> url
        }
        val builder = ImageRequest.Builder(context)
            .data(upgradedUrl)
            .scale(Scale.FILL)
            .crossfade(false)
            .allowHardware(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)

        if (targetSize > 0) {
            builder.size(targetSize, targetSize)
        } else {
            // Full High Quality image (ImageConfig.PLAYER_SCREEN_SIZE) without downscaling
            builder.size(coil.size.Size.ORIGINAL)
        }

        builder.build()
    }
}
