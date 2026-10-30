package com.example.data.cache

import android.content.Context
import android.util.AtomicFile
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.viewmodel.MostListenedArtist
import com.example.ui.viewmodel.SectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Last successful shelf selection, independent of Activity and process lifetime. */
data class RecommendationShelfLoad(val context: String, val fetchedAt: Long, val partial: Boolean = false) {
    fun needsRefresh(context: String, now: Long, ttl: Long, allowEnrichment: Boolean): Boolean =
        this.context != context || now - fetchedAt !in 0 until ttl || partial && allowEnrichment
}

data class SavedHomeCards(
    val profileKey: String,
    val signal: String = "",
    val generation: Long = 1L,
    val refreshedAt: Long = System.currentTimeMillis(),
    val quick: SectionState = SectionState(title = "Quick Picks"),
    val era: SectionState = SectionState(title = "Era-Specific Hits"),
    val chart: SectionState = SectionState(title = "Trending"),
    val history: SectionState = SectionState(title = "Recently Played"),
    val personalized: SectionState = SectionState(title = "Music for You"),
    val artists: List<MostListenedArtist> = emptyList(),
    val quickCandidates: List<MusicTrack> = emptyList(),
    val eraCandidates: List<MusicTrack> = emptyList(),
    val chartCandidates: List<MusicTrack> = emptyList(),
    val catalog: List<MusicTrack> = emptyList(),
    val chartRefreshedAt: Long = refreshedAt,
    val inspiredTrack: SectionState = SectionState(title = "Because You Listened"),
    val personalizedCandidates: List<MusicTrack> = emptyList(),
    val inspiredTrackCandidates: List<MusicTrack> = emptyList(),
    val recommendationSource: String = "youtube_music_server",
    val seedKey: String = "",
    val seedTitle: String = "",
    val shelfLoads: Map<String, RecommendationShelfLoad> = emptyMap(),
    val readyShelves: Set<String> = emptySet(),
    val presentationComplete: Boolean = true,
    val checkpointRevision: Long = 0L,
    val inputRevision: String? = null,
    val serverShelves: List<ServerHomeShelf> = emptyList(),
    val profileShelf: ServerHomeShelf? = null,
    val continuationToken: String? = null
)

/**
 * Cache for direct YouTube Music server shelves and profile shelf with async atomic persistence.
 */
class HomeCardCache(context: Context, @Suppress("UNUSED_PARAMETER") workerDispatcher: CoroutineDispatcher = AppDispatchers.Io) {
    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "home_cards_v2.json"))
    private val legacyFile = AtomicFile(File(appContext.filesDir, "home_cards_v1.json"))
    private val presentationFile = AtomicFile(File(appContext.filesDir, "home_cards_presentation_v2.json"))
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val writeChannel = Channel<SavedHomeCards>(Channel.CONFLATED)
    private val presentationChannel = Channel<SavedHomeCards>(Channel.CONFLATED)

    private val writer: Job = scope.launch {
        writeChannel.receiveAsFlow().collect { cards ->
            writeToDisk(file, cards)
        }
    }

    private val presentationWriter: Job = scope.launch {
        presentationChannel.receiveAsFlow().collect { cards ->
            writeToDisk(presentationFile, cards)
        }
    }

    private data class Retained(val path: String, val cards: SavedHomeCards)

    companion object {
        private val memory = AtomicReference<Retained?>(null)
        private val previews = AtomicReference<Retained?>(null)
        private val checkpointSequence = AtomicLong()
        private val fileMutex = Mutex()
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

    fun peek(profile: String): SavedHomeCards? {
        val full = peekFull(profile)
        val preview = peekPresentation(profile)
        return if (full == null || (preview != null && preview.checkpointRevision > full.checkpointRevision)) preview else full
    }

    fun peekFull(profile: String): SavedHomeCards? {
        return memory.get()?.takeIf {
            it.path == file.baseFile.path && it.cards.profileKey == profile
        }?.cards
    }

    private fun peekPresentation(profile: String): SavedHomeCards? = previews.get()?.takeIf {
        it.path == file.baseFile.path && it.cards.profileKey == profile
    }?.cards

    suspend fun read(profile: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
        peekFull(profile)?.let { return@withContext it }
        val loaded = readDisk(profile)
        if (loaded != null) {
            memory.set(Retained(file.baseFile.path, loaded))
        }
        loaded
    }

    suspend fun readInitial(profile: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
        peekPresentation(profile)?.let { return@withContext it }
        val bytes = fileMutex.withLock {
            if (presentationFile.baseFile.exists() && presentationFile.baseFile.length() > 0) {
                runCatching { presentationFile.openRead().use { it.readBytes() } }.getOrNull()
            } else null
        }
        val cards = bytes?.let { deserialize(String(it)) }
        if (cards != null && cards.profileKey == profile) {
            previews.set(Retained(file.baseFile.path, cards))
            return@withContext cards
        }
        read(profile)
    }

    suspend fun readDisk(profile: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
        val bytes = fileMutex.withLock {
            val target = if (file.baseFile.exists()) file else if (legacyFile.baseFile.exists()) legacyFile else null
            if (target != null && target.baseFile.length() > 0) {
                runCatching { target.openRead().use { it.readBytes() } }.getOrNull()
            } else null
        } ?: return@withContext null

        val cards = try {
            deserialize(String(bytes))
        } catch (_: Exception) {
            null
        }

        if (cards != null && cards.profileKey == profile) {
            memory.set(Retained(file.baseFile.path, cards))
            cards
        } else null
    }

    suspend fun save(cards: SavedHomeCards) {
        val stamped = cards.copy(checkpointRevision = checkpointSequence.incrementAndGet())
        memory.set(Retained(file.baseFile.path, stamped))
        previews.set(Retained(file.baseFile.path, stamped))
        writeChannel.send(stamped)
        presentationChannel.send(stamped)
    }

    suspend fun savePresentation(cards: SavedHomeCards) {
        val stamped = cards.copy(checkpointRevision = checkpointSequence.incrementAndGet())
        previews.set(Retained(file.baseFile.path, stamped))
        presentationChannel.send(stamped)
    }

    fun savePresentationCheckpoint(cards: SavedHomeCards, @Suppress("UNUSED_PARAMETER") initialComplete: Boolean = true) {
        scope.launch { savePresentation(cards) }
    }

    fun saveAsync(cards: SavedHomeCards) {
        scope.launch { save(cards) }
    }

    private suspend fun writeToDisk(target: AtomicFile, cards: SavedHomeCards) = withContext(AppDispatchers.Io) {
        val jsonString = serialize(cards).toString()
        fileMutex.withLock {
            try {
                val stream = target.startWrite()
                var success = false
                try {
                    stream.write(jsonString.toByteArray(Charsets.UTF_8))
                    target.finishWrite(stream)
                    success = true
                } finally {
                    if (!success) target.failWrite(stream)
                }
            } catch (e: Exception) {
                android.util.Log.w("HomeCardCache", "Failed to write home card cache", e)
            }
        }
    }

    private fun serialize(cards: SavedHomeCards): JSONObject {
        val root = JSONObject()
        root.put("profileKey", cards.profileKey)
        root.put("signal", cards.signal)
        root.put("generation", cards.generation)
        root.put("refreshedAt", cards.refreshedAt)
        root.put("recommendationSource", cards.recommendationSource)
        root.put("presentationComplete", cards.presentationComplete)
        root.put("checkpointRevision", cards.checkpointRevision)
        cards.inputRevision?.let { root.put("inputRevision", it) }
        cards.continuationToken?.let { root.put("continuationToken", it) }

        // Server Shelves
        val shelvesArray = JSONArray()
        for (shelf in cards.serverShelves) {
            val shelfObj = JSONObject()
            shelfObj.put("id", shelf.id)
            shelfObj.put("title", shelf.title)
            shelfObj.put("subtitle", shelf.subtitle)
            shelf.continuationToken?.let { shelfObj.put("continuationToken", it) }
            val tracksArray = JSONArray()
            for (t in shelf.tracks) {
                tracksArray.put(serializeTrack(t))
            }
            shelfObj.put("tracks", tracksArray)
            shelvesArray.put(shelfObj)
        }
        root.put("serverShelves", shelvesArray)

        // Profile Shelf
        cards.profileShelf?.let { shelf ->
            val pObj = JSONObject()
            pObj.put("id", shelf.id)
            pObj.put("title", shelf.title)
            pObj.put("subtitle", shelf.subtitle)
            val pTracks = JSONArray()
            for (t in shelf.tracks) pTracks.put(serializeTrack(t))
            pObj.put("tracks", pTracks)
            root.put("profileShelf", pObj)
        }

        // Backward compatibility sections
        root.put("quick", serializeSection(cards.quick))
        root.put("era", serializeSection(cards.era))
        root.put("chart", serializeSection(cards.chart))
        root.put("history", serializeSection(cards.history))
        root.put("personalized", serializeSection(cards.personalized))
        root.put("inspiredTrack", serializeSection(cards.inspiredTrack))

        // Candidates / catalog
        root.put("quickCandidates", serializeTracks(cards.quickCandidates))
        root.put("eraCandidates", serializeTracks(cards.eraCandidates))
        root.put("chartCandidates", serializeTracks(cards.chartCandidates))
        root.put("catalog", serializeTracks(cards.catalog))

        // Shelf loads
        val loadsObj = JSONObject()
        for ((k, v) in cards.shelfLoads) {
            val lObj = JSONObject()
            lObj.put("context", v.context)
            lObj.put("fetchedAt", v.fetchedAt)
            lObj.put("partial", v.partial)
            loadsObj.put(k, lObj)
        }
        root.put("shelfLoads", loadsObj)

        val readyArray = JSONArray()
        for (r in cards.readyShelves) readyArray.put(r)
        root.put("readyShelves", readyArray)

        return root
    }

    private fun serializeTrack(track: MusicTrack): JSONObject {
        return JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("durationMs", track.durationMs)
            put("coverUrl", track.coverUrl)
            put("audioUrl", track.audioUrl)
            put("source", track.source)
        }
    }

    private fun serializeTracks(tracks: List<MusicTrack>): JSONArray {
        val arr = JSONArray()
        for (t in tracks) arr.put(serializeTrack(t))
        return arr
    }

    private fun serializeSection(section: SectionState): JSONObject {
        return JSONObject().apply {
            put("title", section.title)
            put("subtitle", section.subtitle)
            put("isLoaded", section.isLoaded)
            put("tracks", serializeTracks(section.tracks))
        }
    }

    private fun deserialize(jsonStr: String): SavedHomeCards {
        val root = JSONObject(jsonStr)
        val profileKey = root.optString("profileKey", "")
        val signal = root.optString("signal", "")
        val generation = root.optLong("generation", 1L)
        val refreshedAt = root.optLong("refreshedAt", System.currentTimeMillis())
        val source = root.optString("recommendationSource", "youtube_music_server")
        val presentationComplete = root.optBoolean("presentationComplete", true)
        val checkpointRevision = root.optLong("checkpointRevision", 0L)
        val inputRevision = root.optString("inputRevision").takeIf { it.isNotBlank() }
        val continuationToken = root.optString("continuationToken").takeIf { it.isNotBlank() }

        // Server Shelves
        val serverShelves = mutableListOf<ServerHomeShelf>()
        val shelvesArray = root.optJSONArray("serverShelves")
        if (shelvesArray != null) {
            for (i in 0 until shelvesArray.length()) {
                val sObj = shelvesArray.optJSONObject(i) ?: continue
                val id = sObj.optString("id", "shelf_$i")
                val title = sObj.optString("title", "")
                val subtitle = sObj.optString("subtitle", "")
                val cont = sObj.optString("continuationToken").takeIf { it.isNotBlank() }
                val tracks = deserializeTracks(sObj.optJSONArray("tracks"))
                if (title.isNotBlank() && tracks.isNotEmpty()) {
                    serverShelves.add(ServerHomeShelf(id, title, subtitle, tracks, cont))
                }
            }
        }

        // Profile Shelf
        val profileShelf = root.optJSONObject("profileShelf")?.let { pObj ->
            val id = pObj.optString("id", "profile_shelf_music_for_you")
            val title = pObj.optString("title", "Music for You")
            val subtitle = pObj.optString("subtitle", "")
            val tracks = deserializeTracks(pObj.optJSONArray("tracks"))
            ServerHomeShelf(id, title, subtitle, tracks)
        }

        val quick = deserializeSection(root.optJSONObject("quick"), "Quick Picks")
        val era = deserializeSection(root.optJSONObject("era"), "Era-Specific Hits")
        val chart = deserializeSection(root.optJSONObject("chart"), "Trending")
        val history = deserializeSection(root.optJSONObject("history"), "Recently Played")
        val personalized = deserializeSection(root.optJSONObject("personalized"), "Music for You")
        val inspired = deserializeSection(root.optJSONObject("inspiredTrack"), "Because You Listened")

        val quickCandidates = deserializeTracks(root.optJSONArray("quickCandidates"))
        val eraCandidates = deserializeTracks(root.optJSONArray("eraCandidates"))
        val chartCandidates = deserializeTracks(root.optJSONArray("chartCandidates"))
        val catalog = deserializeTracks(root.optJSONArray("catalog"))

        val shelfLoads = mutableMapOf<String, RecommendationShelfLoad>()
        val loadsObj = root.optJSONObject("shelfLoads")
        if (loadsObj != null) {
            val keys = loadsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val lObj = loadsObj.optJSONObject(k) ?: continue
                shelfLoads[k] = RecommendationShelfLoad(
                    context = lObj.optString("context", ""),
                    fetchedAt = lObj.optLong("fetchedAt", 0L),
                    partial = lObj.optBoolean("partial", false)
                )
            }
        }

        val readyShelves = mutableSetOf<String>()
        val readyArr = root.optJSONArray("readyShelves")
        if (readyArr != null) {
            for (i in 0 until readyArr.length()) readyArr.optString(i).takeIf(String::isNotBlank)?.let(readyShelves::add)
        }

        return SavedHomeCards(
            profileKey = profileKey,
            signal = signal,
            generation = generation,
            refreshedAt = refreshedAt,
            quick = if (quick.tracks.isNotEmpty()) quick else SectionState(title = "Quick Picks", tracks = serverShelves.firstOrNull()?.tracks.orEmpty(), isLoaded = serverShelves.isNotEmpty()),
            era = era,
            chart = chart,
            history = history,
            personalized = if (personalized.tracks.isNotEmpty()) personalized else SectionState(title = "Music for You", tracks = profileShelf?.tracks.orEmpty(), isLoaded = profileShelf != null),
            inspiredTrack = inspired,
            quickCandidates = quickCandidates,
            eraCandidates = eraCandidates,
            chartCandidates = chartCandidates,
            catalog = catalog,
            recommendationSource = source,
            shelfLoads = shelfLoads,
            readyShelves = readyShelves,
            presentationComplete = presentationComplete,
            checkpointRevision = checkpointRevision,
            inputRevision = inputRevision,
            serverShelves = serverShelves,
            profileShelf = profileShelf,
            continuationToken = continuationToken
        )
    }

    private fun deserializeTrack(obj: JSONObject?): MusicTrack? {
        if (obj == null) return null
        val id = obj.optString("id")
        val title = obj.optString("title")
        if (id.isBlank() || title.isBlank()) return null
        val artist = obj.optString("artist", "YouTube Music")
        val album = obj.optString("album", "YouTube Music")
        val durationMs = obj.optLong("durationMs", 0L)
        val coverUrl = obj.optString("coverUrl")
        val audioUrl = obj.optString("audioUrl")
        val source = obj.optString("source", "YouTube Music")
        return MusicTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = coverUrl,
            audioUrl = audioUrl,
            source = source,
            qualityBadge = "HQ Stream",
            recommendationOnly = true
        )
    }

    private fun deserializeTracks(arr: JSONArray?): List<MusicTrack> {
        if (arr == null) return emptyList()
        val list = mutableListOf<MusicTrack>()
        for (i in 0 until arr.length()) {
            deserializeTrack(arr.optJSONObject(i))?.let(list::add)
        }
        return list
    }

    private fun deserializeSection(obj: JSONObject?, defaultTitle: String): SectionState {
        if (obj == null) return SectionState(title = defaultTitle)
        val title = obj.optString("title", defaultTitle)
        val subtitle = obj.optString("subtitle", "")
        val isLoaded = obj.optBoolean("isLoaded", false)
        val tracks = deserializeTracks(obj.optJSONArray("tracks"))
        return SectionState(title = title, subtitle = subtitle, tracks = tracks, isLoaded = isLoaded || tracks.isNotEmpty())
    }

    fun close() {
        scope.cancel()
    }
}
