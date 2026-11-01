package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.*
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.repository.*
import com.example.ui.viewmodel.appendServerShelves
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerHomeCollectionsTest {
    private fun feed() = YouTubeHomeParser.parseHomeFeed(JSONObject(NativeFeedFixtures.home(
        NativeFeedFixtures.collectionShelf("Albums & Singles", NativeFeedFixtures.album(1)),
        NativeFeedFixtures.shelf("Quick Picks", 2, 3),
        NativeFeedFixtures.collectionShelf("Trending Playlists", NativeFeedFixtures.playlist(4))
    )), countryCode = "IN", languageCode = "en")

    @Test fun oneServerResponsePreservesAllThreeShelfKindsTheirTitlesAndTheirOrder() {
        val page = feed()
        assertEquals(listOf("Albums & Singles", "Quick Picks", "Trending Playlists"), page.shelves.map { it.title })
        val album = (page.shelves[0].items.single() as ServerHomeItem.Album).album
        val playlist = (page.shelves[2].items.single() as ServerHomeItem.Playlist).playlist
        assertEquals("MPRE1", album.id); assertEquals(CatalogProvider.YOUTUBE_MUSIC, album.provider)
        assertEquals("VLplaylist-4", playlist.id); assertEquals("playlist-params-4", playlist.browseParams)
        assertEquals("IN", album.countryCode); assertEquals("IN", playlist.countryCode)
        assertEquals(listOf(2, 3).map { "yt_${NativeFeedFixtures.id(it)}" }, page.shelves[1].tracks.map { it.id })
        assertTrue(page.shelves[0].tracks.isEmpty()); assertTrue(page.shelves[2].tracks.isEmpty())
    }
    @Test fun mixedShelvesRetainNativeItemOrderingAndNeverPlayCollectionOverlaysAsSongs() {
        val album = NativeFeedFixtures.album(1)
        album.getJSONObject("musicTwoRowItemRenderer").put("overlay", JSONObject("""{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}}}"""))
        val response = NativeFeedFixtures.home(NativeFeedFixtures.collectionShelf("Native mix", album, NativeFeedFixtures.song(2), NativeFeedFixtures.playlist(3)))
        val shelf = YouTubeHomeParser.parseHomeFeed(JSONObject(response)).shelves.single()
        assertTrue(shelf.items[0] is ServerHomeItem.Album); assertTrue(shelf.items[1] is ServerHomeItem.Song)
        assertTrue(shelf.items[2] is ServerHomeItem.Playlist)
        assertEquals(listOf("yt_${NativeFeedFixtures.id(2)}"), shelf.tracks.map { it.id })
    }
    @Test fun unsupportedArtistAndUnknownBrowseItemsAreStillOmittedRatherThanInventingTracks() {
        val rows = listOf("UC-real-artist", "FEmusic_unknown").map { id -> NativeFeedFixtures.album(1).apply {
            getJSONObject("musicTwoRowItemRenderer").getJSONObject("navigationEndpoint").getJSONObject("browseEndpoint").put("browseId", id)
        } }
        val response = NativeFeedFixtures.home(NativeFeedFixtures.collectionShelf("Unsupported", *rows.toTypedArray()))
        assertTrue(YouTubeHomeParser.parseHomeFeed(JSONObject(response)).shelves.isEmpty())
    }
    @Test fun collectionOnlyContinuationsAreNotDeduplicatedJustBecauseTheirSongListsAreEmpty() {
        val first = feed().shelves.first()
        val second = first.copy(id = "another", items = listOf(ServerHomeItem.Album((first.items.single() as ServerHomeItem.Album).album.copy(id = "MPRE-other"))))
        val appended = appendServerShelves(listOf(first), listOf(second))
        assertEquals(2, appended.size); assertSame(first, appended.first())
        val merged = appendServerShelves(listOf(first), listOf(second.copy(id = first.id)))
        assertEquals(listOf("MPRE1", "MPRE-other"), merged.single().items.map { (it as ServerHomeItem.Album).album.id })
        assertSame(merged, appendServerShelves(merged, listOf(second.copy(id = first.id))))
    }
    @Test fun cacheRoundTripRestoresTypedCollectionsTheirOpaqueParametersAndRegionalContext() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cache = HomeCardCache(app)
        val original = feed().shelves
        cache.saveHome(SavedHomeCards("IN|anonymous", refreshedAt = System.currentTimeMillis(),
            serverShelves = original, serverFetchSucceeded = true))
        val restored = cache.read("IN|anonymous")!!
        assertEquals(SERVER_HOME_CONTENT_VERSION, restored.serverContentVersion)
        assertEquals(original.map { it.id }, restored.serverShelves.map { it.id })
        assertEquals((original[0].items.single() as ServerHomeItem.Album).album,
            (restored.serverShelves[0].items.single() as ServerHomeItem.Album).album)
        assertEquals(original[2].items, restored.serverShelves[2].items)
        assertEquals(original[1].items.map { it.key }, restored.serverShelves[1].items.map { it.key })
        assertTrue(cache.read("GB|anonymous")?.serverShelves.isNullOrEmpty())
    }
}
