package com.example.downloads

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogRepository
import com.example.data.repository.CatalogBrowseContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

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
): CollectionDownloadProgress {
    val local = tracks.filter { it.audioUrl.substringBefore(':').lowercase() in setOf("file", "content", OfflineDownloads.SCHEME) }
        .mapTo(mutableSetOf()) { it.id }
    val ids = (if (collectionComplete) tracks.map { it.id } else savedMembers.orEmpty() + tracks.map { it.id }).distinct()
    val allKnown = collectionComplete || savedMembers != null
    val completed = ids.count { it in local || records[it]?.status == DownloadStatus.COMPLETE }
    val active = ids.any { records[it]?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) }
    val portion = ids.sumOf { id ->
        if (id in local || records[id]?.status == DownloadStatus.COMPLETE) 1.0 else records[id]?.fraction?.toDouble() ?: 0.0
    }
    val unknownSize = ids.any { records[it]?.let { record ->
        record.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) && record.total <= 0
    } == true }
    val fraction = if (!allKnown || (active && unknownSize && portion == 0.0)) null
        else if (ids.isEmpty()) 0f else (portion / ids.size).toFloat().coerceIn(0f, 1f)
    return CollectionDownloadProgress(fraction, completed, ids.size, active, allKnown && ids.isNotEmpty() && completed == ids.size)
}
