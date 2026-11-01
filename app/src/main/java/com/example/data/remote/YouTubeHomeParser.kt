package com.example.data.remote

import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.data.model.ServerHomeItem
import com.example.data.repository.CatalogDestination
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Parses direct YouTube Music server responses for Home (FEmusic_home) and browse continuations.
 * Preserves server shelf titles, subtitles, track order, artwork, and genuine video IDs.
 */
object YouTubeHomeParser {

    data class ParsedHomeFeed(
        val shelves: List<ServerHomeShelf>,
        val continuationToken: String?,
        val isError: Boolean = false,
        val errorMessage: String? = null,
        val accountIdentity: String? = null
    )

    private fun string(value: JSONObject, key: String): String =
        value.optString(key).takeUnless { it == "null" }.orEmpty()

    private fun text(value: JSONObject?): String {
        if (value == null) return ""
        val runs = value.optJSONArray("runs")
        return if (runs != null) {
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.trim()
        } else {
            string(value, "simpleText").ifBlank { string(value, "content") }.trim()
        }
    }

    private fun extractContinuation(obj: JSONObject?): String? {
        if (obj == null) return null
        val continuations = obj.optJSONArray("continuations")
        if (continuations != null && continuations.length() > 0) {
            val first = continuations.optJSONObject(0)
            val data = first?.optJSONObject("nextContinuationData") ?: first?.optJSONObject("reloadContinuationData")
            val token = data?.optString("continuation").orEmpty()
            if (token.isNotBlank()) return token
        }
        val items = obj.optJSONArray("continuationItems") ?: obj.optJSONArray("contents")
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i)?.optJSONObject("continuationItemRenderer") ?: continue
                val ep = item.optJSONObject("continuationEndpoint")
                val cmd = ep?.optJSONObject("continuationCommand")
                    ?: ep?.optJSONObject("innertubeCommand")?.optJSONObject("continuationCommand")
                val token = cmd?.optString("token").orEmpty()
                if (token.isNotBlank()) return token
            }
        }
        return null
    }

    private fun parseItem(itemJson: JSONObject): MusicTrack? {
        for (key in listOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer")) {
            itemJson.optJSONObject(key)?.let { renderer ->
                if (renderer.optJSONObject("navigationEndpoint")?.has("browseEndpoint") == true ||
                    renderer.has("isPlayable") && !renderer.optBoolean("isPlayable")) return null
            }
        }
        // 1. Try musicResponsiveListItemRenderer
        val responsive = itemJson.optJSONObject("musicResponsiveListItemRenderer")
        if (responsive != null) {
            YouTubeMusicApiService.parseAnyRenderer(itemJson)?.let { return it }
        }

        // 2. Try musicTwoRowItemRenderer
        val twoRow = itemJson.optJSONObject("musicTwoRowItemRenderer") ?: itemJson
        if (twoRow.has("musicTwoRowItemRenderer")) {
            return parseItem(twoRow.optJSONObject("musicTwoRowItemRenderer") ?: return null)
        }

        val videoId = extractVideoId(twoRow)
        if (videoId != null && YouTubeAudioStreamService.isVideoId(videoId)) {
            val titleObj = twoRow.optJSONObject("title")
            val rawTitle = text(titleObj)
            if (rawTitle.isBlank() || rawTitle == "Private video" || rawTitle == "Deleted video") return null

            val subtitleObj = twoRow.optJSONObject("subtitle")
            val credits = extractCredits(subtitleObj)
            val rawArtist = credits.joinToString(", ").ifBlank { text(subtitleObj) }
                .takeWhile { it != '•' && it != '·' }.trim()
                .ifBlank { "YouTube Music" }

            val thumbUrl = YouTubeExploreParser.thumbnail(twoRow)
                .ifBlank { "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg" }

            return MusicTrack(
                id = "yt_$videoId",
                title = cleanTitle(rawTitle),
                artist = rawArtist,
                album = "YouTube Music",
                durationMs = 0L,
                coverUrl = thumbUrl,
                audioUrl = YouTubeAudioStreamService.playbackUri("yt_$videoId", ""),
                bitrateKbps = 0,
                qualityBadge = "HQ Stream",
                genre = "Unknown",
                source = "YouTube Music",
                primaryArtists = credits.ifEmpty { listOf(rawArtist) }
            )
        }

        // 3. Try fallback parseAnyRenderer
        return YouTubeMusicApiService.parseAnyRenderer(itemJson)
    }

    private fun extractVideoId(obj: JSONObject): String? {
        // watchEndpoint on navigationEndpoint
        obj.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf(String::isNotBlank)?.let { return it }
        // watchEndpoint on overlay -> musicItemThumbnailOverlayRenderer -> content -> musicPlayButtonRenderer -> playNavigationEndpoint
        obj.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            ?.optString("videoId")?.takeIf(String::isNotBlank)?.let { return it }
        // onTap -> watchEndpoint
        obj.optJSONObject("onTap")?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf(String::isNotBlank)?.let { return it }
        return null
    }

    private fun extractCredits(subtitleObj: JSONObject?): List<String> {
        if (subtitleObj == null) return emptyList()
        val runs = subtitleObj.optJSONArray("runs") ?: return emptyList()
        val artists = mutableListOf<String>()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val endpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val pageType = endpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
            val browseId = endpoint?.optString("browseId").orEmpty()
            val txt = run.optString("text").trim()
            if (txt.isNotBlank() && txt != "•" && txt != "·" && (browseId.startsWith("UC") || pageType == "MUSIC_PAGE_TYPE_ARTIST")) {
                artists.add(txt)
            }
        }
        return artists
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("(?i)\\s*\\(official (?:music )?video\\)"), "")
            .replace(Regex("(?i)\\s*\\[official (?:music )?video\\]"), "")
            .replace(Regex("(?i)\\s*\\(audio\\)"), "")
            .replace(Regex("(?i)\\s*\\[audio\\]"), "")
            .replace(Regex("(?i)\\s*\\(lyric video\\)"), "")
            .replace(Regex("(?i)\\s*\\[lyric video\\]"), "")
            .trim()
    }

    fun parseHomeFeed(rootJson: JSONObject, pageIdentity: String? = null,
        countryCode: String = "US", languageCode: String = "en"): ParsedHomeFeed {
        if (rootJson.has("error") || listOf("contents", "continuationContents", "onResponseReceivedActions",
                "onResponseReceivedCommands", "onResponseReceivedEndpoints").none(rootJson::has))
            throw IOException("Invalid Home response")
        val shelves = mutableListOf<ServerHomeShelf>()
        var sectionContinuation: String? = null

        // Navigate sectionListRenderer
        val contentsNode = rootJson.optJSONObject("contents")
        val tabs = (contentsNode?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?: contentsNode?.optJSONObject("twoColumnBrowseResultsRenderer"))
            ?.optJSONArray("tabs")
        val tabContent = tabs?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
        val sectionList = tabContent?.optJSONObject("sectionListRenderer")
            ?: contentsNode?.optJSONObject("sectionListRenderer")
            ?: rootJson.optJSONObject("continuationContents")?.optJSONObject("sectionListContinuation")
        val containers = mutableListOf<JSONObject>()
        sectionList?.let(containers::add)
        for (key in listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints")) {
            val actions = rootJson.optJSONArray(key) ?: continue
            for (index in 0 until actions.length()) actions.optJSONObject(index)?.optJSONObject("appendContinuationItemsAction")?.let(containers::add)
        }
        if (containers.isEmpty()) throw IOException("Unsupported Home response")
        val rawSections = JSONArray()
        for (container in containers) {
            extractContinuation(container)?.let { sectionContinuation = it }
            val rows = container.optJSONArray("contents") ?: container.optJSONArray("continuationItems") ?: continue
            for (index in 0 until rows.length()) rawSections.put(rows.opt(index))
        }

        if (rawSections != null) {
            for (i in 0 until rawSections.length()) {
                val section = rawSections.optJSONObject(i) ?: continue
                val shelfRenderer = section.optJSONObject("musicCarouselShelfRenderer")
                    ?: section.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("musicPlaylistShelfRenderer")
                    ?: section.optJSONObject("musicImmersiveCarouselShelfRenderer")

                if (shelfRenderer != null) {
                    val headerObj = shelfRenderer.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                        ?: shelfRenderer.optJSONObject("header")?.optJSONObject("musicShelfRenderer")
                        ?: shelfRenderer.optJSONObject("header")
                    val title = text(headerObj?.optJSONObject("title")).ifBlank { text(shelfRenderer.optJSONObject("title")) }
                    val subtitle = text(headerObj?.optJSONObject("strapline")).ifBlank { text(headerObj?.optJSONObject("subtitle")) }

                    val itemsArray = shelfRenderer.optJSONArray("contents")
                    val tracks = mutableListOf<MusicTrack>()
                    val items = mutableListOf<ServerHomeItem>()
                    val seen = hashSetOf<String>()
                    if (itemsArray != null) {
                        for (j in 0 until itemsArray.length()) {
                            val itemObj = itemsArray.optJSONObject(j) ?: continue
                            val renderer = itemObj.optJSONObject("musicTwoRowItemRenderer")
                                ?: itemObj.optJSONObject("musicResponsiveListItemRenderer")
                            val collection = renderer?.let { YouTubeExploreParser.collection(it, countryCode, languageCode) }
                            val item = when (collection) {
                                is CatalogDestination.Album -> ServerHomeItem.Album(collection.album, text(renderer?.optJSONObject("subtitle")))
                                is CatalogDestination.Playlist -> ServerHomeItem.Playlist(collection.playlist, text(renderer?.optJSONObject("subtitle")))
                                else -> parseItem(itemObj)?.let { ServerHomeItem.Song(it) }
                            }
                            if (item != null && seen.add(item.key)) {
                                items.add(item)
                                if (item is ServerHomeItem.Song) tracks.add(item.track)
                            }
                        }
                    }

                    if (items.isNotEmpty() && title.isNotBlank()) {
                        val shelfContinuation = extractContinuation(shelfRenderer)
                        val titleSlug = title.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").take(30)
                        val target = headerObj?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
                            ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        val nativeId = shelfRenderer.optString("targetId").ifBlank { shelfRenderer.optString("shelfId") }
                        val destination = target?.let { it.optString("browseId") + "|" + it.optString("params") }.orEmpty()
                        val page = if (nativeId.isBlank() && destination.isBlank()) pageIdentity.orEmpty() else ""
                        val descriptor = "$title|$nativeId|$destination|$page"
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(descriptor.toByteArray())
                            .take(12).joinToString("") { "%02x".format(it) }
                        val baseShelfId = "server_shelf_${titleSlug}_$hash"
                        var shelfId = baseShelfId
                        var collisionCounter = 1
                        while (shelves.any { it.id == shelfId }) {
                            shelfId = "${baseShelfId}_${collisionCounter++}"
                        }
                        shelves.add(
                            ServerHomeShelf(
                                id = shelfId,
                                title = title,
                                subtitle = subtitle,
                                tracks = tracks,
                                continuationToken = shelfContinuation,
                                items = items
                            )
                        )
                    }
                }
            }
        }

        return ParsedHomeFeed(shelves, sectionContinuation)
    }
}
