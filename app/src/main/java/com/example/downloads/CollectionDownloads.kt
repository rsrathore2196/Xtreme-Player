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
