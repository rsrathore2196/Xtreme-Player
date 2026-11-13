package com.example

import android.app.Application
import com.example.data.repository.*
import com.example.ui.viewmodel.CatalogBrowserState
import com.example.ui.viewmodel.preparePlaybackQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionCardPreparationTest {
    private val artist = CatalogDestination.Artist(CatalogArtist("UC-artist", "Artist"))
    private fun album() = CatalogDestination.Album(CatalogAlbum("MPRE-album", "Album", emptyList(),
        "https://example.test/exact-cover", "2026", CatalogProvider.YOUTUBE_MUSIC, "opaque-params", "IN", "hi"))

    private fun page(ids: List<String>, token: String? = null): String {
        val rows = ids.map { id -> JSONObject().put("playlistVideoRenderer", JSONObject()
            .put("videoId", id).put("title", JSONObject().put("simpleText", "Song $id"))
            .put("shortBylineText", JSONObject().put("simpleText", "Artist")).put("lengthSeconds", "180")) }
        return JSONObject().put("contents", JSONObject().put("musicPlaylistShelfRenderer", JSONObject()
            .put("contents", JSONArray(rows)).apply { token?.let { put("continuations", JSONArray().put(JSONObject()
                .put("nextContinuationData", JSONObject().put("continuation", it)))) } })).toString()
    }

    @Test fun albumCardPreparationKeepsCurrentNavigationAndUsesExactReleaseMetadata() = runTest {
        val calls = mutableListOf<List<String?>>()
        val repository = CatalogRepository(browseLoader = { id, params, cursor, country, language ->
            calls += listOf(id, params, cursor, country, language)
            page(listOf("abcdefghijk", "bcdefghijkl"))
        })
        val browser = CatalogBrowserState(repository, backgroundScope, preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.open(artist)
        browser.rememberScroll(4, 37)
        val before = browser.detail.value
        val selected = album()
        val prepared = browser.prepareCollectionPlayback(selected)
        assertSame(before, browser.detail.value)
        assertEquals(listOf(listOf("MPRE-album", "opaque-params", null, "IN", "hi")), calls)
        assertEquals(listOf("yt_abcdefghijk", "yt_bcdefghijkl"), prepared.tracks.map { it.id })
        assertTrue(prepared.tracks.all { it.coverUrl == selected.album.image })
        assertEquals(prepared.tracks, prepared.preparePlaybackQueue(prepared.tracks.first()))
        browser.open(CatalogDestination.Album(selected.album.copy(id = "MPRE-other")))
        browser.back()
        assertEquals(artist, browser.detail.value!!.destination)
        assertEquals(4, browser.detail.value!!.scrollIndex)
        assertEquals(37, browser.detail.value!!.scrollOffset)
    }

    @Test fun playlistCardPaginationContinuesAfterTheBrowsingPageCloses() = runTest {
        val cursors = mutableListOf<String?>()
        val repository = CatalogRepository(browseLoader = { _, _, token, _, _ ->
            cursors += token
            page(listOf(if (token == null) "abcdefghijk" else "bcdefghijkl"), if (token == null) "next-token" else null)
        })
        val browser = CatalogBrowserState(repository, backgroundScope, preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.open(artist)
        val destination = CatalogDestination.Playlist(CatalogPlaylist("VL-playlist", "Playlist", "",
            CatalogProvider.YOUTUBE_MUSIC, "playlist-params"))
        val prepared = browser.prepareCollectionPlayback(destination)
        val loader = browser.playlistPlaybackContinuation(prepared)!!
        browser.close()
        val more = loader.fetch(loader.page)
        assertEquals(listOf(null, "next-token"), cursors)
        assertEquals("yt_bcdefghijkl", more.tracks.single().id)
        assertNull(browser.detail.value)
        assertNull(loader.after(more))
    }

    @Test fun failedPreparationCannotChangeTheOpenCollection() = runTest {
        val repository = CatalogRepository(browseLoader = { _, _, _, _, _ -> throw java.io.IOException("unavailable") })
        val browser = CatalogBrowserState(repository, backgroundScope, preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.open(artist)
        val before = browser.detail.value
        assertTrue(runCatching { browser.prepareCollectionPlayback(album()) }.isFailure)
        assertSame(before, browser.detail.value)
    }
}
