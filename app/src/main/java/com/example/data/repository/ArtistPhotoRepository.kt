package com.example.data.repository

import android.content.Context
import com.example.data.remote.YouTubeMusicApiService
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Only verified artist entities supply portraits. Track artwork is never a portrait fallback. */
object ArtistPhotoRepository {
    private val locks = Array(8) { Mutex() }
    private val persistenceLock = Mutex()
    private const val PHOTO_VERSION = 7
    private const val PHOTO_TTL = 7 * 86_400_000L
    internal const val MAX_RESTORED_PORTRAITS = 512
    internal const val HOT_PORTRAIT_KEYS = "portrait_hot_keys_v1"
    @Volatile private var restoreDemanded = false
    private data class Photo(val expires: Long, val url: String?, val source: String,
        val musicBrainzId: String = "", val catalogId: String = "",
        val canonicalName: String = "")
    private val memory = android.util.LruCache<String, Photo>(512)
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
    private fun key(artist: CatalogArtist) = artist.id.takeIf { it.startsWith("UC") }?.let { "portrait:channel:$it" }
        ?: mbid(artist.musicBrainzId).lowercase(java.util.Locale.ROOT)
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
        ?.takeIf { it.source == "youtube" }?.url?.let(::image)
    fun initialPhoto(artist: CatalogArtist): String? = cachedPhoto(artist)
        ?: image(artist.image).takeIf { artist.id.startsWith("UC") }
    /** Memory-only, provider-verified aliases; never fuzzy-merge unrelated performers. */
    fun canonicalName(name: String): String = memory.get("portrait:${CatalogNames.normalize(name)}")
        ?.takeIf { it.source == "youtube" }?.canonicalName?.takeIf(String::isNotBlank) ?: name
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
                if (version !in 3..PHOTO_VERSION || source != "youtube") continue
                val url = image(row.optString("image"))
                // Older misses used incomplete artist/header parsing. Keep valid
                // portraits, but retry those obsolete negative entries now.
                if (url == null && version < PHOTO_VERSION) continue
                val expires = row.optLong("expires")
                val canonical = row.optString("canonicalName")
                if (expires > now || url != null) synchronized(memory) {
                    // Disk restoration cannot overwrite a newer in-flight provider result.
                    if (memory.get(entry.first) == null) {
                        memory.put(entry.first, Photo(expires, url, source, mbid(row.optString("mbid")),
                            row.optString("catalogId"), canonical))
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

    private fun needsRefresh(photo: Photo, now: Long) = photo.expires <= now
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
                val source = "youtube"
                var verifiedIdentity = artist
                var identityVerified = false
                val result = attempt {
                    YouTubeMusicApiService.artistIdentity(artist, context)?.let { identity ->
                        verifiedIdentity = identity.copy(musicBrainzId = artist.musicBrainzId,
                            aliases = artist.aliases)
                        identityVerified = true
                        identity.image
                    }
                } ?: image(cached?.url)?.takeIf { cached?.source == "youtube" }
                val completed = System.currentTimeMillis()
                val retry = if (failed) 60_000L else if (result == null) 3_600_000L else PHOTO_TTL
                val canonical = if (identityVerified || result != null) verifiedIdentity.name else cached?.canonicalName.orEmpty()
                val photo = Photo(completed + retry, result, source,
                    mbid(verifiedIdentity.musicBrainzId).ifBlank { cached?.musicBrainzId.orEmpty() }, verifiedIdentity.id,
                    canonical)
                val aliases = (if (identityVerified || result != null)
                    listOf(artist.name, verifiedIdentity.name) + verifiedIdentity.aliases else listOf(artist.name))
                    .map(CatalogNames::normalize).filter(String::isNotBlank).distinct()
                val body = withContext(AppDispatchers.CpuBounded) { JSONObject().put("version", PHOTO_VERSION)
                    .put("image", result.orEmpty()).put("source", source).put("expires", photo.expires)
                    .put("mbid", photo.musicBrainzId).put("catalogId", photo.catalogId)
                    .put("canonicalName", photo.canonicalName)
                    .toString() }
                val edits = linkedMapOf<String, String>()
                var identitiesChanged = false
                for (alias in aliases) {
                    val aliasKey = "portrait:$alias"
                    synchronized(memory) {
                        val previous = memory.get(aliasKey)
                        if (!(previous != null && previous.expires > completed && image(previous.url) != null &&
                                previous.expires > photo.expires) && (previous == null || compatible(previous, verifiedIdentity))) {
                            if (photo.canonicalName.isNotBlank() &&
                                CatalogNames.normalize(previous?.canonicalName?.takeIf(String::isNotBlank) ?: alias) != CatalogNames.normalize(photo.canonicalName))
                                identitiesChanged = true
                            memory.put(aliasKey, photo); edits[aliasKey] = body
                        }
                    }
                }
                // Homonymous performers with known MBIDs keep independent entries;
                // they must not overwrite or repeatedly miss the name-only cache.
                if (photo.catalogId.startsWith("UC") || photo.musicBrainzId.isNotBlank()) {
                    val verifiedKey = if (photo.catalogId.startsWith("UC")) "portrait:channel:${photo.catalogId}"
                        else "portrait:mbid:${photo.musicBrainzId.lowercase(java.util.Locale.ROOT)}"
                    synchronized(memory) {
                        val previous = memory.get(verifiedKey)
                        if (previous == null || previous.expires <= completed || image(previous.url) == null ||
                            previous.expires <= photo.expires) {
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

}
