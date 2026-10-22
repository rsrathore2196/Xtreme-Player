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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class RoutedSearchResult(val tracks: List<MusicTrack>, val isLossless: Boolean)

/** YouTube-first song catalog with a provider-failure fallback. The browser adds policy-gated, verified Archive files independently. */
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
            // Song discovery uses the original YouTube catalog. Only an API
            // failure falls back to JioSaavn; a normal page never waits on or
            // verifies against a second catalog.
            val extended = if (fetchYouTube) request {
                withContext(AppDispatchers.Io) {
                    YouTubeMusicApiService.searchSongsPage(cleaned, youtubeContinuation, useInnerTubeX = true)
                }
            } else null
            val useStandard = extended == null && (!fetchYouTube || youtubeContinuation == null)
            val standard = if (useStandard && standardPage != null) request {
                withContext(AppDispatchers.Io) { OnlineMusicApiService.searchSongPage(cleaned, limit = limit, page = standardPage) }
            } else null
            SearchPage(rankTracks(query, extended?.tracks ?: standard?.tracks.orEmpty()),
                standard?.nextPage, extended?.continuation, useStandard && standard == null,
                fetchYouTube && extended == null)
        }
    }

    @Suppress("UNUSED_PARAMETER") // Existing callers need no quality-specific route.
    suspend fun resolveSearchTracks(context: Context?, query: String, limit: Int = 25): RoutedSearchResult {
        val page = searchPage(query, limit = limit)
        return RoutedSearchResult(page.tracks, isLossless = false)
    }

    private fun providerRank(track: MusicTrack): Int = when {
        isArchiveResult(track) -> 2
        track.id.startsWith("yt_") -> 0
        track.id.startsWith("online_") -> 1
        else -> 2
    }

    internal fun isArchiveResult(track: MusicTrack): Boolean = track.id.startsWith("ia_") ||
        track.source.equals("INTERNET_ARCHIVE", true) || android.net.Uri.parse(track.audioUrl).host.orEmpty().let {
            it == "archive.org" || it.endsWith(".archive.org")
        }

    /** Replace an equivalent upload at its existing position; never replace a distinct song. */
    fun mergeTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        val identities = mutableListOf<RecordingIdentity.Signature>()
        for (track in tracks) {
            if (isArchiveResult(track) ||
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

    fun rankTracks(query: String, tracks: List<MusicTrack>, includeArchive: Boolean = false,
        deduplicateRecordings: Boolean = true): List<MusicTrack> =
        (if (deduplicateRecordings) mergeTracks(tracks) +
            if (includeArchive) tracks.filter(::isArchiveResult).distinctBy { it.id } else emptyList()
        else tracks.filter { includeArchive || !isArchiveResult(it) }.distinctBy { it.id })
        .mapNotNull { track ->
            val relevance = maxOf(CatalogNames.score(query, track.title, listOf(track.artist, track.album)),
                CatalogNames.score(StreamResolver.extractCleanTitle(query),
                    StreamResolver.extractCleanTitle(track.title, track.artist), listOf(track.artist, track.album)))
            if (relevance <= 0) null else track to (relevance * 10 + OriginalMusicFilter.searchPreferenceScore(track, query))
        }.sortedWith(compareByDescending<Pair<MusicTrack, Int>> { it.second }
            .thenBy { providerRank(it.first) }).map { it.first }
}
