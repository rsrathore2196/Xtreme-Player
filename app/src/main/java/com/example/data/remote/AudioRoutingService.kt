package com.example.data.remote

import com.example.performance.AppDispatchers

import android.content.Context
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import com.example.recommendation.OriginalMusicFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class RoutedSearchResult(val tracks: List<MusicTrack>, val isLossless: Boolean)

/** One metadata-only search policy, independent of playback quality. Archive is upgrade-only. */
object AudioRoutingService {
    private val requests = Semaphore(2)
    data class SearchPage(
        val tracks: List<MusicTrack>, val standardNext: Int?, val youtubeNext: String?,
        val standardFailed: Boolean = false, val youtubeFailed: Boolean = false
    )

    suspend fun searchPage(query: String, standardPage: Int? = 1,
        youtubeContinuation: String? = null, fetchYouTube: Boolean = true, limit: Int = 10
    ): SearchPage = withContext(AppDispatchers.CpuBounded) {
        val cleaned = StreamResolver.extractCleanTitle(query).trim()
        if (cleaned.isBlank()) return@withContext SearchPage(emptyList(), null, null)
        supervisorScope {
            suspend fun <T> request(block: suspend () -> T): T? = try {
                withTimeoutOrNull(12_000L) { requests.withPermit { block() } }
            } catch (e: TimeoutCancellationException) { coroutineContext.ensureActive(); null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            val jio = standardPage?.let { page -> async {
                request { OnlineMusicApiService.searchSongPage(cleaned, limit = limit, page = page) }
            } }
            val youtube = if (fetchYouTube) async {
                request { YouTubeMusicApiService.searchSongsPage(cleaned, youtubeContinuation, useInnerTubeX = true) }
            } else null
            val standard = jio?.await(); val extended = youtube?.await()
            SearchPage(rankTracks(query, standard?.tracks.orEmpty() + extended?.tracks.orEmpty()),
                standard?.nextPage, extended?.continuation, jio != null && standard == null,
                youtube != null && extended == null)
        }
    }

    @Suppress("UNUSED_PARAMETER") // Existing callers need no quality-specific route.
    suspend fun resolveSearchTracks(context: Context?, query: String, limit: Int = 25): RoutedSearchResult {
        val page = searchPage(query, limit = limit)
        return RoutedSearchResult(page.tracks, isLossless = false)
    }

    private fun providerRank(track: MusicTrack): Int = when {
        track.id.startsWith("online_") -> 0
        track.id.startsWith("yt_") -> 1
        else -> 2
    }

    /** Replace an equivalent upload at its existing position; never replace a distinct song. */
    fun mergeTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        val identities = mutableListOf<RecordingIdentity.Signature>()
        for (track in tracks) {
            if (track.id.startsWith("ia_") || track.source.equals("INTERNET_ARCHIVE", true) ||
                track.id.startsWith("xtreme_") || track.audioUrl.contains("soundhelix", true)) continue
            val identity = RecordingIdentity.signature(track)
            val existing = result.indices.firstOrNull { result[it].id == track.id ||
                RecordingIdentity.same(identities[it], identity, requireAlbum = true) }
            if (existing == null) { result.add(track); identities.add(identity) }
            else if (providerRank(track) < providerRank(result[existing])) {
                result[existing] = track; identities[existing] = identity
            }
        }
        return result
    }

    fun rankTracks(query: String, tracks: List<MusicTrack>): List<MusicTrack> = mergeTracks(tracks)
        .mapNotNull { track ->
            val relevance = maxOf(CatalogNames.score(query, track.title, listOf(track.artist, track.album)),
                CatalogNames.score(StreamResolver.extractCleanTitle(query),
                    StreamResolver.extractCleanTitle(track.title, track.artist), listOf(track.artist, track.album)))
            if (relevance <= 0) null else track to (relevance * 10 + OriginalMusicFilter.searchPreferenceScore(track, query))
        }.sortedWith(compareByDescending<Pair<MusicTrack, Int>> { it.second }
            .thenBy { providerRank(it.first) }).map { it.first }
}
