package com.example.data.remote

import android.util.LruCache
import com.example.data.local.UserProfilePreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Public recording artwork only. Never changes the selected video or resolves an audio URL. */
internal class YouTubePlayerArtworkResolver(
    private val next: suspend (String) -> String,
    private val search: suspend (String) -> String
) {
    private val cache = LruCache<String, Pair<Long, String?>>(128)
    private val locks = Array(4) { Mutex() }

    suspend fun resolve(track: MusicTrack): String? = withContext(AppDispatchers.Io) {
        if (!needsSongCover(track)) return@withContext null
        val key = playerCoverLookupIdentity(track)
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cache.get(key)?.takeIf { android.os.SystemClock.elapsedRealtime() < it.first }
                ?.let { return@withLock it.second }
            val videoId = YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl) ?: return@withLock null
            val linked = attempt {
                val body = next(videoId)
                withContext(AppDispatchers.CpuBounded) { counterpartCover(JSONObject(body), videoId) }
            }
            val cover = linked ?: if (RecordingIdentity.artistNames(track).isEmpty()) null else {
                val query = withContext(AppDispatchers.CpuBounded) {
                    "${StreamResolver.extractCleanTitle(track.title, track.artist)} ${RecordingIdentity.artistNames(track).first()}".trim()
                }
                attempt {
                    val body = search(query)
                    withContext(AppDispatchers.CpuBounded) { matchingSongCover(JSONObject(body), track) }
                }
            }
            currentCoroutineContext().ensureActive()
            // Failures are brief, not permanent missing covers. Successful metadata is reusable
            // across replay/foreground changes; Coil still owns image memory/disk caching.
            cache.put(key, (android.os.SystemClock.elapsedRealtime() + if (cover == null) 60_000L else 24 * 60 * 60_000L) to cover)
            cover
        }
    }

    private suspend fun attempt(block: suspend () -> String?): String? = try {
        withTimeoutOrNull(4_000L) { block() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    companion object {
        fun needsSongCover(track: MusicTrack): Boolean {
            if (YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl) == null) return false
            if (track.coverUrl.isBlank()) return true
            val uri = try { URI(track.coverUrl) } catch (_: Exception) { return false }
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            if (host == "img.youtube.com" || host.endsWith(".ytimg.com")) return true
            // A square rendition can still be a cropped video frame. Native
            // song/video metadata establishes which cover belongs in the player.
            return host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")
        }

        private fun square(value: JSONObject): String? {
            val declared = YouTubeExploreParser.thumbnail(value, squareOnly = true)
                .takeIf { it.isNotBlank() && !isVideoImage(it) }
            if (declared != null) return declared
            // Some native song thumbnails omit dimensions but retain an explicit
            // square Google rendition. Never infer square art from a video URL.
            return first(value.optJSONObject("thumbnailRenderer") ?: value.optJSONObject("thumbnail") ?: value.optJSONObject("image")) { node ->
                val url = node.optString("url")
                val host = try { URI(url).host.orEmpty().lowercase(java.util.Locale.ROOT) } catch (_: Exception) { "" }
                if (!(host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")) ||
                    !url.startsWith("https://") || node.optInt("width") > 0 || node.optInt("height") > 0) return@first null
                val w = Regex("(?:=|-)w(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                val h = Regex("(?:=|-)h(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                url.takeIf { w != null && w > 0 && w == h || Regex("=s[1-9][0-9]*(?:-|$)").containsMatchIn(url) }
            }
        }
        private fun isVideoImage(url: String): Boolean {
            val host = try { URI(url).host.orEmpty() } catch (_: Exception) { return true }
            return host == "img.youtube.com" || host.endsWith(".ytimg.com")
        }
        /** The requested upload can be either side of a native song/video pair. */
        fun counterpartCover(root: JSONObject, videoId: String): String? {
            fun type(renderer: JSONObject) = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType").orEmpty()
            fun cover(song: JSONObject): String? = if (song.has("unplayableText") ||
                !YouTubeAudioStreamService.isVideoId(song.optString("videoId")) ||
                type(song).let { it.isNotBlank() && it != "MUSIC_VIDEO_TYPE_ATV" }) null else square(song)
            return first(root) { node ->
                val wrapper = node.optJSONObject("playlistPanelVideoWrapperRenderer")
                if (wrapper == null) {
                    val song = node.optJSONObject("playlistPanelVideoRenderer") ?: return@first null
                    return@first if (song.optString("videoId") == videoId && type(song) == "MUSIC_VIDEO_TYPE_ATV") cover(song) else null
                }
                val primary = wrapper.optJSONObject("primaryRenderer")?.optJSONObject("playlistPanelVideoRenderer")
                    ?: return@first null
                val counterparts = wrapper.optJSONArray("counterpart")
                val pair = listOf(primary) + (0 until (counterparts?.length() ?: 0)).mapNotNull { index ->
                    counterparts?.optJSONObject(index)?.optJSONObject("counterpartRenderer")?.optJSONObject("playlistPanelVideoRenderer")
                }
                if (pair.none { it.optString("videoId") == videoId }) return@first null
                val covers = pair.mapNotNull { renderer -> cover(renderer)?.let { it to type(renderer) } }
                covers.firstOrNull { it.second == "MUSIC_VIDEO_TYPE_ATV" }?.first
                    // If both sides have untyped square crops, use the songs lookup
                    // rather than guess which image is the original cover.
                    ?: covers.singleOrNull()?.first
            }
        }
        /** Songs-filtered native results still need recording identity and a genuine square image. */
        fun matchingSongCover(root: JSONObject, track: MusicTrack): String? = first(root) { node ->
            val renderer = node.optJSONObject("musicResponsiveListItemRenderer") ?: return@first null
            val cover = square(renderer) ?: return@first null
            val type = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType").orEmpty()
            if (type.isNotBlank() && type != "MUSIC_VIDEO_TYPE_ATV") return@first null
            val candidate = YouTubeMusicApiService.parseAnyRenderer(node) ?: return@first null
            // Official videos can include an intro/outro absent from the audio
            // recording. For artwork only, retain exact title/artist/version/ISRC
            // checks without requiring the two edits to have the same duration.
            val videoFrame = isVideoImage(track.coverUrl) || run {
                val w = Regex("(?:=|-)w(\\d+)").find(track.coverUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val h = Regex("(?:=|-)h(\\d+)").find(track.coverUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                h > 0 && w > h
            }
            if (RecordingIdentity.providerConfidence(track, candidate) != null || videoFrame &&
                RecordingIdentity.providerConfidence(track.copy(durationMs = 0), candidate.copy(durationMs = 0)) != null) cover else null
        }

        private fun first(root: Any?, read: (JSONObject) -> String?): String? {
            var visited = 0
            fun walk(node: Any?, depth: Int): String? {
                if (depth > 24 || ++visited > 20_000) return null
                return when (node) {
                    is JSONObject -> read(node) ?: node.keys().asSequence().firstNotNullOfOrNull { walk(node.opt(it), depth + 1) }
                    is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { walk(node.opt(it), depth + 1) }
                    else -> null
                }
            }
            return walk(root, 0)
        }
    }
}

internal fun playerCoverIdentity(track: MusicTrack) = "${track.id}|${track.title}|${track.artist}|${track.album}|${track.coverUrl}"
internal fun playerCoverLookupIdentity(track: MusicTrack) = "${playerCoverIdentity(track)}|${track.durationMs}|${track.primaryArtists.joinToString()}"

private val playerCoverResolver = YouTubePlayerArtworkResolver(
    next = { id -> YouTubeAudioStreamService.discoveryResponse("related", id,
        UserProfilePreferences.peekUserProfile()?.countryCode ?: "US") },
    search = { query -> YouTubeAudioStreamService.discoveryResponse("search", query,
        UserProfilePreferences.peekUserProfile()?.countryCode ?: "US") }
)
internal suspend fun resolveYouTubePlayerCover(track: MusicTrack): String? = playerCoverResolver.resolve(track)
