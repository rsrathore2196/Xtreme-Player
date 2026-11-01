package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.data.remote.YouTubeHomeParser
import com.example.ui.viewmodel.appendServerShelves
import com.example.ui.viewmodel.refreshServerShelves
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomePresentationRegressionTest {
    private fun track(id: String) = MusicTrack(id, id, "Artist", "Album", 180_000, "", "")
    private fun shelf(id: String, vararg songs: String) = ServerHomeShelf(id, "Native $id", tracks = songs.map(::track))
    @Test fun serverHeadingsAndTrackOrderAreAuthoritative() {
        val page = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("A native heading", 3, 1, 2))))
        assertEquals("A native heading", page.shelves.single().title)
        assertEquals(listOf(3, 1, 2).map { "yt_${NativeFeedFixtures.id(it)}" }, page.shelves.single().tracks.map { it.id })
    }
    @Test fun refreshedArtworkOrFirstTrackNeverChangesTheShelfIdentity() {
        fun parse(vararg ids: Int) = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("Mix", *ids)))).shelves.single()
        assertEquals(parse(1, 2).id, parse(2, 3).id)
    }
    @Test fun continuationAppendKeepsAllEarlierPositions() {
        val before = listOf(shelf("a", "one"), shelf("b", "two"))
        val after = appendServerShelves(before, listOf(shelf("c", "three")))
        assertEquals(listOf("a", "b", "c"), after.map { it.id }); assertSame(before[0], after[0]); assertSame(before[1], after[1])
    }
    @Test fun lateContinuationMergesOnlyItsOwnShelfWithoutReplacingEarlierTracks() {
        val before = listOf(shelf("a", "one"), shelf("b", "two"))
        val after = appendServerShelves(before, listOf(shelf("a", "one", "three")))
        assertEquals(listOf("one", "three"), after[0].tracks.map { it.id }); assertSame(before[1], after[1])
    }
    @Test fun duplicateResponsesDoNotCauseNewCardInstances() {
        val before = listOf(shelf("a", "one"))
        assertSame(before, appendServerShelves(before, before))
    }
    @Test fun repeatedPageWithADifferentCursorCannotDuplicateAnIdenticalShelf() {
        val before = listOf(shelf("a", "one")); val duplicate = before.single().copy(id = "another-page")
        assertSame(before, appendServerShelves(before, listOf(duplicate)))
    }
    @Test fun emptyPageCannotClearExistingCards() {
        val before = listOf(shelf("a", "one")); assertSame(before, appendServerShelves(before, emptyList()))
    }
    @Test fun titleOnlyShelvesFromDifferentPagesHaveDistinctStableIds() {
        val root = org.json.JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("Mix", 1)))
        val a = YouTubeHomeParser.parseHomeFeed(root, "page-a").shelves.single()
        val b = YouTubeHomeParser.parseHomeFeed(root, "page-b").shelves.single()
        assertNotEquals(a.id, b.id); assertEquals(a.id, YouTubeHomeParser.parseHomeFeed(root, "page-a").shelves.single().id)
    }
    @Test fun collectionOverlaysRemainCollectionsAndCannotBecomeArbitrarySongs() {
        val row = NativeFeedFixtures.album(1)
        row.getJSONObject("musicTwoRowItemRenderer").put("overlay", org.json.JSONObject("""{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}}}"""))
        val shelf = NativeFeedFixtures.shelf("Albums"); shelf.getJSONObject("musicCarouselShelfRenderer").getJSONArray("contents").put(row)
        val parsed = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(shelf))).shelves.single()
        assertTrue(parsed.tracks.isEmpty())
        assertEquals("MPRE1", (parsed.items.single() as com.example.data.model.ServerHomeItem.Album).album.id)
    }
    @Test fun continuationsKeepTheActualServerCursor() {
        val page = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("Native", 1), continuation = "cursor")))
        assertEquals("cursor", page.continuationToken)
    }
    @Test fun allPlayableServerShelvesAreParsedWithoutATwoCardLimit() {
        val page = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(
            *(1..8).map { NativeFeedFixtures.shelf("Server $it", it) }.toTypedArray())))
        assertEquals((1..8).map { "Server $it" }, page.shelves.map { it.title })
    }
    @Test fun continuationActionsAndCommandsRetainAllShelvesAndTheNextCursor() {
        val root = org.json.JSONObject("""{"onResponseReceivedActions":[],"onResponseReceivedCommands":[]}""")
        fun action(vararg rows: org.json.JSONObject) = org.json.JSONObject().put("appendContinuationItemsAction",
            org.json.JSONObject().put("continuationItems", org.json.JSONArray(rows.toList())))
        root.getJSONArray("onResponseReceivedActions").put(action(NativeFeedFixtures.shelf("A", 1)))
            .put(action(NativeFeedFixtures.shelf("B", 2)))
        root.getJSONArray("onResponseReceivedCommands").put(action(NativeFeedFixtures.shelf("C", 3),
            org.json.JSONObject("""{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}""")))
        val page = YouTubeHomeParser.parseHomeFeed(root, "current")
        assertEquals(listOf("A", "B", "C"), page.shelves.map { it.title })
        assertEquals("next", page.continuationToken)
    }
    @Test fun refreshUpdatesTheFirstPageInServerOrderAndRetainsExpandedPages() {
        val old = listOf(shelf("a", "old"), shelf("b", "retained"))
        val refreshed = listOf(shelf("c", "new"), shelf("a", "updated"))
        val result = refreshServerShelves(old, refreshed)
        assertEquals(listOf("c", "a", "b"), result.map { it.id })
        assertEquals(listOf("updated"), result[1].tracks.map { it.id })
        assertSame(old[1], result[2])
    }
    @Test fun continuationButtonCommandsAndReloadResponsesPreserveNativeItemsAndCursor() {
        for (endpoint in listOf(
            """{"continuationCommand":{"token":"next"}}""",
            """{"innertubeCommand":{"continuationCommand":{"token":"next"}}}""",
            """{"commandExecutorCommand":{"commands":[{"clickTrackingParams":"ignore"},{"continuationCommand":{"token":"next"}}]}}""")) {
            val row = org.json.JSONObject("""{"continuationItemRenderer":{"button":{"buttonRenderer":{"command":$endpoint}}}}""")
            val root = org.json.JSONObject().put("onResponseReceivedCommands", org.json.JSONArray().put(
                org.json.JSONObject().put("reloadContinuationItemsCommand", org.json.JSONObject().put("continuationItems",
                    org.json.JSONArray().put(NativeFeedFixtures.shelf("Native", 2, 1)).put(row)))))
            val parsed = YouTubeHomeParser.parseHomeFeed(root, "previous")
            assertEquals("next", parsed.continuationToken)
            assertEquals(listOf(2, 1).map { "yt_${NativeFeedFixtures.id(it)}" }, parsed.shelves.single().tracks.map { it.id })
        }
    }
    @Test fun emptyFirstContinuationEntryCannotHideALaterValidToken() {
        val root = org.json.JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("Native", 1)))
        root.getJSONObject("contents").getJSONObject("singleColumnBrowseResultsRenderer").getJSONArray("tabs")
            .getJSONObject(0).getJSONObject("tabRenderer").getJSONObject("content").getJSONObject("sectionListRenderer")
            .put("continuations", org.json.JSONArray("""[{"nextContinuationData":{}},{"nextContinuationData":{"continuation":"next"}}]"""))
        assertEquals("next", YouTubeHomeParser.parseHomeFeed(root).continuationToken)
    }
}
