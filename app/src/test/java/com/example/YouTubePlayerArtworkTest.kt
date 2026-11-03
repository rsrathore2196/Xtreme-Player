package com.example

import android.app.Application
import android.os.Looper
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubePlayerArtworkResolver
import com.example.data.remote.playerCoverIdentity
import com.example.playback.PlayerUiState
import com.example.ui.util.ResolvedPlayerCover
import com.example.ui.util.playerCoverState
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class YouTubePlayerArtworkTest {
    private val videoId = NativeFeedFixtures.id(1)
    private val square = "https://lh3.googleusercontent.com/album=w600-h600-l90-rj"
    private val video = MusicTrack("yt_$videoId", "Song 1", "Singer 1", "Album", 180000,
        "https://i.ytimg.com/vi/$videoId/hq720.jpg", "youtube:audio", source = "YouTube Music")
    private fun thumbnail(url: String, width: Int = 600, height: Int = 600) = JSONObject()
        .put("thumbnails", JSONArray().put(JSONObject().put("url", url).put("width", width).put("height", height)))
    private fun linked(primary: String = videoId, url: String = square, width: Int = 600, height: Int = 600) = JSONObject()
        .put("contents", JSONObject().put("playlistPanelVideoWrapperRenderer", JSONObject()
            .put("primaryRenderer", JSONObject().put("playlistPanelVideoRenderer", JSONObject().put("videoId", primary)))
            .put("counterpart", JSONArray().put(JSONObject().put("counterpartRenderer", JSONObject()
                .put("playlistPanelVideoRenderer", JSONObject().put("videoId", NativeFeedFixtures.id(2))
                    .put("thumbnail", thumbnail(url, width, height))))))))
    private fun song(title: String = "Song 1", artist: String = "Singer 1", cover: String = square) = NativeFeedFixtures.song(2, title).apply {
        val renderer = getJSONObject("musicResponsiveListItemRenderer")
        renderer.getJSONArray("flexColumns").getJSONObject(1).getJSONObject("musicResponsiveListItemFlexColumnRenderer")
            .getJSONObject("text").getJSONArray("runs").getJSONObject(0).put("text", artist)
        renderer.put("thumbnail", thumbnail(cover))
        renderer.put("fixedColumns", JSONArray().put(JSONObject().put("musicResponsiveListItemFixedColumnRenderer",
            JSONObject().put("text", NativeFeedFixtures.text("3:00")))))
    }
    private fun search(vararg songs: JSONObject) = JSONObject().put("contents", JSONArray(songs.toList()))

    @Test fun croppedSquareVideoRenditionsAlsoCheckNativeSongMetadataButNonYoutubeTracksDoNot() = runBlocking {
        val calls = AtomicInteger()
        val resolver = YouTubePlayerArtworkResolver({ calls.incrementAndGet(); linked().toString() }, { error("No search") })
        assertNull(resolver.resolve(video.copy(id = "online_song")))
        assertEquals(0, calls.get())
        assertEquals(square, resolver.resolve(video.copy(coverUrl = square)))
        assertEquals(1, calls.get())
        assertTrue(YouTubePlayerArtworkResolver.needsSongCover(video.copy(
            coverUrl = "https://lh3.googleusercontent.com/video=w544-h306-l90-rj")))
        assertTrue(YouTubePlayerArtworkResolver.needsSongCover(video.copy(
            coverUrl = "https://lh3.googleusercontent.com/video-crop=w544-h544-l90-rj")))
    }
    @Test fun nativeCounterpartNeedsTheRequestedPrimaryAndAGenuineSquareSongImage() {
        assertEquals(square, YouTubePlayerArtworkResolver.counterpartCover(linked(), videoId))
        assertNull(YouTubePlayerArtworkResolver.counterpartCover(linked(primary = NativeFeedFixtures.id(8)), videoId))
        assertNull(YouTubePlayerArtworkResolver.counterpartCover(linked(width = 1280, height = 720), videoId))
        assertNull(YouTubePlayerArtworkResolver.counterpartCover(linked(url = video.coverUrl), videoId))
    }
    @Test fun songsFallbackRejectsUnrelatedArtistsVersionsAndVideoFrames() {
        val root = search(song(artist = "Other Singer"), song(title = "Song 1 (Live)"), song(cover = video.coverUrl), song())
        assertEquals(square, YouTubePlayerArtworkResolver.matchingSongCover(root, video))
        assertNull(YouTubePlayerArtworkResolver.matchingSongCover(search(song(artist = "Other Singer")), video))
    }
    @Test fun nativeCounterpartIsReusedWithoutSongSearchOrRepeatedRequests() = runBlocking {
        val calls = AtomicInteger()
        val resolver = YouTubePlayerArtworkResolver({
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            calls.incrementAndGet(); linked().toString()
        }, { error("Linked cover must not search") })
        val results = coroutineScope { List(5) { async { resolver.resolve(video) } }.awaitAll() }
        assertTrue(results.all { it == square }); assertEquals(1, calls.get())
        assertEquals(square, resolver.resolve(video.copy(audioUrl = "archive:transport", source = "INTERNET_ARCHIVE")))
        assertEquals(1, calls.get())
    }
    @Test fun failedNativeLookupUsesOnlyOneRecordingMatchedSearch() = runBlocking {
        val calls = AtomicInteger()
        val resolver = YouTubePlayerArtworkResolver({ throw IOException("Unavailable") }, { query ->
            calls.incrementAndGet(); assertEquals("Song 1 singer 1", query); search(song()).toString()
        })
        assertEquals(square, resolver.resolve(video)); assertEquals(1, calls.get())
    }
    @Test fun cancellationReleasesLookupAndDoesNotCacheAMissingCover() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var first = true
        val resolver = YouTubePlayerArtworkResolver({
            if (first) { entered.complete(Unit); awaitCancellation() }
            linked().toString()
        }, { error("Cancelled work must not search") })
        val request = async { resolver.resolve(video) }
        entered.await(); request.cancelAndJoin(); first = false
        assertEquals(square, resolver.resolve(video))
    }
    @Test fun presentationUsesSquareArtworkWithoutChangingAudioMetadataOrQueueIdentity() {
        val state = PlayerUiState(currentTrack = video, queue = listOf(video), isPlaying = true, currentPositionMs = 12345)
        val cover = ResolvedPlayerCover(playerCoverIdentity(video), square)
        val presented = playerCoverState(state, cover)
        assertEquals(state.copy(currentTrack = video.copy(coverUrl = square)), presented)
        assertSame(state.queue, presented.queue)
        assertEquals(video.coverUrl, state.currentTrack!!.coverUrl)
        val next = state.copy(currentTrack = video.copy(id = "yt_${NativeFeedFixtures.id(9)}"))
        assertSame(next, playerCoverState(next, cover))
        val empty = state.copy(currentTrack = null)
        assertSame(empty, playerCoverState(empty, cover))
    }
    @Test fun squareSongPrimaryIsFoundWhenTheRequestedVideoIsItsCounterpart() {
        val root = linked(primary = NativeFeedFixtures.id(2))
        val wrapper = root.getJSONObject("contents").getJSONObject("playlistPanelVideoWrapperRenderer")
        wrapper.getJSONObject("primaryRenderer").getJSONObject("playlistPanelVideoRenderer")
            .put("thumbnail", thumbnail(square))
        wrapper.getJSONArray("counterpart").getJSONObject(0).getJSONObject("counterpartRenderer")
            .getJSONObject("playlistPanelVideoRenderer").put("videoId", videoId)
            .put("thumbnail", thumbnail(video.coverUrl, 1280, 720))
        assertEquals(square, YouTubePlayerArtworkResolver.counterpartCover(root, videoId))
    }

    @Test fun twoUntypedSquareRenditionsUseSongMatchingInsteadOfGuessingTheVideoCrop() = runBlocking {
        val root = linked()
        root.getJSONObject("contents").getJSONObject("playlistPanelVideoWrapperRenderer")
            .getJSONObject("primaryRenderer").getJSONObject("playlistPanelVideoRenderer")
            .put("thumbnail", thumbnail("https://lh3.googleusercontent.com/video-crop=w600-h600-l90-rj"))
        assertNull(YouTubePlayerArtworkResolver.counterpartCover(root, videoId))
        val resolver = YouTubePlayerArtworkResolver({ root.toString() }, { search(song()).toString() })
        assertEquals(square, resolver.resolve(video))
    }

}
