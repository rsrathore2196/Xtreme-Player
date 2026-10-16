package com.example.data.remote

import android.content.Context
import android.text.Html
import android.util.Log
import com.example.config.SecurityConfig
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object OnlineMusicApiService {
    private const val TAG = "OnlineMusicApiService"
    @Volatile private var context: Context? = null
    private val saavnCdnPattern = Regex("(?i)^https?://(?:[a-z0-9-]+\\.)*saavncdn\\.com(?:/|:)")
    private val bitrateSuffixPattern = Regex("_(?:320|160|96|48)\\.(mp4|m4a)(?=$|[?#])")
    private val streamBitratePattern = Regex("_(320|160|96|48)\\.(?:mp4|m4a)(?=$|[?#])")

    fun initialize(applicationContext: Context) {
        context = applicationContext
    }

    /**
     * Resolve on the first decryption in the IO parsing paths, never during initialization.
     * Lazy publication shares one securely resolved key across parsing threads.
     */
    private val desKeySpec: SecretKeySpec by lazy {
        val ctx = context
        val key = if (ctx != null) {
            SecurityConfig.getDESKey(ctx)
        } else {
            SecurityConfig.getFallbackDESKey()
        }
        SecretKeySpec(key.toByteArray(Charsets.UTF_8), 0, 8, "DES")
    }

    private val mediaUrlCiphers = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher = Cipher.getInstance("DES/ECB/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, desKeySpec)
        }
    }

    data class SongSearchPage(val tracks: List<MusicTrack>, val nextPage: Int?)
    private val memoryQueryCache = object : android.util.LruCache<String, Pair<Long, SongSearchPage>>(60) {}
    private val queryLocks = Array(16) { Mutex() }

    private val httpClientDelegate = lazy {
        val cache = context?.let { ctx ->
            try {
                val httpCacheDirectory = java.io.File(ctx.cacheDir, "http_music_cache")
                okhttp3.Cache(httpCacheDirectory, 40L * 1024 * 1024) // 40MB smart disk cache
            } catch (_: Exception) {
                null
            }
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
        if (cache != null) {
            builder.cache(cache)
        }
        builder.build()
    }
    private val httpClient by httpClientDelegate

    /** Maintenance stays with OkHttp, including open responses and the disk journal. */
    fun trimNetworkCache(targetBytes: Long): Long {
        if (!httpClientDelegate.isInitialized()) return 0L
        val cache = httpClient.cache ?: return 0L
        return try {
            val before = cache.size()
            if (targetBytes <= 0L) cache.evictAll() else {
                val entries = cache.urls()
                while (cache.size() > targetBytes && entries.hasNext()) {
                    entries.next()
                    entries.remove()
                }
            }
            (before - cache.size()).coerceAtLeast(0L)
        } catch (e: Exception) {
            Log.w(TAG, "HTTP cache maintenance failed: ${e.message}")
            0L
        }
    }

    /**
     * Decrypts JioSaavn's DES-ECB encrypted media URL and upgrades it to requested kbps stream.
     */
    fun decryptMediaUrl(encryptedUrl: String, targetQuality: String = "320"): String? {
        if (encryptedUrl.isBlank()) return null
        return try {
            val cipher = mediaUrlCiphers.get()!!

            val decodedBytes = try {
                java.util.Base64.getDecoder().decode(encryptedUrl)
            } catch (_: Throwable) {
                try {
                    android.util.Base64.decode(encryptedUrl, android.util.Base64.DEFAULT)
                } catch (e2: Throwable) {
                    throw e2
                }
            }
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val rawUrl = String(decryptedBytes, Charsets.UTF_8)

            formatUrlForQuality(rawUrl, targetQuality)
        } catch (e: Exception) {
            // A successful doFinal resets the cipher; discard any state left by a failure.
            mediaUrlCiphers.remove()
            try {
                Log.e(TAG, "Error decrypting media URL: ${e.message}")
            } catch (_: Throwable) {
                System.err.println("Error decrypting media URL: ${e.message}")
            }
            null
        }
    }

    /**
     * Formats an audio stream URL to the requested bitrate quality (lossless/320, 160, 96).
     */
    fun formatUrlForQuality(rawUrl: String, quality: String): String {
        if (quality == "original" || !saavnCdnPattern.containsMatchIn(rawUrl)) return rawUrl
        val q = when (quality.lowercase().trim()) {
            "lossless", "hires", "flac" -> "320" // Highest available studio master bit-stream
            "320", "ultrahd" -> "320"
            "160", "high" -> "160"
            "96", "medium", "standard", "datasaver" -> "96"
            "48" -> "48"
            else -> "320"
        }
        // Rewrite only a known CDN bitrate suffix, preserving extensions and signed queries.
        // Appending to arbitrary .m4a paths produced URLs such as song_320_320.mp4.
        return rawUrl.replace(bitrateSuffixPattern) {
            "_${q}.${it.groupValues[1]}"
        }
    }

    /**
     * Search songs online across JioSaavn's comprehensive global and Indian library.
     */
    suspend fun searchSongs(query: String, limit: Int = 30, page: Int = 1, forceRefresh: Boolean = false): List<MusicTrack> = try {
        searchSongPage(query, limit, page, forceRefresh).tracks
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Log.w(TAG, "Online search failed: ${e.message}"); emptyList() }

    /** The same cached request serves paged search, imports and legacy song lookups. */
    suspend fun searchSongPage(query: String, limit: Int = 30, page: Int = 1,
        forceRefresh: Boolean = false): SongSearchPage = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SongSearchPage(emptyList(), null)
        val rows = limit.coerceIn(1, 50)
        val cacheKey = "${trimmed.lowercase(java.util.Locale.ROOT)}_${page}_$rows"
        queryLocks[(cacheKey.hashCode() and Int.MAX_VALUE) % queryLocks.size].withLock {
        synchronized(memoryQueryCache) {
            if (forceRefresh) memoryQueryCache.remove(cacheKey)
            else memoryQueryCache.get(cacheKey)?.let {
                val ttl = if (it.second.tracks.isEmpty()) 30_000L else 300_000L
                if (System.currentTimeMillis() - it.first < ttl) return@withLock it.second
                memoryQueryCache.remove(cacheKey)
            }
        }

        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&cc=in&includeMetaTags=1&p=$page&n=$rows&q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .apply { if (forceRefresh) cacheControl(okhttp3.CacheControl.FORCE_NETWORK) }
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json")
                .build()

            val bodyString = httpClient.catalogRequest(request)
            val parsed = withContext(Dispatchers.Default) {
                val root = JSONObject(bodyString)
                val results = root.optJSONArray("results") ?: throw java.io.IOException("Invalid song catalogue response")
                val tracks = (0 until results.length()).mapNotNull { index ->
                    results.optJSONObject(index)?.let { parseSong(it, allowMissingStream = true) }
                }
                val more = results.length() > 0 && if (root.has("total"))
                    page.toLong() * rows < root.optLong("total") else results.length() >= rows
                SongSearchPage(tracks, if (more) page + 1 else null)
            }
            synchronized(memoryQueryCache) { memoryQueryCache.put(cacheKey, System.currentTimeMillis() to parsed) }
            parsed
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "Online search failed: ${e.message}")
            throw e
        }
        }
    }

    private val trendingCache = ConcurrentHashMap<Int, List<MusicTrack>>()

    /**
     * Fetch trending online hits for Home screen carousel and Explore feed.
     * Parallelized with coroutines for non-blocking high-speed response.
     */
    suspend fun getTrendingSongs(limit: Int = 25): List<MusicTrack> = withContext(Dispatchers.IO) {
        trendingCache[limit]?.let { cached ->
            if (cached.isNotEmpty()) return@withContext cached
        }

        val candidates = listOf("Top Global Hits", "Viral Pop Hits", "Trending Bollywood Hits", "Top Punjabi Hits", "Indie Music Hits")
        val results = coroutineScope {
            val deferreds = candidates.map { candidate ->
                async {
                    try {
                        searchSongs(candidate, limit = 10)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch trending from $candidate: ${e.message}")
                        emptyList()
                    }
                }
            }
            deferreds.awaitAll()
        }

        val diversified = com.example.recommendation.AlbumArtDeduplicator.interleaveAndDiversify(results, limit)
        if (diversified.isNotEmpty()) {
            trendingCache[limit] = diversified
        }
        diversified
    }

    private val profileTrendingCache = ConcurrentHashMap<String, List<MusicTrack>>()

    /**
     * Fetch country-specific and language-specific trending songs tailored to user profile.
     * Uses iconic regional genres and famous chart hits, ensuring songs don't just have the country name in their title.
     * Fetches concurrently for high-speed app startup.
     */
    suspend fun getTrendingSongsForProfile(
        country: String,
        languages: List<String>,
        limit: Int = 25
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val cacheKey = "${country}_${languages.sorted().joinToString(",")}_$limit"
        profileTrendingCache[cacheKey]?.let { cached ->
            if (cached.isNotEmpty()) return@withContext cached
        }

        val candidates = mutableListOf<String>()
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        for (lang in languages.take(3)) {
            candidates.add("New $lang Songs $currentYear")
            candidates.add("Latest $lang Hits $currentYear")
            candidates.add("Top $lang Songs")
        }
        if (country.isNotBlank()) {
            val famousQueries = com.example.data.model.CountryData.getFamousMusicQueriesForCountry(country)
            candidates.addAll(famousQueries.shuffled().take(2))
        }
        candidates.add("Top Global Hits $currentYear")

        val distinctQueries = candidates.distinct().take(4)
        val deferredList = distinctQueries.map { candidate ->
            async {
                try {
                    val results = searchSongs(candidate, limit = 10)
                    results.filter { !com.example.data.model.CountryData.hasCountryNameInTitle(it.title, country) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed query for profile candidate $candidate: ${e.message}")
                    emptyList()
                }
            }
        }

        val allResults = deferredList.awaitAll()
        val diversified = com.example.recommendation.AlbumArtDeduplicator.interleaveAndDiversify(allResults, limit)

        val finalResult = if (diversified.isNotEmpty()) {
            diversified
        } else {
            getTrendingSongs(limit).filter { !com.example.data.model.CountryData.hasCountryNameInTitle(it.title, country) }
        }

        if (finalResult.isNotEmpty()) {
            profileTrendingCache[cacheKey] = finalResult
        }
        finalResult
    }

    /**
     * Fetch songs by specific genre or mood with diverse queries and shuffling every time opened.
     */
    suspend fun getSongsByGenre(genre: String, limit: Int = 25): List<MusicTrack> = withContext(Dispatchers.IO) {
        val queryCandidates = when (genre.lowercase()) {
            "all" -> listOf("Top Hits", "Trending Global", "Viral Hits", "Hot Tracks", "Billboard 100")
            "pop" -> listOf("Top Pop Hits", "Viral Pop", "Pop Hits 2024", "English Pop Hits", "Dance Pop", "Modern Pop", "Pop Anthems")
            "hip-hop", "hiphop" -> listOf("Hip Hop Rap", "Top Rap Hits", "Trap Music", "Global Hip Hop", "Rap Classics", "Urban Beats")
            "rock" -> listOf("Rock Hits", "Classic Rock", "Alternative Rock", "Modern Rock", "Hard Rock", "Indie Rock")
            "electronic" -> listOf("Electronic Dance EDM", "Festival EDM", "Club Dance Hits", "House Music", "Electro Pop", "Bass Boosted EDM")
            "bollywood" -> listOf("Latest Bollywood Hits", "Bollywood Romantic Songs", "Bollywood Party", "Best Hindi Hits", "Retro Bollywood", "Arijit Singh Hits")
            "punjabi" -> listOf("Top Punjabi Hits", "Latest Punjabi Songs", "Punjabi Pop", "Bhangra Hits", "Sidhu Moose Wala", "Diljit Dosanjh", "Karan Aujla")
            "synthwave" -> listOf("Synthwave Retrowave", "80s Retro Electro", "Outrun Synth", "Cyberpunk Synthwave", "Darksynth")
            "chillhop" -> listOf("Chillhop Essentials", "Lofi Chillhop", "Coffee Chill Beats", "Chillhop Beats")
            "lo-fi", "lofi" -> listOf("Lo-Fi Beats Chill", "Lofi Study Beats", "Chillhop Music", "Lofi Sleep Beats", "Late Night Lofi")
            "jazz" -> listOf("Smooth Jazz", "Coffee Jazz", "Classic Jazz Standards", "Late Night Jazz", "Bebop Jazz")
            "ambient" -> listOf("Ambient Chillout", "Deep Ambient Meditation", "Atmospheric Soundscapes", "Calm Ambient Space")
            else -> listOf("$genre Songs", "$genre Hits", "$genre Top Tracks", "Best of $genre", "$genre Mix")
        }

        val selectedQueries = queryCandidates.shuffled().take(3)
        val randomPage = (1..3).random()
        val queryResults = mutableListOf<List<MusicTrack>>()

        for (candidateQuery in selectedQueries) {
            try {
                val songs = searchSongs(candidateQuery, limit = 12, page = randomPage)
                if (songs.isNotEmpty()) {
                    queryResults.add(songs)
                } else if (randomPage > 1) {
                    val fallbackSongs = searchSongs(candidateQuery, limit = 12, page = 1)
                    if (fallbackSongs.isNotEmpty()) queryResults.add(fallbackSongs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching genre songs for $candidateQuery: ${e.message}")
            }
        }

        if (queryResults.isEmpty()) {
            val fallbackQuery = queryCandidates.first()
            val fallbackSongs = searchSongs(fallbackQuery, limit = limit, page = 1)
            if (fallbackSongs.isNotEmpty()) queryResults.add(fallbackSongs)
        }

        com.example.recommendation.AlbumArtDeduplicator.interleaveAndDiversify(queryResults, limit)
    }

    /** Both legacy search and v4 catalog responses describe the same real song IDs. */
    internal fun parseSong(item: JSONObject, allowMissingStream: Boolean = false): MusicTrack? {
        fun value(objectValue: JSONObject?, key: String): String = objectValue?.optString(key)
            ?.takeUnless { it == "null" }?.let(::cleanHtml).orEmpty()
        val info = item.optJSONObject("more_info")
        fun field(key: String) = value(info, key).ifBlank { value(item, key) }
        val id = value(item, "id")
        val title = value(item, "title").ifBlank { value(item, "song") }
        if (id.isBlank() || title.isBlank() || value(item, "type").let { it.isNotBlank() && it != "song" }) return null

        val artistMap = info?.optJSONObject("artistMap")
        fun artistNames(key: String, performersOnly: Boolean = false): List<String> {
            val entries = artistMap?.optJSONArray(key) ?: return emptyList()
            return (0 until entries.length()).mapNotNull { index ->
                val credit = entries.optJSONObject(index) ?: return@mapNotNull null
                if (performersOnly && value(credit, "role").lowercase() !in
                    setOf("singer", "vocalist", "primary_artists", "featured_artists")) return@mapNotNull null
                value(credit, "name").ifBlank { value(credit, "title") }.takeIf(String::isNotBlank)
            }
        }
        val performers = (artistNames("primary_artists") + artistNames("featured_artists") +
            artistNames("artists", performersOnly = true)).distinct()
        val artist = performers.joinToString(", ").ifBlank {
            field("primary_artists").ifBlank { field("singers") }
        }.ifBlank { "Unknown Artist" }
        val availability = field("320kbps").lowercase()
        val requestedBitrate = when (availability) { "true", "1" -> "320"; "false", "0" -> "160"; else -> "original" }
        val stream = field("encrypted_media_url").takeIf(String::isNotBlank)
            ?.let { decryptMediaUrl(it, requestedBitrate) }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
        // Preview clips are not substitutes for full songs. Metadata-only catalog rows can
        // obtain their full stream later through song.getDetails using this exact ID.
        if (stream.isBlank() && !allowMissingStream) return null
        val bitrate = when (availability) {
            "true", "1" -> 320
            "false", "0" -> 160
            else -> streamBitratePattern.find(stream)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return MusicTrack(id = "online_$id", title = title, artist = artist,
            album = field("album").ifBlank { "Unknown" },
            durationMs = (field("duration").toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1000L,
            coverUrl = field("image").replace("150x150", "500x500").replace("50x50", "500x500").replaceFirst("http://", "https://"),
            audioUrl = stream, bitrateKbps = bitrate,
            qualityBadge = if (bitrate > 0) "HD • $bitrate kbps" else "HQ Stream",
            singers = field("singers").ifBlank { performers.joinToString(", ") },
            writer = field("music"), language = field("language").ifBlank { "Unknown" },
            genre = field("genre").ifBlank { "Unknown" }, year = field("year"), isrc = field("isrc"))
    }

    suspend fun fetchSongDetails(songId: String, forceRefresh: Boolean = false): MusicTrack? = withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext null
        try {
            val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("__call", "song.getDetails").addQueryParameter("pids", songId)
                .addQueryParameter("api_version", "4").addQueryParameter("ctx", "web6dot0")
                .addQueryParameter("_format", "json").addQueryParameter("_marker", "0").build()
            val request = Request.Builder().url(url)
                .apply { if (forceRefresh) cacheControl(okhttp3.CacheControl.FORCE_NETWORK) }
                .addHeader("User-Agent", "Mozilla/5.0").build()
            val root = JSONObject(httpClient.catalogRequest(request))
            val songs = root.optJSONArray("songs")
            val exact = root.optJSONObject(songId) ?: (0 until (songs?.length() ?: 0))
                .mapNotNull { songs?.optJSONObject(it) }.firstOrNull { it.optString("id") == songId }
                ?: root.takeIf { it.optString("id") == songId }
            exact?.takeIf { it.optString("id") == songId }?.let { parseSong(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "Song details unavailable: ${e.message}"); null }
    }

    private fun cleanHtml(raw: String): String {
        return try {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } catch (e: Exception) {
            raw.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
        }
    }

    /**
     * Look up exact ISRC fingerprint for a JioSaavn song ID
     */
    suspend fun fetchSongIsrc(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=$songId"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val body = httpClient.catalogRequest(request)
            val root = JSONObject(body)
            val songObj = root.optJSONObject(songId)
                ?: root.optJSONArray("songs")?.optJSONObject(0)
                ?: root
            val isrc = songObj.optString("isrc", "").ifBlank {
                songObj.optJSONObject("more_info")?.optString("isrc", "") ?: ""
            }
            if (isrc.isNotBlank()) isrc.trim() else null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch song ISRC: ${e.message}")
            null
        }
    }
}
