package com.example.data.repository

import com.example.performance.AppDispatchers

import android.content.Context
import com.example.data.remote.CatalogHttpException
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.catalogGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    private const val PHOTO_VERSION = 3
    private data class Photo(val expires: Long, val url: String?, val source: String,
        val musicBrainzId: String = "", val catalogId: String = "", val albumArtwork: String? = null)
    private val memory = object : android.util.LruCache<String, Photo>(512) {}
    private val rejectedImages = ConcurrentHashMap<String, Long>()
    private val photoRevision = MutableStateFlow(0L)
    private fun key(artist: CatalogArtist) = "portrait:${CatalogNames.normalize(artist.name)}"
    private fun compatible(photo: Photo, artist: CatalogArtist): Boolean {
        if (photo.musicBrainzId.isNotBlank() && artist.musicBrainzId.isNotBlank())
            return photo.musicBrainzId.equals(artist.musicBrainzId, true)
        val sameProvider = photo.catalogId.isNotBlank() && artist.id.isNotBlank() &&
            (photo.catalogId.all(Char::isDigit) && artist.id.all(Char::isDigit) ||
                photo.catalogId.startsWith("UC") && artist.id.startsWith("UC"))
        return !sameProvider || photo.catalogId == artist.id
    }
    fun cachedPhoto(artist: CatalogArtist): String? = memory.get(key(artist))
        ?.takeIf { compatible(it, artist) }?.url?.let(::image)
    fun initialPhoto(artist: CatalogArtist): String? = cachedPhoto(artist) ?: image(artist.image)
    fun observePhoto(artist: CatalogArtist): Flow<String?> = photoRevision.map { cachedPhoto(artist) }
        .distinctUntilChanged().flowOn(AppDispatchers.Io)

    /** A failed image URL is not a failed artist: try the next verified photograph. */
    fun rejectPhoto(url: String) {
        val now = System.currentTimeMillis()
        if ((rejectedImages[url] ?: 0L) > now) return
        if (rejectedImages.size >= 512) rejectedImages.keys.firstOrNull()?.let(rejectedImages::remove)
        rejectedImages[url] = now + 3_600_000L
        photoRevision.update { it + 1L }
    }
    private val photoScope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val photoSlots = Semaphore(3)
    private var warmup: Deferred<Unit>? = null
    private val warmupReaders = java.util.concurrent.atomic.AtomicInteger()
    private val placeholderImage = Regex("(?i)(?:no[_-]?image|default[_-]?(?:artist|avatar)|placeholder)")

    @Synchronized
    fun prewarm(context: Context): Deferred<Unit> = warmup ?: photoScope.async {
      com.example.performance.BackgroundWorkCoordinator.startup(
        com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED,
        urgent = { warmupReaders.get() > 0 }, label = "Portrait cache prewarm") {
        val now = System.currentTimeMillis()
        val prefs = context.applicationContext.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        val storedEntries = prefs.all
        withContext(com.example.performance.BackgroundWorkCoordinator.optionalCpuDispatcher) {
            for ((index, entry) in storedEntries.entries.withIndex()) {
                if (index % 64 == 0) com.example.performance.BackgroundWorkCoordinator.yieldToInteraction()
                val (key, value) = entry
                if (!key.startsWith("portrait:")) continue
                val stored = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                // Old entries mixed album covers and portraits and included provider IDs
                // in their keys. They must not suppress the newly shared artist lookup.
                if (stored.optInt("version") != PHOTO_VERSION || !key.startsWith("portrait:")) continue
                val expires = stored.optLong("expires")
                val url = image(stored.optString("image"))
                if (expires > now || url != null) memory.put(key, Photo(expires, url, stored.optString("source"),
                    stored.optString("mbid"), stored.optString("catalogId"), image(stored.optString("albumArtwork"))))
            }
        }
        photoRevision.update { it + 1L }
      }
    }.also { warmup = it }
    private val identityMemory = android.util.LruCache<String, Pair<Long, JSONObject?>>(100)
    private val identityLocks = Array(8) { Mutex() }

    private fun image(value: String?): String? = value?.takeIf {
        (it.startsWith("https://") || it.startsWith("http://")) && !placeholderImage.containsMatchIn(it)
    }?.replaceFirst("http://", "https://")?.takeIf { (rejectedImages[it] ?: 0L) <= System.currentTimeMillis() }

    suspend fun photo(context: Context, artist: CatalogArtist, fallbackArtwork: String? = null): String? = withContext(AppDispatchers.CpuBounded) {
        val name = CatalogNames.normalize(artist.name)
        if (name.isBlank() || name in setOf("unknown", "unknown artist", "various artists")) return@withContext null
        val key = key(artist)
        // Visible portrait requests can promote the same warmup immediately;
        // optional Application prewarming must not gate a user-visible image.
        warmupReaders.incrementAndGet()
        try { prewarm(context).await() } finally { warmupReaders.decrementAndGet() }
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val now = System.currentTimeMillis()
            val cached = memory.get(key)?.takeIf { compatible(it, artist) }
            val provided = image(artist.image)
            val newIdentity = artist.musicBrainzId.isNotBlank() && cached?.musicBrainzId.isNullOrBlank()
            val newCatalogPhoto = provided != null && cached?.source == "catalog" && cached.url != provided
            if (cached != null && cached.expires > now && !newIdentity && !newCatalogPhoto) {
                image(cached.url)?.let { return@withLock it }
                // A newly available artist-entity image supersedes a negative/fallback
                // entry. Failed photo URLs also need another source immediately.
                if (cached.url == null && provided == null)
                    return@withLock image(cached.albumArtwork) ?: image(fallbackArtwork)
            }
            if (provided != null && image(cached?.url) == null) {
                // Share a known artist-entity portrait immediately while preferred
                // providers run. Expiry zero keeps this from bypassing TheAudioDB.
                memory.put(key, Photo(0L, provided, "catalog", artist.musicBrainzId, artist.id))
                photoRevision.update { it + 1L }
            }
            // Visible portraits are demand-driven UI content. Waiting for global
            // idle here starved them behind ongoing artwork downloads/animations.
            com.example.performance.BackgroundWorkCoordinator.awaitNavigationIdle()
            photoSlots.withPermit {
                var failed = false
                suspend fun attempt(load: suspend () -> String?): String? = try { withTimeout(6_000L) { image(load()) } }
                    catch (_: TimeoutCancellationException) { failed = true; null }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true; null }
                var source = "audiodb"
                var result = attempt { audioDb(artist) }
                if (result == null) {
                    source = "youtube"
                    for (query in (listOf(artist.name) + artist.aliases).filter(String::isNotBlank).distinct().take(2)) {
                        result = attempt { YouTubeMusicApiService.artistPhoto(query) }
                        if (result != null) break
                    }
                }
                var resolved: CatalogArtist? = null
                if (result == null) {
                    // Resolve an exact catalogue entity/verified alias, never a loose
                    // song-title match. This also repairs stage-name differences.
                    resolved = try { catalog.resolveArtist(artist) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true; null }
                    if (resolved != null && (resolved.name != artist.name || resolved.aliases != artist.aliases ||
                            resolved.musicBrainzId != artist.musicBrainzId)) {
                        source = "audiodb"
                        result = attempt { audioDb(requireNotNull(resolved)) }
                        if (result == null) {
                            source = "youtube"
                            result = attempt { YouTubeMusicApiService.artistPhoto(requireNotNull(resolved).name) }
                        }
                    }
                }
                if (result == null) {
                    source = "catalog"
                    result = image(provided) ?: image(resolved?.image)
                }
                // Preserve a previously verified portrait through temporary outages.
                if (result == null && failed) image(cached?.url)?.let { result = it; source = cached!!.source }
                var album = image(cached?.albumArtwork) ?: image(fallbackArtwork)
                if (result == null && album == null && resolved != null) album = attempt {
                    catalog.artistSongs(requireNotNull(resolved), 0).tracks.firstOrNull { image(it.coverUrl) != null }?.coverUrl
                }
                val expires = System.currentTimeMillis() + when {
                    failed -> 60_000L
                    result != null -> 7 * 86_400_000L
                    else -> 3_600_000L
                }
                val selected = resolved ?: artist
                val photo = Photo(expires, result, source, selected.musicBrainzId, selected.id,
                    albumArtwork = album.takeIf { result == null })
                // Same normalized artist identity for Home, search and detail. Album
                // fallback is marked separately and never cached as a real portrait.
                val aliases = (listOf(artist.name, selected.name) + selected.aliases).map(CatalogNames::normalize)
                    .filter(String::isNotBlank).distinct()
                val photoPreferences = withContext(AppDispatchers.Io) {
                    context.applicationContext.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
                }
                val edits = linkedMapOf<String, String>()
                val json = JSONObject().put("version", PHOTO_VERSION).put("image", result.orEmpty())
                    .put("source", source).put("expires", expires).put("mbid", photo.musicBrainzId)
                    .put("catalogId", photo.catalogId).put("albumArtwork", photo.albumArtwork.orEmpty()).toString()
                fun priority(value: Photo) = when (value.source) { "audiodb" -> 3; "youtube" -> 2; "catalog" -> 1; else -> 0 }
                    .takeIf { value.url != null } ?: 0
                for (alias in aliases) {
                    val aliasKey = "portrait:$alias"
                    val previous = memory.get(aliasKey)
                    if (previous != null && previous.expires > System.currentTimeMillis() && image(previous.url) != null &&
                        priority(previous) > priority(photo)) continue
                    if (previous == null || compatible(previous, selected)) {
                        memory.put(aliasKey, photo)
                        edits[aliasKey] = json
                    }
                }
                withContext(AppDispatchers.Io) {
                    photoPreferences.edit().also { edit -> edits.forEach { (key, value) -> edit.putString(key, value) } }.commit()
                }
                photoRevision.update { it + 1L }
                cachedPhoto(artist) ?: result ?: album
            }
        }
    }

    private fun identityNames(row: JSONObject) = (listOf(row.optString("strArtist")) +
        row.optString("strAlternate").split(';', '|', ',')).map(CatalogNames::clean)
        .filter { it.isNotBlank() && it != "null" }.distinct()

    /** Only aliases from a uniquely verified artist may broaden catalogue credit matching. */
    internal suspend fun enrichIdentity(artist: CatalogArtist): CatalogArtist = withContext(AppDispatchers.CpuBounded) {
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
                    val httpClient = withContext(AppDispatchers.Io) { client }
                    val array = JSONObject(httpClient.catalogGet(url)).optJSONArray("artists")
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
