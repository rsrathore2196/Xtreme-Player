package com.example.data.remote

import android.content.Context
import android.text.Html
import android.util.Base64
import android.util.Log
import com.example.config.SecurityConfig
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
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

        val ctx = context ?: throw IllegalStateException("OnlineMusicApiService not initialized. Call initialize() in Application.onCreate()")
        cachedDESKey = SecurityConfig.getDESKey(ctx)
        return cachedDESKey!!
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
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
                android.util.Base64.decode(encryptedUrl, android.util.Base64.DEFAULT)
            } catch (e: Throwable) {
                java.util.Base64.getDecoder().decode(encryptedUrl)
            }
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val rawUrl = String(decryptedBytes, Charsets.UTF_8)

            formatUrlForQuality(rawUrl, targetQuality)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting media URL: ${e.message}")
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
    suspend fun searchSongs(query: String, limit: Int = 30): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&includeMetaTags=1&p=1&n=$limit&q=$encodedQuery"

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
            parseSongsJson(bodyString, fallbackGenre = query.trim().replaceFirstChar { it.uppercase() })
        } catch (e: Exception) {
            Log.e(TAG, "Online search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch trending online hits for Home screen carousel and Explore feed.
     */
    suspend fun getTrendingSongs(limit: Int = 25): List<MusicTrack> = withContext(Dispatchers.IO) {
        val candidates = listOf("Top Global Hits", "Trending 2024", "Viral Hits", "Latest Bollywood")
        var lastException: Exception? = null

        for (candidate in candidates) {
            try {
                val results = searchSongs(candidate, limit)
                if (results.isNotEmpty()) {
                    Log.i(TAG, "Fetched trending songs from: $candidate")
                    return@withContext results
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch trending from $candidate: ${e.message}")
                lastException = e
            }
        }

        // Log failure with all attempts
        Log.e(TAG, "Failed to fetch trending songs after all attempts. Last error: ${lastException?.message}")
        emptyList()
    }

    /**
     * Fetch songs by specific genre or mood (Electronic, Rock, Pop, Hip-Hop, Lo-Fi, etc.)
     */
    suspend fun getSongsByGenre(genre: String, limit: Int = 25): List<MusicTrack> = withContext(Dispatchers.IO) {
        val query = when (genre.lowercase()) {
            "all" -> "Top Hits"
            "electronic" -> "Electronic Dance EDM"
            "synthwave" -> "Synthwave Retrowave"
            "rock" -> "Rock Hits"
            "hip-hop", "hiphop" -> "Hip Hop Rap"
            "lo-fi", "lofi" -> "Lo-Fi Beats Chill"
            "pop" -> "Top Pop Hits"
            "ambient" -> "Ambient Chillout"
            else -> "$genre Songs"
        }
        searchSongs(query, limit)
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

                val rawArtist = item.optString("primary_artists", "").ifBlank {
                    item.optString("singers", "").ifBlank {
                        item.optString("music", "Unknown Artist")
                    }
                }
                val cleanArtist = cleanHtml(rawArtist)

                val rawAlbum = item.optString("album", "Single")
                val cleanAlbum = cleanHtml(rawAlbum)

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
                        year = rawYear
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
}
