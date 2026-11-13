package com.example.downloads

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogRepository
import com.example.data.repository.CatalogBrowseContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal fun savedDownloadRecords(records: Map<String, DownloadRecord>): List<DownloadRecord> = records.values
    .filter { it.status == DownloadStatus.COMPLETE && it.plan != null }
    .sortedWith(compareByDescending<DownloadRecord> { it.addedAt }.thenBy { it.track.id })

/** Direct playback validates every saved file and retains the Downloads page's order. */
internal suspend fun OfflineDownloads.playableSavedTracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
    load()
    savedDownloadRecords(records.value).mapNotNull { localTrack(it.track) }
}

/** A collection download includes the server's later pages, not just rows already displayed. */
internal suspend fun loadCollectionTracks(repository: CatalogRepository, destination: CatalogDestination): List<MusicTrack> {
    val tracks = mutableListOf<MusicTrack>()
    var page = 1
    var continuation: CatalogBrowseContinuation? = null
    val seen = mutableSetOf<String>()
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = when (destination) {
            is CatalogDestination.Album -> repository.albumSongs(destination.album, continuation)
            is CatalogDestination.Playlist -> repository.playlistSongs(destination.playlist, page, continuation)
            else -> throw IOException("Only albums and playlists can be downloaded as collections")
        }
        tracks += result.tracks
        val next = result.nextPage ?: break
        continuation = result.browseContinuation
        val key = continuation?.token ?: next.toString()
        if (!seen.add(key) || seen.size > 2000) throw IOException("The collection returned a repeated page. Try again.")
        page = next
    }
    if (tracks.isEmpty()) throw IOException("No tracks were returned for this collection")
    return tracks
}

/** Each track contributes one equal share; partial tracks retain their byte progress. */
internal data class CollectionDownloadProgress(
    val fraction: Float?, val completed: Int, val total: Int, val active: Boolean, val complete: Boolean
)

internal fun collectionDownloadProgress(
    tracks: List<MusicTrack>, records: Map<String, DownloadRecord>,
    savedMembers: List<String>? = null, collectionComplete: Boolean = true
): CollectionDownloadProgress = CollectionDownloadMembership(tracks, savedMembers, collectionComplete).progress(records)

/** Membership and URI classification stay unchanged during byte progress updates. */
internal class CollectionDownloadMembership(tracks: List<MusicTrack>, savedMembers: List<String>?, complete: Boolean) {
    private val local = tracks.asSequence().filter {
        val scheme = it.audioUrl.substringBefore(':').lowercase()
        scheme == "file" || scheme == "content" || scheme == OfflineDownloads.SCHEME
    }.map { it.id }.toSet()
    private val ids = (if (complete) tracks.map { it.id } else savedMembers.orEmpty() + tracks.map { it.id }).distinct()
    private val allKnown = complete || savedMembers != null

    fun progress(records: Map<String, DownloadRecord>): CollectionDownloadProgress {
        var completed = 0
        var active = false
        var portion = 0.0
        var unknownSize = false
        for (id in ids) {
            val record = records[id]
            val saved = id in local || record?.status == DownloadStatus.COMPLETE
            if (saved) completed++
            portion += if (saved) 1.0 else record?.fraction?.toDouble() ?: 0.0
            if (record?.status == DownloadStatus.QUEUED || record?.status == DownloadStatus.DOWNLOADING) {
                active = true
                if (record.total <= 0) unknownSize = true
            }
        }
        val fraction = if (!allKnown || (active && unknownSize && portion == 0.0)) null
            else if (ids.isEmpty()) 0f else (portion / ids.size).toFloat().coerceIn(0f, 1f)
        return CollectionDownloadProgress(fraction, completed, ids.size, active, allKnown && ids.isNotEmpty() && completed == ids.size)
    }
}
