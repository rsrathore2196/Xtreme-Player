package com.example.data.remote

import android.util.Log
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

    /**
     * Searches YouTube Music using the official Innertube WEB_REMIX API.
     * Returns a list of standardized MusicTrack objects with source = "YouTube Music".
     */
    suspend fun searchSongs(query: String, limit: Int = 25): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

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
                put("query", query.trim())
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://music.youtube.com/")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "YTMusic search returned status ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            parseSearchResults(body, limit)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching YouTube Music: ${e.message}")
            emptyList()
        }
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
            val rawTitle = runs0.getJSONObject(0).optString("text", "").trim()
            if (rawTitle.isBlank()) return null

            // Subtitle runs: contains type (Song/Video), artist, album, duration
            var artist = "Online Artist"
            var album = "Online Stream"
            var durationMs = 210000L

            if (flexCols.length() > 1) {
                val col1 = flexCols.getJSONObject(1)
                val text1 = col1.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
                val runs1 = text1?.optJSONArray("runs")
                if (runs1 != null) {
                    val texts = mutableListOf<String>()
                    for (k in 0 until runs1.length()) {
                        val t = runs1.getJSONObject(k).optString("text", "").trim()
                        if (t.isNotBlank() && t != "•") {
                            texts.add(t)
                        }
                    }
                    if (texts.isNotEmpty()) {
                        // Often texts are ["Song", "Artist Name", "Album Name", "3:45"] or ["Artist Name", "3:45"]
                        val nonType = if (texts.first().equals("Song", true) || texts.first().equals("Video", true)) {
                            texts.drop(1)
                        } else {
                            texts
                        }
                        if (nonType.isNotEmpty()) {
                            artist = nonType[0]
                        }
                        if (nonType.size > 1 && !nonType[1].contains(":")) {
                            album = nonType[1]
                        }
                        val durationText = nonType.find { it.contains(":") }
                        if (durationText != null) {
                            durationMs = parseDurationText(durationText)
                        }
                    }
                }
            }

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
            if (videoId.isBlank()) return null

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
                audioUrl = "", // Populated via Unified Audio Layer fallback/stream resolver
                bitrateKbps = 256,
                qualityBadge = "HQ • 256 kbps",
                genre = "Pop",
                source = "Extended Stream"
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
                else -> 210000L
            }
        } catch (e: Exception) {
            210000L
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

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
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

                    val webResp = httpClient.newCall(webReq).execute()
                    if (webResp.isSuccessful) {
                        val webRoot = JSONObject(webResp.body?.string().orEmpty())
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
                } catch (e: Exception) {
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

                    val contResp = httpClient.newCall(contRequest).execute()
                    if (!contResp.isSuccessful) break
                    val contBody = contResp.body?.string() ?: break
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
                } catch (e: Exception) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching playlist details: ${e.message}")
            null
        }
    }

    private fun parseAnyRenderer(item: JSONObject): MusicTrack? {
        val r = item.optJSONObject("musicResponsiveListItemRenderer")
        if (r != null) return parseListItemRenderer(r)

        val pvr = item.optJSONObject("playlistVideoRenderer")
        if (pvr != null) {
            val vid = pvr.optString("videoId", "")
            val rawTitle = pvr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                ?: pvr.optJSONObject("title")?.optString("simpleText", "").orEmpty()
            val rawArtist = pvr.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "YouTube Artist").orEmpty()
            val durSec = pvr.optString("lengthSeconds", "0").toLongOrNull() ?: 0L
            val thumbs = pvr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbUrl = if (thumbs != null && thumbs.length() > 0) thumbs.getJSONObject(thumbs.length() - 1).optString("url", "") else ""

            if (rawTitle.isNotBlank() && rawTitle != "Private video" && rawTitle != "Deleted video") {
                val cleanTitle = cleanTitle(rawTitle)
                val cleanArtist = rawArtist.ifBlank { "YouTube Artist" }
                return MusicTrack(
                    id = "yt_$vid",
                    title = cleanTitle,
                    artist = cleanArtist,
                    album = "YouTube Playlist",
                    durationMs = if (durSec > 0) durSec * 1000L else 210000L,
                    coverUrl = thumbUrl.ifBlank { "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80" },
                    audioUrl = "",
                    bitrateKbps = 256,
                    qualityBadge = "HQ • 256 kbps",
                    genre = "Pop",
                    source = "Extended Stream"
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

            if (rawTitle.isNotBlank() && rawTitle != "Private video" && rawTitle != "Deleted video") {
                val cleanTitle = cleanTitle(rawTitle)
                val cleanArtist = rawArtist.ifBlank { "YouTube Artist" }
                return MusicTrack(
                    id = "yt_$vid",
                    title = cleanTitle,
                    artist = cleanArtist,
                    album = "YouTube Playlist",
                    durationMs = 210000L,
                    coverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80",
                    audioUrl = "",
                    bitrateKbps = 256,
                    qualityBadge = "HQ • 256 kbps",
                    genre = "Pop",
                    source = "Extended Stream"
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
}
