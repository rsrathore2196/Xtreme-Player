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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Metadata sources supply images only; listening history continues to determine artist ranking. */
object ArtistPhotoRepository {
    private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build() }
    private val catalog by lazy { CatalogRepository() }
    private val locks = Array(8) { Mutex() }
    private val rateLock = Mutex()
    private var nextRequestAt = 0L
    @Volatile private var audioDbBackoffUntil = 0L
    private val memory = object : android.util.LruCache<String, Pair<Long, String?>>(512) {}
    private val negatives = ConcurrentHashMap<String, Long>()
    private val photoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val photoSlots = Semaphore(3)
    private var warmup: Deferred<Unit>? = null
    private val placeholderImage = Regex("(?i)(?:no[_-]?image|default[_-]?(?:artist|avatar)|placeholder)")

    @Synchronized
    fun prewarm(context: Context): Deferred<Unit> = warmup ?: photoScope.async {
        val now = System.currentTimeMillis()
        val prefs = context.applicationContext.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            val stored = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            val expires = stored.optLong("expires")
            val url = image(stored.optString("image"))
            if (url != null) memory.put(key, expires to url)
            else if (expires > now) negatives[key] = expires
        }
    }.also { warmup = it }
    private val identityMemory = android.util.LruCache<String, Pair<Long, JSONObject?>>(100)
    private val identityLocks = Array(8) { Mutex() }

    private fun image(value: String?): String? = value?.takeIf {
        (it.startsWith("https://") || it.startsWith("http://")) &&
            !placeholderImage.containsMatchIn(it)
    }?.replaceFirst("http://", "https://")

    suspend fun photo(context: Context, artist: CatalogArtist, fallbackArtwork: String? = null): String? = withContext(Dispatchers.IO) {
        image(artist.image)?.let { return@withContext it }
        val name = CatalogNames.normalize(artist.name)
        if (name.isBlank() || name in setOf("unknown", "unknown artist", "various artists")) return@withContext null
        val key = "$name:${artist.musicBrainzId}:${artist.id}"
        memory.get(key)?.takeIf { it.first > System.currentTimeMillis() }?.second?.let { return@withContext it }
        prewarm(context).await()
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val now = System.currentTimeMillis()
            memory.get(key)?.takeIf { it.first > now }?.let { return@withLock it.second ?: image(fallbackArtwork) }
            negatives[key]?.let { if (it > now) return@withLock image(fallbackArtwork) else negatives.remove(key) }
            val prefs = context.applicationContext.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
            val stale = memory.get(key)?.second
            com.example.performance.BackgroundWorkCoordinator.awaitIdle(minimumUptimeMillis = 3_500L)
            photoSlots.withPermit {
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
            if (result == null && failed) result = stale
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
            if (result == null) {
                negatives.entries.removeAll { it.value <= System.currentTimeMillis() }
                if (negatives.size >= 512) negatives.keys.firstOrNull()?.let(negatives::remove)
                negatives[key] = expires
            } else negatives.remove(key)
            prefs.edit().putString(key, JSONObject().put("image", result.orEmpty()).put("expires", expires).toString()).apply()
            result
            }
        }
    }

    private fun identityNames(row: JSONObject) = (listOf(row.optString("strArtist")) +
        row.optString("strAlternate").split(';', '|', ',')).map(CatalogNames::clean)
        .filter { it.isNotBlank() && it != "null" }.distinct()

    /** Only aliases from a uniquely verified artist may broaden catalogue credit matching. */
    internal suspend fun enrichIdentity(artist: CatalogArtist): CatalogArtist = withContext(Dispatchers.IO) {
        val row = audioDbIdentity(artist) ?: return@withContext artist
        artist.copy(musicBrainzId = artist.musicBrainzId.ifBlank {
            row.optString("strMusicBrainzID").takeUnless { it == "null" }.orEmpty()
        }, aliases = (artist.aliases + identityNames(row)).filter { it != artist.name }.distinct())
    }

    private suspend fun audioDb(artist: CatalogArtist): String? = audioDbIdentity(artist)?.let { row ->
        image(row.optString("strArtistThumb")) ?: image(row.optString("strArtistWideThumb"))
    }

    private suspend fun audioDbIdentity(artist: CatalogArtist): JSONObject? {
        val key = "${CatalogNames.normalize(artist.name)}:${artist.musicBrainzId}:${artist.aliases.sorted()}"
        return identityLocks[(key.hashCode() and Int.MAX_VALUE) % identityLocks.size].withLock {
            val now = System.currentTimeMillis()
            val cached = synchronized(identityMemory) { identityMemory.get(key) }
            if (cached != null && cached.first > now) return@withLock cached.second
            if (now < audioDbBackoffUntil) return@withLock cached?.second
            try {
                val queries = (listOf(artist.name) + artist.aliases).filter(String::isNotBlank).distinct().take(3)
                for (query in queries) {
                    rateLock.withLock {
                        delay((nextRequestAt - System.currentTimeMillis()).coerceAtLeast(0))
                        nextRequestAt = System.currentTimeMillis() + 500
                    }
                    if (System.currentTimeMillis() < audioDbBackoffUntil) return@withLock cached?.second
                    val url = "https://www.theaudiodb.com/api/v1/json/123/search.php".toHttpUrl().newBuilder()
                        .addQueryParameter("s", query).build().toString()
                    val array = JSONObject(client.catalogGet(url)).optJSONArray("artists")
                    val matches = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }.filter { row ->
                        row.optString("idArtist").isNotBlank() && if (artist.musicBrainzId.isNotBlank()) {
                            row.optString("strMusicBrainzID").equals(artist.musicBrainzId, true)
                        } else (listOf(artist.name) + artist.aliases).any { name ->
                            identityNames(row).any { CatalogNames.same(name, it) }
                        }
                    }.distinctBy { it.optString("idArtist") }
                    // An ambiguous name is not an alias or photograph verification.
                    if (matches.size > 1) return@withLock null
                    matches.singleOrNull()?.let { row ->
                        synchronized(identityMemory) { identityMemory.put(key, System.currentTimeMillis() + 7 * 86_400_000L to row) }
                        return@withLock row
                    }
                }
                synchronized(identityMemory) { identityMemory.put(key, System.currentTimeMillis() + 3_600_000L to null) }
                null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val retry = if (e is CatalogHttpException && e.status == 429)
                    (e.retryAfter ?: 60).coerceIn(30, 3600) * 1000 else 30_000L
                audioDbBackoffUntil = System.currentTimeMillis() + retry
                cached?.second ?: throw e
            }
        }
    }
}
