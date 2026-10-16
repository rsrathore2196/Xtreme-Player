package com.example.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.example.data.model.MusicTrack
import com.example.data.repository.ArtworkDimensions
import com.example.data.repository.iTunesArtworkRepository
import com.example.data.repository.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ImageConfig {
    const val LIST_ITEM_SIZE = 120 // ~120dp for track list items, playlist rows, search result items, and queue items
    const val LIST_THUMBNAIL_SIZE = 120 // alias
    const val MINI_PLAYER_SIZE = 240 // ~200-240dp for the mini player
    const val PLAYLIST_HEADER_SIZE = 400 // ~400dp for playlist header cover
    const val RECOMMENDATION_CARD_SIZE = 450 // Crisp 400-600px for recommendation cards on Home screen
    const val HERO_BANNER_SIZE = 1200 // 1000-1200px ultra high-res for Now Playing hero banners
    const val PLAYER_SCREEN_SIZE = -1 // High-quality source decoded to the expanded player's displayed bounds
    const val PLAYER_COVER_SIZE = -1 // alias
    const val PALETTE_THUMBNAIL_SIZE = 120 // for fast palette extraction
}

/**
 * In-memory thread-safe cache for extracted palette color schemes by track ID.
 * Once a track's colors are extracted, re-playing that track never re-triggers processing.
 */
@androidx.compose.runtime.Immutable
data class ExtractedTrackColors(
    val dominantColor: Color,
    val accentColor: Color,
    val vibrantColor: Color = accentColor
)

object TrackPaletteCache {
    private val memoryCache = android.util.LruCache<String, ExtractedTrackColors>(128)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val pending = kotlinx.coroutines.channels.Channel<Map<String, ExtractedTrackColors>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var clearGeneration = 0L
    @Volatile private var file: android.util.AtomicFile? = null

    fun initialize(context: android.content.Context) {
        if (file != null) return
        val storage = android.util.AtomicFile(java.io.File(context.applicationContext.filesDir, "track_palettes_v1.json"))
        file = storage
        val readGeneration = synchronized(memoryCache) { clearGeneration }
        scope.launch {
            try {
                if (storage.baseFile.exists() && storage.baseFile.length() <= 100_000L) {
                    val json = org.json.JSONObject(storage.openRead().use { String(it.readBytes()) })
                    json.keys().asSequence().take(128).forEach { key ->
                        val item = json.getJSONArray(key)
                        synchronized(memoryCache) {
                            if (clearGeneration == readGeneration && memoryCache.get(key) == null)
                                memoryCache.put(key, ExtractedTrackColors(
                                    Color(item.getInt(0)), Color(item.getInt(1)), Color(item.getInt(2))))
                        }
                    }
                }
            } catch (_: Exception) { /* A palette miss never prevents artwork/playback. */ }
            for (snapshot in pending) {
                try {
                    val json = org.json.JSONObject()
                    snapshot.forEach { (key, c) -> json.put(key, org.json.JSONArray().put(c.dominantColor.toArgb())
                        .put(c.accentColor.toArgb()).put(c.vibrantColor.toArgb())) }
                    val stream = storage.startWrite()
                    try { stream.write(json.toString().toByteArray()); storage.finishWrite(stream) }
                    catch (e: Exception) { storage.failWrite(stream); throw e }
                } catch (_: Exception) { /* Keep the in-memory result if storage is unavailable. */ }
            }
        }
    }

    fun get(trackId: String): ExtractedTrackColors? = memoryCache.get(trackId)

    fun put(trackId: String, colors: ExtractedTrackColors) {
        if (trackId.isNotBlank()) {
            synchronized(memoryCache) {
                memoryCache.put(trackId, colors)
                pending.trySend(memoryCache.snapshot())
            }
        }
    }

    fun clear() {
        synchronized(memoryCache) {
            ++clearGeneration
            memoryCache.evictAll()
            pending.trySend(emptyMap())
        }
    }
}

fun mapTargetSizeToArtworkDimension(targetSize: Int): Int {
    return when {
        targetSize >= 900 -> ArtworkDimensions.HERO_CARD // 1200
        targetSize >= 500 || targetSize < 0 -> ArtworkDimensions.EXPANDED_PLAYER // 1200
        targetSize in 250..899 -> ArtworkDimensions.HOME_CARD // 600
        targetSize in 150..249 -> ArtworkDimensions.MINI_PLAYER // 200
        else -> ArtworkDimensions.SEARCH_AND_LIST // 200
    }
}

/**
 * The request stays stable while Coil resolves artwork internally. Source resolution is
 * independent of decode size: AsyncImage supplies its pixel bounds unless explicitly set.
 */
@Composable
fun rememberOptimizedImageRequest(
    track: MusicTrack,
    targetSize: Int = ImageConfig.LIST_ITEM_SIZE,
    crossfadeEnabled: Boolean = true,
    preferStableArtwork: Boolean = false,
    decodeSize: IntSize? = null
): ImageRequest = rememberOptimizedImageRequest(
    url = track.coverUrl,
    targetSize = targetSize,
    track = track,
    crossfadeEnabled = crossfadeEnabled,
    preferStableArtwork = preferStableArtwork,
    decodeSize = decodeSize
)

@Composable
fun rememberOptimizedImageRequest(
    url: String,
    targetSize: Int = ImageConfig.LIST_ITEM_SIZE,
    track: MusicTrack? = null,
    crossfadeEnabled: Boolean = true,
    preferStableArtwork: Boolean = false,
    decodeSize: IntSize? = null
): ImageRequest {
    val context = LocalContext.current
    // Transport URL/bitrate changes do not change artwork. Metadata edits and a larger
    // destination do create a new request, including when a recycled row keeps its ID.
    return remember(context, url, track?.id, track?.title, track?.artist, track?.album,
        track?.source, track?.coverUrl, targetSize, crossfadeEnabled, preferStableArtwork, decodeSize) {
        val metadata = ArtworkRequestMetadata(
            item = track?.toMediaItem(),
            trackId = track?.id,
            album = track?.album,
            providerUrl = url,
            sourceDimension = mapTargetSizeToArtworkDimension(targetSize),
            preferStableArtwork = preferStableArtwork,
            targetSize = targetSize
        )
        // Re-entering cards can start on a prepared resolved URL without constructing the
        // repository or sanitizing metadata on Main. The interceptor retains raw fallbacks.
        val initialUrl = metadata.item?.let {
            iTunesArtworkRepository.peekInstance()?.peekMemoryCachedUrl(it, metadata.sourceDimension)
        }?.takeIf { it.isNotBlank() } ?: url
        ImageRequest.Builder(context)
            .data(initialUrl)
            .setParameter(ARTWORK_METADATA_PARAMETER, metadata, null)
            .placeholderMemoryCacheKey(initialUrl.takeIf { it.isNotBlank() })
            .interceptorDispatcher(Dispatchers.IO)
            .scale(Scale.FILL)
            .crossfade(if (crossfadeEnabled) 200 else 0)
            .allowHardware(true)
            .bitmapConfig(android.graphics.Bitmap.Config.HARDWARE)
            .allowRgb565(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                if (decodeSize != null && decodeSize.width > 0 && decodeSize.height > 0) {
                    size(decodeSize.width, decodeSize.height)
                }
            }
            .build()
    }
}
