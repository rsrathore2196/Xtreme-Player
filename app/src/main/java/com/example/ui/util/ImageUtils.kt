package com.example.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

object ImageConfig {
    const val LIST_ITEM_SIZE = 120 // ~120dp for track list items, playlist rows, search result items, and queue items
    const val LIST_THUMBNAIL_SIZE = 120 // alias
    const val MINI_PLAYER_SIZE = 240 // ~200-240dp for the mini player
    const val PLAYLIST_HEADER_SIZE = 400 // ~400dp for playlist header cover
    const val RECOMMENDATION_CARD_SIZE = 450 // Crisp 400-600px for recommendation cards on Home screen
    const val HERO_BANNER_SIZE = 1200 // 1000-1200px ultra high-res for Now Playing hero banners
    const val PLAYER_SCREEN_SIZE = -1 // Full High Quality image (Size.ORIGINAL) for expanded music player screen album art
    const val PLAYER_COVER_SIZE = -1 // alias
    const val PALETTE_THUMBNAIL_SIZE = 120 // for fast palette extraction
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
 * Shared high-performance Coil ImageRequest with explicit hardware downscaling,
 * GPU hardware acceleration, 3-layer caching (LruCache, Room DB, Coil disk cache),
 * and iTunes Artwork resolution with smooth 200ms crossfade.
 */
@Composable
fun rememberOptimizedImageRequest(
    track: MusicTrack,
    targetSize: Int = ImageConfig.LIST_ITEM_SIZE
): ImageRequest = rememberOptimizedImageRequest(
    url = track.coverUrl,
    targetSize = targetSize,
    track = track
)

@Composable
fun rememberOptimizedImageRequest(
    url: String,
    targetSize: Int = ImageConfig.LIST_ITEM_SIZE,
    track: MusicTrack? = null
): ImageRequest {
    val context = LocalContext.current
    val repository = remember { iTunesArtworkRepository.getInstance(context) }
    val targetDimension = remember(targetSize) { mapTargetSizeToArtworkDimension(targetSize) }

    val initialUrl = remember(url) {
        when {
            url.contains("150x150.jpg") -> url.replace("150x150.jpg", "500x500.jpg")
            url.contains("50x50.jpg") -> url.replace("50x50.jpg", "500x500.jpg")
            url.contains("hqdefault.jpg") -> url.replace("hqdefault.jpg", "maxresdefault.jpg")
            url.contains("mqdefault.jpg") -> url.replace("mqdefault.jpg", "maxresdefault.jpg")
            url.contains("default.jpg") && !url.contains("maxresdefault.jpg") -> url.replace("default.jpg", "maxresdefault.jpg")
            url.contains("100x100bb") -> repository.formatUrl(url, targetDimension)
            url.contains("100x100") -> repository.formatUrl(url, targetDimension)
            else -> url
        }
    }

    if (track == null) {
        return remember(initialUrl, targetSize) {
            buildImageRequest(context, initialUrl, targetSize)
        }
    }

    val memoryCachedUrl = remember(track.id, targetDimension) {
        repository.getMemoryCachedUrl(track.toMediaItem(), targetDimension)
    }

    var displayUrl by remember(track.id, targetDimension) {
        mutableStateOf(memoryCachedUrl ?: initialUrl)
    }

    LaunchedEffect(track.id, targetDimension) {
        if (memoryCachedUrl == null) {
            val resolved = withContext(Dispatchers.IO) {
                repository.resolveArtwork(track.toMediaItem(), targetDimension)
            }
            if (resolved.isNotBlank() && resolved != displayUrl) {
                displayUrl = resolved
            }
        }
    }

    return remember(displayUrl, targetSize) {
        buildImageRequest(context, displayUrl, targetSize)
    }
}

private fun buildImageRequest(context: android.content.Context, url: String, targetSize: Int): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(url)
        .scale(Scale.FILL)
        .crossfade(true)
        .crossfade(200) // smooth 200ms crossfade transition once resolved
        .allowHardware(true)
        .bitmapConfig(android.graphics.Bitmap.Config.HARDWARE)
        .allowRgb565(true)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)

    if (targetSize > 0) {
        builder.size(targetSize, targetSize)
    } else {
        builder.size(coil.size.Size.ORIGINAL)
    }

    return builder.build()
}
