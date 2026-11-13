package com.example.connections

import android.content.Context
import androidx.room.withTransaction
import com.example.XtremeMusicApp
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeExploreParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.text.Normalizer
import java.util.Locale

internal data class ConnectedCollection(val provider: String, val id: String, val title: String, val image: String = "") {
    val isLikedSongs: Boolean get() = (provider == "youtube" && id.removePrefix("VL") == "LM") ||
        (provider == "spotify" && id == "liked")
}
internal data class ImportResult(val imported: Int, val unmatched: Int, val skipped: Int = 0)
internal data class MetadataTrack(val id: String, val title: String, val artist: String, val album: String, val duration: Long, val image: String)

/** Connections supply metadata; every imported song is resolved through the app's ordinary music catalog. */
internal class ConnectedLibraries(
    private val context: Context,
    private val database: MusicDatabase? = null,
    private val httpClient: OkHttpClient = http,
    private val refreshSession: suspend (SpotifyConnection) -> SpotifyConnection = { refreshSpotifySession(context.applicationContext, it) }
) {
    private val connections = ConnectionStore.get(context)
    suspend fun collections(provider: String): List<ConnectedCollection> = withContext(Dispatchers.IO) {
        connections.load()
        if (provider == "youtube") {
            check(connections.state.value.activeYouTube != null) { "Connect YouTube Music first" }
            val items = mutableListOf(ConnectedCollection(provider, "VLLM", "Liked Songs"))
            for (browse in listOf("FEmusic_liked_playlists", "FEmusic_liked_albums")) {
                youtubePages(browse) { root ->
                    walk(root) { node ->
                        listOf("musicTwoRowItemRenderer", "musicResponsiveListItemRenderer").forEach { key ->
                            val value = node.optJSONObject(key) ?: return@forEach
                            when (val destination = YouTubeExploreParser.collection(value, "US", "en")) {
                                is CatalogDestination.Playlist -> items += ConnectedCollection(provider,
                                    destination.playlist.id.let { if (it.startsWith("VL")) it else "VL$it" }, destination.playlist.title, destination.playlist.image)
                                is CatalogDestination.Album -> items += ConnectedCollection(provider, destination.album.id, destination.album.title, destination.album.image)
                                else -> Unit
                            }
                        }
                    }
                    youtubeContinuation(root)
                }
            }
            items.distinctBy { it.id }
        } else {
            val items = mutableListOf(ConnectedCollection("spotify", "liked", "Liked Songs"))
            var offset = 0
            do {
                val variables = JSONObject().put("filters", JSONArray(listOf("Playlists"))).put("order", JSONObject.NULL)
                    .put("textFilter", "").put("features", JSONArray(listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS")))
                    .put("limit", 50).put("offset", offset).put("flatten", true).put("expandedFolders", JSONArray())
                    .put("folderUri", JSONObject.NULL).put("includeFoldersWhenFlattening", false)
                val root = spotify("libraryV3", LIBRARY_HASH, variables)
                val page = root.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("libraryV3")
                    ?: throw IOException("Spotify library is unavailable. Reconnect and try again.")
                val raw = page.optJSONArray("items") ?: JSONArray()
                items += parseSpotifyCollections(raw)
                offset += raw.length()
                if (raw.length() == 0 || offset >= page.optInt("totalCount", offset)) break
                check(offset < 100_000) { "Spotify library is too large to load" }
            } while (true)
            items.distinctBy { it.id }
        }
    }
    suspend fun import(collection: ConnectedCollection, onProgress: (Int, Int) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        connections.load()
        val account = if (collection.provider == "youtube") connections.state.value.activeYouTube?.id
            ?: throw IOException("YouTube Music is disconnected")
            else connections.state.value.spotify?.cookie?.let(ConnectionStore::digest) ?: throw IOException("Spotify is disconnected")
        val songs = mutableListOf<MusicTrack>()
        var missed = 0
        if (collection.provider == "youtube") {
            youtubePages(collection.id) { root ->
                val page = connectedYouTubePage(root)
                songs += page.tracks
                onProgress(songs.size, songs.size)
                page.continuation
            }
        } else {
            val metadata = spotifyTracks(collection.id)
            metadata.forEachIndexed { index, track ->
                currentCoroutineContext().ensureActive()
                val identity = MusicTrack("metadata:${track.id}", track.title, track.artist, track.album,
                    track.duration, track.image, "", primaryArtists = listOf(track.artist.substringBefore(",")))
                val matches = YouTubeMusicApiService.searchSongs("${track.title} ${track.artist}", limit = 15, songsOnly = true)
                val match = matches.firstOrNull { StreamResolver.matches(identity, it) }
                if (match != null) songs += match else missed++
                onProgress(index + 1, metadata.size)
            }
        }
        currentCoroutineContext().ensureActive()
        val currentAccount = if (collection.provider == "youtube") connections.state.value.activeYouTube?.id
            else connections.state.value.spotify?.cookie?.let(ConnectionStore::digest)
        check(account == currentAccount) { "The connected account changed. Try again." }
        if (songs.isEmpty()) throw IOException("No matching music tracks were found. Your library has not changed.")
        val added = saveImport(collection, account, songs)
        ImportResult(added, missed, songs.size - added)
    }
    internal suspend fun saveImport(collection: ConnectedCollection, account: String, tracks: List<MusicTrack>): Int {
        val db = database ?: MusicDatabase.getDatabase(context)
        return db.withTransaction {
            val dao = db.musicDao()
            if (collection.isLikedSongs) {
                val favorites = dao.getAllFavoriteTracksSync().map { it.toMusicTrack() }
                val rows = newConnectedTracks(favorites, tracks).map(TrackEntity::fromMusicTrack)
                dao.mergeFavorites(rows)
                return@withTransaction rows.size
            }
            val name = connectedPlaylistName(collection.title)
            val previous = dao.getAllPlaylistsSync().filter { connectedPlaylistName(it.title) == name }
                .minWithOrNull(compareBy<PlaylistEntity> { it.createdAt }.thenBy { it.playlistId })
            val existing = previous?.let { dao.playlistTracks(it.playlistId).map(TrackEntity::toMusicTrack) }.orEmpty()
            val rows = newConnectedTracks(existing, tracks).map(TrackEntity::fromMusicTrack)
            val localId = if (previous == null) dao.createPlaylistWithEntries(PlaylistEntity(title = collection.title,
                description = if (collection.provider == "youtube") "Imported from YouTube Music" else "Imported from Spotify",
                coverUrl = collection.image), rows)
            else {
                dao.mergePlaylistEntries(previous.playlistId, rows)
                previous.playlistId
            }
            dao.saveConnectedCollection(ConnectedCollectionEntity(collection.provider, account, collection.id, localId))
            rows.size
        }
    }
    private suspend fun youtubePages(browse: String, onPage: (JSONObject) -> String?) {
        var continuation: String? = null
        val seen = hashSetOf<String>()
        do {
            currentCoroutineContext().ensureActive()
            val root = JSONObject(YouTubeAudioStreamService.discoveryResponse("browse", browse, "US", continuation = continuation, includeAccount = true))
            if (root.has("error")) throw IOException("YouTube Music session expired. Reconnect and try again.")
            continuation = onPage(root)
            if (continuation != null && !seen.add(continuation)) throw IOException("Music library returned a repeated page. Please retry.")
            check(seen.size < 2000) { "Music library is too large to load" }
        } while (continuation != null)
    }
    private suspend fun spotifyTracks(id: String): List<MetadataTrack> {
        val out = mutableListOf<MetadataTrack>()
        var offset = 0
        do {
            val liked = id == "liked"
            val variables = JSONObject().put("offset", offset).put("limit", 100)
            if (!liked) variables.put("uri", "spotify:playlist:$id").put("enableWatchFeedEntrypoint", false)
            val root = spotify(if (liked) "fetchLibraryTracks" else "fetchPlaylist", if (liked) LIKED_HASH else PLAYLIST_HASH, variables)
            val data = root.optJSONObject("data")
            val page = if (liked) data?.optJSONObject("me")?.optJSONObject("library")?.optJSONObject("tracks")
                else data?.optJSONObject("playlistV2")?.optJSONObject("content")
            page ?: throw IOException("Spotify playlist is unavailable")
            val raw = page.optJSONArray("items") ?: JSONArray()
            out += parseSpotifyTracks(raw)
            offset += raw.length()
            if (raw.length() == 0 || offset >= page.optInt("totalCount", offset)) break
            check(offset < 100_000) { "Spotify playlist is too large to load" }
        } while (true)
        return out
    }
    private suspend fun spotify(operation: String, hash: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("operationName", operation).put("variables", variables)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash)))
            .toString().toRequestBody("application/json".toMediaType())
        var rejectedBearer: String? = null
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val session = spotifyTokens(rejectedBearer)
            val request = Request.Builder().url("https://api-partner.spotify.com/pathfinder/v2/query")
                .header("Authorization", "Bearer ${session.bearer}").header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/").header("app-platform", "WebPlayer")
                .apply { if (session.clientToken.isNotBlank()) header("Client-Token", session.clientToken) }
                .post(body).build()
            val result = httpClient.newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) {
                    rejectedBearer = session.bearer
                    return@use null
                }
                if (!response.isSuccessful) throw IOException("Spotify connection needs attention. Reconnect and try again.")
                val root = JSONObject(response.body?.string() ?: throw IOException("Empty Spotify response"))
                if (root.optJSONArray("errors")?.length()?.let { it > 0 } == true)
                    throw IOException("Spotify library is unavailable. Reconnect and try again.")
                root
            }
            if (result != null) return result
        }
        throw IOException("Spotify connection needs attention. Reconnect and try again.")
    }
    private suspend fun spotifyTokens(rejectedBearer: String? = null): SpotifyConnection = tokenLock.withLock {
        val saved = connections.state.value.spotify ?: throw IOException("Connect Spotify first")
        if ((rejectedBearer == null || saved.bearer != rejectedBearer) && saved.bearer.isNotBlank() &&
            saved.expiresAt > System.currentTimeMillis() + 60_000L) return@withLock saved
        val refreshed = refreshSession(saved)
        currentCoroutineContext().ensureActive()
        connections.change { if (it.spotify?.cookie == saved.cookie) it.copy(spotify = refreshed) else it }
        connections.state.value.spotify?.takeIf { it.cookie == refreshed.cookie } ?: throw IOException("Spotify account changed")
    }
    companion object {
        private val tokenLock = Mutex()
        private val http by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }
        private const val LIBRARY_HASH = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
        private const val PLAYLIST_HASH = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
        private const val LIKED_HASH = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    }
}

private fun connectedPlaylistName(raw: String): String = Normalizer.normalize(CatalogNames.clean(raw), Normalizer.Form.NFKC)
    .replace(Regex("[\\s\\u00a0]+"), " ").trim().lowercase(Locale.ROOT)

/** Different provider IDs can still identify the same recording. Version and artist checks stay strict. */
private fun newConnectedTracks(existing: List<MusicTrack>, incoming: List<MusicTrack>): List<MusicTrack> {
    val ids = existing.mapTo(mutableSetOf()) { it.id }
    val titles = mutableMapOf<String, MutableList<RecordingIdentity.Signature>>()
    val codes = mutableMapOf<String, MutableList<RecordingIdentity.Signature>>()
    fun remember(signature: RecordingIdentity.Signature) {
        titles.getOrPut(signature.title) { mutableListOf() }.add(signature)
        signature.isrc?.let { codes.getOrPut(it) { mutableListOf() }.add(signature) }
    }
    existing.forEach { remember(RecordingIdentity.signature(it)) }
    return incoming.filter { track ->
        if (!ids.add(track.id)) return@filter false
        val signature = RecordingIdentity.signature(track)
        val candidates = titles[signature.title].orEmpty() + signature.isrc?.let { codes[it] }.orEmpty()
        val duplicate = candidates.any { known ->
            RecordingIdentity.same(signature, known) &&
                ((signature.isrc != null && signature.isrc == known.isrc) || (signature.durationMs > 0 && known.durationMs > 0))
        }
        if (!duplicate) remember(signature)
        !duplicate
    }
}

internal fun parseSpotifyCollections(items: JSONArray): List<ConnectedCollection> = (0 until items.length()).mapNotNull { i ->
    val item = items.optJSONObject(i)?.optJSONObject("item") ?: return@mapNotNull null
    val data = item.optJSONObject("data") ?: return@mapNotNull null
    if (data.optString("__typename") != "Playlist") return@mapNotNull null
    val uri = item.optString("_uri").takeIf { it.startsWith("spotify:playlist:") } ?: return@mapNotNull null
    val id = uri.substringAfterLast(':')
    if (!id.matches(Regex("[A-Za-z0-9]{22}")) || data.optString("name").isBlank()) return@mapNotNull null
    ConnectedCollection("spotify", id, data.getString("name"), largestImage(data.optJSONObject("images")?.optJSONArray("items")?.optJSONObject(0)?.optJSONArray("sources")))
}
internal fun parseSpotifyTracks(items: JSONArray): List<MetadataTrack> = (0 until items.length()).mapNotNull { i ->
    val wrapper = items.optJSONObject(i)?.optJSONObject("itemV2") ?: items.optJSONObject(i)?.optJSONObject("track") ?: return@mapNotNull null
    val data = wrapper.optJSONObject("data") ?: return@mapNotNull null
    val uri = data.optString("uri", wrapper.optString("_uri")).takeIf { it.startsWith("spotify:track:") } ?: return@mapNotNull null
    if (data.has("__typename") && data.optString("__typename") != "Track") return@mapNotNull null
    val id = uri.substringAfterLast(':')
    if (data.optString("name").isBlank() || !id.matches(Regex("[A-Za-z0-9]{22}"))) return@mapNotNull null
    val artists = data.optJSONObject("artists")?.optJSONArray("items") ?: JSONArray()
    val artist = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optJSONObject("profile")?.optString("name") }
        .filter(String::isNotBlank).joinToString(", ")
    if (artist.isBlank()) return@mapNotNull null
    val album = data.optJSONObject("albumOfTrack")
    MetadataTrack(id, data.getString("name"), artist, album?.optString("name").orEmpty(),
        (data.optJSONObject("duration") ?: data.optJSONObject("trackDuration"))?.optLong("totalMilliseconds") ?: 0L,
        largestImage(album?.optJSONObject("coverArt")?.optJSONArray("sources")))
}
private fun largestImage(sources: JSONArray?): String = sources?.let { a ->
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.maxByOrNull { it.optInt("width") }?.optString("url") }.orEmpty()
internal fun youtubeContinuation(root: JSONObject): String? {
    var found: String? = null
    walk(root) { node ->
        if (found == null) found = node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf(String::isNotBlank)
            ?: node.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank)
    }
    return found
}
internal fun youtubeProfileName(root: JSONObject): String? {
    var name: String? = null
    walk(root) { node ->
        val text = node.optJSONObject("activeAccountHeaderRenderer")?.optJSONObject("accountName")
        if (text != null && name == null) name = text.optString("simpleText").ifBlank {
            val runs = text.optJSONArray("runs") ?: JSONArray()
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }.trim().takeIf(String::isNotBlank)
    }
    return name
}
private fun walk(value: Any?, depth: Int = 0, visit: (JSONObject) -> Unit) {
    if (depth > 64) return
    when (value) {
        is JSONObject -> { visit(value); value.keys().forEach { walk(value.opt(it), depth + 1, visit) } }
        is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it), depth + 1, visit) }
    }
}
