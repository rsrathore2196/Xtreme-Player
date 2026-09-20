package com.example.data.remote

import android.content.Context
import android.text.Html
import android.util.Base64
import android.util.Log
import com.example.config.SecurityConfig
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object OnlineMusicApiService {
    private const val TAG = "OnlineMusicApiService"
    private var context: Context? = null
    private var cachedDESKey: String? = null

    fun initialize(applicationContext: Context) {
        context = applicationContext
    }

    /**
     * Get DES key from secure storage.
     * This method retrieves the key from Android KeyStore instead of hardcoding it.
     */
    private fun getDESKey(): String {
        if (cachedDESKey != null) {
            return cachedDESKey!!
        }

        val ctx = context
        cachedDESKey = if (ctx != null) {
            SecurityConfig.getDESKey(ctx)
        } else {
            SecurityConfig.getFallbackDESKey()
        }
        return cachedDESKey!!
    }

    private val memoryQueryCache = object : android.util.LruCache<String, List<MusicTrack>>(60) {}

    private val httpClient by lazy {
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

    /**
     * Decrypts JioSaavn's DES-ECB encrypted media URL and upgrades it to requested kbps stream.
     */
    fun decryptMediaUrl(encryptedUrl: String, targetQuality: String = "320"): String? {
        if (encryptedUrl.isBlank()) return null
        return try {
            val keyBytes = getDESKey().toByteArray(Charsets.UTF_8)
            val keySpec = SecretKeySpec(keyBytes, 0, 8, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)

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
            try {
                Log.e(TAG, "Error decrypting media URL: ${e.message}")
            } catch (_: Throwable) {
                System.err.println("Error decrypting media URL: ${e.message}")
            }
            null
        }
    }

    /**
     * Formats an audio stream URL to the requested bitrate quality (320, 160, 96).
     */
    fun formatUrlForQuality(rawUrl: String, quality: String): String {
        val targetSuffix = "_$quality.mp4"
        return rawUrl.replace("_320.mp4", targetSuffix)
            .replace("_160.mp4", targetSuffix)
            .replace("_96.mp4", targetSuffix)
            .replace(".m4a", targetSuffix)
    }

    /**
     * Search songs online across JioSaavn's comprehensive global and Indian library.
     */
    suspend fun searchSongs(query: String, limit: Int = 30, page: Int = 1): List<MusicTrack> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val cacheKey = "${trimmed.lowercase()}_${page}_$limit"
        synchronized(memoryQueryCache) {
            memoryQueryCache.get(cacheKey)?.let { return@withContext it }
        }

        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&includeMetaTags=1&p=$page&n=$limit&q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Search HTTP error: ${response.code}")
                return@withContext emptyList()
            }

            val bodyString = response.body?.string() ?: return@withContext emptyList()
            val parsed = parseSongsJson(bodyString, fallbackGenre = trimmed.replaceFirstChar { it.uppercase() })
            if (parsed.isNotEmpty()) {
                synchronized(memoryQueryCache) {
                    memoryQueryCache.put(cacheKey, parsed)
                }
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Online search failed: ${e.message}")
            emptyList()
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

        val candidates = listOf("Top Global Hits", "Trending 2024", "Viral Hits", "Latest Bollywood")
        val results = coroutineScope {
            val deferreds = candidates.map { candidate ->
                async {
                    try {
                        searchSongs(candidate, limit)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch trending from $candidate: ${e.message}")
                        emptyList()
                    }
                }
            }
            deferreds.awaitAll().firstOrNull { it.isNotEmpty() } ?: emptyList()
        }

        if (results.isNotEmpty()) {
            trendingCache[limit] = results
        }
        results
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
        if (country.isNotBlank()) {
            val famousQueries = com.example.data.model.CountryData.getFamousMusicQueriesForCountry(country)
            candidates.addAll(famousQueries.shuffled().take(2))
        }
        for (lang in languages.take(2)) {
            candidates.add("Top $lang Hits")
        }
        candidates.add("Top Global Hits")

        val distinctQueries = candidates.distinct().take(3)
        val deferredList = distinctQueries.map { candidate ->
            async {
                try {
                    val results = searchSongs(candidate, limit = 12)
                    results.filter { !com.example.data.model.CountryData.hasCountryNameInTitle(it.title, country) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed query for profile candidate $candidate: ${e.message}")
                    emptyList()
                }
            }
        }

        val allResults = deferredList.awaitAll().flatten()
        val filtered = allResults.distinctBy { it.id }

        val finalResult = if (filtered.isNotEmpty()) {
            filtered.take(limit)
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

        val selectedQueries = queryCandidates.shuffled().take(2)
        val randomPage = (1..3).random()
        val collected = mutableListOf<MusicTrack>()

        for (candidateQuery in selectedQueries) {
            try {
                val songs = searchSongs(candidateQuery, limit = limit, page = randomPage)
                if (songs.isNotEmpty()) {
                    collected.addAll(songs)
                } else if (randomPage > 1) {
                    collected.addAll(searchSongs(candidateQuery, limit = limit, page = 1))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching genre songs for $candidateQuery: ${e.message}")
            }
        }

        if (collected.isEmpty()) {
            val fallbackQuery = queryCandidates.first()
            collected.addAll(searchSongs(fallbackQuery, limit = limit, page = 1))
        }

        collected.distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }.shuffled()
    }

    private fun parseSongsJson(jsonString: String, fallbackGenre: String): List<MusicTrack> {
        val trackList = mutableListOf<MusicTrack>()
        try {
            val root = JSONObject(jsonString)
            val results = root.optJSONArray("results") ?: return emptyList()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue

                val id = item.optString("id")
                if (id.isBlank()) continue

                val rawTitle = item.optString("song", "")
                val cleanTitle = cleanHtml(rawTitle)
                    .replace(Regex("(?i)\\b(?:youtube\\s*music|youtube|jiosaavn|saavn)\\b"), "")
                    .trim()

                val rawArtist = item.optString("primary_artists", "").ifBlank {
                    item.optString("singers", "").ifBlank {
                        item.optString("music", "Unknown Artist")
                    }
                }
                val cleanArtist = cleanHtml(rawArtist)
                    .replace(Regex("(?i)\\b(?:youtube\\s*music|youtube|jiosaavn|saavn)\\b"), "")
                    .trim().ifBlank { "Various Artists" }

                val rawAlbum = item.optString("album", "Single")
                val cleanAlbum = cleanHtml(rawAlbum)
                    .replace(Regex("(?i)\\b(?:youtube\\s*music|youtube|jiosaavn|saavn)\\b"), "")
                    .trim().ifBlank { "HD Stream" }

                val rawImage = item.optString("image", "")
                // High-resolution 500x500 album art
                val cleanImage = rawImage
                    .replace("\\/", "/")
                    .replace("150x150.jpg", "500x500.jpg")
                    .replace("50x50.jpg", "500x500.jpg")

                val encMediaUrl = item.optString("encrypted_media_url", "")
                var audioUrl = decryptMediaUrl(encMediaUrl)

                // Fallback to media_preview_url if decryption fails
                if (audioUrl.isNullOrBlank()) {
                    val preview = item.optString("media_preview_url", "")
                    if (preview.isNotBlank()) {
                        audioUrl = preview.replace("\\/", "/")
                    }
                }

                if (audioUrl.isNullOrBlank()) continue

                val rawSingers = item.optString("singers", "").ifBlank { cleanArtist }
                val cleanSingers = cleanHtml(rawSingers)

                val rawWriter = item.optString("music", "").ifBlank {
                    item.optString("starring", "").ifBlank { "Original Composer" }
                }
                val cleanWriter = cleanHtml(rawWriter)

                val rawLanguage = item.optString("language", "Hindi").replaceFirstChar { it.uppercase() }
                val rawYear = item.optString("year", "")

                val durationSec = item.optString("duration", "210").toLongOrNull() ?: 210L
                val durationMs = durationSec * 1000L

                val is320k = item.optString("320kbps", "true").equals("true", ignoreCase = true)
                val badge = if (is320k) "HD • 320 kbps" else "HQ • Lossless"

                val moreInfo = item.optJSONObject("more_info")
                val rawIsrc = item.optString("isrc", "").ifBlank {
                    moreInfo?.optString("isrc", "") ?: ""
                }.trim()

                trackList.add(
                    MusicTrack(
                        id = "online_$id",
                        title = cleanTitle.ifBlank { "Track $id" },
                        artist = cleanArtist.ifBlank { "Unknown Artist" },
                        album = cleanAlbum.ifBlank { "Online Stream" },
                        durationMs = durationMs,
                        coverUrl = cleanImage,
                        audioUrl = audioUrl,
                        genre = fallbackGenre,
                        bitrateKbps = if (is320k) 320 else 160,
                        qualityBadge = badge,
                        isLiked = false,
                        singers = cleanSingers,
                        writer = cleanWriter,
                        language = rawLanguage,
                        year = rawYear,
                        isrc = rawIsrc
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JioSaavn JSON: ${e.message}")
        }
        return trackList
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
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val root = JSONObject(body)
            val songObj = root.optJSONObject(songId)
                ?: root.optJSONArray("songs")?.optJSONObject(0)
                ?: root
            val isrc = songObj.optString("isrc", "").ifBlank {
                songObj.optJSONObject("more_info")?.optString("isrc", "") ?: ""
            }
            if (isrc.isNotBlank()) isrc.trim() else null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch song ISRC: ${e.message}")
            null
        }
    }
}
