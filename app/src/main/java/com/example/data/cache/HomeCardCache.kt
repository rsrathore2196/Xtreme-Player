package com.example.data.cache

import android.content.Context
import android.util.AtomicFile
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.performance.AppDispatchers
import com.example.ui.viewmodel.MostListenedArtist
import com.example.ui.viewmodel.SectionState
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Server and profile freshness/identity are independent of each other and of local listening data. */
data class SavedHomeCards(
    val profileKey: String,
    val refreshedAt: Long = 0L,
    val history: SectionState = SectionState(title = "Jump Back In"),
    val artists: List<MostListenedArtist> = emptyList(),
    val inputRevision: String? = null,
    val serverShelves: List<ServerHomeShelf> = emptyList(),
    val profileShelf: ServerHomeShelf? = null,
    val continuationToken: String? = null,
    val profileShelfKey: String = "",
    val profileFetchedAt: Long = 0L,
    val serverFetchSucceeded: Boolean = false,
    val profileFetchSucceeded: Boolean = false,
    val consumedContinuations: Set<String> = emptySet(),
    val artistDataVersion: Int = 2
)

class HomeCardCache(context: Context, @Suppress("UNUSED_PARAMETER") workerDispatcher: CoroutineDispatcher = AppDispatchers.Io) {
    private val app = context.applicationContext
    private val namespace = "${app.packageName}:${System.identityHashCode(app)}"
    private val directory by lazy { app.filesDir }
    companion object {
        private const val VERSION = 3
        private const val MAX_BYTES = 4 * 1024 * 1024
        private data class Pending(val target: File, val text: String)
        private val memory = android.util.LruCache<String, JSONObject>(12)
        private val pending = linkedMapOf<String, Pending>()
        private val fileMutex = Mutex()
        private val signal = Channel<Unit>(Channel.CONFLATED)
        private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
        init { scope.launch {
            for (ignored in signal) fileMutex.withLock {
                while (true) {
                    val write = synchronized(pending) { pending.values.firstOrNull() } ?: break
                    val file = AtomicFile(write.target)
                    var output: java.io.FileOutputStream? = null
                    try {
                        output = file.startWrite(); output.write(write.text.toByteArray()); file.finishWrite(output)
                        synchronized(pending) { if (pending[write.target.path] === write) pending.remove(write.target.path) }
                    } catch (cancelled: CancellationException) { file.failWrite(output); throw cancelled }
                    catch (error: Exception) {
                        file.failWrite(output)
                        android.util.Log.w("HomeCardCache", "Home cache write remains pending", error)
                        break
                    }
                }
            }
        } }
        fun preloadAsync(context: Context, profileKey: String, @Suppress("UNUSED_PARAMETER") previewOnly: Boolean = false): Deferred<SavedHomeCards?> =
            scope.async { HomeCardCache(context).read(profileKey) }
    }
    private fun file(name: String) = File(directory, "home_${name}_v3.json")
    private fun memoryKey(name: String) = "$namespace:$name"
    private fun peekJson(name: String) = synchronized(memory) { memory.get(memoryKey(name)) }
    private suspend fun readJson(name: String): JSONObject? = withContext(AppDispatchers.Io) {
        peekJson(name) ?: try {
            val text = fileMutex.withLock {
                val file = AtomicFile(file(name))
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
                file.openRead().bufferedReader().use { reader ->
                    val chars = CharArray(MAX_BYTES + 1); var count = 0
                    while (count < chars.size) { val next = reader.read(chars, count, chars.size - count); if (next < 0) break; count += next }
                    if (count > MAX_BYTES) null else String(chars, 0, count)
                }
            } ?: return@withContext null
            withContext(AppDispatchers.CpuBounded) { JSONObject(text).takeIf { it.optInt("version") == VERSION } }
                ?.also { synchronized(memory) { memory.put(memoryKey(name), it) } }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    private suspend fun write(name: String, value: JSONObject) = withContext(AppDispatchers.Io) {
        val encoded = withContext(AppDispatchers.CpuBounded) { value.put("version", VERSION).toString() }
        if (encoded.toByteArray().size > MAX_BYTES) return@withContext
        synchronized(memory) { memory.put(memoryKey(name), value) }
        val target = file(name)
        synchronized(pending) { pending[target.path] = Pending(target, encoded); while (pending.size > 12) pending.remove(pending.keys.first()) }
        signal.trySend(Unit)
    }
    private fun tracks(rows: List<MusicTrack>) = JSONArray(RecommendationTrackCodec.encode(rows))
    private fun tracks(rows: JSONArray?) = rows?.let { RecommendationTrackCodec.decode(it.toString(), 250) }.orEmpty()
    private fun nullable(row: JSONObject, key: String) = row.optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun shelf(row: ServerHomeShelf) = JSONObject().put("id", row.id).put("title", row.title).put("subtitle", row.subtitle)
        .put("tracks", tracks(row.tracks)).put("continuationToken", row.continuationToken)
    private fun shelf(row: JSONObject): ServerHomeShelf = ServerHomeShelf(row.getString("id"), row.getString("title"), row.optString("subtitle"),
        tracks(row.optJSONArray("tracks")).map { it.copy(recommendationOnly = true) }, nullable(row, "continuationToken"))
    private fun shelves(row: JSONObject?): List<ServerHomeShelf> = row?.optJSONArray("serverShelves")?.let { array ->
        (0 until minOf(60, array.length())).mapNotNull { runCatching { shelf(array.getJSONObject(it)) }.getOrNull() }
    }.orEmpty()
    private fun local(homeKey: String, home: JSONObject?, local: JSONObject?): SavedHomeCards {
        val artists = local?.optJSONArray("artists")
        return SavedHomeCards(homeKey, home?.optLong("refreshedAt") ?: 0L,
            history = SectionState("Jump Back In", tracks = tracks(local?.optJSONArray("recent")), isLoaded = true),
            artists = (0 until minOf(15, artists?.length() ?: 0)).mapNotNull { i -> runCatching {
                val artist = artists!!.getJSONObject(i)
                MostListenedArtist(artist.getString("name"), artist.getInt("playCount"), tracks(artist.optJSONArray("tracks")))
            }.getOrNull() }, inputRevision = local?.let { nullable(it, "inputRevision") }, serverShelves = shelves(home),
            continuationToken = home?.let { nullable(it, "continuationToken") }, serverFetchSucceeded = home?.optBoolean("success") == true,
            consumedContinuations = home?.optJSONArray("consumedContinuations")?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty(),
            artistDataVersion = local?.optInt("artistDataVersion", 0) ?: 0)
    }
    fun peek(profile: String): SavedHomeCards? {
        val home = peekJson("server_${HomeCacheIdentity.fileKey(profile)}")
        val history = peekJson("local")
        return if (home == null && history == null) null else local(profile, home, history)
    }
    fun peekFull(profile: String) = peek(profile)
    suspend fun readInitial(profile: String) = read(profile)
    suspend fun readDisk(profile: String) = read(profile)
    suspend fun read(profile: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
        val home = readJson("server_${HomeCacheIdentity.fileKey(profile)}")?.takeIf { it.optString("key") == profile }
        val history = readJson("local")
        if (home == null && history == null) return@withContext legacy(profile)
        withContext(AppDispatchers.CpuBounded) { local(profile, home, history) }
    }
    suspend fun readProfile(key: String): SavedHomeCards? {
        val row = readJson("profile_${HomeCacheIdentity.fileKey(key)}")?.takeIf { it.optString("key") == key } ?: return null
        return withContext(AppDispatchers.CpuBounded) { SavedHomeCards("", profileShelfKey = key,
            profileShelf = row.optJSONObject("shelf")?.let(::shelf), profileFetchedAt = row.optLong("fetchedAt"), profileFetchSucceeded = row.optBoolean("success")) }
    }
    suspend fun saveHome(cards: SavedHomeCards) = withContext(AppDispatchers.CpuBounded) {
        write("server_${HomeCacheIdentity.fileKey(cards.profileKey)}", JSONObject().put("key", cards.profileKey).put("refreshedAt", cards.refreshedAt)
            .put("success", cards.serverFetchSucceeded).put("serverShelves", JSONArray(cards.serverShelves.map(::shelf)))
            .put("continuationToken", cards.continuationToken).put("consumedContinuations", JSONArray(cards.consumedContinuations.toList())))
    }
    suspend fun saveProfile(cards: SavedHomeCards) = withContext(AppDispatchers.CpuBounded) {
        write("profile_${HomeCacheIdentity.fileKey(cards.profileShelfKey)}", JSONObject().put("key", cards.profileShelfKey)
            .put("fetchedAt", cards.profileFetchedAt).put("success", cards.profileFetchSucceeded).put("shelf", cards.profileShelf?.let(::shelf)))
    }
    suspend fun saveLocal(cards: SavedHomeCards) = withContext(AppDispatchers.CpuBounded) {
        write("local", JSONObject().put("inputRevision", cards.inputRevision).put("artistDataVersion", cards.artistDataVersion)
            .put("recent", tracks(cards.history.tracks.take(30))).put("artists", JSONArray(cards.artists.take(15).map {
                JSONObject().put("name", it.name).put("playCount", it.playCount).put("tracks", tracks(it.tracks.take(3)))
            })))
    }
    suspend fun save(cards: SavedHomeCards) { saveHome(cards); if (cards.profileShelfKey.isNotBlank()) saveProfile(cards); saveLocal(cards) }
    suspend fun savePresentation(cards: SavedHomeCards) = save(cards)
    fun savePresentationCheckpoint(cards: SavedHomeCards, @Suppress("UNUSED_PARAMETER") initialComplete: Boolean = true) { scope.launch { save(cards) } }
    fun saveAsync(cards: SavedHomeCards) { scope.launch { save(cards) } }
    private suspend fun legacy(key: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
        for (name in listOf("home_cards_v2.json", "home_cards_v1.json")) {
            val target = AtomicFile(File(directory, name))
            val encoded = try {
                if (!target.baseFile.exists() || target.baseFile.length() > MAX_BYTES) continue
                target.openRead().bufferedReader().use { it.readText() }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
            val restored = withContext(AppDispatchers.CpuBounded) {
                val root = try { JSONObject(encoded) } catch (_: Exception) { return@withContext null }
                val oldArtists = root.optJSONArray("artists")
                val artists = JSONArray((0 until minOf(15, oldArtists?.length() ?: 0)).mapNotNull { index ->
                    oldArtists?.optJSONObject(index)?.let { artist ->
                        JSONObject().put("name", artist.optString("name")).put("playCount", artist.optInt("playCount"))
                            .put("tracks", tracks(legacyTracks(artist.optJSONArray("tracks"))))
                    }
                })
                val localJson = JSONObject().put("artists", artists).put("artistDataVersion", 0)
                    .put("recent", tracks(legacyTracks(root.optJSONObject("history")?.optJSONArray("tracks"))))
                val ownRegion = root.optString("profileKey").substringBefore('|')
                val canRestoreServer = name == "home_cards_v2.json" && key == "$ownRegion|anonymous"
                val server = if (canRestoreServer) shelvesFromLegacy(root) else emptyList()
                val home = if (canRestoreServer) JSONObject().put("serverShelves", JSONArray(server.map(::shelf)))
                    .put("refreshedAt", root.optLong("refreshedAt")).put("continuationToken", nullable(root, "continuationToken"))
                    .put("success", server.isNotEmpty()) else null
                local(key, home, localJson)
            }
            if (restored != null) return@withContext restored
        }
        null
    }
    private fun legacyTracks(rows: JSONArray?): List<MusicTrack> = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
        rows?.optJSONObject(i)?.let { row ->
            val id = row.optString("id"); val title = row.optString("title")
            if (id.isBlank() || title.isBlank()) null else MusicTrack(id, title, row.optString("artist"), row.optString("album"),
                row.optLong("durationMs"), row.optString("coverUrl"), row.optString("audioUrl"), source = row.optString("source"))
        }
    }
    private fun shelvesFromLegacy(root: JSONObject): List<ServerHomeShelf> = root.optJSONArray("serverShelves")?.let { a ->
        (0 until minOf(a.length(), 60)).mapNotNull { i -> a.optJSONObject(i)?.let { row ->
            if (row.optString("id").isBlank()) null else ServerHomeShelf(row.getString("id"), row.optString("title"), row.optString("subtitle"),
                legacyTracks(row.optJSONArray("tracks")), nullable(row, "continuationToken"))
        } }
    }.orEmpty()
    fun close() { /* Process-owned writer finishes pending atomic checkpoints. */ }
}
