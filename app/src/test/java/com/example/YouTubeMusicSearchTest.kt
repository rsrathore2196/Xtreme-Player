package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.AudioRoutingService
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.YouTubeMusicApiService.SearchFilter
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class YouTubeMusicSearchTest {
    private fun track(id: String, title: String, artist: String = "Artist") = MusicTrack(
        "yt_$id", title, artist, "Unknown", 180_000, "", "", source = "YouTube Music")

    private fun text(value: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
    private fun row(id: String, title: String): JSONObject {
        val artist = JSONObject().put("text", "8D World").put("navigationEndpoint", JSONObject()
            .put("browseEndpoint", JSONObject().put("browseId", "UCuploader")))
        return JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
            .put("playlistItemData", JSONObject().put("videoId", id))
            .put("flexColumns", JSONArray()
                .put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", text(title))))
                .put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject()
                    .put("runs", JSONArray().put(JSONObject().put("text", "Video"))
                        .put(JSONObject().put("text", " • ")).put(artist)
                        .put(JSONObject().put("text", " • ")).put(JSONObject().put("text", "1M views"))
                        .put(JSONObject().put("text", " • ")).put(JSONObject().put("text", "3:07")))))))
            .put("thumbnail", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail", JSONObject()
                .put("thumbnails", JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/vi/$id/hqdefault.jpg")))))))
    }
    private fun body(id: String = "abcdefghijk", continuation: Boolean = false): String {
        val shelf = JSONObject().put("contents", JSONArray().put(row(id, "Softly (8D Audio + Slowed & Reverb)")))
            .put("continuations", JSONArray().put(JSONObject().put("nextContinuationData",
                JSONObject().put("continuation", "video-next"))))
        return if (continuation) JSONObject().put("continuationContents", JSONObject()
            .put("musicShelfContinuation", shelf)).toString()
        else JSONObject().put("contents", JSONObject().put("musicShelfRenderer", shelf)).toString()
    }

    @Test fun nativeRequestsUseSeparateSongsAndVideosFiltersAndKeepTheExactQueryAndCursor() = runBlocking {
        val requests = mutableListOf<Pair<Request, JSONObject>>()
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request() to JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body().toResponseBody()).build()
        }.build()
        val http = HttpClient(OkHttp) {
            engine { preconfigured = network }
            install(ContentNegotiation) { json(YouTubeAudioStreamService.playerJson) }
        }
        val session = InnerTube(http)
        val query = "Softly (Audio) Karan Aujla 8D slowed + reverb"
        try {
            for (filter in SearchFilter.entries) {
                val response = YouTubeAudioStreamService.requestDiscovery(session, "search", query, filter.params)
                assertEquals(query, requests.last().second.getString("query"))
                assertEquals(filter.params, requests.last().second.getString("params"))
                val page = YouTubeMusicApiService.parseSongSearchPage(response)
                assertEquals("yt_abcdefghijk", page.tracks.single().id)
                YouTubeAudioStreamService.requestDiscovery(session, "search", query, filter.params, page.continuation)
                assertEquals("video-next", requests.last().first.url.queryParameter("continuation"))
                assertEquals("video-next", requests.last().first.url.queryParameter("ctoken"))
                assertTrue(requests.last().second.optString("query").isBlank())
                assertTrue(requests.last().second.optString("params").isBlank())
            }
            assertNotEquals(requests[0].second.getString("params"), requests[2].second.getString("params"))
        } finally { session.close(); http.close() }
    }

    @Test fun uploadedVideoMetadataAndVersionLabelsSurviveFirstAndContinuationPages() {
        for (continuation in listOf(false, true)) {
            val page = YouTubeMusicApiService.parseSongSearchPage(body(continuation = continuation))
            val result = page.tracks.single()
            assertEquals("Softly (8D Audio + Slowed & Reverb)", result.title)
            assertEquals(result.title, result.originalProviderTitle)
            assertEquals("8D World", result.artist)
            assertEquals(187_000L, result.durationMs)
            assertTrue(result.coverUrl.contains("abcdefghijk"))
            assertTrue(YouTubeAudioStreamService.isPlaybackUri(result.audioUrl))
            assertEquals("video-next", page.continuation)
        }
    }

    @Test fun correctedAndAppendedSearchRowsKeepTheirVideoIdentity() {
        val contents = JSONArray().put(row("abcdefghijk", "Softly (Reverbed)"))
            .put(row("abcdefghijk", "Softly (Reverbed)"))
            .put(JSONObject().put("continuationItemRenderer", JSONObject().put("continuationEndpoint",
                JSONObject().put("continuationCommand", JSONObject().put("token", "next")))))
        val response = JSONObject().put("onResponseReceivedCommands", JSONArray().put(JSONObject()
            .put("appendContinuationItemsAction", JSONObject().put("continuationItems", contents))))
        val page = YouTubeMusicApiService.parseSongSearchPage(response.toString())
        assertEquals(listOf("yt_abcdefghijk"), page.tracks.map { it.id })
        assertEquals("next", page.continuation)
    }

    @Test fun songsAndVideosLoadConcurrentlyWithTheirOwnCursorsAndUnmodifiedQuery() = runBlocking {
        val started = SearchFilter.entries.associateWith { CompletableDeferred<Unit>() }
        val calls = CopyOnWriteArrayList<Pair<SearchFilter, String?>>()
        val query = "Softly (Audio) 8D Audio"
        val result = withTimeout(5_000) { AudioRoutingService.searchPage("  $query  ",
            youtubeContinuation = "songs-two", youtubeVideoContinuation = "videos-three",
            searchYouTube = { text, token, filter ->
                assertEquals(query, text)
                calls += filter to token
                started.getValue(filter).complete(Unit)
                started.values.forEach { it.await() }
                YouTubeMusicApiService.SongSearchPage(listOf(if (filter == SearchFilter.SONGS)
                    track("abcdefghijk", "Softly") else track("bcdefghijkl", "Softly (8D Audio)")), "${filter.name}-next")
            }, searchStandard = { _, _, _ -> error("Playable YouTube results need no fallback") }) }
        assertEquals(setOf(SearchFilter.SONGS to "songs-two", SearchFilter.VIDEOS to "videos-three"), calls.toSet())
        assertEquals("yt_bcdefghijkl", result.tracks.first().id)
        assertEquals(2, result.tracks.size)
        assertEquals("SONGS-next", result.youtubeNext)
        assertEquals("VIDEOS-next", result.youtubeVideoNext)
    }

    @Test fun aFailedSongsRequestDoesNotHideWorkingUploadedVideos() = runBlocking {
        val result = AudioRoutingService.searchPage("Softly reverbed", searchYouTube = { _, _, filter ->
            if (filter == SearchFilter.SONGS) throw IOException("Songs unavailable")
            YouTubeMusicApiService.SongSearchPage(listOf(track("abcdefghijk", "Softly (Reverb)")), "videos-two")
        }, searchStandard = { _, _, _ -> error("Keep the working video results") })
        assertEquals("yt_abcdefghijk", result.tracks.single().id)
        assertTrue(result.youtubeFailed)
        assertFalse(result.youtubeVideoFailed)
        assertEquals("videos-two", result.youtubeVideoNext)
    }

    @Test fun bothYoutubeFailuresUseTheExistingFallbackWithoutLosingQueryWords() = runBlocking {
        val query = "Softly (Audio)"
        val result = AudioRoutingService.searchPage(query, standardPage = 3,
            searchYouTube = { _, _, _ -> throw IOException("Unavailable") },
            searchStandard = { text, _, page ->
                assertEquals(query, text)
                assertEquals(3, page)
                OnlineMusicApiService.SongSearchPage(listOf(track("abcdefghijk", "Softly").copy(id = "online_song")), 4)
            })
        assertEquals("online_song", result.tracks.single().id)
        assertEquals(4, result.standardNext)
        assertTrue(result.youtubeFailed && result.youtubeVideoFailed)
        assertFalse(result.standardFailed)
    }

    @Test fun cancellingSearchStopsBothRequestsAndDoesNotStartFallbackWork() = runBlocking {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val job = launch { AudioRoutingService.searchPage("Softly", searchYouTube = { _, _, _ ->
            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }, searchStandard = { _, _, _ -> error("Cancelled searches must not fall back") }) }
        try {
            withTimeout(5_000) { bothStarted.await() }
            job.cancelAndJoin()
            assertEquals(2, cancelled.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun explicitlyRequestedEditKindsRankBeforeTheOriginalEvenWithDifferentUploaderCredits() {
        val variants = listOf("8d audio" to "8 D Audio", "mixed" to "Remix", "reverbed" to "Reverb",
            "slowed + reverb" to "Slowed & Reverbed", "lo-fi" to "Lofi", "bass boosted" to "Bass Boost")
        for ((queryVersion, titleVersion) in variants) {
            val original = track("abcdefghijk", "Softly", "Karan Aujla")
            val edit = track("bcdefghijkl", "Softly ($titleVersion)", "Music Edits")
            val ranked = AudioRoutingService.rankTracks("Softly Karan Aujla $queryVersion", listOf(original, edit))
            assertEquals(queryVersion, edit.id, ranked.first().id)
            assertEquals(setOf(original.id, edit.id), ranked.map { it.id }.toSet())
        }
    }

    @Test fun exact8dIntentDoesNotPreferAnotherDimensionOrAReverbOnlyVersion() {
        val eight = track("abcdefghijk", "Softly (8D Audio)")
        val sixteen = track("bcdefghijkl", "Softly (16D Audio)")
        val reverb = track("cdefghijklm", "Softly (Reverb)")
        assertEquals(eight.id, AudioRoutingService.rankTracks("Softly 8d", listOf(sixteen, reverb, eight)).first().id)
    }

    @Test fun providerCorrectionsAndTranslationsSurviveWithoutEveryLiteralQueryWord() {
        val corrected = track("abcdefghijk", "Softly (8D Audio)", "8D World")
        val translated = track("bcdefghijkl", "सॉफ्टली (8D Audio)", "8D World")
        val results = AudioRoutingService.rankTracks("Sofly Karan Aujla 8d", listOf(corrected, translated))
        assertEquals(listOf(corrected.id, translated.id), results.map { it.id })
    }

    @Test fun ordinarySearchKeepsTheOriginalFirstWithoutHidingAlternativeRecordings() {
        val original = track("abcdefghijk", "Softly")
        val remix = track("bcdefghijkl", "Softly (Remix)")
        val eight = track("cdefghijklm", "Softly (8D Audio)")
        val unrelatedFallback = original.copy(id = "online_other", title = "Another song")
        val results = AudioRoutingService.rankTracks("Softly", listOf(remix, eight, original, unrelatedFallback))
        assertEquals(original.id, results.first().id)
        assertEquals(setOf(original.id, remix.id, eight.id), results.map { it.id }.toSet())
    }

    @Test fun distinctYoutubeUploadsRemainSeparateWhileRepeatedVideoIdsAndEquivalentFallbacksMerge() {
        val first = track("abcdefghijk", "Softly (Remix)")
        val second = first.copy(id = "yt_bcdefghijkl")
        val fallback = first.copy(id = "online_song")
        val results = AudioRoutingService.mergeTracks(listOf(fallback, first, first, second))
        assertEquals(listOf(first.id, second.id), results.map { it.id })
    }
}
