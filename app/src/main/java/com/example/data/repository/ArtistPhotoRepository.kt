package com.example.data.repository

import android.content.Context
import com.example.data.remote.CatalogHttpException
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.catalogGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Metadata sources supply images only; listening history continues to determine artist ranking. */
object ArtistPhotoRepository {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val catalog = CatalogRepository()
    private val locks = Array(8) { Mutex() }
    private val rateLock = Mutex()
    private var nextRequestAt = 0L
    @Volatile private var audioDbBackoffUntil = 0L
    private val memory = object : android.util.LruCache<String, Pair<Long, String?>>(150) {}

    private fun image(value: String?): String? = value?.takeIf {
        (it.startsWith("https://") || it.startsWith("http://")) &&
            !Regex("(?i)(?:no[_-]?image|default[_-]?(?:artist|avatar)|placeholder)").containsMatchIn(it)
    }?.replaceFirst("http://", "https://")

    suspend fun photo(context: Context, artist: CatalogArtist, fallbackArtwork: String? = null): String? = withContext(Dispatchers.IO) {
        val name = CatalogNames.normalize(artist.name)
        if (name.isBlank() || name in setOf("unknown", "unknown artist", "various artists")) return@withContext null
        val key = "$name:${artist.musicBrainzId}:${artist.id}"
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val now = System.currentTimeMillis()
            memory.get(key)?.takeIf { it.first > now }?.let { return@withLock it.second ?: image(fallbackArtwork) }
            // A new cache namespace avoids retaining the former negative, AudioDB-only entries.
            val prefs = context.applicationContext.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
            val stored = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (stored != null && stored.optLong("expires") > now) {
                val cached = image(stored.optString("image"))
                memory.put(key, stored.optLong("expires") to cached)
                return@withLock cached ?: image(fallbackArtwork)
            }
            var failed = false
            suspend fun attempt(load: suspend () -> String?): String? = try { image(load()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true; null }

            var result = attempt { audioDb(artist) }
            var resolved: CatalogArtist? = null
            if (result == null) result = attempt {
                resolved = catalog.resolveArtist(artist)
                resolved?.image
            }
            if (result == null) result = attempt { YouTubeMusicApiService.artistPhoto(artist.name) }
            // Keep a previously verified image through temporary provider failures.
            if (result == null && failed) result = image(stored?.optString("image"))
            val hasPortrait = result != null
            if (result == null) result = image(fallbackArtwork)
            if (result == null && resolved != null) result = attempt {
                catalog.artistSongs(requireNotNull(resolved), 0).tracks.firstOrNull { image(it.coverUrl) != null }?.coverUrl
            }
            val expires = System.currentTimeMillis() + when {
                failed -> 60_000L
                hasPortrait -> 7 * 86_400_000L
                result != null -> 12 * 3_600_000L
                else -> 3_600_000L
            }
            memory.put(key, expires to result)
            prefs.edit().putString(key, JSONObject().put("image", result.orEmpty()).put("expires", expires).toString()).apply()
            result
        }
    }

    private suspend fun audioDb(artist: CatalogArtist): String? {
        if (System.currentTimeMillis() < audioDbBackoffUntil) return null
        rateLock.withLock {
            delay((nextRequestAt - System.currentTimeMillis()).coerceAtLeast(0))
            nextRequestAt = System.currentTimeMillis() + 500
        }
        if (System.currentTimeMillis() < audioDbBackoffUntil) return null
        try {
            val url = "https://www.theaudiodb.com/api/v1/json/123/search.php".toHttpUrl().newBuilder()
                .addQueryParameter("s", artist.name).build().toString()
            val array = JSONObject(client.catalogGet(url)).optJSONArray("artists")
            val matches = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }.filter { row ->
                val names = listOf(row.optString("strArtist")) + row.optString("strAlternate").split(';', '|', ',')
                row.optString("idArtist").isNotBlank() && if (artist.musicBrainzId.isNotBlank()) {
                    row.optString("strMusicBrainzID").equals(artist.musicBrainzId, true)
                } else (listOf(artist.name) + artist.aliases).any { name -> names.any { CatalogNames.same(name, it) } }
            }.distinctBy { it.optString("idArtist") }
            return matches.singleOrNull()?.let { row ->
                image(row.optString("strArtistThumb")) ?: image(row.optString("strArtistWideThumb"))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val retry = if (e is CatalogHttpException && e.status == 429)
                (e.retryAfter ?: 60).coerceIn(30, 3600) * 1000 else 30_000L
            audioDbBackoffUntil = System.currentTimeMillis() + retry
            throw e
        }
    }
}
