package com.example.ui.util

import com.example.performance.AppDispatchers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Scale
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Hidden retained tabs may reuse memory images, but never fetch/decode unseen artwork. */
val LocalArtworkRequestsActive = compositionLocalOf { true }

internal const val ARTWORK_VISIBILITY_PARAMETER = "com.example.artwork.visibility"

internal const val PLAYLIST_COVER_PARAMETER = "com.example.artwork.playlist_cover"

/** Playlist artwork uses its supplied provider URL. Only URL/size change the request. */
@Composable
fun rememberPlaylistCoverRequest(
    url: String,
    targetSize: Int = ImageConfig.PLAYLIST_HEADER_SIZE,
    loadAllowed: Boolean = true
): ImageRequest? {
    val context = LocalContext.current
    val active = LocalArtworkRequestsActive.current && loadAllowed
    val visibility = remember(context, url, targetSize) { ArtworkVisibility(active) }
    SideEffect { visibility.active.value = active }
    val previouslyRequested = remember(url) { mutableStateOf(false) }
    // Keep an existing painter/request when a retained tab hides. Never start
    // artwork for a hidden, never-visited row, including lazy precomposition.
    SideEffect { if (active) previouslyRequested.value = true }
    if (url.isBlank() || !active && !previouslyRequested.value) return null
    val dimension = mapTargetSizeToArtworkDimension(targetSize)
    return remember(context, url, dimension, visibility) {
        buildPlaylistCoverRequest(context, url, dimension, visibility)
    }
}

internal fun buildPlaylistCoverRequest(context: android.content.Context, url: String,
    dimension: Int, visibility: ArtworkVisibility): ImageRequest {
        val source = providerArtworkUrl(url, maxOf(600, dimension))
    val memoryKey = "playlist-cover:$dimension:$source"
        return ImageRequest.Builder(context)
            .data(source)
            .setParameter(PLAYLIST_COVER_PARAMETER, true, null)
            .setParameter(ARTWORK_VISIBILITY_PARAMETER, visibility, null)
            .memoryCacheKey(memoryKey)
            .placeholderMemoryCacheKey(memoryKey)
            // Grid/detail decode identities may differ, but share the original file.
            .diskCacheKey(source)
            .size(dimension, dimension)
            .interceptorDispatcher(AppDispatchers.Io)
            .fetcherDispatcher(AppDispatchers.Io)
            .decoderDispatcher(AppDispatchers.CpuBounded)
            .scale(Scale.FILL)
            .allowHardware(true)
            .bitmapConfig(if (android.os.Build.VERSION.SDK_INT >= 26) android.graphics.Bitmap.Config.HARDWARE else android.graphics.Bitmap.Config.ARGB_8888)
            .allowRgb565(false)
            .crossfade(200)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .listener(onSuccess = { _, result ->
                // Memory hits also keep this source recent in the persistent LRU
                // index. This only touches RAM; maintenance owns disk persistence.
                ((context.applicationContext as? com.example.XtremeMusicApp)?.initializedImageDiskCache
                    as? com.example.data.cache.ManagedImageDiskCache)?.recordAccess(source)
                if (com.example.BuildConfig.DEBUG) android.util.Log.d("PlaylistArtwork",
                    "cover=${Integer.toHexString(url.hashCode())} size=$dimension source=${result.dataSource}")
            })
            .build()
}

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
                    val text = storage.openRead().use { String(it.readBytes()) }
                    kotlinx.coroutines.withContext(AppDispatchers.CpuBounded) {
                    val json = org.json.JSONObject(text)
                    json.keys().asSequence().take(128).forEach { key ->
                        val item = json.getJSONArray(key)
                        synchronized(memoryCache) {
                            if (clearGeneration == readGeneration && memoryCache.get(key) == null)
                                memoryCache.put(key, ExtractedTrackColors(
                                    Color(item.getInt(0)), Color(item.getInt(1)), Color(item.getInt(2))))
                        }
                    }
                    }
                }
            } catch (_: Exception) { /* A palette miss never prevents artwork/playback. */ }
            for (snapshot in pending) {
                try {
                    val content = kotlinx.coroutines.withContext(AppDispatchers.CpuBounded) {
                    val json = org.json.JSONObject()
                    snapshot.forEach { (key, c) -> json.put(key, org.json.JSONArray().put(c.dominantColor.toArgb())
                        .put(c.accentColor.toArgb()).put(c.vibrantColor.toArgb())) }
                    json.toString().toByteArray()
                    }
                    val stream = storage.startWrite()
                    try { stream.write(content); storage.finishWrite(stream) }
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
        targetSize >= 2000 -> targetSize.coerceAtMost(3000)
        targetSize >= 900 -> 1200 // 1200
        targetSize >= 500 || targetSize < 0 -> 1200 // 1200
        targetSize in 250..899 -> 600 // 600
        targetSize in 150..249 -> 200 // 200
        else -> 200 // 200
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
    val visibleContent = LocalArtworkRequestsActive.current
    val visibility = remember(context, url, track?.id, track?.title, track?.artist, track?.album,
        track?.durationMs, track?.coverUrl, targetSize, decodeSize) {
        ArtworkVisibility(visibleContent)
    }
    SideEffect { visibility.active.value = visibleContent }
    // Transport URL/bitrate changes do not change artwork. Metadata edits and a larger
    // destination do create a new request, including when a recycled row keeps its ID.
    return remember(context, url, track?.id, track?.title, track?.artist, track?.album,
        track?.durationMs, track?.coverUrl, targetSize, crossfadeEnabled, preferStableArtwork, decodeSize, visibility) {
        buildOptimizedImageRequest(context, url, targetSize, track, crossfadeEnabled,
            preferStableArtwork, decodeSize, visibleContent, visibility = visibility)
    }
}

/** Same identity/cache keys for bounded startup prefetch and the eventual visible target. */
internal fun buildOptimizedImageRequest(
    context: android.content.Context,
    url: String,
    targetSize: Int,
    track: MusicTrack?,
    crossfadeEnabled: Boolean = false,
    preferStableArtwork: Boolean = true,
    decodeSize: IntSize? = null,
    visibleContent: Boolean = true,
    startupPreparation: Boolean = false,
    visibility: ArtworkVisibility? = null,
    currentTrack: Boolean = false
): ImageRequest {
    val metadata = ArtworkRequestMetadata(
        trackId = track?.id,
        providerUrl = url,
        sourceDimension = mapTargetSizeToArtworkDimension(targetSize),
        preferStableArtwork = preferStableArtwork,
        targetSize = targetSize,
        visibleContent = visibleContent,
        startupPreparation = startupPreparation,
        visibility = visibility,
        currentTrack = currentTrack
    )
    // Direct provider URL: no Room lookup or search before the image can load.
    val initialUrl = providerArtworkUrl(url, metadata.sourceDimension).ifBlank {
        youtubeArtworkAlternatives(track?.id, emptyList()).firstOrNull().orEmpty()
    }
    val placeholder = if (currentTrack) {
        // Reuse the same catalog cover already shown on Home/Search while its
        // full-player rendition downloads. These are RAM peeks, never image lookups.
        val candidates = listOf(initialUrl, providerArtworkUrl(url, 1200), providerArtworkUrl(url, 600),
            providerArtworkUrl(url, 200), url).filter(String::isNotBlank).distinct()
        val memory = context.imageLoader.memoryCache
        candidates.firstOrNull { memory?.get(MemoryCache.Key(it)) != null } ?: initialUrl
    } else initialUrl
    return ImageRequest.Builder(context)
        .data(initialUrl)
        .setParameter(ARTWORK_METADATA_PARAMETER, metadata, null)
        .placeholderMemoryCacheKey(placeholder.takeIf(String::isNotBlank))
        .interceptorDispatcher(AppDispatchers.Io)
        .fetcherDispatcher(AppDispatchers.Io)
        // One process-wide CPU budget, including explicitly opened player covers.
        .decoderDispatcher(AppDispatchers.CpuBounded)
        .scale(Scale.FILL)
        .crossfade(if (crossfadeEnabled) 200 else 0)
        .allowHardware(true)
        .bitmapConfig(if (android.os.Build.VERSION.SDK_INT >= 26) android.graphics.Bitmap.Config.HARDWARE else android.graphics.Bitmap.Config.ARGB_8888)
        .allowRgb565(false)
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
