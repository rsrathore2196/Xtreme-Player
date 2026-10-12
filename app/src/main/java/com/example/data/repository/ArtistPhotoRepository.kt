package com.example.data.repository

import android.content.Context
import com.example.data.remote.catalogGet
import com.example.data.remote.CatalogHttpException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** TheAudioDB supplies identity and photographs only; it never supplies listening rankings. */
object ArtistPhotoRepository {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private var nextRequestAt = 0L
    private var backoffUntil = 0L
    private val memory = object : android.util.LruCache<String, Pair<Long, String?>>(150) {}

    suspend fun photo(context: Context, artist: CatalogArtist): String? = withContext(Dispatchers.IO) {
        val key = CatalogNames.normalize(artist.name) + ":" + artist.musicBrainzId
        if (CatalogNames.normalize(artist.name).isBlank()) return@withContext null
        lock.withLock {
            val now = System.currentTimeMillis()
            memory.get(key)?.takeIf { it.first > now }?.let { return@withLock it.second }
            val prefs = context.applicationContext.getSharedPreferences("verified_artist_photos_v1", Context.MODE_PRIVATE)
            val stored = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (stored != null && stored.optLong("expires") > now) {
                val image = stored.optString("image").takeIf(String::isNotBlank)
                memory.put(key, stored.optLong("expires") to image)
                return@withLock image
            }
            if (now < backoffUntil) return@withLock stored?.optString("image")?.takeIf(String::isNotBlank)
            delay((nextRequestAt - now).coerceAtLeast(0))
            nextRequestAt = System.currentTimeMillis() + 500
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
                // Name collisions are deliberately left without a photo, never guessed.
                val match = matches.singleOrNull()
                val image = match?.let { row ->
                    listOf(row.optString("strArtistThumb"), row.optString("strArtistWideThumb"))
                        .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }?.replaceFirst("http://", "https://")
                }
                val expires = System.currentTimeMillis() + if (image != null) 7 * 86_400_000L else 3_600_000L
                memory.put(key, expires to image)
                prefs.edit().putString(key, JSONObject().put("image", image.orEmpty()).put("expires", expires)
                    .put("idArtist", match?.optString("idArtist").orEmpty()).toString()).apply()
                image
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val retry = if (e is CatalogHttpException && e.status == 429) (e.retryAfter ?: 60).coerceIn(30, 3600) * 1000 else 30_000L
                backoffUntil = System.currentTimeMillis() + retry
                val old = stored?.optString("image")?.takeIf(String::isNotBlank)
                memory.put(key, backoffUntil to old)
                old
            }
        }
    }
}
