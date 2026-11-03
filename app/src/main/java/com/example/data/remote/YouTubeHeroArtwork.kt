package com.example.data.remote

import android.util.LruCache
import com.example.data.local.UserProfilePreferences
import com.example.data.model.MusicTrack
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

/** Hero artwork is a separate presentation source; the song's square cover stays intact. */
internal class YouTubeHeroArtworkResolver(
    private val next: suspend (String) -> String,
    private val seed: suspend (MusicTrack) -> String?
) {
    private val cache = LruCache<String, Pair<Long, String?>>(48)
    private val locks = Array(4) { Mutex() }

    suspend fun resolve(track: MusicTrack): String? = withContext(AppDispatchers.Io) {
        val key = heroArtworkIdentity(track)
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cache.get(key)?.takeIf { android.os.SystemClock.elapsedRealtime() < it.first }
                ?.let { return@withLock it.second }
            val supplied = frameId(track.coverUrl)
            val id = YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl)
                ?: supplied ?: attempt { seed(track) }?.takeIf(YouTubeAudioStreamService::isVideoId)
            val image = id?.let { video ->
                attempt {
                    val body = next(video)
                    withContext(AppDispatchers.CpuBounded) {
                        val root = JSONObject(body)
                        landscapeCover(root, video) ?: YouTubePlayerArtworkResolver.counterpartCover(root, video)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            cache.put(key, (android.os.SystemClock.elapsedRealtime() + if (image == null) 60_000L else 24 * 60 * 60_000L) to image)
            image
        }
    }

    private suspend fun attempt(block: suspend () -> String?): String? = try {
        withTimeoutOrNull(4_000L) { block() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    companion object {
        private fun frameId(url: String): String? {
            val uri = try { URI(url) } catch (_: Exception) { return null }
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            if (host != "img.youtube.com" && !host.endsWith(".ytimg.com")) return null
            return Regex("/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/").find(uri.path.orEmpty())?.groupValues?.get(1)
        }
        // Audio-only uploads have video IDs too. Their generated widescreen frames
        // often contain a square cover with baked-in blurred sides. Use the supplied
        // cover until native metadata supplies a real landscape music-video image.
        fun initial(track: MusicTrack): String? = track.coverUrl.takeIf(String::isNotBlank)

        /** A linked native music video may illustrate an audio track; unrelated queue items cannot. */
        fun landscapeCover(root: JSONObject, videoId: String): String? {
            fun cover(renderer: JSONObject): String? {
                val type = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                    ?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                    ?.optString("musicVideoType").orEmpty()
                if (renderer.has("unplayableText") ||
                    type !in setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC") ||
                    !YouTubeAudioStreamService.isVideoId(renderer.optString("videoId"))) return null
                return YouTubeExploreParser.thumbnail(renderer, landscapeOnly = true).takeIf(String::isNotBlank)
            }
            var visited = 0
            fun walk(node: Any?, depth: Int): String? {
                if (depth > 24 || ++visited > 20_000) return null
                when (node) {
                    is JSONObject -> {
                        node.optJSONObject("playlistPanelVideoWrapperRenderer")?.let { wrapper ->
                            val primary = wrapper.optJSONObject("primaryRenderer")?.optJSONObject("playlistPanelVideoRenderer")
                            val counterparts = wrapper.optJSONArray("counterpart")
                            val pair = listOfNotNull(primary) + (0 until (counterparts?.length() ?: 0)).mapNotNull { index ->
                                counterparts?.optJSONObject(index)?.optJSONObject("counterpartRenderer")?.optJSONObject("playlistPanelVideoRenderer")
                            }
                            if (pair.any { it.optString("videoId") == videoId }) {
                                pair.firstNotNullOfOrNull(::cover)?.let { return it }
                            }
                            return null
                        }
                        node.optJSONObject("playlistPanelVideoRenderer")?.takeIf { it.optString("videoId") == videoId }
                            ?.let(::cover)?.let { return it }
                        node.keys().forEach { walk(node.opt(it), depth + 1)?.let { found -> return found } }
                    }
                    is JSONArray -> for (index in 0 until node.length()) walk(node.opt(index), depth + 1)?.let { return it }
                }
                return null
            }
            return walk(root, 0)
        }
    }
}

internal fun heroArtworkIdentity(track: MusicTrack) = "${track.id}|${track.title}|${track.artist}|${track.album}"
private val heroArtworkResolver = YouTubeHeroArtworkResolver(
    next = { id -> YouTubeAudioStreamService.discoveryResponse("related", id,
        UserProfilePreferences.peekUserProfile()?.countryCode ?: "US") },
    seed = { YouTubeMusicApiService.resolveSeedVideoId(it) }
)
internal suspend fun resolveYouTubeHeroArtwork(track: MusicTrack): String? = heroArtworkResolver.resolve(track)
