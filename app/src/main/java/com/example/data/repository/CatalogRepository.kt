package com.example.data.repository

import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.catalogGet
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Typed browsing through the app's existing catalog provider and stream URL decoder. */
class CatalogRepository {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = object : android.util.LruCache<String, Pair<Long, String>>(80) {}
    private val locks = Array(8) { Mutex() }
    private suspend fun request(call: String, vararg params: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("__call", call).addQueryParameter("_format", "json").addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4").addQueryParameter("ctx", "web6dot0")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
        locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val cached = synchronized(cache) { cache.get(url) }
            if (cached != null && System.currentTimeMillis() - cached.first < 300_000) return@withLock JSONObject(cached.second)
            try {
                val body = client.catalogGet(url)
                val json = JSONObject(body)
                if (json.has("error")) throw IOException("Catalog unavailable")
                synchronized(cache) { cache.put(url, System.currentTimeMillis() to body) }
                json
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { cached?.let { JSONObject(it.second) } ?: throw e }
        }
    }
    private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
    private fun text(o: JSONObject, key: String) = o.optString(key).takeUnless { it == "null" }.orEmpty().let(CatalogNames::clean)
    private fun image(o: JSONObject) = text(o, "image").replace("150x150", "500x500").replace("50x50", "500x500").replaceFirst("http://", "https://")
    private fun artist(o: JSONObject): CatalogArtist? {
        val id = text(o, "id"); val name = text(o, "name").ifBlank { text(o, "title") }
        if (id.isBlank() || name.isBlank() || text(o, "type").let { it.isNotBlank() && it != "artist" }) return null
        if (CatalogNames.normalize(name) in setOf("unknown", "unknown artist", "various artists")) return null
        return CatalogArtist(id, name, text(o, "musicbrainz_id"), text(o, "aka").split(';', '|').filter(String::isNotBlank), image(o))
    }
    private fun credits(o: JSONObject, album: Boolean = false): List<CatalogArtist> {
        val map = o.optJSONObject("more_info")?.optJSONObject("artistMap") ?: return emptyList()
        val direct = (map.optJSONArray("primary_artists").objects() + map.optJSONArray("featured_artists").objects())
        val performers = if (album) direct else direct + map.optJSONArray("artists").objects().filter {
            text(it, "role").lowercase() in setOf("singer", "primary_artists", "featured_artists", "vocalist")
        }
        return performers.mapNotNull(::artist).distinctBy { it.id }
    }
    private val unofficial = Regex("(?i)\\b(?:unofficial|bootleg|karaoke|tribute to|fan cover|cover version|slowed|reverb|jukebox|user playlist)\\b")
    private fun album(o: JSONObject): CatalogAlbum? {
        val id = text(o, "id"); val title = text(o, "title"); val artists = credits(o, album = true)
        val year = text(o, "year").ifBlank { o.optJSONObject("more_info")?.let { text(it, "year") }.orEmpty() }
        if (text(o, "type") != "album" || id.isBlank() || title.isBlank() || artists.isEmpty() || image(o).isBlank() || year.toIntOrNull() == null || unofficial.containsMatchIn(title)) return null
        return CatalogAlbum(id, title, artists, image(o), year)
    }
    private fun playlist(o: JSONObject): CatalogPlaylist? {
        if (text(o, "type") != "playlist" || text(o, "id").isBlank() || text(o, "title").isBlank()) return null
        return CatalogPlaylist(text(o, "id"), text(o, "title"), image(o))
    }
    private fun track(o: JSONObject): MusicTrack? {
        if (text(o, "type") != "song" || text(o, "id").isBlank()) return null
        val info = o.optJSONObject("more_info") ?: return null
        val artists = credits(o)
        val title = text(o, "title")
        if (title.isBlank() || artists.isEmpty()) return null
        val stream = text(info, "encrypted_media_url").takeIf(String::isNotBlank)?.let { OnlineMusicApiService.decryptMediaUrl(it) }
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return MusicTrack(id = "online_${text(o, "id")}", title = title, artist = artists.joinToString(", ") { it.name },
            album = text(info, "album"), durationMs = info.optLong("duration") * 1000,
            coverUrl = image(o), audioUrl = stream, singers = artists.joinToString(", ") { it.name },
            language = text(o, "language").ifBlank { "Unknown" }, genre = text(info, "genre").ifBlank { "Unknown" },
            year = text(o, "year"), isrc = text(info, "isrc"))
    }
    fun uniqueTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val ids = mutableSetOf<String>()
        val recordings = mutableSetOf<String>()
        val metadata = mutableSetOf<String>()
        return tracks.filter { track ->
            val isrc = track.isrc.uppercase().replace("-", "").takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }
            // Keep version text and non-Latin vowel marks; neither is disposable punctuation.
            val key = CatalogNames.normalize(track.title) + "|" + track.artist.split(',').map(CatalogNames::normalize).sorted().joinToString(",")
            val fresh = track.id !in ids && key !in metadata && (isrc == null || isrc !in recordings)
            ids.add(track.id); metadata.add(key); isrc?.let(recordings::add)
            fresh
        }
    }
    suspend fun search(query: String, type: String, page: Int = 1): CatalogPage = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext CatalogPage()
        if (type == "All") return@withContext supervisorScope {
            val tasks = listOf("Tracks", "Albums", "Artists").map { kind -> async {
                try { search(query, kind, 1) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            } }
            val results = tasks.awaitAll()
            if (results.all { it == null }) throw IOException("Search unavailable")
            CatalogPage(tracks = results[0]?.tracks.orEmpty(), albums = results[1]?.albums.orEmpty(),
                artists = results[2]?.artists.orEmpty(), warning = if (results.any { it == null }) "Some results couldn't load. Tap to retry." else null)
        }
        val call = when (type) { "Tracks" -> "search.getResults"; "Albums" -> "search.getAlbumResults"; "Artists" -> "search.getArtistResults"; "Playlists" -> "search.getPlaylistResults"; else -> return@withContext CatalogPage() }
        val root = request(call, "q" to query, "p" to page.toString(), "n" to "10")
        val raw = root.optJSONArray("results").objects()
        if (!root.has("results")) throw IOException("Invalid catalog response")
        val next = if (raw.isNotEmpty() && (if (root.has("total")) page * 10 < root.optInt("total") else raw.size >= 10)) page + 1 else null
        when (type) {
            "Tracks" -> CatalogPage(tracks = uniqueTracks(raw.mapNotNull(::track)).filter { CatalogNames.score(query, it.title, listOf(it.artist, it.album)) > 0 }
                .sortedByDescending { CatalogNames.score(query, it.title, listOf(it.artist, it.album)) }, nextPage = next)
            "Albums" -> CatalogPage(albums = raw.mapNotNull(::album).distinctBy { it.id }.filter { CatalogNames.score(query, it.title, it.artists.map { a -> a.name }) > 0 }
                .sortedByDescending { CatalogNames.score(query, it.title, it.artists.map { a -> a.name }) }, nextPage = next)
            "Artists" -> CatalogPage(artists = raw.mapNotNull(::artist).distinctBy { it.id }.filter { CatalogNames.score(query, it.name, it.aliases) > 0 }
                .sortedByDescending { CatalogNames.score(query, it.name, it.aliases) }, nextPage = next)
            else -> CatalogPage(playlists = raw.mapNotNull(::playlist).distinctBy { it.id }.filter { CatalogNames.score(query, it.title) > 0 }
                .sortedByDescending { CatalogNames.score(query, it.title) }, nextPage = next)
        }
    }
    suspend fun resolveArtist(selected: CatalogArtist): CatalogArtist {
        if (selected.id.isNotBlank()) return selected
        val matches = search(selected.name, "Artists").artists.filter { candidate ->
            (listOf(candidate.name) + candidate.aliases).any { CatalogNames.same(selected.name, it) }
        }
        return matches.singleOrNull() ?: throw IOException("No unambiguous catalog artist found")
    }
    suspend fun artistSongs(selected: CatalogArtist, page: Int): CatalogPage = withContext(Dispatchers.Default) {
        val root = request("artist.getArtistMoreSong", "artistId" to selected.id, "page" to page.toString(),
            "category" to "popularity", "sort_order" to "desc", "n" to "10", "song_count" to "10")
        if (text(root, "artistId").ifBlank { text(root, "id") } != selected.id) throw IOException("Artist identity mismatch")
        val songs = root.optJSONObject("topSongs") ?: throw IOException("Missing artist songs")
        val raw = songs.optJSONArray("songs").objects()
        val tracks = raw.filter { item -> credits(item).any { it.id == selected.id } }
            .mapNotNull(::track).filterNot { unofficial.containsMatchIn(it.title) || Regex("(?i)\\bcover\\b").containsMatchIn(it.title) }
        CatalogPage(tracks = uniqueTracks(tracks), nextPage = if (raw.isNotEmpty() && (page + 1) * raw.size < songs.optInt("total", Int.MAX_VALUE)) page + 1 else null)
    }
    suspend fun albumSongs(selected: CatalogAlbum): CatalogPage = withContext(Dispatchers.Default) {
        val root = request("content.getAlbumDetails", "albumid" to selected.id)
        val verified = album(root) ?: throw IOException("Album metadata unavailable")
        if (verified.id != selected.id || !CatalogNames.same(verified.title, selected.title) || verified.artists.none { a -> selected.artists.any { it.id == a.id } }) throw IOException("Album identity mismatch")
        val raw = root.optJSONArray("list").objects()
        // Album details return the release's ordered track list (no discography or title search).
        val members = raw.filter { item -> item.optJSONObject("more_info")?.let { text(it, "album_id") } == selected.id }
        val ordered = if (members.all { (it.optJSONObject("more_info")?.optInt("track_number", 0) ?: 0) > 0 })
            members.sortedWith(compareBy<JSONObject> { it.optJSONObject("more_info")?.optInt("disc_number", 1) ?: 1 }.thenBy { it.optJSONObject("more_info")?.optInt("track_number") ?: 0 }) else members
        CatalogPage(tracks = ordered.mapNotNull(::track).distinctBy { it.id }, warning =
            if (root.optJSONObject("more_info")?.optInt("song_count", raw.size)?.let { it > raw.size } == true) "The catalog returned only part of this release." else null)
    }
    suspend fun playlistSongs(selected: CatalogPlaylist, page: Int): CatalogPage = withContext(Dispatchers.Default) {
        val root = request("playlist.getDetails", "listid" to selected.id, "p" to page.toString(), "n" to "10")
        if (text(root, "id").ifBlank { text(root, "listid") } != selected.id) throw IOException("Playlist identity mismatch")
        val raw = (root.optJSONArray("list") ?: root.optJSONArray("songs")).objects()
        val count = root.optJSONObject("more_info")?.optInt("song_count", -1) ?: root.optInt("list_count", -1)
        CatalogPage(tracks = uniqueTracks(raw.mapNotNull(::track)), nextPage = if (raw.isNotEmpty() && (if (count >= 0) page * 10 < count else raw.size >= 10)) page + 1 else null)
    }
}
