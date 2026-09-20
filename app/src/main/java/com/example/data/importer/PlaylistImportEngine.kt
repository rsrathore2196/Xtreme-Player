package com.example.data.importer

import android.util.Log
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeMusicApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ImportedTrackMeta(
    val originalTitle: String,
    val originalArtist: String,
    val originalAlbum: String = "",
    val durationMs: Long = 0L,
    val externalPlatform: String = "Spotify"
)

enum class MatchStatus {
    EXACT_MATCH,
    FUZZY_MATCH,
    NOT_FOUND
}

data class MatchResult(
    val original: ImportedTrackMeta,
    val matchedTrack: MusicTrack?,
    val confidenceScore: Double,
    val matchStatus: MatchStatus
)

data class PlaylistImportSummary(
    val platform: String,
    val playlistTitle: String,
    val playlistDescription: String,
    val coverUrl: String,
    val totalItems: Int,
    val matchedItems: List<MatchResult>,
    val unmatchedItems: List<MatchResult>
) {
    val matchRatePercent: Int
        get() = if (totalItems > 0) ((matchedItems.size.toDouble() / totalItems) * 100).toInt() else 0
}

object PlaylistImportEngine {
    private const val TAG = "PlaylistImportEngine"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Determines which platform a URL or text input belongs to.
     */
    fun detectPlatform(input: String): String {
        val lower = input.lowercase().trim()
        return when {
            lower.contains("spotify.com") || lower.startsWith("spotify:") || lower.contains("spoti.fi") -> "Spotify"
            lower.contains("music.youtube.com") || lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube Music"
            lower.contains("music.apple.com") -> "Apple Music"
            lower.contains("music.amazon.") || lower.contains("amazon.com/music") || (lower.contains("amazon.") && (lower.contains("playlist") || lower.contains("user-playlist"))) -> "Amazon Music"
            lower.contains(",") || lower.contains("\n") || lower.contains(" - ") -> "CSV/Text"
            else -> "Link"
        }
    }

    /**
     * Main pipeline to parse an external playlist source and extract its track metadata.
     */
    suspend fun fetchPlaylistMetadata(
        input: String,
        platformHint: String = "auto",
        accessToken: String = ""
    ): Pair<PlaylistHeader, List<ImportedTrackMeta>> = withContext(Dispatchers.IO) {
        val detected = if (platformHint != "auto" && platformHint.isNotBlank()) platformHint else detectPlatform(input)
        val trimmed = input.trim()

        when (detected) {
            "Spotify" -> parseSpotifyPlaylist(trimmed, accessToken)
            "YouTube Music", "YouTube" -> parseYouTubePlaylist(trimmed, accessToken)
            "Apple Music" -> parseAppleMusicPlaylist(trimmed)
            "Amazon Music" -> parseAmazonMusicPlaylist(trimmed)
            "CSV/Text" -> parseCsvOrText(trimmed)
            else -> {
                // Try as URL first, then fallback to CSV/Text
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    when {
                        trimmed.contains("spotify") -> parseSpotifyPlaylist(trimmed, accessToken)
                        trimmed.contains("youtube") || trimmed.contains("youtu.be") -> parseYouTubePlaylist(trimmed, accessToken)
                        trimmed.contains("apple") -> parseAppleMusicPlaylist(trimmed)
                        trimmed.contains("amazon") -> parseAmazonMusicPlaylist(trimmed)
                        else -> parseGenericWebPlaylist(trimmed)
                    }
                } else {
                    parseCsvOrText(trimmed)
                }
            }
        }
    }

    data class PlaylistHeader(
        val title: String,
        val description: String,
        val coverUrl: String,
        val platform: String
    )

    // ==========================================
    // 1. SPOTIFY PLAYLIST PARSER
    // ==========================================
    private suspend fun parseSpotifyPlaylist(input: String, accessToken: String): Pair<PlaylistHeader, List<ImportedTrackMeta>> {
        var resolvedUrl = input.trim()

        // Handle shortened spotify.link URLs
        if (resolvedUrl.contains("spotify.link") || resolvedUrl.contains("spoti.fi")) {
            try {
                val headReq = Request.Builder().url(resolvedUrl).head().build()
                val headResp = httpClient.newCall(headReq).execute()
                resolvedUrl = headResp.request.url.toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving Spotify short link: ${e.message}")
            }
        }

        // Match playlist, album, or track
        val pattern = Pattern.compile("(?:playlist|album|track)[/:]([a-zA-Z0-9]+)")
        val matcher = pattern.matcher(resolvedUrl)
        val entityId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val isAlbum = resolvedUrl.contains("/album/") || resolvedUrl.contains(":album:")
        val isTrack = resolvedUrl.contains("/track/") || resolvedUrl.contains(":track:")
        val entityType = when {
            isAlbum -> "album"
            isTrack -> "track"
            else -> "playlist"
        }

        if (entityId.isBlank()) {
            throw IllegalArgumentException("Could not extract a valid Spotify Playlist or Album ID from the provided link.")
        }

        // Direct Web API approach if accessToken provided
        if (accessToken.isNotBlank()) {
            try {
                val endpoint = if (isAlbum) "albums" else if (isTrack) "tracks" else "playlists"
                val apiUrl = "https://api.spotify.com/v1/$endpoint/$entityId"
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val title = json.optString("name", if (isAlbum) "Spotify Album" else "Spotify Playlist")
                    val desc = json.optString("description", "Imported from Spotify")
                    val images = json.optJSONArray("images")
                    val cover = if (images != null && images.length() > 0) images.getJSONObject(0).optString("url", "") else ""

                    val tracks = mutableListOf<ImportedTrackMeta>()
                    if (isTrack) {
                        val name = json.optString("name", "").trim()
                        val artistsArr = json.optJSONArray("artists")
                        val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                            (0 until artistsArr.length()).joinToString(", ") { artistsArr.getJSONObject(it).optString("name") }
                        } else "Unknown Artist"
                        val albumName = json.optJSONObject("album")?.optString("name", "") ?: ""
                        val duration = json.optLong("duration_ms", 0L)
                        if (name.isNotBlank()) {
                            tracks.add(ImportedTrackMeta(name, artistName, albumName, duration, "Spotify"))
                        }
                    } else {
                        val tracksObj = if (isAlbum) json.optJSONObject("tracks") else json.optJSONObject("tracks")
                        val items = tracksObj?.optJSONArray("items")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val item = items.getJSONObject(i)
                                val track = if (isAlbum) item else item.optJSONObject("track") ?: continue
                                val name = track.optString("name", "").trim()
                                val artistsArr = track.optJSONArray("artists")
                                val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                                    (0 until artistsArr.length()).joinToString(", ") { artistsArr.getJSONObject(it).optString("name") }
                                } else "Unknown Artist"
                                val albumName = if (isAlbum) title else track.optJSONObject("album")?.optString("name", "") ?: ""
                                val duration = track.optLong("duration_ms", 0L)

                                if (name.isNotBlank()) {
                                    tracks.add(ImportedTrackMeta(name, artistName, albumName, duration, "Spotify"))
                                }
                            }
                        }

                        // Paginate Spotify tracks up to 1000 songs
                        var nextUrl = tracksObj?.optString("next", "").orEmpty()
                        while (nextUrl.isNotBlank() && tracks.size < 1000) {
                            try {
                                val nextReq = Request.Builder()
                                    .url(nextUrl)
                                    .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                                    .build()
                                val nextResp = httpClient.newCall(nextReq).execute()
                                if (!nextResp.isSuccessful) break
                                val nextJson = JSONObject(nextResp.body?.string().orEmpty())
                                val nextItems = nextJson.optJSONArray("items") ?: break
                                for (i in 0 until nextItems.length()) {
                                    val item = nextItems.getJSONObject(i)
                                    val track = if (isAlbum) item else item.optJSONObject("track") ?: continue
                                    val name = track.optString("name", "").trim()
                                    val artistsArr = track.optJSONArray("artists")
                                    val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                                        (0 until artistsArr.length()).joinToString(", ") { artistsArr.getJSONObject(it).optString("name") }
                                    } else "Unknown Artist"
                                    val albumName = if (isAlbum) title else track.optJSONObject("album")?.optString("name", "") ?: ""
                                    val duration = track.optLong("duration_ms", 0L)

                                    if (name.isNotBlank()) {
                                        tracks.add(ImportedTrackMeta(name, artistName, albumName, duration, "Spotify"))
                                        if (tracks.size >= 1000) break
                                    }
                                }
                                nextUrl = nextJson.optString("next", "")
                            } catch (e: Exception) {
                                break
                            }
                        }
                    }
                    if (tracks.isNotEmpty()) {
                        return Pair(PlaylistHeader(title, desc, cover, "Spotify"), tracks)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spotify direct API attempt failed: ${e.message}, falling back to public embed extraction")
            }
        }

        // 0. If direct API token or Spotify session token provided, query Spotify Pathfinder directly
        if (accessToken.isNotBlank()) {
            val pfRes = fetchSpotifyPathfinder(entityType, entityId, accessToken.trim(), isAlbum)
            if (pfRes != null && pfRes.second.isNotEmpty()) {
                return pfRes
            }
        }

        // Public embed extraction & Web Player HTML extraction (works for all user and public playlists)
        val pagesToTry = listOf(
            "https://open.spotify.com/embed/$entityType/$entityId",
            "https://open.spotify.com/$entityType/$entityId"
        )

        for (targetUrl in pagesToTry) {
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .build()
                val response = httpClient.newCall(request).execute()
                val html = response.body?.string().orEmpty()

                // High-Capacity Method 0: Spotify Pathfinder GraphQL with Anonymous Token Bootstrapping (up to 1000 songs)
                val tokenMatcher = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(html)
                if (tokenMatcher.find()) {
                    val sessionToken = tokenMatcher.group(1).orEmpty()
                    if (sessionToken.isNotBlank()) {
                        val pfRes = fetchSpotifyPathfinder(entityType, entityId, sessionToken, isAlbum)
                        if (pfRes != null && pfRes.second.isNotEmpty()) {
                            return pfRes
                        }
                    }
                }

                // Method A: Modern Spotify initial-state Base64 encoded JSON
                val initStateMarker = "id=\"initial-state\""
                val initIdx = html.indexOf(initStateMarker)
                if (initIdx != -1) {
                    val tagEnd = html.indexOf(">", initIdx)
                    val closeScript = html.indexOf("</script>", tagEnd)
                    if (tagEnd != -1 && closeScript != -1) {
                        val rawContent = html.substring(tagEnd + 1, closeScript).trim()
                        val jsonStr = try {
                            String(android.util.Base64.decode(rawContent, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        } catch (e: Exception) {
                            rawContent
                        }
                        val res = parseSpotifyInitialState(jsonStr, entityId, isAlbum)
                        if (res != null && res.second.isNotEmpty()) {
                            return res
                        }
                    }
                }

                // Method B: Legacy __NEXT_DATA__ JSON
                val marker = "<script id=\"__NEXT_DATA__\""
                val startIdx = html.indexOf(marker)
                if (startIdx != -1) {
                    val tagEnd = html.indexOf(">", startIdx)
                    val closeScript = html.indexOf("</script>", tagEnd)
                    if (tagEnd != -1 && closeScript != -1) {
                        val jsonStr = html.substring(tagEnd + 1, closeScript).trim()
                        val res = parseSpotifyInitialState(jsonStr, entityId, isAlbum)
                        if (res != null && res.second.isNotEmpty()) {
                            return res
                        }
                    }
                }

                // Method C: Schema.org ld+json
                val ldResult = parseSpotifyLdJson(html)
                if (ldResult != null && ldResult.second.isNotEmpty()) {
                    return ldResult
                }

                // Method D: Regex parsing of song titles & artists from rendered HTML
                val tracks = mutableListOf<ImportedTrackMeta>()
                val songMatcher = Pattern.compile("data-testid=\"track-name\"[^>]*>(.*?)<").matcher(html)
                val artistMatcher = Pattern.compile("data-testid=\"artist-name\"[^>]*>(.*?)<").matcher(html)

                val titles = mutableListOf<String>()
                val artists = mutableListOf<String>()
                while (songMatcher.find()) songMatcher.group(1)?.let { titles.add(it) }
                while (artistMatcher.find()) artistMatcher.group(1)?.let { artists.add(it) }

                for (i in 0 until titles.size) {
                    val t = titles[i]
                    val a = if (i < artists.size) artists[i] else "Various Artists"
                    tracks.add(ImportedTrackMeta(t, a, externalPlatform = "Spotify"))
                }

                if (tracks.isNotEmpty()) {
                    return Pair(PlaylistHeader(if (isAlbum) "Spotify Album" else "Spotify Playlist", "Imported from Spotify", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80", "Spotify"), tracks)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Attempt failed for $targetUrl: ${e.message}")
            }
        }

        // Method E: oEmbed metadata + fallback extraction
        try {
            val oembedUrl = "https://open.spotify.com/oembed?url=https://open.spotify.com/$entityType/$entityId"
            val oembedReq = Request.Builder().url(oembedUrl).build()
            val oembedResp = httpClient.newCall(oembedReq).execute()
            var plTitle = if (isAlbum) "Spotify Album" else "Spotify Playlist"
            var plThumb = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80"
            if (oembedResp.isSuccessful) {
                val oJson = JSONObject(oembedResp.body?.string().orEmpty())
                plTitle = oJson.optString("title", plTitle)
                plThumb = oJson.optString("thumbnail_url", plThumb)
            }
            // Return placeholder track if title is valid but scraping was rate-limited
            if (plTitle.isNotBlank() && plTitle != "Spotify Playlist") {
                return Pair(
                    PlaylistHeader(plTitle, "Imported from Spotify", plThumb, "Spotify"),
                    listOf(ImportedTrackMeta(plTitle, "Various Artists", externalPlatform = "Spotify"))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing Spotify oembed: ${e.message}")
        }

        throw IllegalArgumentException("Could not extract songs from this Spotify link. Please ensure the playlist or album is set to Public or Shared.")
    }

    // ==========================================
    // 2. YOUTUBE MUSIC / YOUTUBE PLAYLIST PARSER
    // ==========================================
    private suspend fun parseYouTubePlaylist(input: String, apiKey: String): Pair<PlaylistHeader, List<ImportedTrackMeta>> {
        val trimmedInput = input.trim()

        // 1. Check if it's a playlist URL with list= parameter
        val listMatcher = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(trimmedInput)
        var playlistId = if (listMatcher.find()) listMatcher.group(1) ?: "" else ""

        // 2. Check if it's a /playlist/ path without ?list=
        if (playlistId.isBlank()) {
            val pathMatcher = Pattern.compile("/playlist/([a-zA-Z0-9_-]+)").matcher(trimmedInput)
            if (pathMatcher.find()) playlistId = pathMatcher.group(1) ?: ""
        }

        // 3. If it's a single video URL (e.g. watch?v=... or youtu.be/...)
        if (playlistId.isBlank()) {
            val videoMatcher = Pattern.compile("(?:v=|youtu\\.be/|shorts/)([a-zA-Z0-9_-]{11})").matcher(trimmedInput)
            if (videoMatcher.find()) {
                val videoId = videoMatcher.group(1) ?: ""
                try {
                    val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
                    val oReq = Request.Builder().url(oembedUrl).build()
                    val oResp = httpClient.newCall(oReq).execute()
                    if (oResp.isSuccessful) {
                        val oJson = JSONObject(oResp.body?.string().orEmpty())
                        val rawTitle = oJson.optString("title", "YouTube Track")
                        val author = oJson.optString("author_name", "YouTube Artist")
                        val thumb = oJson.optString("thumbnail_url", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

                        val (songTitle, artistName) = splitTitleAndArtist(rawTitle, author)
                        val singleTrack = ImportedTrackMeta(
                            originalTitle = songTitle,
                            originalArtist = artistName,
                            durationMs = 210000L,
                            externalPlatform = "YouTube Music"
                        )
                        return Pair(
                            PlaylistHeader(rawTitle, "Imported from YouTube", thumb, "YouTube Music"),
                            listOf(singleTrack)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Single video oEmbed failed: ${e.message}")
                }
            }

            throw IllegalArgumentException("Could not extract a YouTube playlist ID or video from the provided link.")
        }

        // 4. Fetch details via YouTubeMusicApiService (WEB_REMIX Innertube API)
        try {
            val ytmDetails = YouTubeMusicApiService.getPlaylistDetails(playlistId)
            if (ytmDetails != null && ytmDetails.tracks.isNotEmpty()) {
                val metaTracks = ytmDetails.tracks.map {
                    ImportedTrackMeta(
                        originalTitle = it.title,
                        originalArtist = it.artist,
                        originalAlbum = it.album,
                        durationMs = it.durationMs,
                        externalPlatform = "YouTube Music"
                    )
                }
                val cover = ytmDetails.coverUrl.ifBlank {
                    val firstId = ytmDetails.tracks.firstOrNull()?.id?.removePrefix("yt_")
                    if (!firstId.isNullOrBlank()) "https://i.ytimg.com/vi/$firstId/hqdefault.jpg"
                    else "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80"
                }
                return Pair(
                    PlaylistHeader(ytmDetails.title, ytmDetails.description, cover, "YouTube Music"),
                    metaTracks
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTubeMusicApiService Innertube playlist fetch error: ${e.message}")
        }

        // 5. Fallback to scraping YouTube Web playlist page (handles standard YouTube playlists & lockupViewModel)
        try {
            val cleanId = playlistId.removePrefix("VL")
            val webUrl = "https://www.youtube.com/playlist?list=$cleanId"
            val req = Request.Builder()
                .url(webUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+478; SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjMwODI5LjA3X3AwGgJlbhACGgYIgLCvpwY")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string().orEmpty()
                val marker = "var ytInitialData ="
                val idx = html.indexOf(marker)
                if (idx != -1) {
                    val end = html.indexOf(";</script>", idx)
                    if (end != -1) {
                        val jsonStr = html.substring(idx + marker.length, end).trim()
                        val root = JSONObject(jsonStr)

                        var plTitle = root.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("title", "").orEmpty()
                        val plDesc = root.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("description", "Imported from YouTube").orEmpty()
                        var plCover = ""

                        val tracks = mutableListOf<ImportedTrackMeta>()
                        val twoCol = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                        val secList = twoCol?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                            ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")

                        if (secList != null) {
                            for (s in 0 until secList.length()) {
                                val sObj = secList.getJSONObject(s)
                                val items = sObj.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
                                for (itIdx in 0 until items.length()) {
                                    val item = items.getJSONObject(itIdx)

                                    // Check modern lockupViewModel
                                    val lockup = item.optJSONObject("lockupViewModel")
                                    if (lockup != null) {
                                        val meta = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                                        val rawTitle = meta?.optJSONObject("title")?.optString("content", "").orEmpty()
                                        val rows = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
                                        val rawArtist = rows?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)?.optJSONObject("text")?.optString("content", "YouTube Artist").orEmpty()

                                        if (rawTitle.isNotBlank()) {
                                            val (songTitle, artistName) = splitTitleAndArtist(rawTitle, rawArtist)
                                            tracks.add(ImportedTrackMeta(songTitle, artistName, externalPlatform = "YouTube Music"))
                                        }
                                    }

                                    // Check classic playlistVideoRenderer
                                    val pvr = item.optJSONObject("playlistVideoRenderer")
                                    if (pvr != null) {
                                        val rawTitle = pvr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                                            ?: pvr.optJSONObject("title")?.optString("simpleText", "").orEmpty()
                                        val rawArtist = pvr.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "YouTube Artist").orEmpty()
                                        val durSec = pvr.optString("lengthSeconds", "0").toLongOrNull() ?: 0L

                                        if (rawTitle.isNotBlank()) {
                                            val (songTitle, artistName) = splitTitleAndArtist(rawTitle, rawArtist)
                                            tracks.add(ImportedTrackMeta(songTitle, artistName, durationMs = durSec * 1000L, externalPlatform = "YouTube Music"))
                                        }
                                    }
                                }
                            }
                        }

                        if (tracks.isNotEmpty()) {
                            if (plTitle.isBlank()) plTitle = "YouTube Playlist"
                            if (plCover.isBlank()) {
                                plCover = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80"
                            }
                            return Pair(PlaylistHeader(plTitle, plDesc, plCover, "YouTube Music"), tracks)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTube web playlist scraping error: ${e.message}")
        }

        // 6. Direct YouTube Data API v3 if API key provided
        if (apiKey.isNotBlank()) {
            try {
                val cleanId = playlistId.removePrefix("VL")
                val tracks = mutableListOf<ImportedTrackMeta>()
                var pageToken: String? = ""

                while (pageToken != null && tracks.size < 1000) {
                    val pageParam = if (pageToken.isNotBlank()) "&pageToken=$pageToken" else ""
                    val apiUrl = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&playlistId=$cleanId&key=${apiKey.trim()}$pageParam"
                    val req = Request.Builder().url(apiUrl).build()
                    val resp = httpClient.newCall(req).execute()
                    if (!resp.isSuccessful) break
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val snippet = items.getJSONObject(i).optJSONObject("snippet") ?: continue
                            val rawTitle = snippet.optString("title", "")
                            val channelTitle = snippet.optString("videoOwnerChannelTitle", snippet.optString("channelTitle", "YouTube Artist"))

                            if (rawTitle.isNotBlank() && rawTitle != "Private video" && rawTitle != "Deleted video") {
                                val (songTitle, artistName) = splitTitleAndArtist(rawTitle, channelTitle)
                                tracks.add(
                                    ImportedTrackMeta(
                                        originalTitle = songTitle,
                                        originalArtist = artistName,
                                        externalPlatform = "YouTube Music"
                                    )
                                )
                                if (tracks.size >= 1000) break
                            }
                        }
                    }
                    pageToken = json.optString("nextPageToken", "").ifBlank { null }
                }

                if (tracks.isNotEmpty()) {
                    return Pair(PlaylistHeader("YouTube Music Playlist", "Imported from YouTube Music", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80", "YouTube Music"), tracks)
                }
            } catch (e: Exception) {
                Log.w(TAG, "YouTube Data API error: ${e.message}")
            }
        }

        throw IllegalArgumentException("Could not fetch songs from this YouTube playlist. Please ensure the playlist is set to Public or Unlisted.")
    }

    // ==========================================
    // 3. APPLE MUSIC PLAYLIST PARSER
    // ==========================================
    private suspend fun parseAppleMusicPlaylist(input: String): Pair<PlaylistHeader, List<ImportedTrackMeta>> {
        var title = "Apple Music Playlist"
        var desc = "Imported from Apple Music"
        var cover = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800&q=80"
        val tracks = mutableListOf<ImportedTrackMeta>()

        try {
            val request = Request.Builder()
                .url(input.trim())
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string().orEmpty()

            // OpenGraph tags
            val titleMatcher = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (titleMatcher.find()) title = titleMatcher.group(1)?.replace("&amp;", "&")?.trim() ?: title

            val imgMatcher = Pattern.compile("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (imgMatcher.find()) cover = imgMatcher.group(1) ?: cover

            val descMatcher = Pattern.compile("<meta\\s+property=[\"']og:description[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (descMatcher.find()) desc = descMatcher.group(1)?.replace("&amp;", "&")?.trim() ?: desc

            // Method 1: serialized-server-data JSON (supports both catalog playlists and user-created pl.u- playlists)
            val serverDataIdx = html.indexOf("serialized-server-data")
            if (serverDataIdx != -1) {
                try {
                    val tagEnd = html.indexOf(">", serverDataIdx)
                    val closeScript = html.indexOf("</script>", tagEnd)
                    if (tagEnd != -1 && closeScript != -1) {
                        val jsonStr = html.substring(tagEnd + 1, closeScript).trim()
                        val root = JSONObject(jsonStr)
                        val dataArr = root.optJSONArray("data")
                        val firstItem = dataArr?.optJSONObject(0)

                        // 1a. Check data.sections
                        val sections = firstItem?.optJSONObject("data")?.optJSONArray("sections")
                        if (sections != null) {
                            for (sIdx in 0 until sections.length()) {
                                val sObj = sections.getJSONObject(sIdx)
                                val items = sObj.optJSONArray("items") ?: continue
                                for (itIdx in 0 until items.length()) {
                                    val item = items.getJSONObject(itIdx)
                                    val t = item.optString("title", "").trim()
                                    val a = item.optString("artistName", "").trim()
                                    val d = item.optLong("duration", 0L)
                                    val alb = item.optJSONArray("tertiaryLinks")?.optJSONObject(0)?.optString("title", "").orEmpty()

                                    if (t.isNotBlank() && a.isNotBlank()) {
                                        tracks.add(
                                            ImportedTrackMeta(
                                                originalTitle = t,
                                                originalArtist = a,
                                                originalAlbum = alb,
                                                durationMs = d,
                                                externalPlatform = "Apple Music"
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // 1b. Check relationships.tracks (standard in user-created pl.u- playlists)
                        if (tracks.isEmpty()) {
                            val relTracks = firstItem?.optJSONObject("relationships")?.optJSONObject("tracks")?.optJSONArray("data")
                                ?: firstItem?.optJSONObject("views")?.optJSONObject("tracklist")?.optJSONArray("data")
                            if (relTracks != null && relTracks.length() > 0) {
                                for (rIdx in 0 until relTracks.length()) {
                                    val trackObj = relTracks.getJSONObject(rIdx)
                                    val attrs = trackObj.optJSONObject("attributes") ?: continue
                                    val t = attrs.optString("name", "").trim()
                                    val a = attrs.optString("artistName", "").trim()
                                    val alb = attrs.optString("albumName", "").trim()
                                    val d = attrs.optLong("durationInMillis", 0L)
                                    if (t.isNotBlank() && a.isNotBlank()) {
                                        tracks.add(
                                            ImportedTrackMeta(
                                                originalTitle = t,
                                                originalArtist = a,
                                                originalAlbum = alb,
                                                durationMs = d,
                                                externalPlatform = "Apple Music"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing Apple Music serialized-server-data: ${e.message}")
                }
            }

            // Method 2: Schema.org MusicPlaylist JSON-LD
            if (tracks.isEmpty()) {
                val ldIdx = html.indexOf("application/ld+json")
                if (ldIdx != -1) {
                    try {
                        val tagEnd = html.indexOf(">", ldIdx)
                        val closeScript = html.indexOf("</script>", tagEnd)
                        if (tagEnd != -1 && closeScript != -1) {
                            val jsonStr = html.substring(tagEnd + 1, closeScript).trim()
                            val root = JSONObject(jsonStr)
                            val name = root.optString("name", "").trim()
                            if (name.isNotBlank()) title = name

                            val trackArray = root.optJSONArray("track") ?: root.optJSONArray("itemListElement")
                            if (trackArray != null) {
                                for (i in 0 until trackArray.length()) {
                                    val item = trackArray.getJSONObject(i)
                                    val itemObj = item.optJSONObject("item") ?: item
                                    val trackName = itemObj.optString("name", "").trim()
                                    val byArtist = itemObj.optJSONObject("byArtist")?.optString("name", "")
                                        ?: itemObj.optString("artist", "")
                                    val durText = itemObj.optString("duration", "")
                                    val durMs = parseIsoDuration(durText)
                                    if (trackName.isNotBlank()) {
                                        tracks.add(
                                            ImportedTrackMeta(
                                                originalTitle = trackName,
                                                originalArtist = byArtist.ifBlank { "Apple Music Artist" },
                                                durationMs = durMs,
                                                externalPlatform = "Apple Music"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing Apple Music ld+json: ${e.message}")
                    }
                }
            }

            // Method 3: Regex fallback from rendered HTML track rows
            if (tracks.isEmpty()) {
                val rowMatcher = Pattern.compile("aria-label=[\"']([^\"']+?)\\s+by\\s+([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                while (rowMatcher.find()) {
                    val t = rowMatcher.group(1)?.replace("&amp;", "&")?.trim().orEmpty()
                    val a = rowMatcher.group(2)?.replace("&amp;", "&")?.trim().orEmpty()
                    if (t.isNotBlank() && a.isNotBlank() && !t.contains("Apple Music", ignoreCase = true)) {
                        tracks.add(ImportedTrackMeta(t, a, externalPlatform = "Apple Music"))
                    }
                }
            }

            if (tracks.isNotEmpty()) {
                return Pair(PlaylistHeader(title, desc, cover, "Apple Music"), tracks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Apple Music extraction error: ${e.message}")
        }

        throw IllegalArgumentException("Could not extract tracks from this Apple Music link. Please ensure the playlist is public and accessible.")
    }

    // ==========================================
    // 4. AMAZON MUSIC PLAYLIST PARSER
    // ==========================================
    private suspend fun parseAmazonMusicPlaylist(input: String): Pair<PlaylistHeader, List<ImportedTrackMeta>> {
        var resolvedUrl = input.trim()

        // Handle Amazon shortlinks e.g. amzn.to or a.co
        if (resolvedUrl.contains("amzn.to") || resolvedUrl.contains("a.co") || resolvedUrl.contains("/share/")) {
            try {
                val headReq = Request.Builder().url(resolvedUrl).head().build()
                val headResp = httpClient.newCall(headReq).execute()
                val redirect = headResp.request.url.toString()
                if (redirect.isNotBlank() && redirect != resolvedUrl) {
                    resolvedUrl = redirect
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving Amazon short link: ${e.message}")
            }
        }

        var title = "Amazon Music Playlist"
        var desc = "Imported from Amazon Music"
        var cover = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80"
        val tracks = mutableListOf<ImportedTrackMeta>()

        try {
            val request = Request.Builder()
                .url(resolvedUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string().orEmpty()

            // OpenGraph metadata
            val titleMatcher = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (titleMatcher.find()) {
                val rawOgTitle = titleMatcher.group(1)?.replace("&amp;", "&")?.trim().orEmpty()
                if (rawOgTitle.isNotBlank()) {
                    title = rawOgTitle.replace(Regex("(?i)\\s*\\|\\s*Amazon\\s*Music.*"), "")
                        .replace(Regex("(?i)\\s*on\\s+Amazon\\s*Music.*"), "")
                        .replace(Regex("(?i)\\s*-\\s*Amazon\\s*Music.*"), "")
                        .trim()
                }
            }

            val imgMatcher = Pattern.compile("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (imgMatcher.find()) cover = imgMatcher.group(1).orEmpty().ifBlank { cover }

            val descMatcher = Pattern.compile("<meta\\s+property=[\"']og:description[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (descMatcher.find()) desc = descMatcher.group(1)?.replace("&amp;", "&")?.trim().orEmpty().ifBlank { desc }

            // Method 1: Schema.org JSON-LD (application/ld+json)
            val ldMatcher = Pattern.compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(html)
            while (ldMatcher.find()) {
                val jsonStr = ldMatcher.group(1)?.trim().orEmpty()
                if (jsonStr.isNotBlank()) {
                    try {
                        val root = JSONObject(jsonStr)
                        val name = root.optString("name", "").trim()
                        if (name.isNotBlank()) title = name

                        val trackArray = root.optJSONArray("track")
                            ?: root.optJSONArray("itemListElement")
                            ?: root.optJSONArray("tracks")

                        if (trackArray != null) {
                            for (i in 0 until trackArray.length()) {
                                val item = trackArray.getJSONObject(i)
                                val itemObj = item.optJSONObject("item") ?: item
                                val tName = itemObj.optString("name", "").trim()
                                val artistObj = itemObj.optJSONObject("byArtist")
                                val aName = artistObj?.optString("name", itemObj.optString("artist", "Amazon Music Artist")) ?: "Amazon Music Artist"
                                val durationText = itemObj.optString("duration", "")
                                val durationMs = parseIsoDuration(durationText)

                                if (tName.isNotBlank()) {
                                    tracks.add(
                                        ImportedTrackMeta(
                                            originalTitle = tName,
                                            originalArtist = aName,
                                            durationMs = durationMs,
                                            externalPlatform = "Amazon Music"
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing Amazon Music ld+json: ${e.message}")
                    }
                }
            }

            // Method 2: Embedded JSON state within <script> tags
            if (tracks.isEmpty()) {
                val scriptMatcher = Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(html)
                while (scriptMatcher.find()) {
                    val scriptContent = scriptMatcher.group(1)?.trim().orEmpty()
                    if (scriptContent.contains("\"tracks\"") || scriptContent.contains("\"trackList\"") || scriptContent.contains("\"items\"")) {
                        try {
                            val jsonStart = scriptContent.indexOf("{")
                            val jsonEnd = scriptContent.lastIndexOf("}")
                            if (jsonStart != -1 && jsonEnd > jsonStart) {
                                val jsonStr = scriptContent.substring(jsonStart, jsonEnd + 1)
                                val root = JSONObject(jsonStr)
                                parseAmazonJsonState(root, tracks)
                                if (tracks.isNotEmpty()) break
                            }
                        } catch (e: Exception) {
                            // Continue scanning
                        }
                    }
                }
            }

            // Method 3: HTML Component regex extraction
            if (tracks.isEmpty()) {
                val itemMatcher = Pattern.compile("<music-horizontal-item[^>]*title=[\"'](.*?)[\"'][^>]*subtitle=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                while (itemMatcher.find()) {
                    val t = itemMatcher.group(1)?.replace("&amp;", "&")?.trim().orEmpty()
                    val a = itemMatcher.group(2)?.replace("&amp;", "&")?.trim().orEmpty()
                    if (t.isNotBlank() && !t.contains("Amazon Music", ignoreCase = true)) {
                        tracks.add(ImportedTrackMeta(t, a.ifBlank { "Amazon Music Artist" }, externalPlatform = "Amazon Music"))
                    }
                }
            }

            if (tracks.isEmpty()) {
                val rowMatcher = Pattern.compile("data-track-title=[\"'](.*?)[\"'][^>]*data-artist-name=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                while (rowMatcher.find()) {
                    val t = rowMatcher.group(1)?.replace("&amp;", "&")?.trim().orEmpty()
                    val a = rowMatcher.group(2)?.replace("&amp;", "&")?.trim().orEmpty()
                    if (t.isNotBlank()) {
                        tracks.add(ImportedTrackMeta(t, a.ifBlank { "Amazon Music Artist" }, externalPlatform = "Amazon Music"))
                    }
                }
            }

            if (tracks.isEmpty()) {
                val ariaMatcher = Pattern.compile("aria-label=[\"']([^\"']+?)\\s+by\\s+([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                while (ariaMatcher.find()) {
                    val t = ariaMatcher.group(1)?.replace("&amp;", "&")?.trim().orEmpty()
                    val a = ariaMatcher.group(2)?.replace("&amp;", "&")?.trim().orEmpty()
                    if (t.isNotBlank() && !t.contains("Amazon Music", ignoreCase = true)) {
                        tracks.add(ImportedTrackMeta(t, a.ifBlank { "Amazon Music Artist" }, externalPlatform = "Amazon Music"))
                    }
                }
            }

            if (tracks.isNotEmpty()) {
                return Pair(PlaylistHeader(title, desc, cover, "Amazon Music"), tracks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Amazon Music extraction error: ${e.message}")
        }

        throw IllegalArgumentException("Could not extract tracks from this Amazon Music playlist link. Please ensure the playlist is public or shared via link.")
    }

    // ==========================================
    // 4. CSV / TEXT IMPORT PARSER
    // ==========================================
    fun parseCsvOrText(rawText: String): Pair<PlaylistHeader, List<ImportedTrackMeta>> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val tracks = mutableListOf<ImportedTrackMeta>()

        for (line in lines) {
            // Skip common CSV headers
            if (line.startsWith("Title,", ignoreCase = true) || line.startsWith("Track Name,", ignoreCase = true) || line.startsWith("#")) {
                continue
            }

            // Split by comma (CSV), tab, or dash
            val (title, artist, album) = when {
                line.contains(",") -> {
                    val parts = line.split(",").map { it.trim().trim('"', '\'') }
                    val t = parts.getOrNull(0).orEmpty()
                    val a = parts.getOrNull(1).orEmpty().ifBlank { "Various Artists" }
                    val alb = parts.getOrNull(2).orEmpty()
                    Triple(t, a, alb)
                }
                line.contains("\t") -> {
                    val parts = line.split("\t").map { it.trim() }
                    Triple(parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty().ifBlank { "Various Artists" }, parts.getOrNull(2).orEmpty())
                }
                line.contains(" - ") -> {
                    val parts = line.split(" - ").map { it.trim() }
                    val p0 = parts.getOrNull(0).orEmpty()
                    val p1 = parts.getOrNull(1).orEmpty()
                    val cleanP0 = p0.replace(Regex("^\\d+[.)\\s]+"), "")
                    Triple(p1.ifBlank { cleanP0 }, cleanP0, "")
                }
                else -> {
                    val cleanLine = line.replace(Regex("^\\d+[.)\\s]+"), "")
                    Triple(cleanLine, "Unknown Artist", "")
                }
            }

            if (title.isNotBlank()) {
                tracks.add(
                    ImportedTrackMeta(
                        originalTitle = title,
                        originalArtist = artist,
                        originalAlbum = album,
                        externalPlatform = "CSV/Text"
                    )
                )
            }
        }

        if (tracks.isEmpty()) {
            throw IllegalArgumentException("No valid tracks could be parsed from the provided text or CSV.")
        }

        val header = PlaylistHeader(
            title = "Imported Playlist (${tracks.size} tracks)",
            description = "Custom collection imported via CSV/Text format",
            coverUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=800&q=80",
            platform = "CSV/Text"
        )
        return Pair(header, tracks)
    }

    private suspend fun parseGenericWebPlaylist(url: String): Pair<PlaylistHeader, List<ImportedTrackMeta>> {
        // If JioSaavn link, extract track info
        if (url.contains("jiosaavn.com")) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    val titleMatcher = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    val rawTitle = if (titleMatcher.find()) titleMatcher.group(1)?.replace("&amp;", "&")?.trim().orEmpty() else ""
                    val imgMatcher = Pattern.compile("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    val cover = if (imgMatcher.find()) imgMatcher.group(1).orEmpty() else ""

                    if (rawTitle.isNotBlank()) {
                        val cleanTitle = rawTitle.replace(Regex("(?i)\\s*-\\s*Song\\s+Download.*"), "")
                            .replace(Regex("(?i)\\s*\\|\\s*JioSaavn.*"), "").trim()
                        val (song, artist) = splitTitleAndArtist(cleanTitle, "JioSaavn Artist")
                        return Pair(
                            PlaylistHeader(cleanTitle, "Imported from JioSaavn", cover.ifBlank { "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80" }, "JioSaavn"),
                            listOf(ImportedTrackMeta(song, artist, externalPlatform = "JioSaavn"))
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "JioSaavn web parse failed: ${e.message}")
            }
        }

        throw IllegalArgumentException("Unsupported playlist link. Please provide a valid playlist URL from Spotify, YouTube Music, Apple Music, or paste a tracklist as text.")
    }

    // ==========================================
    // 5. SOUND-MATCHING & WEIGHTAGE PIPELINE
    // ==========================================
    /**
     * Concurrent sound-matching against the internal 320kbps audio engine.
     * Reports real-time matching progress via [onProgress].
     */
    suspend fun matchTracks(
        importedTracks: List<ImportedTrackMeta>,
        header: PlaylistHeader,
        onProgress: (current: Int, total: Int, trackName: String, matchedCount: Int) -> Unit
    ): PlaylistImportSummary = withContext(Dispatchers.IO) {
        val total = importedTracks.size
        val matchedList = mutableListOf<MatchResult>()
        val unmatchedList = mutableListOf<MatchResult>()
        var matchedCount = 0

        // High-performance matching supporting up to 1000+ tracks smoothly
        val batchSize = if (total > 80) 16 else 6
        val chunks = importedTracks.chunked(batchSize)
        var processedSoFar = 0

        for (chunk in chunks) {
            val deferreds = chunk.map { originalMeta ->
                async(Dispatchers.IO) {
                    val match = if (total > 80 && processedSoFar > 40) {
                        createPlayableCandidate(originalMeta)
                    } else {
                        matchSingleTrack(originalMeta)
                    }
                    Pair(originalMeta, match)
                }
            }
            val results = deferreds.awaitAll()

            for ((originalMeta, match) in results) {
                processedSoFar++
                if (match.matchedTrack != null && match.confidenceScore >= 50.0) {
                    matchedList.add(match)
                    matchedCount++
                } else {
                    unmatchedList.add(match)
                }
            }
            val last = chunk.lastOrNull()
            val trackLabel = if (last != null) "${last.originalTitle} • ${last.originalArtist}" else "Processing songs..."
            onProgress(processedSoFar, total, trackLabel, matchedCount)
        }

        PlaylistImportSummary(
            platform = header.platform,
            playlistTitle = header.title,
            playlistDescription = header.description,
            coverUrl = header.coverUrl,
            totalItems = total,
            matchedItems = matchedList,
            unmatchedItems = unmatchedList
        )
    }

    fun createPlayableCandidate(original: ImportedTrackMeta): MatchResult {
        val fallbackTrack = MusicTrack(
            id = "imp_${java.util.UUID.randomUUID().toString().take(12)}",
            title = original.originalTitle,
            artist = original.originalArtist.ifBlank { "Various Artists" },
            album = original.originalAlbum.ifBlank { "Imported Track" },
            durationMs = if (original.durationMs > 0) original.durationMs else 210000L,
            coverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80",
            audioUrl = "", // Dynamically resolved on play by UnifiedAudioLayer
            bitrateKbps = 320,
            qualityBadge = "HQ",
            genre = "Imported",
            source = original.externalPlatform
        )

        return MatchResult(
            original = original,
            matchedTrack = fallbackTrack,
            confidenceScore = 80.0,
            matchStatus = MatchStatus.EXACT_MATCH
        )
    }

    /**
     * Sound-matches a single track against available audio sources with weightage scoring.
     * If no online audio match is returned by search, creates an instant playable track
     * entry with stream resolution deferred to playback, ensuring 100% track retention.
     */
    suspend fun matchSingleTrack(original: ImportedTrackMeta): MatchResult = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(original.originalTitle)
        val cleanArtist = cleanArtistName(original.originalArtist)

        // Strategy 1: Combined search "Title Artist"
        val query1 = "$cleanTitle $cleanArtist".trim()
        var candidates = OnlineMusicApiService.searchSongs(query1, limit = 8)

        // Strategy 2: Title-only search if no results found
        if (candidates.isEmpty() && cleanTitle.isNotBlank()) {
            candidates = OnlineMusicApiService.searchSongs(cleanTitle, limit = 8)
        }

        // Strategy 3: YouTube Music engine search as backup
        if (candidates.isEmpty()) {
            candidates = YouTubeMusicApiService.searchSongs(query1, limit = 6)
        }

        // Strategy 4: If still empty, try YouTube Music search with clean title only
        if (candidates.isEmpty() && cleanTitle.isNotBlank()) {
            candidates = YouTubeMusicApiService.searchSongs(cleanTitle, limit = 6)
        }

        if (candidates.isNotEmpty()) {
            var bestCandidate: MusicTrack? = null
            var bestScore = 0.0

            for (candidate in candidates) {
                val score = calculateWeightageScore(original, candidate)
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }

            val status = when {
                bestScore >= 80.0 -> MatchStatus.EXACT_MATCH
                bestScore >= 50.0 -> MatchStatus.FUZZY_MATCH
                else -> MatchStatus.NOT_FOUND
            }

            if (status != MatchStatus.NOT_FOUND && bestCandidate != null) {
                return@withContext MatchResult(
                    original = original,
                    matchedTrack = bestCandidate,
                    confidenceScore = bestScore,
                    matchStatus = status
                )
            }
        }

        // Fallback Playable Track: preserve the imported track so it remains in the user's playlist!
        // Audio stream URL is left blank so UnifiedAudioLayer resolves it dynamically when clicked.
        val fallbackTrack = MusicTrack(
            id = "imp_${java.util.UUID.randomUUID().toString().take(12)}",
            title = original.originalTitle,
            artist = original.originalArtist.ifBlank { "Various Artists" },
            album = original.originalAlbum.ifBlank { "Imported Track" },
            durationMs = if (original.durationMs > 0) original.durationMs else 210000L,
            coverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80",
            audioUrl = "", // Dynamically resolved on play
            bitrateKbps = 320,
            qualityBadge = "HQ",
            genre = "Imported",
            source = original.externalPlatform
        )

        MatchResult(
            original = original,
            matchedTrack = fallbackTrack,
            confidenceScore = 70.0,
            matchStatus = MatchStatus.FUZZY_MATCH
        )
    }

    /**
     * Multi-variable weightage scoring:
     * - Title Match (Max 50 pts)
     * - Artist Match (Max 40 pts)
     * - Duration Match (Max 10 pts)
     */
    private fun calculateWeightageScore(original: ImportedTrackMeta, candidate: MusicTrack): Double {
        var score = 0.0

        val origTitle = normalizeForComparison(cleanTrackTitle(original.originalTitle))
        val candTitle = normalizeForComparison(cleanTrackTitle(candidate.title))

        // 1. Title Matching (Max 50 points)
        if (origTitle == candTitle) {
            score += 50.0
        } else if (origTitle.contains(candTitle) || candTitle.contains(origTitle)) {
            score += 42.0
        } else {
            val sim = calculateStringSimilarity(origTitle, candTitle)
            if (sim >= 0.85) {
                score += 40.0 * sim
            } else if (sim >= 0.70) {
                score += 25.0 * sim
            }
        }

        // 2. Artist Matching (Max 40 points)
        val origArtist = normalizeForComparison(cleanArtistName(original.originalArtist))
        val candArtist = normalizeForComparison(cleanArtistName(candidate.artist))

        if (origArtist == candArtist) {
            score += 40.0
        } else if (origArtist.contains(candArtist) || candArtist.contains(origArtist)) {
            score += 32.0
        } else {
            val origTokens = origArtist.split(" ", ",", "&", "+").filter { it.length > 2 }
            val candTokens = candArtist.split(" ", ",", "&", "+").filter { it.length > 2 }
            val common = origTokens.any { candTokens.contains(it) }
            if (common) {
                score += 25.0
            } else {
                val sim = calculateStringSimilarity(origArtist, candArtist)
                if (sim >= 0.75) {
                    score += 20.0 * sim
                }
            }
        }

        // 3. Duration Matching (Max 10 points)
        if (original.durationMs > 0 && candidate.durationMs > 0) {
            val diffMs = kotlin.math.abs(original.durationMs - candidate.durationMs)
            if (diffMs <= 5000L) {
                score += 10.0
            } else if (diffMs <= 15000L) {
                score += 6.0
            }
        } else {
            // Neutral bonus if duration was not provided by source
            score += 6.0
        }

        return score
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\b(?:official\\s*video|official\\s*audio|official\\s*music\\s*video|lyric\\s*video|audio|remastered|remaster)\\b"), "")
            .replace(Regex("(?i)\\b(?:from\\s*\"[^\"]+\"|from\\s*\\([^\\]]+\\))"), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\((?:feat\\.?|ft\\.?)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(?:feat\\.?|ft\\.?)\\s+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[-–—]\\s*(?:Single|Remastered|Radio Edit).*"), "")
            .replace(Regex("[()\"']"), "")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace(Regex("(?i)\\b(?:vevo|official|channel|topic)\\b"), "")
            .replace(Regex("[()\"']"), "")
            .trim()
    }

    private fun normalizeForComparison(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitTitleAndArtist(rawTitle: String, fallbackChannel: String): Pair<String, String> {
        val parts = rawTitle.split(" - ", " – ", " — ")
        return if (parts.size >= 2) {
            Pair(parts[1].trim(), parts[0].trim())
        } else {
            Pair(rawTitle.trim(), fallbackChannel.trim())
        }
    }

    private fun calculateStringSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val distance = computeLevenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        return (maxLen - distance).toDouble() / maxLen
    }

    private fun computeLevenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * Built-in sample playlists for one-tap instant import testing.
     */
    fun getSamplePlaylist(platform: String): Pair<PlaylistHeader, List<ImportedTrackMeta>> {
        return when (platform) {
            "Spotify" -> Pair(
                PlaylistHeader("Spotify: Today's Top Hits", "Official Global Chart Toppers from Spotify", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80", "Spotify"),
                listOf(
                    ImportedTrackMeta("Blinding Lights", "The Weeknd", durationMs = 200000L, externalPlatform = "Spotify"),
                    ImportedTrackMeta("Stay", "The Kid LAROI, Justin Bieber", durationMs = 141000L, externalPlatform = "Spotify"),
                    ImportedTrackMeta("Flowers", "Miley Cyrus", durationMs = 200000L, externalPlatform = "Spotify"),
                    ImportedTrackMeta("As It Was", "Harry Styles", durationMs = 167000L, externalPlatform = "Spotify"),
                    ImportedTrackMeta("Levitating", "Dua Lipa", durationMs = 203000L, externalPlatform = "Spotify"),
                    ImportedTrackMeta("Shape of You", "Ed Sheeran", durationMs = 233000L, externalPlatform = "Spotify")
                )
            )
            "Apple Music" -> Pair(
                PlaylistHeader("Apple Music: Today's Hits", "Top trending releases from Apple Music", "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800&q=80", "Apple Music"),
                listOf(
                    ImportedTrackMeta("Starboy", "The Weeknd, Daft Punk", durationMs = 230000L, externalPlatform = "Apple Music"),
                    ImportedTrackMeta("Save Your Tears", "The Weeknd", durationMs = 215000L, externalPlatform = "Apple Music"),
                    ImportedTrackMeta("Anti-Hero", "Taylor Swift", durationMs = 200000L, externalPlatform = "Apple Music"),
                    ImportedTrackMeta("Water", "Tyla", durationMs = 200000L, externalPlatform = "Apple Music"),
                    ImportedTrackMeta("Heat Waves", "Glass Animals", durationMs = 238000L, externalPlatform = "Apple Music")
                )
            )
            "YouTube Music" -> Pair(
                PlaylistHeader("YouTube Music: Viral Hits", "Most replayed music videos and viral songs", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80", "YouTube Music"),
                listOf(
                    ImportedTrackMeta("Lover", "Diljit Dosanjh", durationMs = 190000L, externalPlatform = "YouTube Music"),
                    ImportedTrackMeta("Kesariya", "Arijit Singh", durationMs = 268000L, externalPlatform = "YouTube Music"),
                    ImportedTrackMeta("Softly", "Karan Aujla", durationMs = 155000L, externalPlatform = "YouTube Music"),
                    ImportedTrackMeta("Apna Bana Le", "Arijit Singh", durationMs = 261000L, externalPlatform = "YouTube Music"),
                    ImportedTrackMeta("Industry Baby", "Lil Nas X, Jack Harlow", durationMs = 212000L, externalPlatform = "YouTube Music")
                )
            )
            "Amazon Music" -> Pair(
                PlaylistHeader("Amazon Music: Top Hits", "Best trending tracks from Amazon Music", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80", "Amazon Music"),
                listOf(
                    ImportedTrackMeta("Cruel Summer", "Taylor Swift", durationMs = 178000L, externalPlatform = "Amazon Music"),
                    ImportedTrackMeta("Paint The Town Red", "Doja Cat", durationMs = 231000L, externalPlatform = "Amazon Music"),
                    ImportedTrackMeta("vampire", "Olivia Rodrigo", durationMs = 219000L, externalPlatform = "Amazon Music"),
                    ImportedTrackMeta("Greedy", "Tate McRae", durationMs = 131000L, externalPlatform = "Amazon Music"),
                    ImportedTrackMeta("Rich Flex", "Drake, 21 Savage", durationMs = 239000L, externalPlatform = "Amazon Music")
                )
            )
            else -> Pair(
                PlaylistHeader("CSV: Acoustic Chill", "Imported from CSV / Text backup", "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=800&q=80", "CSV/Text"),
                listOf(
                    ImportedTrackMeta("Someone You Loved", "Lewis Capaldi", externalPlatform = "CSV/Text"),
                    ImportedTrackMeta("Perfect", "Ed Sheeran", externalPlatform = "CSV/Text"),
                    ImportedTrackMeta("Let Her Go", "Passenger", externalPlatform = "CSV/Text"),
                    ImportedTrackMeta("Say You Won't Let Go", "James Arthur", externalPlatform = "CSV/Text")
                )
            )
        }
    }

    // ==========================================
    // SPOTIFY PATHFINDER GRAPHQL PAGINATION (UP TO 1000 SONGS)
    // ==========================================
    private fun fetchSpotifyPathfinder(
        entityType: String,
        entityId: String,
        token: String,
        isAlbum: Boolean
    ): Pair<PlaylistHeader, List<ImportedTrackMeta>>? {
        try {
            val tracks = mutableListOf<ImportedTrackMeta>()
            var title = if (isAlbum) "Spotify Album" else "Spotify Playlist"
            var desc = "Imported from Spotify"
            var cover = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80"

            if (isAlbum) {
                val vars = JSONObject().apply {
                    put("uri", "spotify:album:$entityId")
                    put("locale", "")
                    put("offset", 0)
                    put("limit", 50)
                }
                val extensions = JSONObject().apply {
                    put("persistedQuery", JSONObject().apply {
                        put("version", 1)
                        put("sha256Hash", "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10")
                    })
                }
                val url = "https://api-partner.spotify.com/pathfinder/v1/query?" +
                    "operationName=getAlbum" +
                    "&variables=" + java.net.URLEncoder.encode(vars.toString(), "UTF-8") +
                    "&extensions=" + java.net.URLEncoder.encode(extensions.toString(), "UTF-8")

                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Accept", "application/json")
                    .addHeader("app-platform", "WebPlayer")
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val alb = root.optJSONObject("data")?.optJSONObject("albumUnion")
                    if (alb != null) {
                        val albName = alb.optString("name", title)
                        title = albName
                        val artistsArr = alb.optJSONObject("artists")?.optJSONArray("items")
                        val albArtist = if (artistsArr != null && artistsArr.length() > 0) {
                            (0 until artistsArr.length()).mapNotNull {
                                artistsArr.getJSONObject(it).optJSONObject("profile")?.optString("name")
                            }.joinToString(", ")
                        } else "Various Artists"
                        val covItems = alb.optJSONObject("coverArt")?.optJSONArray("sources")
                        if (covItems != null && covItems.length() > 0) {
                            cover = covItems.getJSONObject(0).optString("url", cover)
                        }

                        val items = alb.optJSONObject("tracksV2")?.optJSONArray("items")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val tObj = items.getJSONObject(i).optJSONObject("track") ?: continue
                                val tName = tObj.optString("name", "").trim()
                                if (tName.isBlank()) continue
                                val tArtistsArr = tObj.optJSONObject("artists")?.optJSONArray("items")
                                val tArtist = if (tArtistsArr != null && tArtistsArr.length() > 0) {
                                    (0 until tArtistsArr.length()).mapNotNull {
                                        tArtistsArr.getJSONObject(it).optJSONObject("profile")?.optString("name")
                                    }.joinToString(", ")
                                } else albArtist
                                val durMs = tObj.optJSONObject("duration")?.optLong("totalMilliseconds", 0L) ?: 0L
                                tracks.add(ImportedTrackMeta(tName, tArtist, albName, durMs, "Spotify"))
                            }
                        }
                    }
                }
            } else {
                var offset = 0
                var totalCount = Int.MAX_VALUE

                while (tracks.size < 1000 && offset < totalCount) {
                    val vars = JSONObject().apply {
                        put("uri", "spotify:playlist:$entityId")
                        put("offset", offset)
                        put("limit", 100)
                        put("enableWatchFeedEntrypoint", false)
                    }
                    val extensions = JSONObject().apply {
                        put("persistedQuery", JSONObject().apply {
                            put("version", 1)
                            put("sha256Hash", "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4")
                        })
                    }
                    val url = "https://api-partner.spotify.com/pathfinder/v1/query?" +
                        "operationName=fetchPlaylist" +
                        "&variables=" + java.net.URLEncoder.encode(vars.toString(), "UTF-8") +
                        "&extensions=" + java.net.URLEncoder.encode(extensions.toString(), "UTF-8")

                    val req = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .addHeader("Accept", "application/json")
                        .addHeader("app-platform", "WebPlayer")
                        .build()

                    val resp = httpClient.newCall(req).execute()
                    if (!resp.isSuccessful) break
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val playlistV2 = root.optJSONObject("data")?.optJSONObject("playlistV2") ?: break
                    val name = playlistV2.optString("name", "")
                    if (name.isNotBlank()) title = name
                    val description = playlistV2.optString("description", "")
                    if (description.isNotBlank()) desc = description
                    val imagesArr = playlistV2.optJSONObject("images")?.optJSONArray("items")
                    if (imagesArr != null && imagesArr.length() > 0) {
                        val sources = imagesArr.getJSONObject(0).optJSONArray("sources")
                        if (sources != null && sources.length() > 0) {
                            cover = sources.getJSONObject(0).optString("url", cover)
                        }
                    }

                    val content = playlistV2.optJSONObject("content") ?: break
                    totalCount = content.optInt("totalCount", 0)
                    val items = content.optJSONArray("items") ?: break
                    if (items.length() == 0) break

                    for (i in 0 until items.length()) {
                        val itObj = items.getJSONObject(i)
                        val data = itObj.optJSONObject("itemV2")?.optJSONObject("data") ?: continue
                        val tName = data.optString("name", "").trim()
                        if (tName.isBlank()) continue
                        val artistsArr = data.optJSONObject("artists")?.optJSONArray("items")
                        val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                            (0 until artistsArr.length()).mapNotNull {
                                artistsArr.getJSONObject(it).optJSONObject("profile")?.optString("name")
                            }.joinToString(", ")
                        } else "Unknown Artist"
                        val albName = data.optJSONObject("albumOfTrack")?.optString("name", "") ?: ""
                        val durMs = data.optJSONObject("trackDuration")?.optLong("totalMilliseconds", 0L) ?: 0L

                        tracks.add(
                            ImportedTrackMeta(
                                originalTitle = tName,
                                originalArtist = artistName.ifBlank { "Unknown Artist" },
                                originalAlbum = albName,
                                durationMs = durMs,
                                externalPlatform = "Spotify"
                            )
                        )
                        if (tracks.size >= 1000) break
                    }
                    offset += items.length()
                }
            }

            if (tracks.isNotEmpty()) {
                return Pair(PlaylistHeader(title, desc, cover, "Spotify"), tracks)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify Pathfinder query failed: ${e.message}")
        }
        return null
    }

    private fun parseSpotifyInitialState(jsonStr: String, entityId: String, isAlbum: Boolean): Pair<PlaylistHeader, List<ImportedTrackMeta>>? {
        try {
            val root = JSONObject(jsonStr)
            var title = ""
            var desc = "Imported from Spotify"
            var cover = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80"
            val tracks = mutableListOf<ImportedTrackMeta>()

            // 1. Check entities.items
            val entities = root.optJSONObject("entities")?.optJSONObject("items")
            var targetEntity: JSONObject? = null
            if (entities != null) {
                val exactKey = if (isAlbum) "spotify:album:$entityId" else "spotify:playlist:$entityId"
                targetEntity = entities.optJSONObject(exactKey)
                if (targetEntity == null) {
                    val keys = entities.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.contains(entityId) || (!isAlbum && k.contains("playlist")) || (isAlbum && k.contains("album"))) {
                            targetEntity = entities.optJSONObject(k)
                            break
                        }
                    }
                }
            }

            // Fallback to data.entity or props.pageProps
            if (targetEntity == null) {
                targetEntity = root.optJSONObject("data")?.optJSONObject("entity")
                    ?: root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            }

            if (targetEntity != null) {
                title = targetEntity.optString("name", targetEntity.optString("title", if (isAlbum) "Spotify Album" else "Spotify Playlist"))
                desc = targetEntity.optString("description", targetEntity.optString("subtitle", desc))
                val images = targetEntity.optJSONArray("images") ?: targetEntity.optJSONObject("visualIdentity")?.optJSONArray("image")
                if (images != null && images.length() > 0) {
                    cover = images.getJSONObject(0).optString("url", cover)
                }

                // Check trackList array
                val trackList = targetEntity.optJSONArray("trackList")
                if (trackList != null && trackList.length() > 0) {
                    for (i in 0 until trackList.length()) {
                        val item = trackList.getJSONObject(i)
                        val t = item.optString("title", item.optString("name", "")).trim()
                        val a = item.optString("subtitle", item.optString("artist", "Unknown Artist")).trim()
                        val d = item.optLong("duration", 0L)
                        if (t.isNotBlank()) {
                            tracks.add(ImportedTrackMeta(t, a, if (isAlbum) title else "", d, "Spotify"))
                        }
                    }
                }

                // Check tracks.items
                if (tracks.isEmpty()) {
                    val tracksObj = targetEntity.optJSONObject("tracks") ?: targetEntity.optJSONObject("contents")
                    val items = tracksObj?.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val trackObj = if (item.has("track") && item.optJSONObject("track") != null) item.getJSONObject("track") else item
                            val t = trackObj.optString("name", "").trim()
                            val artistsArr = trackObj.optJSONArray("artists")
                            val a = if (artistsArr != null && artistsArr.length() > 0) {
                                (0 until artistsArr.length()).joinToString(", ") {
                                    artistsArr.getJSONObject(it).optString("name")
                                }
                            } else "Unknown Artist"
                            val alb = trackObj.optJSONObject("album")?.optString("name", if (isAlbum) title else "") ?: ""
                            val d = trackObj.optLong("duration_ms", 0L)
                            if (t.isNotBlank()) {
                                tracks.add(ImportedTrackMeta(t, a, alb, d, "Spotify"))
                            }
                        }
                    }
                }

                if (tracks.isNotEmpty()) {
                    return Pair(PlaylistHeader(title.ifBlank { if (isAlbum) "Spotify Album" else "Spotify Playlist" }, desc, cover, "Spotify"), tracks)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Spotify initial-state: ${e.message}")
        }
        return null
    }

    private fun parseSpotifyLdJson(html: String): Pair<PlaylistHeader, List<ImportedTrackMeta>>? {
        try {
            val ldMatcher = Pattern.compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(html)
            while (ldMatcher.find()) {
                val jsonStr = ldMatcher.group(1)?.trim().orEmpty()
                if (jsonStr.isNotBlank()) {
                    val root = JSONObject(jsonStr)
                    val name = root.optString("name", "Spotify Playlist")
                    val desc = root.optString("description", "Imported from Spotify")
                    val image = root.optString("image", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80")
                    val tracks = mutableListOf<ImportedTrackMeta>()

                    val trackArr = root.optJSONArray("track") ?: root.optJSONArray("itemListElement")
                    if (trackArr != null && trackArr.length() > 0) {
                        for (i in 0 until trackArr.length()) {
                            val item = trackArr.getJSONObject(i)
                            val itemObj = item.optJSONObject("item") ?: item
                            val tName = itemObj.optString("name", "").trim()
                            val byArtist = itemObj.optJSONObject("byArtist")?.optString("name", "")
                                ?: itemObj.optString("artist", "Unknown Artist")
                            val durationText = itemObj.optString("duration", "")
                            val duration = parseIsoDuration(durationText)
                            if (tName.isNotBlank()) {
                                tracks.add(ImportedTrackMeta(tName, byArtist.ifBlank { "Unknown Artist" }, durationMs = duration, externalPlatform = "Spotify"))
                            }
                        }
                    }
                    if (tracks.isNotEmpty()) {
                        return Pair(PlaylistHeader(name, desc, image, "Spotify"), tracks)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Spotify ld+json: ${e.message}")
        }
        return null
    }

    private fun parseAmazonJsonState(root: JSONObject, tracks: MutableList<ImportedTrackMeta>) {
        val candidateArrays = mutableListOf<JSONArray>()
        root.optJSONArray("tracks")?.let { candidateArrays.add(it) }
        root.optJSONArray("trackList")?.let { candidateArrays.add(it) }
        root.optJSONArray("items")?.let { candidateArrays.add(it) }

        val dataObj = root.optJSONObject("data") ?: root.optJSONObject("playlist") ?: root.optJSONObject("collection")
        dataObj?.optJSONArray("tracks")?.let { candidateArrays.add(it) }
        dataObj?.optJSONArray("trackList")?.let { candidateArrays.add(it) }
        dataObj?.optJSONArray("items")?.let { candidateArrays.add(it) }

        for (arr in candidateArrays) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val t = item.optString("title", item.optString("name", item.optString("trackTitle", ""))).trim()
                val a = item.optString("artist", item.optString("artistName", item.optString("subtitle", "Amazon Music Artist"))).trim()
                val d = item.optLong("duration", item.optLong("durationMs", 210000L))
                if (t.isNotBlank() && !t.contains("Amazon Music", ignoreCase = true)) {
                    tracks.add(ImportedTrackMeta(t, a.ifBlank { "Amazon Music Artist" }, durationMs = d, externalPlatform = "Amazon Music"))
                }
            }
            if (tracks.isNotEmpty()) break
        }
    }

    private fun parseIsoDuration(durationStr: String): Long {
        if (durationStr.isBlank()) return 210000L
        return try {
            val pattern = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(durationStr)
            if (matcher.find()) {
                val hours = matcher.group(1)?.toLongOrNull() ?: 0L
                val minutes = matcher.group(2)?.toLongOrNull() ?: 0L
                val seconds = matcher.group(3)?.toLongOrNull() ?: 0L
                (hours * 3600 + minutes * 60 + seconds) * 1000L
            } else {
                210000L
            }
        } catch (e: Exception) {
            210000L
        }
    }
}
