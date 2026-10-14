package com.example.data.repository

import android.content.Context
import android.text.Html
import android.util.LruCache
import com.example.data.local.ArtworkCacheDao
import com.example.data.local.ArtworkCacheEntity
import com.example.data.local.MusicDatabase
import com.example.data.model.MusicTrack
import com.example.data.remote.iTunesApiService
import com.example.data.remote.iTunesApiServiceImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class AudioSource {
    JIOSAAVN,
    YOUTUBE_MUSIC,
    INTERNET_ARCHIVE
}

data class MediaItem(
    val rawTitle: String,
    val rawArtist: String?,
    val source: AudioSource,
    val sourceArtworkUrl: String
)

object ArtworkDimensions {
    const val MINI_PLAYER = 200 // 50x50dp (~100x100px) -> 200x200bb.jpg
    const val SEARCH_AND_LIST = 200 // 55x55dp (~110x110px) -> 200x200bb.jpg
    const val HOME_CARD = 600 // 136x136dp to 200x200dp (3x density ~400-600px) -> 600x600bb.jpg for crisp shelf cards
    const val HERO_CARD = 1200 // Hero banner (3x density ~900-1200px) -> 1200x1200bb.jpg ultra high-res
    const val EXPANDED_PLAYER = 1200 // 500x500dp (~1200x1200px) -> 1200x1200bb.jpg
}

fun MusicTrack.toMediaItem(): MediaItem {
    val src = when {
        source.equals("INTERNET_ARCHIVE", ignoreCase = true) || id.startsWith("ia_") || audioUrl.contains("archive.org") -> AudioSource.INTERNET_ARCHIVE
        source.equals("YOUTUBE", ignoreCase = true) || source.equals("YOUTUBE_MUSIC", ignoreCase = true) || id.startsWith("yt_") || audioUrl.contains("youtube") -> AudioSource.YOUTUBE_MUSIC
        else -> AudioSource.JIOSAAVN
    }
    return MediaItem(
        rawTitle = title,
        rawArtist = artist,
        source = src,
        sourceArtworkUrl = coverUrl
    )
}

/**
 * Multi-Source iTunes Artwork Resolver with 3-Layer Caching:
 * 1. In-Memory LruCache (up to 200 entries)
 * 2. Room Database Cache (persisted across app restarts)
 * 3. Network Fetch (iTunes Search API with Title/Artist Verification & Fallback-First strategy)
 */
class iTunesArtworkRepository(
    private val apiService: iTunesApiService,
    private val cacheDao: ArtworkCacheDao
) {
    private data class Lookup(val url: String?, val expiresAt: Long)
    private data class Flight(val result: Deferred<Lookup>, var readers: Int = 0)
    private data class RawMetadata(val title: String, val artist: String?, val source: AudioSource, val artworkUrl: String)
    private data class LookupMetadata(val title: String, val artist: String, val key: String)
    private val metadataCache = LruCache<RawMetadata, LookupMetadata>(256)
    private val parenthesizedVideoTags = Regex("(?i)\\(.*?(official|video|lyric|lyrical|remix|audio|4k|hd|visualizer|mv|music video).*?\\)")
    private val bracketedVideoTags = Regex("(?i)\\[.*?(official|video|lyric|lyrical|remix|audio|4k|hd|visualizer|mv|music video).*?\\]")
    private val bracketedTags = Regex("(?i)\\[.*?\\]")
    private val whitespace = Regex("\\s+")
    private val memoryCache = LruCache<String, Lookup>(200)
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkSlots = Semaphore(4)
    private val inFlight = mutableMapOf<String, Flight>()
    init {
        lookupScope.launch {
            com.example.performance.BackgroundWorkCoordinator.awaitIdle(requireForeground = false)
            try {
                cacheDao.getNewest().forEach { entry ->
                    if (entry.rawArtworkUrl.isNotBlank()) synchronized(memoryCache) {
                        if (memoryCache.get(entry.cacheKey) == null)
                            memoryCache.put(entry.cacheKey, Lookup(entry.rawArtworkUrl, Long.MAX_VALUE))
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Live lookup remains available. */ }
        }
    }

    // A scrolling card can re-enter composition many times. Parse its unchanged metadata once.
    private fun lookupMetadata(item: MediaItem): LookupMetadata {
        val raw = RawMetadata(item.rawTitle, item.rawArtist, item.source, item.sourceArtworkUrl)
        metadataCache.get(raw)?.let { return it }
        val title = sanitizeTitle(raw.title, raw.source)
        val artist = sanitizeArtist(raw.artist, raw.source)
        return LookupMetadata(title, artist, "${title}_${artist}".lowercase()).also {
            metadataCache.put(raw, it)
        }
    }

    suspend fun resolveArtwork(item: MediaItem, targetDimension: Int): String {
        val metadata = lookupMetadata(item)
        val cleanedTitle = metadata.title
        val cleanedArtist = metadata.artist
        val cacheKey = metadata.key

        if (cleanedTitle.isBlank()) {
            return item.sourceArtworkUrl
        }

        cached(cacheKey)?.let { return it.url?.let { url -> formatUrl(url, targetDimension) } ?: item.sourceArtworkUrl }

        // A lookup belongs to all its callers. Only the last departing caller cancels its HTTP work.
        val flight = synchronized(inFlight) {
            inFlight.getOrPut(cacheKey) {
                Flight(lookupScope.async(start = CoroutineStart.LAZY) {
                    lookup(cacheKey, cleanedTitle, cleanedArtist).also { memoryCache.put(cacheKey, it) }
                })
            }.also { it.readers++ }
        }
        return try {
            flight.result.start()
            flight.result.await().url?.let { formatUrl(it, targetDimension) } ?: item.sourceArtworkUrl
        } finally {
            synchronized(inFlight) {
                if (--flight.readers == 0) {
                    if (inFlight[cacheKey] === flight) inFlight.remove(cacheKey)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }

    private fun cached(key: String): Lookup? = synchronized(memoryCache) { memoryCache.get(key)?.let {
        if (it.expiresAt > System.currentTimeMillis()) it else { memoryCache.remove(key); null }
    } }

    private suspend fun lookup(cacheKey: String, cleanedTitle: String, cleanedArtist: String): Lookup {
        // Another caller may have completed just before this flight was registered.
        cached(cacheKey)?.let { return it }

        // 2. Check Room Database Cache
        val dbUrl = withContext(Dispatchers.IO) {
            try {
                cacheDao.getUrl(cacheKey)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                null
            }
        }
        if (dbUrl != null) {
            return Lookup(dbUrl, Long.MAX_VALUE)
        }

        // 3. Network Fetch iTunes API (Offloaded to Dispatchers.IO)
        return try {
            val queryTerm = if (cleanedArtist.isNotBlank()) "$cleanedTitle $cleanedArtist" else cleanedTitle
            val response = networkSlots.withPermit {
                apiService.searchSong(term = queryTerm, media = "music", limit = 4)
            }

            // Verify title and artist matching to avoid showing incorrect artwork covers
            val matchedResult = response.results.firstOrNull { res ->
                val trackName = res.trackName?.trim().orEmpty()
                val artistName = res.artistName?.trim().orEmpty()
                if (trackName.isBlank() || res.artworkUrl100.isNullOrBlank()) return@firstOrNull false

                val titleMatch = trackName.contains(cleanedTitle, ignoreCase = true) ||
                        cleanedTitle.contains(trackName, ignoreCase = true)
                val artistMatch = if (cleanedArtist.isNotBlank() && artistName.isNotBlank()) {
                    artistName.contains(cleanedArtist, ignoreCase = true) ||
                            cleanedArtist.contains(artistName, ignoreCase = true)
                } else true

                titleMatch && artistMatch
            } ?: response.results.firstOrNull()?.takeIf { res ->
                // If only 1 result returned, verify title at least partially matches
                val trackName = res.trackName.orEmpty()
                trackName.contains(cleanedTitle, ignoreCase = true) || cleanedTitle.contains(trackName, ignoreCase = true)
            }

            val rawUrl = matchedResult?.artworkUrl100

            if (rawUrl != null) {
                withContext(Dispatchers.IO) {
                    try {
                        cacheDao.insert(ArtworkCacheEntity(cacheKey, rawUrl))
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                }
                Lookup(rawUrl, Long.MAX_VALUE)
            } else {
                Lookup(null, System.currentTimeMillis() + 3_600_000L)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            Lookup(null, System.currentTimeMillis() + 30_000L)
        }
    }

    fun getMemoryCachedUrl(item: MediaItem, targetDimension: Int): String? {
        val hit = cached(lookupMetadata(item).key) ?: return null
        return hit.url?.let { formatUrl(it, targetDimension) } ?: item.sourceArtworkUrl
    }

    /** UI-safe peek: reuse prepared metadata without sanitizing it or accessing Room/network. */
    fun peekMemoryCachedUrl(item: MediaItem, targetDimension: Int): String? {
        val metadata = metadataCache.get(RawMetadata(item.rawTitle, item.rawArtist, item.source, item.sourceArtworkUrl))
            ?: return null
        return cached(metadata.key)?.url?.let { formatUrl(it, targetDimension) }
    }

    fun formatUrl(url: String, size: Int): String {
        return when {
            url.contains("100x100bb") -> url.replace("100x100bb", "${size}x${size}bb")
            url.contains("100x100") -> url.replace("100x100", "${size}x${size}")
            else -> url
        }
    }

    fun sanitizeTitle(title: String, source: AudioSource? = null): String {
        var t = title

        // 1. JioSaavn / General HTML entities stripping
        if (source == AudioSource.JIOSAAVN || t.contains("&")) {
            t = try {
                Html.fromHtml(t, Html.FROM_HTML_MODE_LEGACY).toString()
            } catch (_: Exception) {
                t.replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&#039;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
            }
        }

        // 2. YouTube Music API: Separate artist name from song title if passed as "Artist - Song Title"
        if (source == AudioSource.YOUTUBE_MUSIC || t.contains(" - ")) {
            if (t.contains(" - ")) {
                val parts = t.split(" - ")
                if (parts.size >= 2) {
                    val rightPart = parts[1].trim()
                    if (rightPart.isNotBlank()) {
                        t = rightPart
                    }
                }
            }
        }

        // 3. YouTube Music & General: Remove video-specific titles
        t = t.replace(parenthesizedVideoTags, "")
            .replace(bracketedVideoTags, "")
            .replace(bracketedTags, "")

        // 4. Internet Archive API: Clean up filename-style titles (replace _ and - with spaces)
        if (source == AudioSource.INTERNET_ARCHIVE || t.contains("_")) {
            t = t.replace("_", " ").replace("-", " ")
        }

        return t.replace(whitespace, " ").trim()
    }

    fun sanitizeArtist(artist: String?, source: AudioSource? = null): String {
        if (artist.isNullOrBlank() ||
            artist.equals("Unknown", ignoreCase = true) ||
            artist.equals("Various", ignoreCase = true) ||
            artist.equals("Internet Archive", ignoreCase = true)
        ) {
            return ""
        }
        var a = artist

        // JioSaavn: Strip html entities
        if (source == AudioSource.JIOSAAVN || a.contains("&")) {
            a = a.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
        }

        // Clean up guest artists/featuring tags to keep only main artist
        return a.split(",", "&", "feat.", "ft.", "Feat.", "Ft.", "featuring", "Featuring", "/", ";")
            .firstOrNull()
            ?.trim()
            ?: ""
    }

    companion object {
        @Volatile
        private var INSTANCE: iTunesArtworkRepository? = null

        fun getInstance(context: Context): iTunesArtworkRepository {
            return INSTANCE ?: synchronized(this) {
                val db = MusicDatabase.getDatabase(context)
                val repo = iTunesArtworkRepository(
                    apiService = iTunesApiServiceImpl(),
                    cacheDao = db.artworkCacheDao()
                )
                INSTANCE = repo
                repo
            }
        }
    }
}

typealias ITunesArtworkRepository = iTunesArtworkRepository
