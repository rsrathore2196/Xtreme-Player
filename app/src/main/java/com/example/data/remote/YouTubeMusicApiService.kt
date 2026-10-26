package com.example.data.remote

import com.example.performance.AppDispatchers

import android.util.Log
import com.example.data.model.MusicTrack
import com.metrolist.innertubex.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object YouTubeMusicApiService {
    private const val TAG = "YouTubeMusicApiService"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    data class SongSearchPage(val tracks: List<MusicTrack>, val continuation: String?,
        val artistCredits: Map<String, List<String>> = emptyMap())
    private val artistSearchClient by lazy { httpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val artistSearchCache = android.util.LruCache<String, Pair<Long, SongSearchPage>>(48)
    private val artistSearchLocks = Array(8) { Mutex() }

    /** Incremental songs-only fallback for artist pages; never loads an entire discography. */
    suspend fun searchSongsPage(query: String, continuation: String? = null,
        useInnerTubeX: Boolean = false, forceRefresh: Boolean = false, countryCode: String? = null): SongSearchPage = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SongSearchPage(emptyList(), null)
        val key = "${query.trim().lowercase(java.util.Locale.ROOT)}|${continuation.orEmpty()}|$useInnerTubeX|${countryCode.orEmpty()}"
        artistSearchLocks[(key.hashCode() and Int.MAX_VALUE) % artistSearchLocks.size].withLock {
            if (forceRefresh) artistSearchCache.remove(key)
            artistSearchCache.get(key)?.takeIf { System.currentTimeMillis() - it.first < 300_000L }
                ?.let { return@withLock it.second }
            if (useInnerTubeX) {
                val page = try {
                    val body = YouTubeAudioStreamService.searchSongsResponse(query.trim(), continuation)
                    withContext(AppDispatchers.CpuBounded) { parseSongSearchPage(body) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (page != null) {
                    val result = page.copy(continuation = page.continuation?.takeUnless { it == continuation })
                    artistSearchCache.put(key, System.currentTimeMillis() to result)
                    return@withLock result
                }
            }
            val profile = YouTubeClient.WEB_REMIX
            val payload = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", profile.clientName).put("clientVersion", profile.clientVersion)
                .put("hl", "en").put("gl", countryCode?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.uppercase(java.util.Locale.ROOT) ?: "US")))
            if (continuation == null) payload.put("query", query.trim()).put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
            // Search continuations are URL parameters, as in InnerTubeX's search API.
            val url = "https://music.youtube.com/youtubei/v1/search".toHttpUrl().newBuilder().apply {
                if (continuation != null) {
                    addQueryParameter("continuation", continuation)
                    addQueryParameter("ctoken", continuation)
                }
            }.build()
            val request = Request.Builder().url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", profile.userAgent).header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .header("X-YouTube-Client-Name", profile.clientId)
                .header("X-YouTube-Client-Version", profile.clientVersion).build()
            val body = artistSearchClient.catalogRequest(request)
            val page = withContext(AppDispatchers.CpuBounded) { parseSongSearchPage(body) }
            val result = page.copy(continuation = page.continuation?.takeUnless { it == continuation })
            artistSearchCache.put(key, System.currentTimeMillis() to result)
            result
        }
    }

    private fun parseSongSearchPage(body: String): SongSearchPage {
        val root = JSONObject(body)
        if (root.has("error") || listOf("contents", "continuationContents", "onResponseReceivedActions",
                "onResponseReceivedCommands", "onResponseReceivedEndpoints").none(root::has))
            throw IOException("YouTube Music song catalogue unavailable")
        val tracks = mutableListOf<MusicTrack>()
        val credits = mutableMapOf<String, List<String>>()
        var continuation: String? = null
        fun shelf(value: JSONObject) {
            val contents = value.optJSONArray("contents") ?: value.optJSONArray("continuationItems")
            for (i in 0 until (contents?.length() ?: 0)) {
                val item = contents?.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                val track = parseListItemRenderer(item) ?: continue
                tracks.add(track)
                val columns = item.optJSONArray("flexColumns")
                val names = mutableListOf<String>()
                for (column in 1 until (columns?.length() ?: 0)) {
                    val runs = columns?.optJSONObject(column)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")?.optJSONArray("runs")
                    for (run in 0 until (runs?.length() ?: 0)) {
                        val value = runs?.optJSONObject(run) ?: continue
                        val endpoint = value.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: continue
                        val type = endpoint.optJSONObject("browseEndpointContextSupportedConfigs")
                            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
                        if (type == "MUSIC_PAGE_TYPE_ARTIST" ||
                            type.isNullOrBlank() && endpoint.optString("browseId").startsWith("UC"))
                            names.add(value.optString("text").trim())
                    }
                }
                credits[track.id] = names.distinct()
            }
            extractContinuationToken(value)?.let { continuation = it }
        }
        fun visit(node: Any?, depth: Int = 0) {
            if (depth > 24) return
            when (node) {
                is JSONObject -> {
                    val page = node.optJSONObject("musicShelfRenderer") ?: node.optJSONObject("musicShelfContinuation")
                        ?: node.optJSONObject("appendContinuationItemsAction")
                    if (page != null) shelf(page)
                    else {
                        // Corrected searches and Top Results can wrap songs outside musicShelfRenderer.
                        node.optJSONObject("musicResponsiveListItemRenderer")?.let { item ->
                            parseListItemRenderer(item)?.let(tracks::add)
                        }
                        node.keys().forEach { visit(node.opt(it), depth + 1) }
                        if (continuation == null && node.has("continuations"))
                            continuation = extractContinuationToken(node)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), depth + 1)
            }
        }
        visit(root)
        return SongSearchPage(tracks.distinctBy { it.id }, continuation, credits)
    }

    /** Resolve the selected video itself, retaining its identity and metadata. */
    suspend fun resolveAudio(track: MusicTrack, quality: com.example.playback.AudioQuality,
        forceRefresh: Boolean = false, requireOpus: Boolean = false, maxBitrateBps: Int? = null): MusicTrack? {
        val videoId = YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl) ?: return null
        val audio = YouTubeAudioStreamService.resolve(videoId, quality, forceRefresh,
            maxBitrateBps = maxBitrateBps, requireOpus = requireOpus)
        if (audio.videoId != videoId) throw IOException("YouTube returned a different recording")
        // Persist the resolved source identity, not an expiring signed CDN URL.
        return track.copy(audioUrl = YouTubeAudioStreamService.playbackUri("yt_$videoId", audio.url, requireOpus), bitrateKbps = audio.bitrateKbps,
            qualityBadge = audio.qualityBadge,
            isLossless = false)
    }

    private data class ProfileLookup(val expires: Long, val profile: com.example.data.repository.CatalogArtistProfile?,
        val temporaryFailure: Boolean = false)
    private val profileCache = android.util.LruCache<String, ProfileLookup>(64)
    private val profileLocks = Array(8) { Mutex() }

    /** Shared artist-filtered search and channel browse, independent of song artwork. */
    suspend fun artistProfile(selected: com.example.data.repository.CatalogArtist): com.example.data.repository.CatalogArtistProfile? =
        withContext(AppDispatchers.Io) {
            val name = com.example.data.repository.CatalogNames.normalize(selected.name)
            if (name.isBlank()) return@withContext null
            profileLocks[(name.hashCode() and Int.MAX_VALUE) % profileLocks.size].withLock {
                val key = selected.id.takeIf { it.startsWith("UC") } ?: name
                val cached = profileCache.get(key) ?: profileCache.get(name)?.takeIf {
                    !selected.id.startsWith("UC") || it.profile?.artist?.id == selected.id }
                if (cached != null && cached.expires > System.currentTimeMillis()) {
                    if (cached.temporaryFailure) throw IOException("Artist profile temporarily unavailable")
                    return@withLock cached.profile
                }
                try {
                    val artist = if (selected.id.startsWith("UC")) selected else {
                        val body = com.example.recommendation.DiscoveryRequestCoordinator.network(essential = true) {
                            YouTubeAudioStreamService.discoveryResponse("search", selected.name, "US", "EgWKAQIgAWoKEAkQChAFEAMQBA==")
                        }
                        withContext(AppDispatchers.CpuBounded) {
                            YouTubeExploreParser.artistMatches(JSONObject(body), selected.name).singleOrNull()
                        } ?: return@withLock null.also { profileCache.put(key, ProfileLookup(System.currentTimeMillis() + 60_000L, null)) }
                    }
                    val body = com.example.recommendation.DiscoveryRequestCoordinator.network(essential = true) {
                        YouTubeAudioStreamService.discoveryResponse("browse", artist.id, "US")
                    }
                    val profile = withContext(AppDispatchers.CpuBounded) { YouTubeExploreParser.artistProfile(JSONObject(body), artist) }
                    val entry = ProfileLookup(System.currentTimeMillis() + 3_600_000L, profile)
                    profileCache.put(key, entry)
                    profileCache.put(artist.id, entry)
                    profileCache.put(name, entry)
                    profile
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    profileCache.put(key, ProfileLookup(System.currentTimeMillis() + 30_000L, null, true))
                    throw IOException("Artist profile timed out", timeout)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // Failed requests have a short retry window, not an artist-not-found cache.
                    profileCache.put(key, ProfileLookup(System.currentTimeMillis() + 30_000L, null, true))
                    throw error
                }
            }
        }

    suspend fun artistPhoto(name: String): String? = artistProfile(com.example.data.repository.CatalogArtist("", name))?.artist?.image


    /**
     * Searches YouTube Music using the official Innertube WEB_REMIX API.
     * Returns a list of standardized MusicTrack objects with source = "YouTube Music".
     */
    suspend fun searchSongs(query: String, limit: Int = 25, songsOnly: Boolean = false): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            if (songsOnly) return@withContext searchSongsPage(query).tracks.take(limit)
            val profile = YouTubeClient.WEB_REMIX
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", profile.clientName)
                        put("clientVersion", profile.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query.trim())
                if (songsOnly) put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .addHeader("User-Agent", profile.userAgent)
                .addHeader("Referer", "https://music.youtube.com/")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("X-YouTube-Client-Name", profile.clientId)
                .addHeader("X-YouTube-Client-Version", profile.clientVersion)
                .build()

            val body = httpClient.catalogRequest(request)
            withContext(AppDispatchers.CpuBounded) {
                (parseSearchResults(body, limit) + parseSongSearchPage(body).tracks).distinctBy { it.id }.take(limit)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "Error searching YouTube Music: ${e.message}")
            emptyList()
        }
    }

    /** Provider radio candidates, preserving its related-song ordering without resolving streams. */
    suspend fun getRadioRecommendations(videoId: String, limit: Int = 40): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return@withContext emptyList()
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject()
                    .put("clientName", "WEB_REMIX").put("clientVersion", "1.20231204.01.00").put("hl", "en")))
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("isAudioOnly", true)
            }
            val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/next")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0").header("Referer", "https://music.youtube.com/").build()
            val body = httpClient.catalogRequest(request)
            withContext(AppDispatchers.CpuBounded) {
                val tracks = mutableListOf<MusicTrack>()
                fun visit(value: Any?, depth: Int = 0) {
                    if (depth > 24 || tracks.size >= limit) return
                    when (value) {
                        is JSONObject -> {
                            if (value.has("playlistPanelVideoRenderer")) parseAnyRenderer(value)?.let(tracks::add)
                            else value.keys().forEach { visit(value.opt(it), depth + 1) }
                        }
                        is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), depth + 1)
                    }
                }
                visit(JSONObject(body))
                tracks.distinctBy { it.id }.take(limit)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }

    private fun parseSearchResults(jsonString: String, limit: Int): List<MusicTrack> {
        val tracks = mutableListOf<MusicTrack>()
        try {
            val root = JSONObject(jsonString)
            val contents = root.optJSONObject("contents") ?: return emptyList()
            val tabbed = contents.optJSONObject("tabbedSearchResultsRenderer") ?: return emptyList()
            val tabs = tabbed.optJSONArray("tabs") ?: return emptyList()
            if (tabs.length() == 0) return emptyList()

            val tabRenderer = tabs.getJSONObject(0).optJSONObject("tabRenderer") ?: return emptyList()
            val content = tabRenderer.optJSONObject("content") ?: return emptyList()
            val sectionListRenderer = content.optJSONObject("sectionListRenderer") ?: return emptyList()
            val sections = sectionListRenderer.optJSONArray("contents") ?: return emptyList()

            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                val itemSection = section.optJSONObject("itemSectionRenderer")
                val musicShelf = section.optJSONObject("musicShelfRenderer")

                val items: JSONArray? = itemSection?.optJSONArray("contents")
                    ?: musicShelf?.optJSONArray("contents")

                if (items != null) {
                    for (j in 0 until items.length()) {
                        val itemObj = items.getJSONObject(j)
                        val r = itemObj.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        val track = parseListItemRenderer(r)
                        if (track != null) {
                            tracks.add(track)
                            if (tracks.size >= limit) return tracks
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YTMusic search results: ${e.message}")
        }
        return tracks
    }

    private fun parseListItemRenderer(r: JSONObject): MusicTrack? {
        try {
            val flexCols = r.optJSONArray("flexColumns") ?: return null
            if (flexCols.length() == 0) return null

            // Title
            val col0 = flexCols.getJSONObject(0)
            val text0 = col0.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            val runs0 = text0?.optJSONArray("runs") ?: return null
            if (runs0.length() == 0) return null
            val rawTitle = (0 until runs0.length()).joinToString("") {
                runs0.optJSONObject(it)?.optString("text").orEmpty()
            }.trim()
            if (rawTitle.isBlank()) return null

            // Only artist/album browse endpoints identify these fields reliably. A plain
            // subtitle may be a channel, view count, or type label instead of an artist.
            val artists = mutableListOf<String>()
            var album = "Unknown"
            var durationMs = 0L
            var releaseYear = ""
            for (columnIndex in 1 until flexCols.length()) {
                val runs = flexCols.optJSONObject(columnIndex)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs") ?: continue
                for (index in 0 until runs.length()) {
                    val run = runs.optJSONObject(index) ?: continue
                    val text = run.optString("text").trim()
                    val endpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    val type = endpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
                    val browseId = endpoint?.optString("browseId").orEmpty()
                    when {
                        type == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> artists.add(text)
                        type == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> album = text
                        text.matches(Regex("[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?")) -> durationMs = parseDurationText(text)
                        text.matches(Regex("(?:19|20)[0-9]{2}")) -> releaseYear = text
                    }
                }
            }
            val fixedColumns = r.optJSONArray("fixedColumns")
            for (index in 0 until (fixedColumns?.length() ?: 0)) {
                val text = fixedColumns?.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                if (text.matches(Regex("[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?"))) durationMs = parseDurationText(text)
            }
            val artist = artists.distinct().joinToString(", ").ifBlank { "Unknown" }

            // Video ID
            var videoId = r.optJSONObject("playlistItemData")?.optString("videoId", "") ?: ""
            if (videoId.isBlank()) {
                val overlay = r.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                videoId = overlay?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId", "") ?: ""
            }
            if (videoId.isBlank()) {
                videoId = r.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId", "") ?: ""
            }
            if (!YouTubeAudioStreamService.isVideoId(videoId)) return null

            // Thumbnail
            var thumbUrl = ""
            val thumbObj = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbs = thumbObj?.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0) {
                thumbUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url", "")
            }
            if (thumbUrl.isBlank()) {
                thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }

            val cleanTitle = rawTitle.replace(Regex("&amp;"), "&")
                .replace(Regex("&#39;"), "'")
                .replace(Regex("&quot;"), "\"")
                .replace(Regex("(?i)\\b(?:youtube\\s*music|youtube|jiosaavn|saavn)\\b"), "")
                .trim()

            val cleanArtist = artist.replace(Regex("(?i)\\b(?:youtube\\s*music|youtube|jiosaavn|saavn)\\b"), "")
                .trim().ifBlank { "Various Artists" }

            val cleanAlbum = album.replace(Regex("(?i)\\b(?:youtube\\s*music|youtube|jiosaavn|saavn)\\b"), "")
                .trim().ifBlank { "Single" }

            return MusicTrack(
                id = "yt_$videoId",
                title = cleanTitle,
                artist = cleanArtist,
                album = cleanAlbum,
                durationMs = durationMs,
                coverUrl = thumbUrl,
                audioUrl = YouTubeAudioStreamService.playbackUri("yt_$videoId", ""),
                bitrateKbps = 0,
                qualityBadge = "HQ Stream",
                genre = r.optString("genre", "Unknown").ifBlank { "Unknown" },
                language = r.optString("language", "Unknown").ifBlank { "Unknown" },
                year = releaseYear,
                isrc = r.optString("isrc", ""),
                source = "YouTube Music"
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseDurationText(text: String): Long {
        return try {
            val parts = text.split(":").map { it.trim().toLong() }
            when (parts.size) {
                2 -> (parts[0] * 60 + parts[1]) * 1000L
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    data class YouTubePlaylistDetails(
        val title: String,
        val description: String,
        val coverUrl: String,
        val tracks: List<MusicTrack>
    )

    /**
     * Fetches tracks and metadata from a YouTube Music playlist ID using Innertube browse API.
     */
    suspend fun getPlaylistDetails(playlistId: String): YouTubePlaylistDetails? = withContext(Dispatchers.IO) {
        val cleanId = playlistId.removePrefix("VL")
        val browseId = if (cleanId.startsWith("MPREb_") || cleanId.startsWith("OLAK5uy_")) cleanId else "VL$cleanId"
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20231204.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://music.youtube.com/")
                .build()

            val body = httpClient.catalogRequest(request)
            val root = JSONObject(body)

            // Metadata from microformat
            val mf = root.optJSONObject("microformat")?.optJSONObject("microformatDataRenderer")
            var title = mf?.optString("title", "").orEmpty()
            val desc = mf?.optString("description", "").orEmpty()
            val thumbObj = mf?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            var coverUrl = if (thumbObj != null && thumbObj.length() > 0) {
                thumbObj.getJSONObject(thumbObj.length() - 1).optString("url", "")
            } else ""

            val contents = root.optJSONObject("contents")
            val tracks = mutableListOf<MusicTrack>()

            // Extract section list from various potential layouts (personal vs system playlists)
            val twoCol = contents?.optJSONObject("twoColumnBrowseResultsRenderer")
            val singleCol = contents?.optJSONObject("singleColumnBrowseResultsRenderer")

            val secContents = twoCol?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?: twoCol?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?: singleCol?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?: contents?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")

            if (title.isBlank()) {
                val header = root.optJSONObject("header")
                    ?: twoCol?.optJSONObject("header")
                    ?: singleCol?.optJSONObject("header")
                val rHeader = header?.optJSONObject("musicResponsiveHeaderRenderer")
                    ?: header?.optJSONObject("musicDetailHeaderRenderer")
                    ?: header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                    ?: header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                    ?: header?.optJSONObject("musicHeaderRenderer")
                title = rHeader?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                    ?: rHeader?.optJSONObject("title")?.optString("simpleText", "").orEmpty()
                if (title.isBlank()) {
                    title = header?.optJSONObject("musicVisualHeaderRenderer")?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                        ?: header?.optJSONObject("musicVisualHeaderRenderer")?.optJSONObject("title")?.optString("simpleText", "")
                        ?: root.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("title", "").orEmpty()
                }
                if (coverUrl.isBlank()) {
                    val hThumbs = rHeader?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    if (hThumbs != null && hThumbs.length() > 0) {
                        coverUrl = hThumbs.getJSONObject(hThumbs.length() - 1).optString("url", "")
                    }
                }
            }

            // Check direct musicPlaylistShelfRenderer if not wrapped in sectionListRenderer
            val directPlaylistShelf = contents?.optJSONObject("musicPlaylistShelfRenderer")
                ?: twoCol?.optJSONObject("secondaryContents")?.optJSONObject("musicPlaylistShelfRenderer")
            var lastShelfRenderer: JSONObject? = directPlaylistShelf
            var continuationToken: String? = extractContinuationToken(directPlaylistShelf)

            val directShelfContents = directPlaylistShelf?.optJSONArray("contents")
            if (directShelfContents != null) {
                for (j in 0 until directShelfContents.length()) {
                    val item = directShelfContents.getJSONObject(j)
                    val track = parseAnyRenderer(item)
                    if (track != null) {
                        tracks.add(track)
                    }
                }
            }

            if (secContents != null) {
                for (i in 0 until secContents.length()) {
                    val sec = secContents.getJSONObject(i)
                    val playlistShelf = sec.optJSONObject("musicPlaylistShelfRenderer")
                        ?: sec.optJSONObject("musicShelfRenderer")
                    if (playlistShelf != null) {
                        lastShelfRenderer = playlistShelf
                        val tok = extractContinuationToken(playlistShelf)
                        if (!tok.isNullOrBlank()) continuationToken = tok
                    }
                    val shelfContents = playlistShelf?.optJSONArray("contents")
                    if (shelfContents != null) {
                        for (j in 0 until shelfContents.length()) {
                            val item = shelfContents.getJSONObject(j)
                            val track = parseAnyRenderer(item)
                            if (track != null) {
                                tracks.add(track)
                            }
                        }
                    }
                    // Also check itemSectionRenderer inside sectionList
                    val itemSection = sec.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
                    if (itemSection != null) {
                        for (j in 0 until itemSection.length()) {
                            val item = itemSection.getJSONObject(j)
                            val track = parseAnyRenderer(item)
                            if (track != null) {
                                tracks.add(track)
                            }
                        }
                        val tok = extractContinuationToken(itemSection)
                        if (!tok.isNullOrBlank()) continuationToken = tok
                    }
                }
            }

            var usedWebClient = false

            // Fallback to standard YouTube WEB client if WEB_REMIX returned no tracks (e.g. standard YouTube playlists)
            if (tracks.isEmpty()) {
                try {
                    val webPayload = JSONObject().apply {
                        put("context", JSONObject().apply {
                            put("client", JSONObject().apply {
                                put("clientName", "WEB")
                                put("clientVersion", "2.20240101.00.00")
                                put("hl", "en")
                                put("gl", "US")
                            })
                        })
                        put("browseId", browseId)
                    }

                    val webReq = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/browse")
                        .post(webPayload.toString().toRequestBody(jsonMediaType))
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()

                    run {
                        val webRoot = JSONObject(httpClient.catalogRequest(webReq))
                        val webContents = webRoot.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                            ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
                            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")

                        if (webContents != null) {
                            for (wIdx in 0 until webContents.length()) {
                                val sec = webContents.getJSONObject(wIdx)
                                val items = sec.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
                                for (itIdx in 0 until items.length()) {
                                    val item = items.getJSONObject(itIdx)
                                    val track = parseAnyRenderer(item)
                                    if (track != null) {
                                        tracks.add(track)
                                    }
                                }
                                val tok = extractContinuationToken(items)
                                if (!tok.isNullOrBlank()) continuationToken = tok
                            }
                        }
                        usedWebClient = true

                        // Check web metadata
                        val webHeader = webRoot.optJSONObject("header")
                        if (title.isBlank()) {
                            val plHeader = webHeader?.optJSONObject("playlistHeaderRenderer")
                            title = plHeader?.optJSONObject("title")?.optString("simpleText", "").orEmpty()
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w(TAG, "YouTube WEB client fallback error: ${e.message}")
                }
            }

            // PAGINATION: Continuously follow continuation tokens up to 1000 songs
            while (!continuationToken.isNullOrBlank() && tracks.size < 1000) {
                try {
                    val clientName = if (usedWebClient) "WEB" else "WEB_REMIX"
                    val clientVer = if (usedWebClient) "2.20240101.00.00" else "1.20231204.01.00"
                    val apiUrl = if (usedWebClient) "https://www.youtube.com/youtubei/v1/browse" else "https://music.youtube.com/youtubei/v1/browse?ctoken=$continuationToken&continuation=$continuationToken"

                    val contPayload = JSONObject().apply {
                        put("context", JSONObject().apply {
                            put("client", JSONObject().apply {
                                put("clientName", clientName)
                                put("clientVersion", clientVer)
                                put("hl", "en")
                                put("gl", "US")
                            })
                        })
                        put("continuation", continuationToken)
                    }

                    val contRequest = Request.Builder()
                        .url(apiUrl)
                        .post(contPayload.toString().toRequestBody(jsonMediaType))
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .apply {
                            if (!usedWebClient) addHeader("Referer", "https://music.youtube.com/")
                        }
                        .build()

                    val contBody = httpClient.catalogRequest(contRequest)
                    val contRoot = JSONObject(contBody)

                    var foundBatchItems: JSONArray? = null

                    // Format 1: continuationContents (music.youtube.com)
                    val continuationContents = contRoot.optJSONObject("continuationContents")
                    val shelfContinuation = continuationContents?.optJSONObject("musicPlaylistShelfContinuation")
                        ?: continuationContents?.optJSONObject("musicShelfContinuation")
                    if (shelfContinuation != null) {
                        foundBatchItems = shelfContinuation.optJSONArray("contents")
                        continuationToken = extractContinuationToken(shelfContinuation)
                    }

                    // Format 2: onResponseReceivedActions (youtube.com / web client)
                    if (foundBatchItems == null) {
                        val actions = contRoot.optJSONArray("onResponseReceivedActions")
                        val act = actions?.optJSONObject(0)
                        val contItems = act?.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems")
                        if (contItems != null) {
                            foundBatchItems = contItems
                            continuationToken = extractContinuationToken(contItems)
                        } else {
                            continuationToken = null
                        }
                    }

                    if (foundBatchItems == null || foundBatchItems.length() == 0) {
                        break
                    }

                    var addedInBatch = 0
                    for (k in 0 until foundBatchItems.length()) {
                        val item = foundBatchItems.getJSONObject(k)
                        val track = parseAnyRenderer(item)
                        if (track != null) {
                            tracks.add(track)
                            addedInBatch++
                            if (tracks.size >= 1000) break
                        }
                    }
                    if (addedInBatch == 0 && continuationToken == null) {
                        break
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w(TAG, "Pagination error fetching continuation: ${e.message}")
                    break
                }
            }

            if (title.isBlank()) title = "YouTube Music Playlist"

            YouTubePlaylistDetails(
                title = title,
                description = desc.ifBlank { "Imported from YouTube Music" },
                coverUrl = coverUrl,
                tracks = tracks
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "Error fetching playlist details: ${e.message}")
            null
        }
    }

    internal fun parseAnyRenderer(item: JSONObject): MusicTrack? {
        val r = item.optJSONObject("musicResponsiveListItemRenderer")
        if (r != null) return parseListItemRenderer(r)

        val pvr = item.optJSONObject("playlistVideoRenderer") ?: item.optJSONObject("playlistPanelVideoRenderer")
        if (pvr != null) {
            val vid = pvr.optString("videoId", "")
            val rawTitle = pvr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                ?: pvr.optJSONObject("title")?.optString("simpleText", "").orEmpty()
            val rawArtist = (pvr.optJSONObject("shortBylineText") ?: pvr.optJSONObject("longBylineText"))
                ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "Unknown").orEmpty()
            val durSec = pvr.optString("lengthSeconds", "0").toLongOrNull() ?: 0L
            val thumbs = pvr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbUrl = if (thumbs != null && thumbs.length() > 0) thumbs.getJSONObject(thumbs.length() - 1).optString("url", "") else ""

            if (YouTubeAudioStreamService.isVideoId(vid) && rawTitle.isNotBlank() && rawTitle != "Private video" && rawTitle != "Deleted video") {
                val cleanTitle = cleanTitle(rawTitle)
                val cleanArtist = rawArtist.ifBlank { "YouTube Artist" }
                return MusicTrack(
                    id = "yt_$vid",
                    title = cleanTitle,
                    artist = cleanArtist,
                    album = if (item.has("playlistPanelVideoRenderer")) "Unknown" else "YouTube Playlist",
                    durationMs = if (durSec > 0) durSec * 1000L else parseDurationText(pvr.optJSONObject("lengthText")?.optString("simpleText").orEmpty()),
                    coverUrl = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$vid/maxresdefault.jpg" },
                    audioUrl = YouTubeAudioStreamService.playbackUri("yt_$vid", ""),
                    bitrateKbps = 0,
                    qualityBadge = "HQ Stream",
                    genre = "Unknown",
                    source = "YouTube Music"
                )
            }
        }

        val lvm = item.optJSONObject("lockupViewModel")
        if (lvm != null) {
            val vid = lvm.optString("contentId", "")
            val meta = lvm.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val rawTitle = meta?.optJSONObject("title")?.optString("content", "").orEmpty()
            val lines = meta?.optJSONObject("metadata")?.optJSONArray("lines")
            val rawArtist = lines?.optJSONObject(0)?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "YouTube Artist").orEmpty()

            if (YouTubeAudioStreamService.isVideoId(vid) && rawTitle.isNotBlank() && rawTitle != "Private video" && rawTitle != "Deleted video") {
                val cleanTitle = cleanTitle(rawTitle)
                val cleanArtist = rawArtist.ifBlank { "YouTube Artist" }
                return MusicTrack(
                    id = "yt_$vid",
                    title = cleanTitle,
                    artist = cleanArtist,
                    album = "YouTube Playlist",
                    durationMs = 0L,
                    coverUrl = "https://i.ytimg.com/vi/$vid/maxresdefault.jpg",
                    audioUrl = YouTubeAudioStreamService.playbackUri("yt_$vid", ""),
                    bitrateKbps = 0,
                    qualityBadge = "HQ Stream",
                    genre = "Unknown",
                    source = "YouTube Music"
                )
            }
        }

        return null
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("&amp;"), "&")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("(?i)\\s*\\(official (?:music )?video\\)"), "")
            .replace(Regex("(?i)\\s*\\[official (?:music )?video\\]"), "")
            .replace(Regex("(?i)\\s*\\(audio\\)"), "")
            .replace(Regex("(?i)\\s*\\[audio\\]"), "")
            .replace(Regex("(?i)\\s*\\(lyric video\\)"), "")
            .replace(Regex("(?i)\\s*\\[lyric video\\]"), "")
            .trim()
    }

    private fun extractContinuationToken(obj: Any?): String? {
        if (obj == null) return null
        if (obj is JSONObject) {
            val continuations = obj.optJSONArray("continuations")
            if (continuations != null) {
                for (i in 0 until continuations.length()) {
                    val nextCont = continuations.optJSONObject(i)?.optJSONObject("nextContinuationData")
                        ?: continuations.optJSONObject(i)?.optJSONObject("reloadContinuationData")
                    val token = nextCont?.optString("continuation", "")
                    if (!token.isNullOrBlank()) return token
                }
            }
            val contents = obj.optJSONArray("contents") ?: obj.optJSONArray("items") ?: obj.optJSONArray("continuationItems")
            if (contents != null) {
                val tok = extractContinuationToken(contents)
                if (!tok.isNullOrBlank()) return tok
            }
        } else if (obj is JSONArray) {
            for (i in (obj.length() - 1) downTo 0) {
                val it = obj.optJSONObject(i) ?: continue
                val cir = it.optJSONObject("continuationItemRenderer")
                if (cir != null) {
                    val endpoint = cir.optJSONObject("continuationEndpoint")
                    val cmd = endpoint?.optJSONObject("continuationCommand") ?: endpoint?.optJSONObject("innertubeCommand")?.optJSONObject("continuationCommand")
                    val tok = cmd?.optString("token", "")
                    if (!tok.isNullOrBlank()) return tok
                }
                val civm = it.optJSONObject("continuationItemViewModel")
                if (civm != null) {
                    val cmd = civm.optJSONObject("continuationCommand")
                    var tok = cmd?.optString("token", "")
                    if (tok.isNullOrBlank()) {
                        tok = cmd?.optJSONObject("continuationCommand")?.optString("token", "")
                    }
                    if (tok.isNullOrBlank()) {
                        tok = cmd?.optJSONObject("innertubeCommand")?.optJSONObject("continuationCommand")?.optString("token", "")
                    }
                    if (!tok.isNullOrBlank()) return tok
                }
            }
        }
        return null
    }

    /**
     * Fetches tracks from a YouTube Music playlist ID using Innertube browse API.
     */
    suspend fun getPlaylistTracks(playlistId: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        getPlaylistDetails(playlistId)?.tracks.orEmpty()
    }

    /**
     * Fetches trending recommendation songs for the Home Screen exclusively via YouTube Music API.
     */
    suspend fun getTrendingSongs(limit: Int = 25): List<MusicTrack> = withContext(Dispatchers.IO) {
        val trendingQueries = listOf("Top Hits", "Trending Songs", "Global Hits", "Billboard Top 50")
        try {
            val results = coroutineScope {
                trendingQueries.map { q ->
                    async {
                        try {
                            searchSongs(q, limit = limit / 2 + 5)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            val flattened = results.flatten().distinctBy { it.id }
            if (flattened.isNotEmpty()) flattened.take(limit) else searchSongs("Top Music Hits", limit)
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching trending songs from YouTube Music: ${e.message}")
            searchSongs("Top Music", limit)
        }
    }

    /**
     * Fetches profile/language/country tailored recommendation tracks exclusively via YouTube Music API.
     */
    suspend fun getTrendingSongsForProfile(
        country: String,
        languages: List<String>,
        limit: Int = 25
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        try {
            val queries = mutableListOf<String>()
            languages.filter { it.isNotBlank() }.forEach { lang ->
                queries.add("$lang top songs")
                queries.add("$lang trending hits")
            }
            if (country.isNotBlank()) {
                queries.add("Top songs in $country")
            }
            if (queries.isEmpty()) {
                queries.add("Top Worldwide Hits")
            }

            val results = coroutineScope {
                queries.take(6).map { q ->
                    async {
                        try {
                            searchSongs(q, limit = 8)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            results.flatten().distinctBy { it.id }.take(limit)
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching profile recommendations from YouTube Music: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches Punjabi hits exclusively via YouTube Music API.
     */
    suspend fun getPunjabiHits(limit: Int = 15): List<MusicTrack> = withContext(Dispatchers.IO) {
        val queries = listOf("Diljit Dosanjh hits", "Sidhu Moose Wala hits", "Karan Aujla hits", "AP Dhillon hits", "Amrinder Gill hits", "Top Punjabi Hits")
        try {
            val results = coroutineScope {
                queries.map { q ->
                    async {
                        try {
                            searchSongs(q, limit = 6)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            results.flatten().distinctBy { it.id }.take(limit)
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Punjabi hits from YouTube Music: ${e.message}")
            searchSongs("Top Punjabi Songs", limit)
        }
    }

    /**
     * Fetches Era-specific hits exclusively via YouTube Music API.
     */
    suspend fun getEraHits(limit: Int = 15): List<MusicTrack> = withContext(Dispatchers.IO) {
        val queries = listOf("90s Bollywood Classics", "2000s Bollywood Hits", "Retro Golden Hits", "Kumar Sanu hits", "Udit Narayan hits", "Sonu Nigam hits")
        try {
            val results = coroutineScope {
                queries.map { q ->
                    async {
                        try {
                            searchSongs(q, limit = 6)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            results.flatten().distinctBy { it.id }.take(limit)
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Era hits from YouTube Music: ${e.message}")
            searchSongs("90s and 2000s Bollywood Hits", limit)
        }
    }

    /**
     * Fetches Genre-specific tracks exclusively via YouTube Music API.
     */
    suspend fun getGenreTracks(genre: String, limit: Int = 25): List<MusicTrack> = withContext(Dispatchers.IO) {
        val q = if (genre.equals("All", true)) "Top Hit Songs" else "$genre top hits"
        searchSongs(q, limit)
    }
}
