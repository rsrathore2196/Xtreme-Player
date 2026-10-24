package com.example.data.repository

import android.content.Context
import com.example.data.remote.CatalogHttpException
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.catalogGet
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Only verified artist entities supply portraits. Track artwork is never a portrait fallback. */
object ArtistPhotoRepository {
    private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build() }
    private val locks = Array(8) { Mutex() }
    private val identityLocks = Array(8) { Mutex() }
    private val rateLock = Mutex()
    private val persistenceLock = Mutex()
    private var nextRequestAt = 0L
    @Volatile private var audioDbBackoffUntil = 0L
    private const val PHOTO_VERSION = 4
    private const val PHOTO_TTL = 7 * 86_400_000L
    private const val PREFERRED_RECHECK = 86_400_000L
    internal const val MAX_RESTORED_PORTRAITS = 512
    internal const val HOT_PORTRAIT_KEYS = "portrait_hot_keys_v1"
    @Volatile private var restoreDemanded = false
    private data class Photo(val expires: Long, val url: String?, val source: String,
        val musicBrainzId: String = "", val catalogId: String = "", val preferredAfter: Long = 0L,
        val canonicalName: String = "")
    private val memory = android.util.LruCache<String, Photo>(512)
    private val identityMemory = android.util.LruCache<String, Pair<Long, JSONObject?>>(100)
    private val rejectedImages = ConcurrentHashMap<String, Long>()
    private val upgrades = ConcurrentHashMap<String, Job>()
    private val photoRevision = MutableStateFlow(0L)
    private val identityRevision = MutableStateFlow(0L)
    val identities: StateFlow<Long> = identityRevision.asStateFlow()
    private val photoScope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val photoSlots = Semaphore(2)
    private var warmup: Deferred<Unit>? = null
    private val placeholderImage = Regex("(?i)(?:no[_-]?image|default[_-]?(?:artist|avatar)|placeholder)")
    private val mbidPattern = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun mbid(value: String): String = value.trim().takeIf {
        it.matches(mbidPattern) && it != "00000000-0000-0000-0000-000000000000"
    }.orEmpty()
    private fun key(artist: CatalogArtist) = mbid(artist.musicBrainzId).lowercase(java.util.Locale.ROOT)
        .takeIf(String::isNotBlank)?.let { "portrait:mbid:$it" } ?: "portrait:${CatalogNames.normalize(artist.name)}"
    private fun cachedEntry(artist: CatalogArtist): Photo? = memory.get(key(artist))?.takeIf { compatible(it, artist) }
        ?: memory.get("portrait:${CatalogNames.normalize(artist.name)}")?.takeIf { compatible(it, artist) }
    private fun compatible(photo: Photo, artist: CatalogArtist): Boolean {
        val requested = mbid(artist.musicBrainzId)
        if (photo.musicBrainzId.isNotBlank() && requested.isNotBlank())
            return photo.musicBrainzId.equals(requested, true)
        val sameProvider = photo.catalogId.isNotBlank() && artist.id.isNotBlank() &&
            (photo.catalogId.all(Char::isDigit) && artist.id.all(Char::isDigit) ||
                photo.catalogId.startsWith("UC") && artist.id.startsWith("UC"))
        return !sameProvider || photo.catalogId == artist.id
    }
    private fun image(value: String?): String? = value?.takeIf {
        (it.startsWith("https://") || it.startsWith("http://")) && !placeholderImage.containsMatchIn(it)
    }?.replaceFirst("http://", "https://")?.takeIf { (rejectedImages[it] ?: 0L) <= System.currentTimeMillis() }

    fun cachedPhoto(artist: CatalogArtist): String? = cachedEntry(artist)
        ?.takeIf { it.source in setOf("audiodb", "youtube") }?.url?.let(::image)
    fun initialPhoto(artist: CatalogArtist): String? = cachedPhoto(artist)
    /** Memory-only, provider-verified aliases; never fuzzy-merge unrelated performers. */
    fun canonicalName(name: String): String = memory.get("portrait:${CatalogNames.normalize(name)}")
        ?.canonicalName?.takeIf(String::isNotBlank) ?: name
    fun observePhoto(artist: CatalogArtist): Flow<String?> = photoRevision.map { cachedPhoto(artist) }
        .distinctUntilChanged().flowOn(AppDispatchers.Io)

    fun rejectPhoto(url: String) {
        val now = System.currentTimeMillis()
        if ((rejectedImages[url] ?: 0L) > now) return
        if (rejectedImages.size >= 512) rejectedImages.keys.firstOrNull()?.let(rejectedImages::remove)
        rejectedImages[url] = now + 3_600_000L
        photoRevision.update { it + 1L }
    }

    /** Bounded shared restoration; a visible caller promotes automatic warming immediately. */
    @Synchronized
    fun prewarm(context: Context, automatic: Boolean = false): Deferred<Unit> {
        if (!automatic) restoreDemanded = true
        return warmup ?: photoScope.async {
        val prefs = context.applicationContext.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        val hot = runCatching { prefs.getStringSet(HOT_PORTRAIT_KEYS, null) }.getOrNull()
        val entries = if (hot != null) hot.asSequence().filter { it.startsWith("portrait:") }
            .take(MAX_RESTORED_PORTRAITS).mapNotNull { key ->
                runCatching { prefs.getString(key, null) }.getOrNull()?.let { key to it }
            }.toList()
        else prefs.all.asSequence().filter { it.key.startsWith("portrait:") }
            .take(MAX_RESTORED_PORTRAITS).mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toList()
        restoreEntries(entries, automatic)
        }.also { warmup = it }
    }

    private suspend fun restoreEntries(entries: List<Pair<String, String>>, automatic: Boolean = false) {
        val now = System.currentTimeMillis()
        var changed = false
        var identitiesChanged = false
        withContext(AppDispatchers.CpuBounded) {
            for ((index, entry) in entries.withIndex()) {
                if (index % 64 == 0) {
                    ensureActive()
                    if (automatic) BackgroundWorkCoordinator.yieldToInteraction(awaitFrameQuiet = false, bypass = { restoreDemanded })
                    else yield()
                }
                val row = runCatching { JSONObject(entry.second) }.getOrNull() ?: continue
                val version = row.optInt("version")
                val source = row.optString("source")
                // v3 verified photographs remain usable; its catalog/album fallbacks do not.
                if (version !in 3..PHOTO_VERSION || source !in setOf("audiodb", "youtube")) continue
                val url = image(row.optString("image"))
                val expires = row.optLong("expires")
                val canonical = row.optString("canonicalName")
                if (expires > now || url != null) synchronized(memory) {
                    // Disk restoration cannot overwrite a newer in-flight provider result.
                    if (memory.get(entry.first) == null) {
                        memory.put(entry.first, Photo(expires, url, source, mbid(row.optString("mbid")),
                            row.optString("catalogId"), row.optLong("preferredAfter"), canonical))
                        changed = true
                        if (canonical.isNotBlank() && !entry.first.startsWith("portrait:mbid:") &&
                            entry.first.removePrefix("portrait:") != CatalogNames.normalize(canonical)) identitiesChanged = true
                    }
                }
            }
        }
        if (changed) photoRevision.update { it + 1L }
        if (identitiesChanged) identityRevision.update { it + 1L }
    }

    private suspend fun restoreArtist(context: Context, artist: CatalogArtist) {
        if (cachedEntry(artist) != null) return
        val prefs = context.applicationContext.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        val entries = listOf(key(artist), "portrait:${CatalogNames.normalize(artist.name)}").distinct()
            .mapNotNull { key -> runCatching { prefs.getString(key, null) }.getOrNull()?.let { key to it } }
        restoreEntries(entries)
    }

    private fun needsRefresh(photo: Photo, now: Long) = photo.expires <= now ||
        photo.source == "youtube" && photo.preferredAfter <= now
    private fun improvedIdentity(photo: Photo, artist: CatalogArtist) =
        photo.musicBrainzId.isBlank() && mbid(artist.musicBrainzId).isNotBlank() ||
            photo.catalogId.isBlank() && artist.id.isNotBlank()

    private fun upgradeLater(context: Context, artist: CatalogArtist) {
        val identity = key(artist)
        synchronized(upgrades) {
            if (upgrades[identity]?.isActive == true) return
            val job = photoScope.launch(start = CoroutineStart.LAZY) {
                try {
                    BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                        label = "Artist portrait upgrade") {
                        // Admission precedes identity locks; never park an optional CPU
                        // continuation while it owns a portrait mutex or network permit.
                        BackgroundWorkCoordinator.finishBoundedBatch { refresh(context, artist) }
                    }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { /* A failed cache write must not crash the application. */ }
            }
            upgrades[identity] = job
            job.invokeOnCompletion { upgrades.remove(identity, job) }
            job.start()
        }
    }

    suspend fun photo(context: Context, artist: CatalogArtist): String? = withContext(AppDispatchers.Io) {
        val name = CatalogNames.normalize(artist.name)
        if (name in setOf("", "unknown", "unknown artist", "various artists")) return@withContext null
        if (cachedEntry(artist) == null) {
            prewarm(context).await()
            restoreArtist(context, artist)
        }
        val cached = cachedEntry(artist)
        val now = System.currentTimeMillis()
        image(cached?.url)?.let {
            if (needsRefresh(requireNotNull(cached), now) || improvedIdentity(cached, artist))
                upgradeLater(context.applicationContext, artist)
            return@withContext it
        }
        if (cached != null && cached.url == null && cached.expires > now && !improvedIdentity(cached, artist))
            return@withContext null
        // Uncached visible portraits use bounded IO immediately, not the 15-second optional idle gate.
        BackgroundWorkCoordinator.awaitNavigationIdle()
        refresh(context.applicationContext, artist)
    }

    private suspend fun refresh(context: Context, artist: CatalogArtist): String? = withContext(AppDispatchers.Io) {
        val identity = key(artist)
        val lockKey = mbid(artist.musicBrainzId).ifBlank { identity }
        locks[(lockKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val cached = cachedEntry(artist)
            val now = System.currentTimeMillis()
            if (cached != null && !needsRefresh(cached, now) && !improvedIdentity(cached, artist) &&
                (cached.url == null || image(cached.url) != null)) return@withLock image(cached.url)
            photoSlots.withPermit {
                var failed = false
                suspend fun attempt(load: suspend () -> String?): String? = try {
                    withTimeout(6_000L) { image(load()) }
                } catch (_: TimeoutCancellationException) { failed = true; null }
                  catch (e: CancellationException) { throw e }
                  catch (_: Exception) { failed = true; null }
                var source = "audiodb"
                var verifiedIdentity = artist
                var identityVerified = false
                var result = attempt { audioDbIdentity(artist)?.let { row ->
                    identityVerified = true
                    verifiedIdentity = artist.copy(name = row.optString("strArtist").let(CatalogNames::clean)
                        .takeIf { it.isNotBlank() && !it.equals("null", true) } ?: artist.name,
                        musicBrainzId = mbid(row.optString("strMusicBrainzID")).ifBlank { mbid(artist.musicBrainzId) },
                        aliases = (artist.aliases + identityNames(row)).distinctBy(CatalogNames::normalize))
                    image(row.optString("strArtistThumb")) ?: image(row.optString("strArtistWideThumb"))
                } }
                if (result == null && cached?.source == "audiodb") result = image(cached.url)
                if (result == null) {
                    source = "youtube"
                    // A cached verified YouTube profile remains visible and avoids
                    // another YouTube search while only checking preferred TheAudioDB.
                    result = image(cached?.url)?.takeIf { cached?.source == "youtube" && cached.expires > now }
                    if (result == null) for (query in (listOf(artist.name, verifiedIdentity.name) + verifiedIdentity.aliases)
                        .filter(String::isNotBlank).distinctBy(CatalogNames::normalize).take(2)) {
                        result = attempt { YouTubeMusicApiService.artistPhoto(query) }
                        if (result != null) break
                    }
                }
                if (result == null) image(cached?.url)?.let { result = it; source = cached!!.source }
                val completed = System.currentTimeMillis()
                val retry = if (failed) 60_000L else if (result == null) 3_600_000L else PHOTO_TTL
                val canonical = if (identityVerified || result != null) verifiedIdentity.name else cached?.canonicalName.orEmpty()
                val photo = Photo(completed + retry, result, source,
                    mbid(verifiedIdentity.musicBrainzId).ifBlank { cached?.musicBrainzId.orEmpty() }, artist.id,
                    completed + if (failed) 60_000L else PREFERRED_RECHECK, canonical)
                val aliases = (if (identityVerified || result != null)
                    listOf(artist.name, verifiedIdentity.name) + verifiedIdentity.aliases else listOf(artist.name))
                    .map(CatalogNames::normalize).filter(String::isNotBlank).distinct()
                val body = withContext(AppDispatchers.CpuBounded) { JSONObject().put("version", PHOTO_VERSION)
                    .put("image", result.orEmpty()).put("source", source).put("expires", photo.expires)
                    .put("mbid", photo.musicBrainzId).put("catalogId", photo.catalogId)
                    .put("canonicalName", photo.canonicalName)
                    .put("preferredAfter", photo.preferredAfter).toString() }
                fun priority(value: Photo) = when (value.source) { "audiodb" -> 2; "youtube" -> 1; else -> 0 }
                val edits = linkedMapOf<String, String>()
                var identitiesChanged = false
                for (alias in aliases) {
                    val aliasKey = "portrait:$alias"
                    synchronized(memory) {
                        val previous = memory.get(aliasKey)
                        if (!(previous != null && previous.expires > completed && image(previous.url) != null &&
                                priority(previous) > priority(photo)) && (previous == null || compatible(previous, verifiedIdentity))) {
                            if (photo.canonicalName.isNotBlank() &&
                                CatalogNames.normalize(previous?.canonicalName?.takeIf(String::isNotBlank) ?: alias) != CatalogNames.normalize(photo.canonicalName))
                                identitiesChanged = true
                            memory.put(aliasKey, photo); edits[aliasKey] = body
                        }
                    }
                }
                // Homonymous performers with known MBIDs keep independent entries;
                // they must not overwrite or repeatedly miss the name-only cache.
                if (photo.musicBrainzId.isNotBlank()) {
                    val verifiedKey = "portrait:mbid:${photo.musicBrainzId.lowercase(java.util.Locale.ROOT)}"
                    synchronized(memory) {
                        val previous = memory.get(verifiedKey)
                        if (previous == null || previous.expires <= completed || image(previous.url) == null ||
                            priority(previous) <= priority(photo)) {
                            memory.put(verifiedKey, photo); edits[verifiedKey] = body
                        }
                    }
                }
                if (edits.isNotEmpty()) {
                    // A slower fallback must not persist over a newer preferred
                    // portrait after the in-memory priority check has completed.
                    persistenceLock.withLock {
                        val current = edits.filterKeys { memory.get(it) == photo }
                        if (current.isNotEmpty()) {
                            val prefs = context.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
                            val hot = synchronized(memory) { memory.snapshot().keys.toSet() }
                            prefs.edit().putStringSet(HOT_PORTRAIT_KEYS, hot)
                                .also { edit -> current.forEach { (key, value) -> edit.putString(key, value) } }.commit()
                        }
                    }
                    photoRevision.update { it + 1L }
                    if (identitiesChanged) identityRevision.update { it + 1L }
                }
                cachedPhoto(artist)
            }
        }
    }

    private fun identityNames(row: JSONObject) = (listOf(row.optString("strArtist")) +
        listOf("strArtistAlternate", "strAlternate").flatMap { row.optString(it).split(';', '|', ',') })
        .map(CatalogNames::clean).filter { it.isNotBlank() && !it.equals("null", true) }.distinctBy(CatalogNames::normalize)

    internal fun matchesIdentity(row: JSONObject, artist: CatalogArtist): Boolean {
        if (row.optString("idArtist").toLongOrNull()?.let { it > 0 } != true) return false
        val requested = mbid(artist.musicBrainzId)
        val returned = mbid(row.optString("strMusicBrainzID"))
        return if (requested.isNotBlank() && returned.isNotBlank()) requested.equals(returned, true)
        else (listOf(artist.name) + artist.aliases).any { name -> identityNames(row).any { CatalogNames.same(name, it) } }
    }

    /** Only aliases from a uniquely verified artist may broaden catalogue credit matching. */
    internal suspend fun enrichIdentity(artist: CatalogArtist): CatalogArtist = withContext(AppDispatchers.Io) {
        val row = audioDbIdentity(artist) ?: return@withContext artist
        artist.copy(musicBrainzId = mbid(artist.musicBrainzId).ifBlank { mbid(row.optString("strMusicBrainzID")) },
            aliases = (artist.aliases + identityNames(row)).filter { it != artist.name }.distinct())
    }
    private suspend fun audioDbIdentity(artist: CatalogArtist): JSONObject? {
        val key = "${CatalogNames.normalize(artist.name)}:${mbid(artist.musicBrainzId)}:${artist.aliases.sorted()}"
        return identityLocks[(key.hashCode() and Int.MAX_VALUE) % identityLocks.size].withLock {
            val now = System.currentTimeMillis()
            val cached = identityMemory.get(key)
            if (cached != null && cached.first > now) return@withLock cached.second
            if (now < audioDbBackoffUntil) throw CatalogHttpException(429, (audioDbBackoffUntil - now) / 1000L)
            try {
                val queries = (listOf(artist.name) + artist.aliases).filter(String::isNotBlank)
                    .distinctBy(CatalogNames::normalize).take(3)
                for (query in queries) {
                    rateLock.withLock {
                        delay((nextRequestAt - System.currentTimeMillis()).coerceAtLeast(0))
                        nextRequestAt = System.currentTimeMillis() + 500L
                    }
                    if (System.currentTimeMillis() < audioDbBackoffUntil)
                        throw CatalogHttpException(429, (audioDbBackoffUntil - System.currentTimeMillis()) / 1000L)
                    val body = client.catalogGet("https://www.theaudiodb.com/api/v1/json/123/search.php".toHttpUrl()
                        .newBuilder().addQueryParameter("s", query).build().toString())
                    val row = withContext(AppDispatchers.CpuBounded) {
                        val array = JSONObject(body).optJSONArray("artists")
                        val matches = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
                            .filter { matchesIdentity(it, artist) }.distinctBy { it.optString("idArtist") }
                        // A same-name ambiguity is not identity verification.
                        if (matches.size > 1) return@withContext null
                        matches.singleOrNull()
                    }
                    if (row != null) {
                        identityMemory.put(key, System.currentTimeMillis() + PHOTO_TTL to row)
                        return@withLock row
                    }
                }
                identityMemory.put(key, System.currentTimeMillis() + 3_600_000L to null)
                null
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                val retry = if (e is CatalogHttpException && e.status == 429)
                    (e.retryAfter ?: 60L).coerceIn(30L, 3600L) * 1000L else 30_000L
                audioDbBackoffUntil = System.currentTimeMillis() + retry
                throw e
            }
        }
    }
}
