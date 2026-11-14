package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.AudioRoutingService
import com.example.data.repository.CatalogPage
import com.example.data.repository.CatalogRepository
import com.example.data.repository.TrackSearchContinuation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrackSearchPaginationTest {
    private fun track(index: Int) = MusicTrack("yt_${index.toString().padStart(11, 'a')}", "Song $index (8D Audio)",
        "Music Edits", "Unknown", 180_000, "", "", source = "YouTube Music")

    @Test fun bufferedResultsAndIndependentCursorsReachEverySongAndVideoWithoutRepeatingRows() = runBlocking {
        val requests = mutableListOf<TrackSearchContinuation>()
        val repository = CatalogRepository(trackSearchLoader = { query, cursor ->
            assertEquals("Song 8d", query)
            requests += cursor
            when (requests.size) {
                1 -> AudioRoutingService.SearchPage((0..21).map(::track), null, "songs-two", youtubeVideoNext = "videos-two")
                2 -> {
                    assertEquals("songs-two", cursor.youtubeContinuation)
                    assertEquals("videos-two", cursor.youtubeVideoContinuation)
                    AudioRoutingService.SearchPage(listOf(track(0), track(21), track(22), track(23)), null, null,
                        youtubeVideoNext = "videos-three")
                }
                3 -> {
                    assertFalse(cursor.youtubeHasMore)
                    assertTrue(cursor.youtubeVideoHasMore)
                    assertEquals("videos-three", cursor.youtubeVideoContinuation)
                    AudioRoutingService.SearchPage(listOf(track(24)), null, null)
                }
                else -> error("Exhausted providers must not restart")
            }
        })
        var previous: CatalogPage? = null
        val all = mutableListOf<MusicTrack>()
        val sizes = mutableListOf<Int>()
        for (pageNumber in 1..4) {
            val page = repository.search("Song 8d", "Tracks", page = pageNumber, previousPage = previous)
            sizes += page.tracks.size
            all += page.tracks
            previous = page.copy(tracks = all.toList())
            if (pageNumber == 2) assertEquals("Consume buffered results before making more requests", 1, requests.size)
        }
        assertEquals(listOf(10, 10, 4, 1), sizes)
        assertEquals((0..24).map { track(it).id }.toSet(), all.map { it.id }.toSet())
        assertEquals(25, all.size)
        assertEquals(3, requests.size)
        assertNull(previous!!.nextPage)
    }

    @Test fun failureRetainsOnlyThatFiltersCursorWhileTheOtherFilterAdvances() = runBlocking {
        val requests = mutableListOf<TrackSearchContinuation>()
        val repository = CatalogRepository(trackSearchLoader = { _, cursor ->
            requests += cursor
            when (requests.size) {
                1 -> AudioRoutingService.SearchPage(listOf(track(1)), null, "songs-two", youtubeVideoFailed = true)
                2 -> {
                    assertEquals("songs-two", cursor.youtubeContinuation)
                    assertNull(cursor.youtubeVideoContinuation)
                    assertTrue(cursor.youtubeVideoHasMore)
                    AudioRoutingService.SearchPage(listOf(track(2)), null, null, youtubeFailed = true,
                        youtubeVideoNext = "videos-two")
                }
                else -> {
                    assertEquals("songs-two", cursor.youtubeContinuation)
                    assertEquals("videos-two", cursor.youtubeVideoContinuation)
                    AudioRoutingService.SearchPage(listOf(track(3)), null, null)
                }
            }
        })
        val first = repository.search("Song 8d", "Tracks")
        assertNotNull(first.warning)
        val second = repository.search("Song 8d", "Tracks", page = 2, previousPage = first)
        assertNotNull(second.warning)
        val third = repository.search("Song 8d", "Tracks", page = 3,
            previousPage = second.copy(tracks = first.tracks + second.tracks))
        assertEquals(listOf(track(3).id), third.tracks.map { it.id })
        assertNull(third.warning)
        assertNull(third.nextPage)
    }

    @Test fun emptySongsDoesNotExhaustTheVideoContinuationOrRestartSongSearch() = runBlocking {
        var requests = 0
        val repository = CatalogRepository(trackSearchLoader = { _, cursor ->
            if (requests++ == 0) AudioRoutingService.SearchPage(emptyList(), null, null, youtubeVideoNext = "videos-two")
            else {
                assertFalse(cursor.youtubeHasMore)
                assertTrue(cursor.youtubeVideoHasMore)
                assertEquals("videos-two", cursor.youtubeVideoContinuation)
                AudioRoutingService.SearchPage(listOf(track(1)), null, null)
            }
        })
        val first = repository.search("Song 8d", "Tracks")
        assertTrue(first.tracks.isEmpty())
        assertEquals(2, first.nextPage)
        val second = repository.search("Song 8d", "Tracks", page = 2, previousPage = first)
        assertEquals(track(1).id, second.tracks.single().id)
        assertNull(second.nextPage)
    }

    @Test(expected = IOException::class)
    fun allProviderFailuresReportAnErrorInsteadOfAnEmptySuccessfulSearch() = runBlocking {
        CatalogRepository(trackSearchLoader = { _, _ -> AudioRoutingService.SearchPage(emptyList(), null, null,
            standardFailed = true, youtubeFailed = true, youtubeVideoFailed = true) })
            .search("Song 8d", "Tracks")
        Unit
    }
}
