package com.example.data.cache

import android.content.Context
import android.util.AtomicFile
import com.example.data.model.MusicTrack
import com.example.data.model.HomeBrowseSnapshot
import com.example.data.model.ServerHomeShelf
import com.example.data.model.ServerHomeItem
import com.example.data.model.HomeShelfIdentity
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogArtist
import com.example.data.repository.CatalogPlaylist
import com.example.data.repository.CatalogProvider
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.viewmodel.MostListenedArtist
import com.example.ui.viewmodel.SectionState
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Small files allocate small buffers; oversized/corrupt snapshots remain bounded. */
internal fun readBoundedHomeCache(input: java.io.InputStream, limit: Int): String? {
    val output = java.io.ByteArrayOutputStream(minOf(8_192, limit + 1))
    val buffer = ByteArray(minOf(8_192, limit + 1))
    while (output.size() <= limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toString("UTF-8")
        if (count == 0) continue
        output.write(buffer, 0, count)
    }
    return null
}

/** Retry each captured key once; a failed file must not starve unrelated checkpoints. */
internal suspend fun <T : Any> persistHomeCheckpoints(pending: MutableMap<String, T>,
    persist: (T) -> Unit, failed: (String, Exception) -> Unit = { _, _ -> }) {
    val keys = synchronized(pending) { pending.keys.toList() }
    for (key in keys) {
        for (attempt in 0..1) {
            val checkpoint = synchronized(pending) { pending[key] } ?: break
            try {
                persist(checkpoint)
                synchronized(pending) { if (pending[key] === checkpoint) pending.remove(key) }
                break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (attempt == 1) failed(key, error) }
            yield()
        }
    }
}

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
    val artistDataVersion: Int = 2,
    val serverContentVersion: Int = SERVER_HOME_CONTENT_VERSION,
    val sourceBrowseId: String = "FEmusic_home",
    val completedBrowseIds: Set<String> = emptySet(),
    val browseSources: List<HomeBrowseSnapshot> = emptyList()
)

internal const val SERVER_HOME_CONTENT_VERSION = 3

class HomeCardCache(context: Context, private val workerDispatcher: CoroutineDispatcher = AppDispatchers.Io) {
    private val app = context.applicationContext
    private val namespace = "${app.packageName}:${System.identityHashCode(app)}"
    private val directory by lazy { app.filesDir }
    companion object {
        private const val VERSION = 3
        private const val MAX_BYTES = 4 * 1024 * 1024
        private data class Pending(val target: File, val bytes: ByteArray)
        private data class CachedJson(val json: JSONObject, val checkpoint: Any? = null)
        private val memory = android.util.LruCache<String, CachedJson>(12)
        private val pending = linkedMapOf<String, Pending>()
        private val fileMutex = Mutex()
        private val submissions = Array(8) { Mutex() }
        private val signal = Channel<Unit>(Channel.CONFLATED)
        private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
        init { scope.launch {
            for (ignored in signal) {
                // Only a failed batch schedules backoff. No timer or disk work
                // runs while idle, and unrelated/newer checkpoints keep priority.
                for (retry in 0..2) {
                    fileMutex.withLock {
                        persistHomeCheckpoints(pending, persist = { write ->
                            val file = AtomicFile(write.target)
                            var output: java.io.FileOutputStream? = null
                            try {
                                output = file.startWrite(); output.write(write.bytes); file.finishWrite(output)
                                // AtomicFile logs some rename failures instead of throwing.
                                // Never acknowledge a checkpoint that did not replace its target.
                                if (!write.target.isFile || write.target.length() != write.bytes.size.toLong() ||
                                    File(write.target.path + ".new").exists()) throw java.io.IOException("Home cache commit did not complete")
                            } catch (cancelled: CancellationException) { file.failWrite(output); throw cancelled }
                            catch (error: Exception) {
                                file.failWrite(output)
                                throw error
                            }
                        }, failed = { _, error -> android.util.Log.w("HomeCardCache", "Home cache write remains pending", error) })
                    }
                    if (synchronized(pending) { pending.isEmpty() } || retry == 2) break
                    delay(250L shl retry)
                }
            }
        } }
        internal fun retryPendingWrites() {
            if (synchronized(pending) { pending.isNotEmpty() }) signal.trySend(Unit)
        }
    }
    private fun file(name: String) = File(directory, "home_${name}_v3.json")
    private fun memoryKey(name: String) = "$namespace:$name"
    private fun peekJson(name: String) = synchronized(memory) { memory.get(memoryKey(name))?.json }
    private fun submission(name: String) = submissions[(memoryKey(name).hashCode() and Int.MAX_VALUE) % submissions.size]
    private suspend fun readJson(name: String): JSONObject? = withContext(workerDispatcher) {
        retryPendingWrites()
        submission(name).withLock { peekJson(name) ?: try {
            val text = fileMutex.withLock {
                val file = AtomicFile(file(name))
                synchronized(pending) { pending[file.baseFile.path]?.bytes }?.let { return@withLock it.toString(Charsets.UTF_8) }
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
                file.openRead().use { readBoundedHomeCache(it, MAX_BYTES) }
            } ?: return@withContext null
            BackgroundWorkCoordinator.finishBoundedBatch { withContext(AppDispatchers.CpuBounded) {
                JSONObject(text).takeIf { it.optInt("version") == VERSION }
            } }?.also { synchronized(memory) { memory.put(memoryKey(name), CachedJson(it)) } }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        }
    }
    private suspend fun write(name: String, checkpoint: Any, encode: () -> JSONObject) = submission(name).withLock {
        val value = BackgroundWorkCoordinator.finishBoundedBatch { withContext(AppDispatchers.CpuBounded) {
            if (synchronized(memory) { memory.get(memoryKey(name))?.checkpoint } == checkpoint) null
            else encode().put("version", VERSION).let { it to it.toString().toByteArray() }
        } }
        withContext(workerDispatcher) {
            if (value != null) {
                val (json, encoded) = value
                if (encoded.size > MAX_BYTES) return@withContext
                synchronized(memory) { memory.put(memoryKey(name), CachedJson(json, checkpoint)) }
                val target = file(name)
                synchronized(pending) {
                    pending.remove(target.path)
                    pending[target.path] = Pending(target, encoded)
                    while (pending.size > 12) pending.remove(pending.keys.first())
                }
            }
            signal.trySend(Unit)
        }
    }
    private fun tracks(rows: List<MusicTrack>) = JSONArray(RecommendationTrackCodec.encode(rows))
    private fun tracks(rows: JSONArray?) = rows?.let { RecommendationTrackCodec.decode(it.toString(), 250) }.orEmpty()
    private fun nullable(row: JSONObject, key: String) = row.optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun validHome(row: JSONObject, key: String) = row.optString("key") == key &&
        (key.substringAfter('|') == "anonymous" || row.optString("accountIdentity") == key.substringAfter('|'))
    private fun shelf(row: ServerHomeShelf) = JSONObject().put("id", row.id).put("title", row.title).put("subtitle", row.subtitle)
        .put("tracks", tracks(row.tracks)).put("continuationToken", row.continuationToken)
        .put("items", JSONArray(row.items.map(::item)))
        .put("providerId", row.providerId).put("browseId", row.browseId).put("browseParams", row.browseParams)
    private fun item(entry: ServerHomeItem): JSONObject {
        if (entry is ServerHomeItem.Song) return JSONObject().put("type", "song").put("id", entry.track.id)
        val album = (entry as? ServerHomeItem.Album)?.album
        val playlist = (entry as? ServerHomeItem.Playlist)?.playlist
        return JSONObject().put("type", if (album != null) "album" else "playlist")
            .put("id", album?.id ?: playlist!!.id).put("title", album?.title ?: playlist!!.title)
            .put("image", album?.image ?: playlist!!.image).put("params", album?.browseParams ?: playlist?.browseParams)
            .put("country", album?.countryCode ?: playlist!!.countryCode).put("language", album?.languageCode ?: playlist!!.languageCode)
            .put("subtitle", when (entry) { is ServerHomeItem.Album -> entry.subtitle; is ServerHomeItem.Playlist -> entry.subtitle; else -> "" })
            .apply { if (album != null) {
                put("year", album.year)
                put("artists", JSONArray(album.artists.map { JSONObject().put("id", it.id).put("name", it.name) }))
            } }
    }
    private fun shelf(row: JSONObject): ServerHomeShelf {
        val tracks = tracks(row.optJSONArray("tracks")).map { it.copy(recommendationOnly = true) }
        val byId = tracks.associateBy { it.id }
        val rows = row.optJSONArray("items")
        val items = (0 until minOf(250, rows?.length() ?: 0)).mapNotNull { index -> runCatching {
            val value = rows!!.getJSONObject(index)
            val id = value.getString("id").takeIf(String::isNotBlank) ?: return@runCatching null
            val title = value.optString("title")
            val country = value.optString("country", "US"); val language = value.optString("language", "en")
            when (value.optString("type")) {
                "song" -> byId[id]?.let { ServerHomeItem.Song(it) }
                "album" -> if (title.isBlank()) null else {
                    val artists = value.optJSONArray("artists")
                    ServerHomeItem.Album(CatalogAlbum(id, title,
                        (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.let { a -> CatalogArtist(a.optString("id"), a.getString("name")) } },
                        value.optString("image"), value.optString("year"), CatalogProvider.YOUTUBE_MUSIC,
                        nullable(value, "params"), country, language), value.optString("subtitle"))
                }
                "playlist" -> if (title.isBlank()) null else ServerHomeItem.Playlist(CatalogPlaylist(id, title,
                    value.optString("image"), CatalogProvider.YOUTUBE_MUSIC, nullable(value, "params"), country, language), value.optString("subtitle"))
                else -> null
            }
        }.getOrNull() }
        return ServerHomeShelf(row.getString("id"), row.getString("title"), row.optString("subtitle"), tracks,
            nullable(row, "continuationToken"), items, row.optString("providerId"), row.optString("browseId"), row.optString("browseParams"))
    }
    private fun shelves(row: JSONObject?): List<ServerHomeShelf> = row?.optJSONArray("serverShelves")?.let { array ->
        (0 until minOf(60, array.length())).mapNotNull { runCatching { shelf(array.getJSONObject(it)) }.getOrNull() }
    }.orEmpty().let(HomeShelfIdentity::deduplicate)
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
            artistDataVersion = local?.optInt("artistDataVersion", 0) ?: 0,
            serverContentVersion = home?.optInt("contentVersion", 0) ?: 0,
            sourceBrowseId = home?.optString("sourceBrowseId", "FEmusic_home") ?: "FEmusic_home",
            completedBrowseIds = home?.optJSONArray("completedBrowseIds")?.let { a ->
                (0 until a.length()).map(a::getString).toSet()
            }.orEmpty(), browseSources = home?.optJSONArray("browseSources")?.let { a ->
                (0 until minOf(3, a.length())).mapNotNull { index -> runCatching {
                    val r = a.getJSONObject(index)
                    HomeBrowseSnapshot(r.getString("browseId"), r.getString("account"),
                        shelves(JSONObject().put("serverShelves", r.optJSONArray("shelves"))), nullable(r, "continuation"),
                        r.optJSONArray("consumed")?.let { tokens -> (0 until minOf(256, tokens.length())).map(tokens::getString).toSet() }.orEmpty(),
                        r.optLong("fetchedAt"), r.optBoolean("success"), r.optInt("emptyPages"))
                }.getOrNull() }
            }.orEmpty())
    }
    fun peek(profile: String): SavedHomeCards? {
        val home = peekJson("server_${HomeCacheIdentity.fileKey(profile)}")?.takeIf { validHome(it, profile) }
        val history = peekJson("local")
        return if (home == null && history == null) null else local(profile, home, history)
    }
    fun peekFull(profile: String) = peek(profile)
    suspend fun readInitial(profile: String) = read(profile)
    suspend fun readDisk(profile: String) = read(profile)
    suspend fun read(profile: String): SavedHomeCards? = withContext(AppDispatchers.Io) {
        val home = readJson("server_${HomeCacheIdentity.fileKey(profile)}")?.takeIf { validHome(it, profile) }
        val history = readJson("local")
        if (home == null && history == null) return@withContext legacy(profile)
        withContext(AppDispatchers.CpuBounded) { local(profile, home, history) }
    }
    suspend fun readServer(profile: String): SavedHomeCards? {
        val home = readJson("server_${HomeCacheIdentity.fileKey(profile)}")?.takeIf { validHome(it, profile) } ?: return null
        return withContext(AppDispatchers.CpuBounded) { local(profile, home, null) }
    }
    suspend fun readLocal(profile: String): SavedHomeCards? {
        val history = readJson("local")
        return withContext(AppDispatchers.CpuBounded) {
            if (history == null) legacy(profile) else local(profile, null, history)
        }
    }
    suspend fun readProfile(key: String): SavedHomeCards? {
        val row = readJson("profile_${HomeCacheIdentity.fileKey(key)}")?.takeIf { it.optString("key") == key } ?: return null
        return withContext(AppDispatchers.CpuBounded) { SavedHomeCards("", profileShelfKey = key,
            profileShelf = row.optJSONObject("shelf")?.let(::shelf), profileFetchedAt = row.optLong("fetchedAt"), profileFetchSucceeded = row.optBoolean("success")) }
    }
    private data class HomeCheckpoint(val key: String, val fetchedAt: Long, val success: Boolean,
        val shelves: List<ServerHomeShelf>, val continuation: String?, val consumed: Set<String>,
        val sourceBrowseId: String, val completedBrowseIds: Set<String>, val sources: List<HomeBrowseSnapshot>)
    private data class ProfileCheckpoint(val key: String, val fetchedAt: Long, val success: Boolean, val shelf: ServerHomeShelf?)
    private data class LocalCheckpoint(val revision: String?, val artistVersion: Int, val recent: List<MusicTrack>, val artists: List<MostListenedArtist>)
    suspend fun saveHome(cards: SavedHomeCards) {
        write("server_${HomeCacheIdentity.fileKey(cards.profileKey)}",
            HomeCheckpoint(cards.profileKey, cards.refreshedAt, cards.serverFetchSucceeded, cards.serverShelves, cards.continuationToken, cards.consumedContinuations, cards.sourceBrowseId, cards.completedBrowseIds, cards.browseSources)) {
            JSONObject().put("key", cards.profileKey).put("accountIdentity", cards.profileKey.substringAfter('|')).put("refreshedAt", cards.refreshedAt)
            .put("success", cards.serverFetchSucceeded).put("contentVersion", SERVER_HOME_CONTENT_VERSION)
            .put("serverShelves", JSONArray(HomeShelfIdentity.deduplicate(cards.serverShelves).map(::shelf)))
            .put("continuationToken", cards.continuationToken).put("consumedContinuations", JSONArray(cards.consumedContinuations.toList()))
            .put("sourceBrowseId", cards.sourceBrowseId).put("completedBrowseIds", JSONArray(cards.completedBrowseIds.toList()))
            .put("browseSources", JSONArray(cards.browseSources.map { source ->
                JSONObject().put("browseId", source.browseId).put("account", source.accountIdentity)
                    .put("shelves", JSONArray(HomeShelfIdentity.deduplicate(source.shelves).map(::shelf)))
                    .put("continuation", source.continuation).put("consumed", JSONArray(source.consumed.toList()))
                    .put("fetchedAt", source.fetchedAt).put("success", source.succeeded).put("emptyPages", source.emptyPages)
            }))
        }
    }
    suspend fun saveProfile(cards: SavedHomeCards) {
        write("profile_${HomeCacheIdentity.fileKey(cards.profileShelfKey)}",
            ProfileCheckpoint(cards.profileShelfKey, cards.profileFetchedAt, cards.profileFetchSucceeded, cards.profileShelf)) {
            JSONObject().put("key", cards.profileShelfKey).put("fetchedAt", cards.profileFetchedAt)
                .put("success", cards.profileFetchSucceeded).put("shelf", cards.profileShelf?.let(::shelf))
        }
    }
    suspend fun saveLocal(cards: SavedHomeCards) {
        write("local", LocalCheckpoint(cards.inputRevision, cards.artistDataVersion, cards.history.tracks.take(30), cards.artists.take(15))) {
            JSONObject().put("inputRevision", cards.inputRevision).put("artistDataVersion", cards.artistDataVersion)
            .put("recent", tracks(cards.history.tracks.take(30))).put("artists", JSONArray(cards.artists.take(15).map {
                JSONObject().put("name", it.name).put("playCount", it.playCount).put("tracks", tracks(it.tracks.take(3)))
            }))
        }
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
