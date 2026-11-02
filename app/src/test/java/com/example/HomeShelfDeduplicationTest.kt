package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.*
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.repository.*
import com.example.ui.viewmodel.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeShelfDeduplicationTest {
    private fun shelf(id: String, vararg songs: Int, title: String = "Quick Picks") = ServerHomeShelf(id, title,
        tracks = songs.map { MusicTrack("yt_${NativeFeedFixtures.id(it)}", "Song $it", "Artist", "Album", 180_000, "", "") })
    @Test fun changedIdsAndReorderedHighConfidenceOverlapMergeInNativeOrder() {
        val first = shelf("first", 1, 2, 3, 4, 5)
        val page = shelf("different", 5, 4, 3, 2, 6, title = " QUICK  PICKS ")
        val result = appendServerShelves(listOf(first), listOf(page))
        assertEquals(1, result.size); assertEquals(first.id, result.single().id)
        assertEquals((1..6).map { "yt_${NativeFeedFixtures.id(it)}" }, result.single().tracks.map { it.id })
        assertSame(result, appendServerShelves(result, listOf(page)))
    }
    @Test fun differentRecordingsRemainSeparateButContentCanOverrideChangedDestinations() {
        val first = shelf("one", 1, 2, 3, 4)
        assertEquals(2, appendServerShelves(listOf(first), listOf(shelf("two", 4, 5, 6, 7))).size)
        assertEquals(1, appendServerShelves(listOf(first.copy(browseId = "VL-one")),
            listOf(first.copy(id = "two", browseId = "VL-two"))).size)
    }
    @Test fun aSingleSharedTrackCannotCollapseDistinctNativeShelves() {
        assertEquals(2, appendServerShelves(listOf(shelf("a", 1, 2)), listOf(shelf("b", 2, 3))).size)
    }
    @Test fun refreshKeepsTheLogicalShelfAndLazyKeyAcrossChangedNativeIds() {
        val old = listOf(shelf("old", 1, 2, 3, 4, 5))
        val cards = presentNativeShelves(initialHomeSkeletons(), old, ShelfLoadStatus.SUCCESS)
        val refreshed = refreshServerShelves(old, listOf(shelf("new", 5, 4, 3, 2, 6)))
        assertEquals(1, refreshed.size); assertEquals("old", refreshed.single().id)
        assertEquals(cards.single().key, presentNativeShelves(cards, refreshed, ShelfLoadStatus.SUCCESS).single().key)
        assertEquals(listOf(5, 4, 3, 2, 6).map { "yt_${NativeFeedFixtures.id(it)}" }, refreshed.single().tracks.map { it.id })
    }
    @Test fun parserDeduplicatesWithinOneResponseAndIdsDoNotDependOnPageOrOrder() {
        val a = YouTubeHomeParser.parseHomeFeed(JSONObject(NativeFeedFixtures.home(
            NativeFeedFixtures.shelf("Quick Picks", 1, 2, 3), NativeFeedFixtures.shelf("Quick Picks", 3, 1, 2))))
        val b = YouTubeHomeParser.parseHomeFeed(JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("Quick Picks", 2, 3, 1))), "cursor")
        assertEquals(1, a.shelves.size); assertEquals(a.shelves.single().id, b.shelves.single().id)
    }
    @Test fun collectionIdentityIsIndependentOfOpaqueTrackingParameters() {
        val playlist = ServerHomeItem.Playlist(CatalogPlaylist("VLsame", "Playlist", "", CatalogProvider.YOUTUBE_MUSIC))
        val one = ServerHomeShelf("one", "Trending Playlists", items = listOf(playlist))
        val two = one.copy(id = "two", items = listOf(playlist.copy(playlist = playlist.playlist.copy(id = "same", browseParams = "tracking"))))
        assertEquals(1, appendServerShelves(listOf(one), listOf(two)).size)
    }
    @Test fun cachePersistenceAndRestorationUseTheSameEquivalenceRules() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cache = HomeCardCache(app)
        cache.saveHome(SavedHomeCards("IN|anonymous", serverShelves = listOf(shelf("a", 1, 2, 3, 4), shelf("b", 4, 3, 2, 1))))
        assertEquals(1, cache.readServer("IN|anonymous")!!.serverShelves.size)
        assertTrue(cache.readServer("GB|anonymous")?.serverShelves.isNullOrEmpty())
    }
    @Test fun selectedTabsAndNativeSectionWrappersRetainTheirActualServerHeadings() {
        val row = NativeFeedFixtures.shelf("Seasonal music", 1, 2)
        val wrapped = JSONObject().put("itemSectionRenderer", JSONObject().put("contents", org.json.JSONArray().put(
            JSONObject().put("richSectionRenderer", JSONObject().put("content", row)))))
        val root = JSONObject(NativeFeedFixtures.home(wrapped))
        val tabs = root.getJSONObject("contents").getJSONObject("singleColumnBrowseResultsRenderer").getJSONArray("tabs")
        tabs.getJSONObject(0).getJSONObject("tabRenderer").put("selected", true)
        tabs.put(0, JSONObject().put("tabRenderer", JSONObject().put("selected", false)))
        // Restore the populated selected tab after a non-selected empty first tab.
        val selected = JSONObject(NativeFeedFixtures.home(wrapped)).getJSONObject("contents")
            .getJSONObject("singleColumnBrowseResultsRenderer").getJSONArray("tabs").getJSONObject(0)
        selected.getJSONObject("tabRenderer").put("selected", true)
        tabs.put(selected)
        assertEquals("Seasonal music", YouTubeHomeParser.parseHomeFeed(root).shelves.single().title)
    }

    @Test fun smallPartiallyChangedShelvesMergeAcrossBrowseSourcesButDistinctOnesRemain() {
        val old = shelf("home", 1, 2, 3).copy(browseId = "VLhome")
        val extra = shelf("explore", 3, 2, 4).copy(browseId = "VLexplore")
        assertEquals(1, appendServerShelves(listOf(old), listOf(extra)).size)
        assertEquals(2, appendServerShelves(listOf(old), listOf(shelf("other", 3, 5, 6))).size)
        var feed = listOf(old)
        repeat(20) { feed = refreshServerShelves(feed, listOf(extra, extra.copy(id = "changing-$it"))) }
        assertEquals(1, feed.size)
        assertEquals("home", feed.single().id)
        assertEquals(listOf(3, 2, 4).map { "yt_${NativeFeedFixtures.id(it)}" }, feed.single().tracks.map { it.id })
    }

    @Test fun aMergedOldShelfCannotGiveTwoDistinctNewShelvesTheSameLazyKey() {
        val old = shelf("old", 1, 2, 3, 4, 5, 6, 7, 8)
        val split = refreshServerShelves(listOf(old), listOf(shelf("one", 1, 2, 3, 4), shelf("two", 5, 6, 7, 8)))
        assertEquals(2, split.size)
        assertEquals(2, split.map { it.id }.distinct().size)
    }

}
