package com.example.data.remote

import android.util.LruCache
import com.example.data.local.UserProfilePreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.CatalogNames
import com.example.data.repository.StreamResolver
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Public recording artwork only. Never changes the selected video or resolves an audio URL. */
internal class YouTubePlayerArtworkResolver(
    private val next: suspend (String) -> String,
    private val search: suspend (String) -> String,
    private val contextIdentity: () -> String = { "" },
    private val readSaved: suspend (String) -> String? = { null },
    private val save: suspend (String, String?) -> Unit = { _, _ -> }
) {
    private val cache = LruCache<String, Pair<Long, String?>>(128)
    private fun key(track: MusicTrack, context: String) = "song-cover-v2:" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(("$context|${playerCoverIdentity(track)}|${RecordingIdentity.isrc(track.isrc).orEmpty()}" +
            track.originalProviderTitle.takeIf(String::isNotBlank)?.let { "|$it" }.orEmpty()).toByteArray()).let { bytes -> buildString(64) {
            for (byte in bytes) { val n = byte.toInt() and 255; append("0123456789abcdef"[n ushr 4]); append("0123456789abcdef"[n and 15]) }
        } }
    fun peek(track: MusicTrack): String? = cache.get(key(track, contextIdentity()))
        ?.takeIf { android.os.SystemClock.elapsedRealtime() < it.first }?.second

    suspend fun resolve(track: MusicTrack, nativeSongCover: String = ""): String? = withContext(AppDispatchers.Io) {
        if (!needsSongCover(track)) return@withContext null
        val context = contextIdentity()
        val key = key(track, context)
        val result = com.example.recommendation.DiscoveryRequestCoordinator.shared(
            "song-cover:${System.identityHashCode(this@YouTubePlayerArtworkResolver)}:$key",
            essential = false, network = false, playbackMetadata = true, awaitFrameQuiet = false) {
          suspend fun find(): String? {
            // Native audio metadata is stronger than an earlier incomplete lookup's negative cache.
            val supplied = nativeSongCover.takeIf(::validSongImageUrl)
            if (supplied != null) { cache.put(key, (android.os.SystemClock.elapsedRealtime() + 86_400_000L) to supplied); save(key, supplied); return supplied }
            cache.get(key)?.takeIf { android.os.SystemClock.elapsedRealtime() < it.first }
                ?.let { return it.second }
            val persisted = try { readSaved(key) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (persisted != null && (persisted.isEmpty() || validSongImageUrl(persisted))) {
                val cover = persisted.takeIf { validSongImageUrl(it) }
                cache.put(key, (android.os.SystemClock.elapsedRealtime() + if (cover == null) 60_000L else 86_400_000L) to cover)
                return cover
            }
            val videoId = YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl) ?: return null
            val linked = attempt {
                val body = next(videoId)
                withContext(AppDispatchers.CpuBounded) { counterpartCover(JSONObject(body), videoId, track) }
            }
            val identityTrack = withContext(AppDispatchers.CpuBounded) { artworkIdentityVariants(track).first() }
            val cover = linked ?: if (RecordingIdentity.artistNames(track.copy(title = "")).isEmpty()) null else {
                val query = withContext(AppDispatchers.CpuBounded) {
                    "${identityTrack.title} ${RecordingIdentity.artistNames(identityTrack).first()}".trim()
                }
                attempt {
                    val body = search(query)
                    withContext(AppDispatchers.CpuBounded) { matchingSongCover(JSONObject(body), track) }
                }
            }
            currentCoroutineContext().ensureActive()
            // Failures are brief, not permanent missing covers. Successful metadata is reusable
            // across replay/foreground changes; Coil still owns image memory/disk caching.
            if (context != contextIdentity()) return null
            cache.put(key, (android.os.SystemClock.elapsedRealtime() + if (cover == null) 60_000L else 24 * 60 * 60_000L) to cover)
            save(key, cover)
            android.util.Log.d("SongArtwork", "resolved linked=${linked != null} found=${cover != null}")
            return cover
          }
          find()?.let { listOf(track.copy(coverUrl = it)) }.orEmpty()
        }
        result.firstOrNull()?.coverUrl?.takeIf { context == contextIdentity() }
    }

    suspend fun reject(track: MusicTrack, url: String) = withContext(AppDispatchers.Io) {
        val key = key(track, contextIdentity())
        if (cache.get(key)?.second == url) {
            cache.put(key, (android.os.SystemClock.elapsedRealtime() + 60_000L) to null)
            save(key, null)
        }
    }

    private suspend fun attempt(block: suspend () -> String?): String? = try {
        block()
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

        internal fun validSongImageUrl(url: String): Boolean {
            val uri = try { URI(url) } catch (_: Exception) { return false }
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            return uri.scheme == "https" && (host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com"))
        }
        internal fun square(value: JSONObject): String? {
            var selected: String? = null
            var largest = 0
            first(value.optJSONObject("thumbnailRenderer") ?: value.optJSONObject("thumbnail") ?: value.optJSONObject("image")) { node ->
                val url = node.optString("url")
                if (!validSongImageUrl(url)) return@first null
                val w = Regex("(?:=|-)w(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                val h = Regex("(?:=|-)h(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                val declaredW = node.optInt("width"); val declaredH = node.optInt("height")
                val size = if (declaredW > 0 || declaredH > 0) declaredW.takeIf { it > 0 && it == declaredH }
                    else w?.takeIf { it > 0 && it == h }
                        ?: Regex("=s([1-9][0-9]*)(?:-|$)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                if (size != null && size > largest) { largest = size; selected = url }
                null
            }
            return selected
        }
        /** The requested upload can be either side of a native song/video pair. */
        fun counterpartCover(root: JSONObject, videoId: String, requested: MusicTrack? = null): String? {
            fun type(renderer: JSONObject) = renderer.optString("musicVideoType").ifBlank { renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType").orEmpty() }
            fun cover(song: JSONObject, pairedAudio: Boolean = false): String? = if (song.has("unplayableText") ||
                !YouTubeAudioStreamService.isVideoId(song.optString("videoId")) ||
                (type(song) != "MUSIC_VIDEO_TYPE_ATV" && !(pairedAudio && type(song).isBlank()))) null else {
                val candidate = YouTubeMusicApiService.parseAnyRenderer(JSONObject().put("playlistPanelVideoRenderer", song))
                val verified = candidate?.copy(isrc = song.optString("isrc").ifBlank { candidate.isrc })
                if (requested != null && verified != null && !compatibleOfficialPair(requested, verified)) null else square(song)
            }
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
                val requestedIsVideo = pair.any { it.optString("videoId") == videoId &&
                    type(it) in setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC") }
                pair.firstNotNullOfOrNull { cover(it, requestedIsVideo && it.optString("videoId") != videoId) }
            }
        }
        /** Songs-filtered native results still need recording identity and a genuine square image. */
        fun matchingSongCover(root: JSONObject, track: MusicTrack): String? =
            if (RecordingIdentity.artistNames(track.copy(title = "")).isEmpty()) null else first(root) { node ->
            val renderer = node.optJSONObject("musicResponsiveListItemRenderer") ?: return@first null
            val cover = square(renderer) ?: return@first null
            val type = renderer.optString("musicVideoType").ifBlank { renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType").orEmpty() }
            if (type.isNotBlank() && type != "MUSIC_VIDEO_TYPE_ATV") return@first null
            val parsed = YouTubeMusicApiService.parseAnyRenderer(node) ?: return@first null
            val candidate = parsed.copy(isrc = renderer.optString("isrc").ifBlank { parsed.isrc })
            // Official videos can include an intro/outro absent from the audio
            // recording. For artwork only, retain exact title/artist/version/ISRC
            // checks without requiring the two edits to have the same duration.
            // Dimensions cannot establish whether the requested upload is audio
            // or a square-cropped video. Artwork matching uses exact recording
            // credits/version/release, without video intro/outro timing.
            if (sameRecording(track, candidate, ignoreDuration = true)) cover else null
        }

        private fun sameRecording(track: MusicTrack, candidate: MusicTrack, ignoreDuration: Boolean): Boolean {
            return artworkIdentityVariants(track).any { requested -> artworkIdentityVariants(candidate).any { found ->
                val first = if (ignoreDuration) requested.copy(durationMs = 0) else requested
                val second = if (ignoreDuration) found.copy(durationMs = 0) else found
                RecordingIdentity.providerConfidence(first, second) != null &&
                    RecordingIdentity.same(first, second, durationToleranceMs = (track.durationMs / 40).coerceIn(3_000L, 8_000L), requireAlbum = true)
            } }
        }

        private fun compatibleOfficialPair(track: MusicTrack, candidate: MusicTrack): Boolean {
            // Only a native pair bound to the requested video may supply missing performer credits.
            val credits = RecordingIdentity.artistNames(candidate)
            return artworkIdentityVariants(track, credits).any { requested -> artworkIdentityVariants(candidate).any { found ->
                val a = RecordingIdentity.signature(requested); val b = RecordingIdentity.signature(found)
                if (a.title != b.title || a.versions != b.versions || a.isrc != null && b.isrc != null && a.isrc != b.isrc) false
                else if (a.artists.isEmpty() || b.artists.isEmpty()) a.album == null || b.album == null || a.album == b.album
                else RecordingIdentity.same(requested.copy(durationMs = 0), found.copy(durationMs = 0), requireAlbum = true)
            } }
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

/** Artwork-only evidence. Provider/audio matching elsewhere keeps its existing rules. */
private fun artworkIdentityVariants(track: MusicTrack, pairedCredits: List<String> = emptyList()): List<MusicTrack> {
    val fullTitle = track.originalProviderTitle.ifBlank { track.title }
    val credits = if (RecordingIdentity.artistNames(track.copy(title = "")).isEmpty()) pairedCredits
        else RecordingIdentity.artistNames(track.copy(title = fullTitle))
    val full = track.copy(title = StreamResolver.extractCleanTitle(fullTitle, credits.joinToString(", ")),
        primaryArtists = credits)
    if (track.originalProviderTitle.isBlank()) return listOf(full)
    val display = full.copy(title = StreamResolver.extractCleanTitle(track.title, credits.joinToString(", ")))
    val original = RecordingIdentity.signature(full)
    val formatted = RecordingIdentity.signature(display)
    // A shortened display form is additional evidence only if it belongs to the
    // full title and has not discarded any recording-version distinction.
    val normalizedFull = CatalogNames.normalize(full.title)
    return if (formatted.title.isNotBlank() && (normalizedFull == formatted.title || normalizedFull.startsWith("${formatted.title} ")) &&
        formatted.versions == original.versions) listOf(full, display).distinctBy { it.title } else listOf(full)
}

internal fun playerCoverIdentity(track: MusicTrack): String {
    val recording = RecordingIdentity.signature(artworkIdentityVariants(track).first())
    return "${track.id}|${recording.title}|${recording.artists.joinToString()}|${recording.versions.sorted().joinToString()}"
}
internal fun playerCoverLookupIdentity(track: MusicTrack) = "${playerCoverIdentity(track)}|${track.durationMs}|${track.album}|${track.coverUrl}|${track.isrc}|${track.originalProviderTitle}"

internal fun songArtworkContext(): String = listOf(YouTubeMusicApiService.discoveryAccountIdentity(),
    com.example.data.cache.HomeCacheIdentity.region(UserProfilePreferences.peekUserProfile() ?: com.example.data.local.UserProfile()),
    java.util.Locale.getDefault().toLanguageTag(), UserProfilePreferences.peekUserProfile()?.languages.orEmpty().map { it.trim().lowercase(java.util.Locale.ROOT) }.sorted().joinToString()).joinToString("|")

private suspend fun songCoverRequest(operation: String, value: String): String {
    val region = com.example.data.cache.HomeCacheIdentity.region(UserProfilePreferences.peekUserProfile() ?: com.example.data.local.UserProfile())
    val language = java.util.Locale.getDefault().toLanguageTag()
    return try { com.example.recommendation.DiscoveryRequestCoordinator.network {
        withTimeoutOrNull(4_000L) { YouTubeAudioStreamService.discoveryResponse(operation, value, region, languageCode = language,
            includeAccount = YouTubeMusicApiService.hasAuthenticatedAccount()) } ?: throw java.io.IOException("Song artwork request timed out")
    } } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        if (!YouTubeMusicApiService.hasAuthenticatedAccount()) throw error
        com.example.recommendation.DiscoveryRequestCoordinator.network {
            withTimeoutOrNull(4_000L) { YouTubeAudioStreamService.discoveryResponse(operation, value, region, languageCode = language, includeAccount = false) }
                ?: throw java.io.IOException("Song artwork fallback timed out")
        }
    }
}

private val playerCoverResolver = YouTubePlayerArtworkResolver(
    next = { id -> songCoverRequest("related", id) },
    search = { query -> songCoverRequest("search", query) },
    contextIdentity = ::songArtworkContext,
    readSaved = { key ->
        val entry = com.example.data.cache.RecommendationCache.get(com.example.XtremeMusicApp.getInstance()).get(key)
        entry?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let {
            withContext(AppDispatchers.CpuBounded) { runCatching { JSONObject(it.body).takeIf { it.optInt("version") == 2 }?.optString("url") }.getOrNull() }
        }
    },
    save = { key, url -> com.example.data.cache.RecommendationCache.get(com.example.XtremeMusicApp.getInstance())
        .putBody(key, JSONObject().put("version", 2).put("url", url.orEmpty()).toString(), if (url == null) 60_000L else 86_400_000L) }
)
internal suspend fun resolveYouTubePlayerCover(track: MusicTrack): String? = playerCoverResolver.resolve(track)
internal suspend fun resolveNativeSongCover(track: MusicTrack, nativeCover: String): String? = playerCoverResolver.resolve(track, nativeCover)
internal fun cachedNativeSongCover(track: MusicTrack): String? = playerCoverResolver.peek(track)
internal suspend fun rejectSongCover(track: MusicTrack, url: String) = playerCoverResolver.reject(track, url)
