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
    const val HOME_CARD = 400 // 200x200dp (~300x300px) -> 400x400bb.jpg
    const val HERO_CARD = 600 // 300x300dp (~600x600px) -> 600x600bb.jpg
    const val EXPANDED_PLAYER = 800 // 500x500dp (~800x800px) -> 800x800bb.jpg
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
 * 3. Network Fetch (iTunes Search API with Fallback-First strategy)
 */
class iTunesArtworkRepository(
    private val apiService: iTunesApiService,
    private val cacheDao: ArtworkCacheDao
) {
    private val memoryCache = LruCache<String, String>(200)

    suspend fun resolveArtwork(item: MediaItem, targetDimension: Int): String {
        val cleanedTitle = sanitizeTitle(item.rawTitle, item.source)
        val cleanedArtist = sanitizeArtist(item.rawArtist, item.source)
        val cacheKey = "${cleanedTitle}_${cleanedArtist}".lowercase()

        if (cleanedTitle.isBlank()) {
            return item.sourceArtworkUrl
        }

        // 1. Check LruCache (instant synchronous lookup)
        memoryCache.get(cacheKey)?.let { return formatUrl(it, targetDimension) }

        // 2. Check Room Database Cache
        val dbUrl = withContext(Dispatchers.IO) {
            try {
                cacheDao.getUrl(cacheKey)
            } catch (_: Exception) {
                null
            }
        }
        if (dbUrl != null) {
            memoryCache.put(cacheKey, dbUrl)
            return formatUrl(dbUrl, targetDimension)
        }

        // 3. Network Fetch iTunes API (Offloaded to Dispatchers.IO)
        return try {
            val queryTerm = if (cleanedArtist.isNotBlank()) "$cleanedTitle $cleanedArtist" else cleanedTitle
            val response = withContext(Dispatchers.IO) {
                apiService.searchSong(term = queryTerm, media = "music", limit = 1)
            }
            val rawUrl = response.results.firstOrNull()?.artworkUrl100

            if (rawUrl != null) {
                withContext(Dispatchers.IO) {
                    try {
                        cacheDao.insert(ArtworkCacheEntity(cacheKey, rawUrl))
                    } catch (_: Exception) {}
                }
                memoryCache.put(cacheKey, rawUrl)
                formatUrl(rawUrl, targetDimension)
            } else {
                item.sourceArtworkUrl // Fallback to original API image if not found on iTunes
            }
        } catch (_: Exception) {
            item.sourceArtworkUrl // Fallback on network failure
        }
    }

    fun getMemoryCachedUrl(item: MediaItem, targetDimension: Int): String? {
        val cleanedTitle = sanitizeTitle(item.rawTitle, item.source)
        val cleanedArtist = sanitizeArtist(item.rawArtist, item.source)
        val cacheKey = "${cleanedTitle}_${cleanedArtist}".lowercase()
        val raw = memoryCache.get(cacheKey) ?: return null
        return formatUrl(raw, targetDimension)
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
        t = t.replace(Regex("(?i)\\(.*?(official|video|lyric|lyrical|remix|audio|4k|hd|visualizer|mv|music video).*?\\)"), "")
            .replace(Regex("(?i)\\[.*?(official|video|lyric|lyrical|remix|audio|4k|hd|visualizer|mv|music video).*?\\]"), "")
            .replace(Regex("(?i)\\[.*?\\]"), "")

        // 4. Internet Archive API: Clean up filename-style titles (replace _ and - with spaces)
        if (source == AudioSource.INTERNET_ARCHIVE || t.contains("_")) {
            t = t.replace("_", " ").replace("-", " ")
        }

        return t.replace(Regex("\\s+"), " ").trim()
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
