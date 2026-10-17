package com.example.data.cache

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import com.example.data.model.MusicTrack
import com.example.recommendation.UnifiedRecommendationEngine
import com.example.performance.BackgroundWorkCoordinator
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Last successful shelf selection, independent of Activity and process lifetime. */
data class RecommendationShelfLoad(val context: String, val fetchedAt: Long)

data class SavedHomeCards(
    val profileKey: String, val signal: String, val generation: Long, val refreshedAt: Long,
    val quick: SectionState, val era: SectionState, val chart: SectionState,
    val history: SectionState, val personalized: SectionState, val artists: List<MostListenedArtist>,
    val quickCandidates: List<MusicTrack>, val eraCandidates: List<MusicTrack>,
    val chartCandidates: List<MusicTrack>, val catalog: List<MusicTrack>,
    val chartRefreshedAt: Long = refreshedAt,
    val similarArtists: SectionState = SectionState(title = "More from Similar Artists"),
    val inspiredTrack: SectionState = SectionState(title = "Inspired by this Track"),
    val personalizedCandidates: List<MusicTrack> = emptyList(),
    val similarArtistCandidates: List<MusicTrack> = emptyList(),
    val inspiredTrackCandidates: List<MusicTrack> = emptyList(),
    val recommendationSource: String = "",
    val seedKey: String = "", val seedArtist: String = "", val seedTitle: String = "",
    val shelfLoads: Map<String, RecommendationShelfLoad> = emptyMap(),
    val readyShelves: Set<String> = emptySet()
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeCardCache(context: Context, workerDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "home_cards_v1.json"))
    private data class Retained(val path: String, val cards: SavedHomeCards)
    companion object {
        private const val MAX_TRACKS_PER_LIST = 40
        private const val MAX_POOL_TRACKS = 80
        private const val MAX_CANDIDATE_TRACKS = 60
        private val memory = AtomicReference<Retained?>(null)
        private val previews = AtomicReference<Retained?>(null)
        private val fileMutex = Mutex()
        private val readMutex = Mutex()
        private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val preloads = mutableMapOf<String, Deferred<SavedHomeCards?>>()
        private fun preloadKey(context: Context, profile: String) = "${context.filesDir.path}|$profile"
        fun preloadAsync(context: Context, profileKey: String): Deferred<SavedHomeCards?> = synchronized(preloads) {
            val key = preloadKey(context, profileKey)
            preloads.getOrPut(key) {
                preloadScope.async(start = CoroutineStart.LAZY) {
                    val cache = HomeCardCache(context.applicationContext)
                    try { cache.readDisk(profileKey) } finally { cache.close() }
                }.also { job ->
                    job.invokeOnCompletion { synchronized(preloads) { if (preloads[key] === job) preloads.remove(key) } }
                }
            }.also { it.start() }
        }
    }
    fun peekFull(profile: String): SavedHomeCards? = memory.get()?.takeIf {
        it.path == file.baseFile.path && it.cards.profileKey == profile
    }?.cards
    fun peek(profile: String): SavedHomeCards? = peekFull(profile) ?: previews.get()?.takeIf {
        it.path == file.baseFile.path && it.cards.profileKey == profile
    }?.cards

    /** Read only visible content; v3 keeps this small header before the large pools. */
    suspend fun readInitial(profile: String): SavedHomeCards? = withContext(Dispatchers.IO) {
      try {
        peek(profile)?.let { return@withContext it }
        readMutex.withLock {
            peek(profile)?.let { return@withLock it }
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
                                    return@previewReader withContext(serializationDispatcher) { decode(root, profile) }
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
            "similarArtistCandidates", "inspiredTrackCandidates", "preview")
        file.openRead().bufferedReader().use { input -> JsonReader(input).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in omitted) reader.skipValue() else root.put(name, readJson(reader))
            }
            reader.endObject()
        } }
        if (root.optString("profile") != profile || root.optInt("version") !in 1..3) return@withLock null
        for (name in listOf("era", "personalized", "similarArtists", "inspiredTrack"))
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
        withContext(serializationDispatcher) { decode(root, profile) }
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serializationDispatcher = workerDispatcher.limitedParallelism(1)
    private val writes = Channel<SavedHomeCards>(Channel.CONFLATED)
    private val closing = AtomicBoolean(false)
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
                    try { output.write(json); file.finishWrite(output) }
                    catch (e: Exception) { file.failWrite(output); throw e }
                }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("HomeCache", "Could not checkpoint shelves: ${e.message}") }
        }
    }
    fun save(snapshot: SavedHomeCards) {
        if (peekFull(snapshot.profileKey) == snapshot) return
        memory.set(Retained(file.baseFile.path, snapshot))
        previews.set(null)
        writes.trySend(snapshot)
    }
    fun close() {
        closing.set(true)
        writes.close()
        writer.invokeOnCompletion { scope.cancel() }
    }
    suspend fun read(profile: String): SavedHomeCards? {
        peekFull(profile)?.let { return it }
        val loading = synchronized(preloads) { preloads["${file.baseFile.parentFile!!.path}|$profile"] }
        return loading?.await() ?: readDisk(profile)
    }
    private suspend fun readDisk(profile: String): SavedHomeCards? = readMutex.withLock {
        readDiskOnce(profile)
    }
    private suspend fun readDiskOnce(profile: String): SavedHomeCards? = withContext(Dispatchers.IO) {
        peekFull(profile)?.let { return@withContext it }
        val before = memory.get()
        try {
            val bytes = fileMutex.withLock {
                if (!file.baseFile.exists() || file.baseFile.length() > 4_000_000L) return@withContext null
                file.openRead().use { it.readBytes() }
            }
            val decoded = withContext(serializationDispatcher) { decode(JSONObject(String(bytes)), profile) }
            decoded?.let {
                memory.compareAndSet(before, Retained(file.baseFile.path, it))
                previews.set(null)
            }
            peekFull(profile) ?: decoded
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    private fun encode(s: SavedHomeCards, includePreview: Boolean = true): JSONObject {
        val tracks = (s.catalog.take(MAX_POOL_TRACKS) + s.quickCandidates.take(MAX_CANDIDATE_TRACKS) +
            s.eraCandidates.take(MAX_CANDIDATE_TRACKS) + s.chartCandidates.take(MAX_CANDIDATE_TRACKS) +
            s.quick.tracks.take(MAX_TRACKS_PER_LIST) + s.era.tracks.take(MAX_TRACKS_PER_LIST) +
            s.chart.tracks.take(MAX_TRACKS_PER_LIST) + s.history.tracks.take(MAX_TRACKS_PER_LIST) +
            s.personalized.tracks.take(MAX_TRACKS_PER_LIST) + s.similarArtists.tracks.take(MAX_TRACKS_PER_LIST) +
            s.inspiredTrack.tracks.take(MAX_TRACKS_PER_LIST) + s.personalizedCandidates.take(MAX_CANDIDATE_TRACKS) +
            s.similarArtistCandidates.take(MAX_CANDIDATE_TRACKS) + s.inspiredTrackCandidates.take(MAX_CANDIDATE_TRACKS) +
            s.artists.flatMap { it.tracks.take(MAX_TRACKS_PER_LIST) }).distinctBy { it.id }
        fun ids(list: List<MusicTrack>) = JSONArray(list.map { it.id })
        fun section(value: SectionState) = JSONObject().put("title", value.title)
            .put("subtitle", value.subtitle).put("tracks", ids(value.tracks.take(MAX_TRACKS_PER_LIST)))
        val root = JSONObject().put("version", 3)
        if (includePreview) {
            val empty = SectionState()
            root.put("preview", encode(s.copy(quick = s.quick.copy(tracks = s.quick.tracks.take(12)),
                chart = s.chart.copy(tracks = s.chart.tracks.take(12)), history = s.history.copy(tracks = s.history.tracks.take(30)),
                era = empty.copy(title = s.era.title),
                personalized = empty.copy(title = s.personalized.title),
                similarArtists = empty.copy(title = s.similarArtists.title), inspiredTrack = empty.copy(title = s.inspiredTrack.title),
                quickCandidates = emptyList(), eraCandidates = emptyList(), chartCandidates = emptyList(),
                personalizedCandidates = emptyList(), similarArtistCandidates = emptyList(), inspiredTrackCandidates = emptyList(),
                catalog = s.catalog.take(1), artists = s.artists.take(20).map { it.copy(tracks = it.tracks.take(1)) }), false))
        }
        return root.put("profile", s.profileKey).put("signal", s.signal)
            .put("generation", s.generation).put("refreshedAt", s.refreshedAt)
            .put("chartRefreshedAt", s.chartRefreshedAt)
            .put("tracks", JSONArray(tracks.map { t -> JSONObject()
                .put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
                .put("duration", t.durationMs).put("cover", t.coverUrl)
                .put("audio", if (t.id.startsWith("yt_")) "" else t.audioUrl)
                .put("bitrate", t.bitrateKbps).put("badge", t.qualityBadge).put("genre", t.genre)
                .put("liked", t.isLiked).put("cached", t.isCached).put("singers", t.singers)
                .put("writer", t.writer).put("language", t.language).put("year", t.year)
                .put("source", t.source).put("isrc", t.isrc).put("lossless", t.isLossless)
                .put("motion", t.motionArtworkUrl.orEmpty()).put("recommendationOnly", t.recommendationOnly) }))
            .put("quick", section(s.quick)).put("era", section(s.era)).put("chart", section(s.chart))
            .put("history", section(s.history)).put("personalized", section(s.personalized))
            .put("similarArtists", section(s.similarArtists)).put("inspiredTrack", section(s.inspiredTrack))
            .put("personalizedCandidates", ids(s.personalizedCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("similarArtistCandidates", ids(s.similarArtistCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("inspiredTrackCandidates", ids(s.inspiredTrackCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("recommendationSource", s.recommendationSource).put("seedKey", s.seedKey)
            .put("seedArtist", s.seedArtist).put("seedTitle", s.seedTitle)
            .put("shelfLoads", JSONObject().apply { s.shelfLoads.forEach { (key, value) ->
                put(key, JSONObject().put("context", value.context).put("fetchedAt", value.fetchedAt)) } })
            .put("readyShelves", JSONArray(s.readyShelves.toList()))
            .put("artists", JSONArray(s.artists.map { a -> JSONObject().put("name", a.name)
                .put("count", a.playCount).put("tracks", ids(a.tracks.take(MAX_TRACKS_PER_LIST))) }))
            .put("quickCandidates", ids(s.quickCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("eraCandidates", ids(s.eraCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("chartCandidates", ids(s.chartCandidates.take(MAX_CANDIDATE_TRACKS)))
            .put("catalog", ids(s.catalog.take(MAX_POOL_TRACKS)))
    }
    private fun decode(root: JSONObject, profile: String): SavedHomeCards? {
        if (root.optInt("version") !in 1..3 || root.optString("profile") != profile) return null
        val array = root.getJSONArray("tracks")
        if (array.length() > 1500) return null
        val records = (0 until array.length()).mapNotNull { i ->
            val t = array.getJSONObject(i)
            val id = t.getString("id"); val title = t.getString("title"); val artist = t.getString("artist")
            if (id.isBlank() || title.isBlank() || artist.isBlank()) return@mapNotNull null
            MusicTrack(id, title, artist, t.optString("album"), t.optLong("duration"), t.optString("cover"),
                t.optString("audio"), t.optInt("bitrate"), t.optString("badge"), t.optString("genre", "Unknown"),
                t.optBoolean("liked"), t.optBoolean("cached"), t.optString("singers"), t.optString("writer"),
                t.optString("language", "Unknown"), t.optString("year"), t.optString("source"),
                t.optString("isrc"), t.optBoolean("lossless"), t.optString("motion").ifBlank { null }, t.optBoolean("recommendationOnly"))
                .takeUnless { it.id.startsWith("xtreme_") || it.audioUrl.contains("soundhelix", true) }
        }.associateBy { it.id }
        fun tracks(ids: JSONArray?): List<MusicTrack> = (0 until (ids?.length() ?: 0))
            .mapNotNull { records[ids?.optString(it)] }
        fun section(name: String): SectionState {
            val value = root.getJSONObject(name); val list = tracks(value.optJSONArray("tracks"))
            return SectionState(value.getString("title"), value.optString("subtitle"), list, isLoaded = list.isNotEmpty())
        }
        val artists = root.optJSONArray("artists")
        val saved = SavedHomeCards(profile, root.optString("signal"), root.optLong("generation"), root.optLong("refreshedAt"),
            section("quick"), section("era"), section("chart"), section("history"), section("personalized"),
            (0 until (artists?.length() ?: 0)).mapNotNull { i ->
                val a = artists?.optJSONObject(i) ?: return@mapNotNull null
                val list = tracks(a.optJSONArray("tracks"))
                MostListenedArtist(a.getString("name"), a.optInt("count"), list).takeIf { list.isNotEmpty() }
            }, tracks(root.optJSONArray("quickCandidates")), tracks(root.optJSONArray("eraCandidates")),
            tracks(root.optJSONArray("chartCandidates")), tracks(root.optJSONArray("catalog")),
            root.optLong("chartRefreshedAt", root.optLong("refreshedAt")),
            if (root.has("similarArtists")) section("similarArtists") else SectionState(title = "More from Similar Artists"),
            if (root.has("inspiredTrack")) section("inspiredTrack") else SectionState(title = "Inspired by this Track"),
            tracks(root.optJSONArray("personalizedCandidates")), tracks(root.optJSONArray("similarArtistCandidates")),
            tracks(root.optJSONArray("inspiredTrackCandidates")), root.optString("recommendationSource"),
            root.optString("seedKey"), root.optString("seedArtist"), root.optString("seedTitle"),
            root.optJSONObject("shelfLoads")?.let { loads -> loads.keys().asSequence().mapNotNull { key ->
                loads.optJSONObject(key)?.let { key to RecommendationShelfLoad(it.optString("context"), it.optLong("fetchedAt")) }
            }.toMap() }.orEmpty(),
            root.optJSONArray("readyShelves")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty())
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
            UnifiedRecommendationEngine.Shelf.SIMILAR.name to saved.similarArtists,
            UnifiedRecommendationEngine.Shelf.INSPIRED.name to saved.inspiredTrack
        ).filterValues { section -> section.tracks.any { !UnifiedRecommendationEngine.verified(it) } }.keys
        return if (saved.recommendationSource == UnifiedRecommendationEngine.SOURCE) saved.copy(
            quick = clean(saved.quick), era = clean(saved.era), chart = clean(saved.chart),
            personalized = clean(saved.personalized), similarArtists = clean(saved.similarArtists), inspiredTrack = clean(saved.inspiredTrack),
            quickCandidates = verified(saved.quickCandidates), eraCandidates = verified(saved.eraCandidates),
            chartCandidates = verified(saved.chartCandidates), personalizedCandidates = verified(saved.personalizedCandidates),
            similarArtistCandidates = verified(saved.similarArtistCandidates), inspiredTrackCandidates = verified(saved.inspiredTrackCandidates),
            catalog = verified(saved.catalog),
            // Keep usable cached Jio tracks visible, but refill shelves whose old
            // snapshots included unsupported provider candidates on next demand.
            shelfLoads = saved.shelfLoads - rejectedShelves,
            readyShelves = saved.readyShelves - rejectedShelves) else saved.copy(
                quick = SectionState(title = "Quick Picks"), era = SectionState(title = "Era-Specific Hits"),
                chart = SectionState(title = saved.chart.title), personalized = SectionState(title = "Recommended for You"),
                similarArtists = SectionState(title = "More from Similar Artists"), inspiredTrack = SectionState(title = "Inspired by this Track"),
                quickCandidates = emptyList(), eraCandidates = emptyList(), chartCandidates = emptyList(),
                personalizedCandidates = emptyList(), similarArtistCandidates = emptyList(), inspiredTrackCandidates = emptyList(),
                catalog = emptyList(), recommendationSource = "", shelfLoads = emptyMap(), readyShelves = emptySet(),
                refreshedAt = 0, chartRefreshedAt = 0, signal = "")

    }
}
