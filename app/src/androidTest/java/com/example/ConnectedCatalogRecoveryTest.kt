package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.connections.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ConnectedCatalogRecoveryTest {
    private fun song(id: String) = JSONObject().put("playlistVideoRenderer", JSONObject().put("videoId", id)
        .put("title", JSONObject().put("simpleText", "Song $id"))
        .put("shortBylineText", JSONObject().put("simpleText", "Artist")).put("lengthSeconds", "180"))
    private fun shelf(vararg ids: String, token: String = "next") = JSONObject()
        .put("contents", JSONArray(ids.map(::song)))
        .put("continuations", JSONArray().put(JSONObject().put("nextContinuationData", JSONObject().put("continuation", token))))

    @Test fun importReadsSelectedPlaylistAndItsCursorWithoutSuggestedOrHiddenTracks() {
        val primary = JSONObject().put("musicPlaylistShelfRenderer", shelf("abcdefghijk", "lmnopqrstuv", token = "own-cursor"))
        val hidden = JSONObject().put("tabRenderer", JSONObject().put("selected", false)
            .put("content", JSONObject().put("musicPlaylistShelfRenderer", shelf("hidden12345"))))
        val selected = JSONObject().put("tabRenderer", JSONObject().put("selected", true)
            .put("content", JSONObject().put("header", JSONObject())))
        val suggestions = JSONObject().put("musicCarouselShelfRenderer", JSONObject().put("contents", JSONArray()
            .put(JSONObject().put("musicPlaylistShelfRenderer", shelf("suggested12", token = "wrong-cursor")))))
        val sections = JSONObject().put("sectionListRenderer", JSONObject().put("contents", JSONArray().put(suggestions).put(primary)))
        val columns = JSONObject().put("tabs", JSONArray().put(hidden).put(selected)).put("secondaryContents", sections)
        val response = JSONObject().put("contents", JSONObject().put("twoColumnBrowseResultsRenderer", columns))
        val page = connectedYouTubePage(response)
        assertEquals(listOf("yt_abcdefghijk", "yt_lmnopqrstuv"), page.tracks.map { it.id })
        assertEquals("own-cursor", page.continuation)
    }

    @Test fun albumAndContinuationPagesPreserveOrderAndUseOnlyTheirOwnContinuation() {
        val album = JSONObject().put("contents", JSONObject().put("musicShelfRenderer", shelf("abcdefghijk", token = "album-next")))
        assertEquals("album-next", connectedYouTubePage(album).continuation)
        val response = JSONObject().put("continuationContents", JSONObject()
            .put("musicPlaylistShelfContinuation", shelf("lmnopqrstuv", "lmnopqrstuv", token = "page-three")))
        val page = connectedYouTubePage(response)
        assertEquals(listOf("yt_lmnopqrstuv", "yt_lmnopqrstuv"), page.tracks.map { it.id })
        assertEquals("page-three", page.continuation)
        val cursor = JSONObject().put("continuationItemRenderer", JSONObject().put("continuationEndpoint", JSONObject()
            .put("continuationCommand", JSONObject().put("token", "append-next"))))
        val items = JSONObject().put("continuationItems", JSONArray().put(song("abcdefghijk")).put(cursor))
        val append = JSONObject().put("onResponseReceivedActions", JSONArray().put(JSONObject().put("appendContinuationItemsAction", items)))
        assertEquals(listOf("yt_abcdefghijk"), connectedYouTubePage(append).tracks.map { it.id })
        assertEquals("append-next", connectedYouTubePage(append).continuation)
        assertTrue(connectedYouTubePage(JSONObject().put("menu", song("abcdefghijk"))).tracks.isEmpty())
    }

    @Test fun unauthorizedSpotifySessionRefreshesOnceAndRetriesTheLibraryRequest() = withSession { store ->
        val authorizations = mutableListOf<String?>()
        var refreshes = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            authorizations += chain.request().header("Authorization")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (authorizations.size == 1) 401 else 200)
                .body("""{"data":{"me":{"libraryV3":{"items":[],"totalCount":0}}}}""".toResponseBody()).build()
        }.build()
        val client = ConnectedLibraries(context, httpClient = http, refreshSession = {
            refreshes++; it.copy(bearer = "new-token", expiresAt = System.currentTimeMillis() + 600_000)
        })
        assertEquals(listOf("liked"), client.collections("spotify").map { it.id })
        assertEquals(listOf("Bearer old-token", "Bearer new-token"), authorizations)
        assertEquals(1, refreshes)
        assertEquals("new-token", store.state.value.spotify?.bearer)
    }

    @Test fun rateLimitsDoNotRefreshAndDisconnectDuringRefreshCannotRestoreAnAccount() = withSession { store ->
        var requests = 0
        var code = 429
        var refreshes = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(code).body("{}".toResponseBody()).build()
        }.build()
        val client = ConnectedLibraries(context, httpClient = http, refreshSession = {
            refreshes++
            store.change { it.copy(spotify = null) }
            it.copy(bearer = "new-token")
        })
        try { client.collections("spotify"); fail("Expected rate limit failure") } catch (_: IOException) { }
        assertEquals(1, requests)
        assertEquals(0, refreshes)
        code = 401
        try { client.collections("spotify"); fail("Expected account change failure") } catch (_: IOException) { }
        assertEquals(2, requests)
        assertEquals(1, refreshes)
        assertNull(store.state.value.spotify)
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withSession(block: suspend (ConnectionStore) -> Unit) = runBlocking(Dispatchers.IO) {
        val store = ConnectionStore.get(context)
        store.load()
        val original = store.state.value
        try {
            store.change { it.copy(spotify = SpotifyConnection("fixture-cookie", "old-token", System.currentTimeMillis() + 600_000)) }
            block(store)
        } finally { store.change { original } }
    }
}
