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
    val sourceArtworkUrl: String,
    val durationMs: Long = 0L,
    val album: String = ""
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
        sourceArtworkUrl = coverUrl,
        durationMs = durationMs,
        album = album
    )
}

/**
 * Multi-Source iTunes Artwork Resolver with 3-Layer Caching:
 * 1. In-Memory LruCache (up to 2048 entries)
 * 2. Room Database Cache (persisted across app restarts)
 * 3. Network Fetch (iTunes Search API with Title/Artist Verification & Fallback-First strategy)
 */
class iTunesArtworkRepository(
    private val apiService: iTunesApiService,
    private val cacheDao: ArtworkCacheDao,
    private val lookupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private data class Lookup(val url: String?, val expiresAt: Long)
    private data class Flight(val result: Deferred<Lookup>, var readers: Int = 0)
    private data class RawMetadata(val title: String, val artist: String?, val source: AudioSource)
    private data class LookupMetadata(val title: String, val artist: String, val key: String)
    private val metadataCache = LruCache<RawMetadata, LookupMetadata>(256)
    private val whitespace = Regex("\\s+")
    private val memoryCache = LruCache<String, Lookup>(2048)
    private val networkSlots = Semaphore(2)
    private val inFlight = mutableMapOf<String, Flight>()
    init {
        lookupScope.launch {
            try {
                com.example.performance.BackgroundWorkCoordinator.startup(
                    com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                    val restored = cacheDao.getNewest(limit = 2048)
                    withContext(com.example.performance.AppDispatchers.CpuBounded) {
                        restored.asReversed().forEachIndexed { index, entry ->
                            if (index % 64 == 0) com.example.performance.BackgroundWorkCoordinator.yieldToInteraction()
                            if (entry.rawArtworkUrl.isNotBlank()) synchronized(memoryCache) {
                                if (memoryCache.get(entry.cacheKey) == null)
                                    memoryCache.put(entry.cacheKey, Lookup(entry.rawArtworkUrl, Long.MAX_VALUE))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Live lookup remains available. */ }
        }
    }

    // A scrolling card can re-enter composition many times. Parse its unchanged metadata once.
    private fun lookupMetadata(item: MediaItem): LookupMetadata {
        val raw = RawMetadata(item.rawTitle, item.rawArtist, item.source)
        metadataCache.get(raw)?.let { return it }
        val title = sanitizeTitle(raw.title, source = raw.source, artist = raw.artist)
        val artist = sanitizeArtist(raw.artist, source = raw.source)
        return LookupMetadata(title, artist, "${title}_${artist}".lowercase(java.util.Locale.ROOT)).also {
            metadataCache.put(raw, it)
        }
    }

    suspend fun resolveArtwork(item: MediaItem, targetDimension: Int): String = withContext(com.example.performance.BackgroundWorkCoordinator.optionalCpuDispatcher) {
        val metadata = lookupMetadata(item)
        val cleanedTitle = metadata.title
        val cleanedArtist = metadata.artist
        val cacheKey = metadata.key

        if (cleanedTitle.isBlank()) {
            return@withContext item.sourceArtworkUrl
        }

        cached(cacheKey)?.let { return@withContext it.url?.let { url -> formatUrl(url, targetDimension) } ?: item.sourceArtworkUrl }

        // A lookup belongs to all its callers. Only the last departing caller cancels its HTTP work.
        val flight = synchronized(inFlight) {
            inFlight.getOrPut(cacheKey) {
                Flight(lookupScope.async(start = CoroutineStart.LAZY) {
                    lookup(cacheKey, cleanedTitle, cleanedArtist, item.durationMs, item.album).also { memoryCache.put(cacheKey, it) }
                })
            }.also { it.readers++ }
        }
        try {
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

    private suspend fun lookup(cacheKey: String, cleanedTitle: String, cleanedArtist: String,
        durationMs: Long, album: String): Lookup {
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
            val reference = MusicTrack(id = "artwork", title = cleanedTitle, artist = cleanedArtist,
                album = album, durationMs = durationMs, coverUrl = "", audioUrl = "")
            val queries = listOf("$cleanedTitle $cleanedArtist".trim(), cleanedTitle).distinct()
            var matchedResult: com.example.data.remote.iTunesSongResult? = null
            for (queryTerm in queries) {
                val response = networkSlots.withPermit {
                    apiService.searchSong(term = queryTerm, media = "music", limit = 15)
                }
                matchedResult = withContext(com.example.performance.BackgroundWorkCoordinator.optionalCpuDispatcher) { response.results.mapNotNull { res ->
                    if (res.trackName.isNullOrBlank() || res.artistName.isNullOrBlank() || res.artworkUrl100.isNullOrBlank()) null
                    else {
                        val candidate = reference.copy(title = sanitizeTitle(res.trackName, artist = res.artistName),
                            artist = res.artistName, album = res.collectionName.orEmpty(), durationMs = res.durationMs)
                        val confidence = RecordingIdentity.fallbackConfidence(reference, candidate)
                            ?: 75.takeIf { RecordingIdentity.same(reference, candidate, requireAlbum = true) }
                        confidence?.let { res to it }
                    }
                }.maxByOrNull { it.second }?.first }
                if (matchedResult != null) break
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

    /** Worker-thread lookup of verified hits only; a negative lookup is not a resolved cover. */
    fun getMemoryCachedResolvedUrl(item: MediaItem, targetDimension: Int): String? =
        cached(lookupMetadata(item).key)?.url?.let { formatUrl(it, targetDimension) }

    /** UI-safe peek: reuse prepared metadata without sanitizing it or accessing Room/network. */
    fun peekMemoryCachedUrl(item: MediaItem, targetDimension: Int): String? {
        val metadata = metadataCache.get(RawMetadata(item.rawTitle, item.rawArtist, item.source))
            ?: return null
        return cached(metadata.key)?.url?.let { formatUrl(it, targetDimension) }
    }

    fun formatUrl(url: String, size: Int): String = formatArtworkUrl(url, size)

    fun sanitizeTitle(title: String, source: AudioSource? = null, artist: String? = null): String {
        var decoded = if (title.contains('&')) {
            runCatching { Html.fromHtml(title, Html.FROM_HTML_MODE_LEGACY)?.toString() }.getOrNull()
                ?: title.replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").replace("&apos;", "'")
        } else title
        val sourceTitle = if (source == AudioSource.INTERNET_ARCHIVE) decoded.replace('_', ' ') else decoded
        val knownArtist = artist?.let(CatalogNames::clean)?.takeIf {
            CatalogNames.normalize(it) !in unknownArtistNames
        }.orEmpty()
        // Missing artist metadata is never evidence that a hyphen separates an
        // artist prefix. The shared cleaner verifies a real credit before stripping.
        return StreamResolver.extractCleanTitle(sourceTitle, knownArtist)
            .replace(whitespace, " ").trim()
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
        private val unknownArtistNames = setOf("", "unknown", "unknown artist", "various", "various artists", "internet archive")
        /** Pure formatting must never require construction of Room or the HTTP client on Main. */
        private val appleArtworkSize = Regex("(?<=/)\\d+x\\d+(bb)?(?=\\.(?:jpg|png|webp))", RegexOption.IGNORE_CASE)
        fun formatArtworkUrl(url: String, size: Int): String {
            val dimension = size.coerceIn(1, 3_000)
            // Reformat any cached Apple size (200/600 included), not only 100x100.
            return appleArtworkSize.replace(url) { match ->
                "${dimension}x${dimension}" + match.groupValues[1]
            }
        }

        @Volatile
        private var INSTANCE: iTunesArtworkRepository? = null

        /** Never constructs Room/the API client; safe for composition's prepared-cache peek. */
        fun peekInstance(): iTunesArtworkRepository? = INSTANCE

        fun getInstance(context: Context): iTunesArtworkRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val db = MusicDatabase.getDatabase(context.applicationContext)
                    iTunesArtworkRepository(
                        apiService = iTunesApiServiceImpl(),
                        cacheDao = db.artworkCacheDao()
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}

typealias ITunesArtworkRepository = iTunesArtworkRepository
