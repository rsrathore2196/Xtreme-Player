package com.example

import com.example.data.model.MusicTrack
import com.example.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GenreProgressiveLoadingTest {
    private val tracks = listOf(MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180000, "", ""))
    private val albums = listOf(CatalogAlbum("album", "Album", emptyList(), "", "2026"))
    @Test fun songsPublishBeforeAlbumsAndTheSameSnapshotSuppliesTheirFallback() = runTest {
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<SearchResultCategory>()
        var loads = 0
        val result = async { loadGenreResults("Pop", { loads++; tracks }, { supplied ->
            assertSame(tracks, supplied); release.await(); albums
        }, { published.complete(it) }) }
        assertSame(tracks, published.await().songs)
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(albums, result.await().catalogAlbums)
        assertEquals(1, loads)
    }
    @Test fun albumFailureRetainsPlayableSongsAndTheirExistingAlbumMetadata() = runTest {
        val result = loadGenreResults("Pop", { tracks }, { throw java.io.IOException("Unavailable") }, {})
        assertSame(tracks, result.songs)
        assertEquals(listOf("Album"), result.albums)
    }
    @Test fun cancellationCannotPublishAnObsoleteSongsSnapshot() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var published = false
        val job = launch { loadGenreResults("Pop", {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            tracks
        }, { albums }, { published = true }) }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(published)
    }
}
