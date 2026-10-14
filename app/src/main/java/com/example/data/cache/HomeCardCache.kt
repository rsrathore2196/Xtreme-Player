package com.example.data.cache

import android.content.Context
import android.util.AtomicFile
import com.example.data.model.MusicTrack
import com.example.ui.viewmodel.SectionState
import com.example.ui.viewmodel.MostListenedArtist
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Last successful shelf selection, independent of Activity and process lifetime. */
data class SavedHomeCards(
    val profileKey: String, val signal: String, val generation: Long, val refreshedAt: Long,
    val quick: SectionState, val era: SectionState, val chart: SectionState,
    val history: SectionState, val personalized: SectionState, val artists: List<MostListenedArtist>,
    val quickCandidates: List<MusicTrack>, val eraCandidates: List<MusicTrack>,
    val chartCandidates: List<MusicTrack>, val catalog: List<MusicTrack>,
    val chartRefreshedAt: Long = refreshedAt
)

class HomeCardCache(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "home_cards_v1.json"))
    private data class Retained(val path: String, val cards: SavedHomeCards)
    private companion object {
        val memory = AtomicReference<Retained?>(null)
        val fileMutex = Mutex()
    }
    fun peek(profile: String): SavedHomeCards? = memory.get()?.takeIf {
        it.path == file.baseFile.path && it.cards.profileKey == profile
    }?.cards
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<SavedHomeCards>(Channel.CONFLATED)
    private val writer = scope.launch {
        for (snapshot in writes) {
            try {
                val json = withContext(Dispatchers.Default) { encode(snapshot).toString().toByteArray() }
                fileMutex.withLock {
                    val output = file.startWrite()
                    try { output.write(json); file.finishWrite(output) }
                    catch (e: Exception) { file.failWrite(output); throw e }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("HomeCache", "Could not checkpoint shelves: ${e.message}") }
        }
    }
    fun save(snapshot: SavedHomeCards) {
        memory.set(Retained(file.baseFile.path, snapshot))
        writes.trySend(snapshot)
    }
    fun close() { writes.close(); writer.invokeOnCompletion { scope.cancel() } }
    suspend fun read(profile: String): SavedHomeCards? = withContext(Dispatchers.IO) {
        peek(profile)?.let { return@withContext it }
        val before = memory.get()
        try {
            val bytes = fileMutex.withLock {
                if (!file.baseFile.exists() || file.baseFile.length() > 4_000_000L) return@withContext null
                file.openRead().use { it.readBytes() }
            }
            val decoded = withContext(Dispatchers.Default) { decode(JSONObject(String(bytes)), profile) }
            decoded?.let { memory.compareAndSet(before, Retained(file.baseFile.path, it)) }
            peek(profile) ?: decoded
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    private fun encode(s: SavedHomeCards): JSONObject {
        val tracks = (s.catalog + s.quickCandidates + s.eraCandidates + s.chartCandidates +
            s.quick.tracks + s.era.tracks + s.chart.tracks + s.history.tracks + s.personalized.tracks +
            s.artists.flatMap { it.tracks }).distinctBy { it.id }
        fun ids(list: List<MusicTrack>) = JSONArray(list.map { it.id })
        fun section(value: SectionState) = JSONObject().put("title", value.title)
            .put("subtitle", value.subtitle).put("tracks", ids(value.tracks))
        return JSONObject().put("version", 1).put("profile", s.profileKey).put("signal", s.signal)
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
                .put("motion", t.motionArtworkUrl.orEmpty()) }))
            .put("quick", section(s.quick)).put("era", section(s.era)).put("chart", section(s.chart))
            .put("history", section(s.history)).put("personalized", section(s.personalized))
            .put("artists", JSONArray(s.artists.map { a -> JSONObject().put("name", a.name)
                .put("count", a.playCount).put("tracks", ids(a.tracks)) }))
            .put("quickCandidates", ids(s.quickCandidates)).put("eraCandidates", ids(s.eraCandidates))
            .put("chartCandidates", ids(s.chartCandidates)).put("catalog", ids(s.catalog))
    }
    private fun decode(root: JSONObject, profile: String): SavedHomeCards? {
        if (root.optInt("version") != 1 || root.optString("profile") != profile) return null
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
                t.optString("isrc"), t.optBoolean("lossless"), t.optString("motion").ifBlank { null })
                .takeUnless { it.id.startsWith("xtreme_") || it.audioUrl.contains("soundhelix", true) }
        }.associateBy { it.id }
        fun tracks(ids: JSONArray?): List<MusicTrack> = (0 until (ids?.length() ?: 0))
            .mapNotNull { records[ids?.optString(it)] }
        fun section(name: String): SectionState {
            val value = root.getJSONObject(name); val list = tracks(value.optJSONArray("tracks"))
            return SectionState(value.getString("title"), value.optString("subtitle"), list, isLoaded = list.isNotEmpty())
        }
        val artists = root.optJSONArray("artists")
        return SavedHomeCards(profile, root.optString("signal"), root.optLong("generation"), root.optLong("refreshedAt"),
            section("quick"), section("era"), section("chart"), section("history"), section("personalized"),
            (0 until (artists?.length() ?: 0)).mapNotNull { i ->
                val a = artists?.optJSONObject(i) ?: return@mapNotNull null
                val list = tracks(a.optJSONArray("tracks"))
                MostListenedArtist(a.getString("name"), a.optInt("count"), list).takeIf { list.isNotEmpty() }
            }, tracks(root.optJSONArray("quickCandidates")), tracks(root.optJSONArray("eraCandidates")),
            tracks(root.optJSONArray("chartCandidates")), tracks(root.optJSONArray("catalog")),
            root.optLong("chartRefreshedAt", root.optLong("refreshedAt")))
    }
}
