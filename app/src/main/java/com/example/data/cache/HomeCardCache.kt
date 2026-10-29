package com.example.data.cache

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import com.example.data.model.MusicTrack
import com.example.recommendation.UnifiedRecommendationEngine
import com.example.recommendation.HomeRecommendationPolicy
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.AppDispatchers
import com.example.ui.viewmodel.SectionState
import com.example.ui.viewmodel.MostListenedArtist
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Last successful shelf selection, independent of Activity and process lifetime. */
data class RecommendationShelfLoad(val context: String, val fetchedAt: Long, val partial: Boolean = false) {
    fun needsRefresh(context: String, now: Long, ttl: Long, allowEnrichment: Boolean): Boolean =
        this.context != context || now - fetchedAt !in 0 until ttl || partial && allowEnrichment
}

data class SavedHomeCards(
    val profileKey: String, val signal: String, val generation: Long, val refreshedAt: Long,
    val quick: SectionState, val era: SectionState, val chart: SectionState,
    val history: SectionState, val personalized: SectionState, val artists: List<MostListenedArtist>,
    val quickCandidates: List<MusicTrack>, val eraCandidates: List<MusicTrack>,
    val chartCandidates: List<MusicTrack>, val catalog: List<MusicTrack>,
    val chartRefreshedAt: Long = refreshedAt,
    val inspiredTrack: SectionState = SectionState(title = "Inspired by this Track"),
    val personalizedCandidates: List<MusicTrack> = emptyList(),
    val inspiredTrackCandidates: List<MusicTrack> = emptyList(),
    val recommendationSource: String = "",
    val seedKey: String = "", val seedTitle: String = "",
    val shelfLoads: Map<String, RecommendationShelfLoad> = emptyMap(),
    val readyShelves: Set<String> = emptySet(),
    /** All displayed shelves are present; large discovery pools may still be omitted. */
    val presentationComplete: Boolean = true,
    val checkpointRevision: Long = 0L,
    val inputRevision: String? = null
)

private fun compactPresentation(s: SavedHomeCards) = s.copy(
    quick = s.quick.copy(tracks = s.quick.tracks.take(12)), era = s.era.copy(tracks = s.era.tracks.take(12)),
    chart = s.chart.copy(tracks = s.chart.tracks.take(12)), history = s.history.copy(tracks = s.history.tracks.take(30)),
    personalized = s.personalized.copy(tracks = s.personalized.tracks.take(12)),
    inspiredTrack = s.inspiredTrack.copy(tracks = s.inspiredTrack.tracks.take(12)),
    quickCandidates = emptyList(), eraCandidates = emptyList(), chartCandidates = emptyList(),
    personalizedCandidates = emptyList(), inspiredTrackCandidates = emptyList(),
    catalog = s.catalog.take(1).ifEmpty { listOfNotNull(s.history.tracks.firstOrNull() ?:
        s.quick.tracks.firstOrNull() ?: s.chart.tracks.firstOrNull()) },
    artists = s.artists.take(20).map { it.copy(tracks = it.tracks.take(1)) })

/** A newer compact checkpoint enriches the old full file without discarding its
 * untouched pools or refreshing TTLs. A newer full snapshot always wins. */
internal fun mergePresentation(full: SavedHomeCards, preview: SavedHomeCards?): SavedHomeCards {
    if (preview == null || preview.profileKey != full.profileKey || preview.recommendationSource != full.recommendationSource ||
        preview.checkpointRevision <= full.checkpointRevision) return full
    fun section(old: SectionState, fresh: SectionState) = fresh.takeIf { it.tracks.isNotEmpty() } ?: old
    fun pool(old: List<MusicTrack>, previous: SectionState, fresh: SectionState) =
        if (fresh.tracks.isNotEmpty() && previous.tracks != fresh.tracks) fresh.tracks else old
    return preview.copy(
        generation = maxOf(full.generation, preview.generation),
        quick = section(full.quick, preview.quick), era = section(full.era, preview.era), chart = section(full.chart, preview.chart),
        personalized = section(full.personalized, preview.personalized),
        inspiredTrack = section(full.inspiredTrack, preview.inspiredTrack),
        quickCandidates = pool(full.quickCandidates, full.quick, preview.quick),
        eraCandidates = pool(full.eraCandidates, full.era, preview.era),
        chartCandidates = pool(full.chartCandidates, full.chart, preview.chart),
        personalizedCandidates = pool(full.personalizedCandidates, full.personalized, preview.personalized),
        inspiredTrackCandidates = pool(full.inspiredTrackCandidates, full.inspiredTrack, preview.inspiredTrack),
        catalog = full.catalog.ifEmpty { preview.catalog },
        shelfLoads = full.shelfLoads + preview.shelfLoads.filter { (key, load) ->
            (full.shelfLoads[key]?.fetchedAt ?: 0L) <= load.fetchedAt },
        readyShelves = full.readyShelves + preview.readyShelves)
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeCardCache(context: Context, workerDispatcher: CoroutineDispatcher = BackgroundWorkCoordinator.optionalCpuDispatcher) {
    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "home_cards_v1.json"))
    private val presentationFile = AtomicFile(File(appContext.filesDir, "home_cards_presentation_v1.json"))
    private data class Retained(val path: String, val cards: SavedHomeCards)
    companion object {
        private const val MAX_TRACKS_PER_LIST = 40
        private const val MAX_POOL_TRACKS = 80
        private const val MAX_CANDIDATE_TRACKS = 60
        private val memory = AtomicReference<Retained?>(null)
        private val previews = AtomicReference<Retained?>(null)
        private val checkpointSequence = AtomicLong()
        private val fileMutex = Mutex()
        private val readMutex = Mutex()
        private val preloadScope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
        private val preloads = mutableMapOf<String, Deferred<SavedHomeCards?>>()
        private val previewPreloads = mutableMapOf<String, Deferred<SavedHomeCards?>>()
        private fun preloadKey(context: Context, profile: String) = "${context.filesDir.path}|$profile"
        fun preloadAsync(context: Context, profileKey: String, previewOnly: Boolean = false): Deferred<SavedHomeCards?> = synchronized(preloads) {
            val key = preloadKey(context, profileKey)
            val flights = if (previewOnly) previewPreloads else preloads
            flights.getOrPut(key) {
                preloadScope.async(start = CoroutineStart.LAZY) {
                    val cache = HomeCardCache(context.applicationContext)
                    try { if (previewOnly) cache.readInitial(profileKey) else cache.readDisk(profileKey) }
                    finally { cache.close() }
                }.also { job ->
                    job.invokeOnCompletion { synchronized(preloads) { if (flights[key] === job) flights.remove(key) } }
                }
            }.also { it.start() }
        }
    }
    fun peekFull(profile: String): SavedHomeCards? {
        while (true) {
            val retained = memory.get()?.takeIf {
                it.path == file.baseFile.path && it.cards.profileKey == profile
            } ?: return null
            val combined = mergePresentation(retained.cards, peekPresentation(profile))
            if (combined === retained.cards) return combined
            if (memory.compareAndSet(retained, Retained(retained.path, combined))) return combined
        }
    }
    private fun peekPresentation(profile: String): SavedHomeCards? = previews.get()?.takeIf {
        it.path == file.baseFile.path && it.cards.profileKey == profile
    }?.cards
    fun peek(profile: String): SavedHomeCards? {
        val full = peekFull(profile)
        val preview = peekPresentation(profile)
        return if (full == null || preview != null && preview.checkpointRevision > full.checkpointRevision) preview else full
    }

    /** The small durable checkpoint can be newer than optional candidate-pool persistence. */
    private suspend fun readPresentation(profile: String): SavedHomeCards? {
        peekPresentation(profile)?.let { return it }
        val bytes = fileMutex.withLock {
            if (!presentationFile.baseFile.exists() || presentationFile.baseFile.length() > 600_000L) null
            else runCatching { presentationFile.openRead().use { it.readBytes() } }.getOrNull()
        } ?: return null
        return withContext(serializationDispatcher) {
            try {
                val root = JSONObject(String(bytes))
                decode(root, profile, presentationComplete = root.optBoolean("presentationComplete", false))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }?.also { value ->
            checkpointSequence.updateAndGet { maxOf(it, value.checkpointRevision) }
            previews.updateAndGet { previous ->
                if (previous?.path == file.baseFile.path && previous.cards.checkpointRevision > value.checkpointRevision) previous
                else Retained(file.baseFile.path, value)
            }
        }
    }

    /** v4 includes all displayed shelves in a small header ahead of the discovery pools. */
    suspend fun readInitial(profile: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
      try {
        peek(profile)?.let { return@withContext it }
        readMutex.withLock {
            peek(profile)?.let { return@withLock it }
            readPresentation(profile)?.let { return@withLock it }
            val preview = fileMutex.withLock {
                if (!file.baseFile.exists() || file.baseFile.length() > 4_000_000L) return@withLock null
                file.openRead().bufferedReader().use { input ->
                    JsonReader(input).use previewReader@ { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "version" -> if (reader.nextInt() < 3) return@previewReader null
                                "preview" -> {
                                    val root = readJson(reader) as JSONObject
                                    return@previewReader withContext(serializationDispatcher) {
                                        decode(root, profile, presentationComplete = root.optBoolean("presentationComplete", false))
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        null
                    }
                }
            }
            val result = preview ?: readLegacyPreview(profile)
            if (result != null) previews.set(Retained(file.baseFile.path, result))
            result
        }
      } catch (e: CancellationException) { throw e }
      catch (_: Exception) { null }
    }

    // Older files have a shared track table before their sections. Two streaming
    // passes skip candidate pools and instantiate only referenced visible tracks.
    private suspend fun readLegacyPreview(profile: String): SavedHomeCards? = fileMutex.withLock {
        if (!file.baseFile.exists() || file.baseFile.length() > 4_000_000L) return@withLock null
        val root = JSONObject()
        val omitted = setOf("tracks", "quickCandidates", "eraCandidates", "chartCandidates", "personalizedCandidates",
            "inspiredTrackCandidates", "preview")
        file.openRead().bufferedReader().use { input -> JsonReader(input).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in omitted || name.endsWith("Candidates")) reader.skipValue() else root.put(name, readJson(reader))
            }
            reader.endObject()
        } }
        if (root.optString("profile") != profile || root.optInt("version") !in 1..4) return@withLock null
        for (name in listOf("era", "personalized", "inspiredTrack"))
            root.optJSONObject(name)?.put("tracks", JSONArray())
        val catalog = root.optJSONArray("catalog")
        root.put("catalog", JSONArray().apply { if (catalog != null && catalog.length() > 0) put(catalog.optString(0)) })
        val needed = mutableSetOf<String>()
        fun collect(ids: JSONArray?) { for (i in 0 until (ids?.length() ?: 0)) needed.add(ids!!.optString(i)) }
        listOf("quick", "chart", "history").forEach { collect(root.optJSONObject(it)?.optJSONArray("tracks")) }
        collect(root.optJSONArray("catalog"))
        val artists = root.optJSONArray("artists") ?: JSONArray()
        val compactArtists = JSONArray()
        for (i in 0 until minOf(20, artists.length())) {
            val artist = artists.getJSONObject(i)
            val tracks = artist.optJSONArray("tracks")
            artist.put("tracks", JSONArray().apply { if (tracks != null && tracks.length() > 0) put(tracks.optString(0)) })
            collect(artist.optJSONArray("tracks")); compactArtists.put(artist)
        }
        root.put("artists", compactArtists)
        val tracks = JSONArray()
        file.openRead().bufferedReader().use { input -> JsonReader(input).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "tracks") { reader.skipValue(); continue }
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    val track = JSONObject()
                    var selected = false
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name == "id") {
                            val id = reader.nextString(); selected = id in needed; track.put(name, id)
                        } else if (selected) track.put(name, readJson(reader)) else reader.skipValue()
                    }
                    reader.endObject()
                    if (selected) tracks.put(track)
                }
                reader.endArray()
            }
            reader.endObject()
        } }
        root.put("tracks", tracks)
        withContext(serializationDispatcher) { decode(root, profile, presentationComplete = false) }
    }

    private fun readJson(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().apply {
            reader.beginObject()
            while (reader.hasNext()) put(reader.nextName(), readJson(reader))
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            reader.beginArray(); while (reader.hasNext()) put(readJson(reader)); reader.endArray()
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        else -> reader.nextString()
    }

    /** Full snapshots can be megabytes; tokenize in bounded, cancellable batches. */
    private suspend fun readPreparedJson(reader: JsonReader, tokens: IntArray): Any {
        if (++tokens[0] % 256 == 0) BackgroundWorkCoordinator.yieldToInteraction()
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) put(reader.nextName(), readPreparedJson(reader, tokens))
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) put(readPreparedJson(reader, tokens))
                reader.endArray()
            }
            else -> readJson(reader)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val serializationDispatcher = workerDispatcher
    private val writes = Channel<SavedHomeCards>(Channel.CONFLATED)
    private val presentationWrites = Channel<SavedHomeCards>(Channel.CONFLATED)
    private val closing = AtomicBoolean(false)
    private var firstCheckpointProfile: String? = null
    private val presentationWriter = scope.launch {
        for (snapshot in presentationWrites) {
            // One small milestone checkpoint, never per-track or behind the optional
            // idle gate. Shared CPU/IO lanes and fileMutex bound all cache work.
            var attempt = 0
            while (isActive && attempt < 3) {
                try {
                    if (peekPresentation(snapshot.profileKey) !== snapshot) break
                    val json = withContext(serializationDispatcher) {
                        encode(compactPresentation(snapshot), includePreview = false).toString().toByteArray()
                    }
                    fileMutex.withLock {
                        if (peekPresentation(snapshot.profileKey) !== snapshot) return@withLock
                        val output = presentationFile.startWrite()
                        try { output.write(json); presentationFile.finishWrite(output) }
                        catch (error: Exception) { presentationFile.failWrite(output); throw error }
                    }
                    break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (++attempt == 3) android.util.Log.w("HomeCache", "Presentation checkpoint unavailable", error)
                    else delay(250L shl (attempt - 1))
                }
            }
        }
    }
    private val writer = scope.launch {
        // Debounce snapshots before building JSON. Closing the channel emits the
        // final pending snapshot immediately, preserving the lifecycle flush.
        writes.receiveAsFlow().debounce(2_000L).collect { snapshot ->
            try {
                if (memory.get()?.let { it.path == file.baseFile.path && it.cards !== snapshot } == true)
                    return@collect
                // A new transition can start during the debounce. Defer JSON
                // work until quiet, while allowing disposal to flush immediately.
                BackgroundWorkCoordinator.startup(if (closing.get()) BackgroundWorkCoordinator.StartupPriority.CRITICAL
                    else BackgroundWorkCoordinator.StartupPriority.DEFERRED, requireForeground = false, urgent = { closing.get() }) {
                if (memory.get()?.let { it.path == file.baseFile.path && it.cards !== snapshot } == true)
                    return@startup
                val json = withContext(serializationDispatcher) { encode(snapshot).toString().toByteArray() }
                fileMutex.withLock {
                    // An older closing instance must not overwrite a newer checkpoint.
                    if (memory.get()?.let { it.path == file.baseFile.path && it.cards !== snapshot } == true)
                        return@withLock
                    val output = file.startWrite()
                    try {
                        output.write(json)
                        file.finishWrite(output)
                        // The full file contains the same small header. Do not
                        // leave an older sidecar shadowing that header next launch,
                        // or allow a queued older sidecar writer to recreate it.
                        if (memory.get()?.cards === snapshot &&
                            (peekPresentation(snapshot.profileKey)?.checkpointRevision ?: 0L) <= snapshot.checkpointRevision) {
                            previews.set(Retained(file.baseFile.path, snapshot))
                            presentationFile.delete()
                        }
                    }
                    catch (e: Exception) { file.failWrite(output); throw e }
                }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("HomeCache", "Could not checkpoint shelves: ${e.message}") }
        }
    }
    fun save(snapshot: SavedHomeCards) {
        if (peekFull(snapshot.profileKey)?.copy(checkpointRevision = 0) == snapshot.copy(checkpointRevision = 0)) return
        val versioned = snapshot.copy(checkpointRevision = checkpointSequence.updateAndGet { maxOf(it, snapshot.checkpointRevision) + 1 })
        memory.set(Retained(file.baseFile.path, versioned))
        writes.trySend(versioned)
    }
    fun savePresentationCheckpoint(snapshot: SavedHomeCards, initialComplete: Boolean = false) {
        if (snapshot.recommendationSource != UnifiedRecommendationEngine.SOURCE ||
            listOf(snapshot.quick, snapshot.chart, snapshot.era, snapshot.personalized,
                snapshot.inspiredTrack).none { it.tracks.isNotEmpty() }) return
        if (firstCheckpointProfile == snapshot.profileKey && !initialComplete) return
        val previous = peekPresentation(snapshot.profileKey)
        firstCheckpointProfile = snapshot.profileKey
        if (previous != null && compactPresentation(previous).copy(checkpointRevision = 0) ==
            compactPresentation(snapshot).copy(checkpointRevision = 0)) return
        val versioned = snapshot.copy(checkpointRevision = checkpointSequence.updateAndGet { maxOf(it, snapshot.checkpointRevision) + 1 })
        previews.set(Retained(file.baseFile.path, versioned))
        presentationWrites.trySend(versioned)
    }
    fun close() {
        closing.set(true)
        writes.close()
        presentationWrites.close()
        scope.launch { writer.join(); presentationWriter.join(); scope.cancel() }
    }
    suspend fun read(profile: String): SavedHomeCards? {
        // The admitted caller owns decoding and its cooperative checkpoints. A
        // detached preload would lose Admission/NonEssentialWork while its caller
        // held the scheduler's slot. Other consumers still join the same result.
        val key = preloadKey(appContext, profile)
        while (true) {
            currentCoroutineContext().ensureActive()
            peekFull(profile)?.let { return mergePresentation(it, peekPresentation(profile)) }
            val completion = CompletableDeferred<SavedHomeCards?>()
            val flight = synchronized(preloads) {
                preloads[key] ?: completion.also { preloads[key] = it }
            }
            if (flight !== completion) {
                try { return flight.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // An obsolete owner may have left Home. A live consumer can
                    // retry without inheriting that owner's cancellation.
                    yield()
                    continue
                }
            }
            try {
                return readDisk(profile).also { completion.complete(it) }
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
                throw error
            } finally {
                synchronized(preloads) { if (preloads[key] === completion) preloads.remove(key) }
            }
        }
    }

    private suspend fun readDisk(profile: String): SavedHomeCards? = readDiskOnce(profile)
    private suspend fun readDiskOnce(profile: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
        peekFull(profile)?.let { return@withContext mergePresentation(it, peekPresentation(profile)) }
        val before = memory.get()
        try {
            val presentation = readPresentation(profile)
            val bytes = readMutex.withLock { fileMutex.withLock {
                if (!file.baseFile.exists() || file.baseFile.length() > 4_000_000L) return@withContext presentation
                file.openRead().use { it.readBytes() }
            } }
            // No disk/cache lock is held while optional decoding yields to touch.
            val decoded = withContext(serializationDispatcher) {
                BackgroundWorkCoordinator.yieldToInteraction()
                JsonReader(String(bytes).reader()).use { reader ->
                    decode(readPreparedJson(reader, intArrayOf(0)) as JSONObject, profile, cooperative = true)
                }
            }
            val combined = decoded?.let { mergePresentation(it, peekPresentation(profile) ?: presentation) } ?: presentation
            combined?.let {
                checkpointSequence.updateAndGet { revision -> maxOf(revision, it.checkpointRevision) }
                memory.compareAndSet(before, Retained(file.baseFile.path, it))
            }
            peekFull(profile)?.let { mergePresentation(it, peekPresentation(profile)) } ?: combined
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    private suspend fun encode(snapshot: SavedHomeCards, includePreview: Boolean = true): JSONObject {
        // Catalog bookkeeping is persistence-only; it must not rerun for every
        // visible shelf batch. The file only retains this 80-track prefix.
        val s = if (!includePreview) snapshot else snapshot.copy(catalog = HomeRecommendationPolicy.originalsCooperatively(
            sequenceOf(snapshot.catalog, snapshot.quickCandidates, snapshot.eraCandidates, snapshot.chartCandidates,
                snapshot.personalizedCandidates, snapshot.inspiredTrackCandidates,
                snapshot.history.tracks).flatMap { it.asSequence() }, MAX_POOL_TRACKS,
            checkpoint = { BackgroundWorkCoordinator.yieldToInteraction(bypass = { closing.get() }) }))
        // The shared ID table must prefer current presentation metadata over
        // older copies in candidate/catalog pools (including artwork changes).
        val tracks = (s.quick.tracks.take(MAX_TRACKS_PER_LIST) + s.era.tracks.take(MAX_TRACKS_PER_LIST) +
            s.chart.tracks.take(MAX_TRACKS_PER_LIST) + s.history.tracks.take(MAX_TRACKS_PER_LIST) +
            s.personalized.tracks.take(MAX_TRACKS_PER_LIST) +
            s.inspiredTrack.tracks.take(MAX_TRACKS_PER_LIST) + s.artists.flatMap { it.tracks.take(MAX_TRACKS_PER_LIST) } +
            s.catalog.take(MAX_POOL_TRACKS) + s.quickCandidates.take(MAX_CANDIDATE_TRACKS) +
            s.eraCandidates.take(MAX_CANDIDATE_TRACKS) + s.chartCandidates.take(MAX_CANDIDATE_TRACKS) +
            s.personalizedCandidates.take(MAX_CANDIDATE_TRACKS) +
            s.inspiredTrackCandidates.take(MAX_CANDIDATE_TRACKS)).distinctBy { it.id }
        fun ids(list: List<MusicTrack>) = JSONArray(list.map { it.id })
        fun section(value: SectionState) = JSONObject().put("title", value.title)
            .put("subtitle", value.subtitle).put("tracks", ids(value.tracks.take(MAX_TRACKS_PER_LIST)))
        val root = JSONObject().put("version", 4).put("presentationComplete", s.presentationComplete)
            .put("checkpointRevision", s.checkpointRevision).put("inputRevision", s.inputRevision)
        if (includePreview) {
            root.put("preview", encode(compactPresentation(s), false))
        }
        return root.put("profile", s.profileKey).put("signal", s.signal)
            .put("generation", s.generation).put("refreshedAt", s.refreshedAt)
            .put("mainArtistsVersion", 2)
            .put("chartRefreshedAt", s.chartRefreshedAt)
            .put("tracks", JSONArray(tracks.mapIndexed { index, t ->
                if (index % 64 == 0) BackgroundWorkCoordinator.yieldToInteraction(bypass = { closing.get() })
                JSONObject()
                .put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
                .put("duration", t.durationMs).put("cover", t.coverUrl)
                .put("audio", if (t.id.startsWith("yt_")) "" else t.audioUrl)
                .put("bitrate", t.bitrateKbps).put("badge", t.qualityBadge).put("genre", t.genre)
                .put("liked", t.isLiked).put("cached", t.isCached).put("singers", t.singers)
                .put("writer", t.writer).put("language", t.language).put("year", t.year)
                .put("primaryArtists", JSONArray(t.primaryArtists))
                .put("source", t.source).put("isrc", t.isrc).put("lossless", t.isLossless)
                .put("motion", t.motionArtworkUrl.orEmpty()).put("recommendationOnly", t.recommendationOnly) }))
            .put("quick", section(s.quick)).put("era", section(s.era)).put("chart", section(s.chart))
            .put("history", section(s.history)).put("personalized", section(s.personalized))
            .put("inspiredTrack", section(s.inspiredTrack))
            .put("personalizedCandidates", ids(s.personalizedCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("inspiredTrackCandidates", ids(s.inspiredTrackCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("recommendationSource", s.recommendationSource).put("seedKey", s.seedKey)
            .put("seedTitle", s.seedTitle)
            .put("shelfLoads", JSONObject().apply { s.shelfLoads.forEach { (key, value) ->
                put(key, JSONObject().put("context", value.context).put("fetchedAt", value.fetchedAt).put("partial", value.partial)) } })
            .put("readyShelves", JSONArray(s.readyShelves.toList()))
            .put("artists", JSONArray(s.artists.map { a -> JSONObject().put("name", a.name)
                .put("count", a.playCount).put("tracks", ids(a.tracks.take(MAX_TRACKS_PER_LIST))) }))
            .put("quickCandidates", ids(s.quickCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("eraCandidates", ids(s.eraCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("chartCandidates", ids(s.chartCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("catalog", ids(s.catalog.take(MAX_POOL_TRACKS)))
    }
    private suspend fun decode(root: JSONObject, profile: String, cooperative: Boolean = false,
        presentationComplete: Boolean = true): SavedHomeCards? {
        if (root.optInt("version") !in 1..4 || root.optString("profile") != profile) return null
        checkpointSequence.updateAndGet { maxOf(it, root.optLong("checkpointRevision")) }
        val array = root.getJSONArray("tracks")
        if (array.length() > 1500) return null
        val records = (0 until array.length()).mapNotNull { i ->
            if (cooperative && i % 64 == 0) BackgroundWorkCoordinator.yieldToInteraction()
            val t = array.getJSONObject(i)
            val id = t.getString("id"); val title = t.getString("title"); val artist = t.getString("artist")
            if (id.isBlank() || title.isBlank() || artist.isBlank()) return@mapNotNull null
            MusicTrack(id, title, artist, t.optString("album"), t.optLong("duration"), t.optString("cover"),
                t.optString("audio"), t.optInt("bitrate"), t.optString("badge"), t.optString("genre", "Unknown"),
                t.optBoolean("liked"), t.optBoolean("cached"), t.optString("singers"), t.optString("writer"),
                t.optString("language", "Unknown"), t.optString("year"), t.optString("source"),
                t.optString("isrc"), t.optBoolean("lossless"), t.optString("motion").ifBlank { null }, t.optBoolean("recommendationOnly"),
                t.optJSONArray("primaryArtists")?.let { names ->
                    (0 until names.length()).map { names.optString(it) }.filter(String::isNotBlank)
                }.orEmpty())
                .takeUnless { it.id.startsWith("xtreme_") || it.audioUrl.contains("soundhelix", true) }
        }.associateBy { it.id }
        fun tracks(ids: JSONArray?): List<MusicTrack> = (0 until (ids?.length() ?: 0))
            .mapNotNull { records[ids?.optString(it)] }
        fun section(name: String): SectionState {
            val value = root.getJSONObject(name); val list = tracks(value.optJSONArray("tracks"))
            return SectionState(value.getString("title"), value.optString("subtitle"), list, isLoaded = list.isNotEmpty())
        }
        // Rebuild outdated artist-credit rankings from actual Room history only;
        // leave playback history and every other recommendation shelf intact.
        val artists = root.optJSONArray("artists").takeIf { root.optInt("mainArtistsVersion") == 2 }
        val saved = SavedHomeCards(profile, root.optString("signal"), root.optLong("generation"), root.optLong("refreshedAt"),
            section("quick"), section("era"), section("chart"), section("history"), section("personalized"),
            (0 until (artists?.length() ?: 0)).mapNotNull { i ->
                val a = artists?.optJSONObject(i) ?: return@mapNotNull null
                val list = tracks(a.optJSONArray("tracks"))
                MostListenedArtist(a.getString("name"), a.optInt("count"), list).takeIf { list.isNotEmpty() }
            }, tracks(root.optJSONArray("quickCandidates")), tracks(root.optJSONArray("eraCandidates")),
            tracks(root.optJSONArray("chartCandidates")), tracks(root.optJSONArray("catalog")),
            root.optLong("chartRefreshedAt", root.optLong("refreshedAt")),
            if (root.has("inspiredTrack")) section("inspiredTrack") else SectionState(title = "Inspired by this Track"),
            tracks(root.optJSONArray("personalizedCandidates")),
            tracks(root.optJSONArray("inspiredTrackCandidates")), root.optString("recommendationSource"),
            root.optString("seedKey"), root.optString("seedTitle"),
            root.optJSONObject("shelfLoads")?.let { loads -> loads.keys().asSequence().mapNotNull { key ->
                loads.optJSONObject(key)?.let { key to RecommendationShelfLoad(it.optString("context"), it.optLong("fetchedAt"), it.optBoolean("partial", false)) }
            }.toMap().filterKeys { key -> UnifiedRecommendationEngine.Shelf.entries.any { it.name == key } } }.orEmpty(),
            root.optJSONArray("readyShelves")?.let { a -> (0 until a.length()).map { a.optString(it) }
                .filter { name -> UnifiedRecommendationEngine.Shelf.entries.any { it.name == name } }.toSet() }.orEmpty(),
            presentationComplete, root.optLong("checkpointRevision"),
            root.optString("inputRevision").takeIf { it.isNotBlank() && it != "null" })
        fun verified(list: List<MusicTrack>) = list.filter(UnifiedRecommendationEngine::verified).map { it.copy(recommendationOnly = true) }
        fun clean(section: SectionState): SectionState {
            val tracks = verified(section.tracks)
            return section.copy(tracks = tracks, isLoaded = tracks.isNotEmpty())
        }
        val rejectedShelves = mapOf(
            UnifiedRecommendationEngine.Shelf.QUICK.name to saved.quick,
            UnifiedRecommendationEngine.Shelf.ERA.name to saved.era,
            UnifiedRecommendationEngine.Shelf.CHART.name to saved.chart,
            UnifiedRecommendationEngine.Shelf.PERSONALIZED.name to saved.personalized,
            UnifiedRecommendationEngine.Shelf.INSPIRED.name to saved.inspiredTrack
        ).filterValues { section -> section.tracks.any { !UnifiedRecommendationEngine.verified(it) } }.keys
        return if (saved.recommendationSource == UnifiedRecommendationEngine.SOURCE) saved.copy(
            quick = clean(saved.quick), era = clean(saved.era), chart = clean(saved.chart),
            personalized = clean(saved.personalized), inspiredTrack = clean(saved.inspiredTrack),
            quickCandidates = verified(saved.quickCandidates), eraCandidates = verified(saved.eraCandidates),
            chartCandidates = verified(saved.chartCandidates), personalizedCandidates = verified(saved.personalizedCandidates),
            inspiredTrackCandidates = verified(saved.inspiredTrackCandidates),
            catalog = verified(saved.catalog),
            // Keep usable cached catalog tracks visible; refill shelves containing
            // unsupported catalog identities on their next demand.
            shelfLoads = saved.shelfLoads - rejectedShelves,
            readyShelves = saved.readyShelves - rejectedShelves) else saved.copy(
                quick = SectionState(title = "Quick Picks"), era = SectionState(title = "Era-Specific Hits"),
                chart = SectionState(title = saved.chart.title), personalized = SectionState(title = "Recommended for You"),
                inspiredTrack = SectionState(title = "Inspired by this Track"),
                quickCandidates = emptyList(), eraCandidates = emptyList(), chartCandidates = emptyList(),
                personalizedCandidates = emptyList(), inspiredTrackCandidates = emptyList(),
                catalog = emptyList(), recommendationSource = "", shelfLoads = emptyMap(), readyShelves = emptySet(),
                refreshedAt = 0, chartRefreshedAt = 0, signal = "")

    }
}
