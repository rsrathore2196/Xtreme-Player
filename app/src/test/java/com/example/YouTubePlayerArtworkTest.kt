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
            .put("primaryRenderer", JSONObject().put("playlistPanelVideoRenderer", JSONObject().put("videoId", primary).put("musicVideoType", "MUSIC_VIDEO_TYPE_OMV")))
            .put("counterpart", JSONArray().put(JSONObject().put("counterpartRenderer", JSONObject()
                .put("playlistPanelVideoRenderer", JSONObject().put("videoId", NativeFeedFixtures.id(2)).put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
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
            .put("thumbnail", thumbnail(square)).put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
        wrapper.getJSONArray("counterpart").getJSONObject(0).getJSONObject("counterpartRenderer")
            .getJSONObject("playlistPanelVideoRenderer").put("videoId", videoId)
            .put("thumbnail", thumbnail(video.coverUrl, 1280, 720)).put("musicVideoType", "MUSIC_VIDEO_TYPE_OMV")
        assertEquals(square, YouTubePlayerArtworkResolver.counterpartCover(root, videoId))
    }

    @Test fun twoUntypedSquareRenditionsUseSongMatchingInsteadOfGuessingTheVideoCrop() = runBlocking {
        val root = linked()
        root.getJSONObject("contents").getJSONObject("playlistPanelVideoWrapperRenderer")
            .getJSONObject("primaryRenderer").getJSONObject("playlistPanelVideoRenderer")
            .put("thumbnail", thumbnail("https://lh3.googleusercontent.com/video-crop=w600-h600-l90-rj"))
            .remove("musicVideoType")
        root.getJSONObject("contents").getJSONObject("playlistPanelVideoWrapperRenderer")
            .getJSONArray("counterpart").getJSONObject(0).getJSONObject("counterpartRenderer")
            .getJSONObject("playlistPanelVideoRenderer").remove("musicVideoType")
        assertNull(YouTubePlayerArtworkResolver.counterpartCover(root, videoId))
        val resolver = YouTubePlayerArtworkResolver({ root.toString() }, { search(song()).toString() })
        assertEquals(square, resolver.resolve(video))
    }

    @Test fun nativeSquareRenditionWithoutDimensionFieldsIsNotMissed() {
        val root = linked()
        val photo = root.getJSONObject("contents").getJSONObject("playlistPanelVideoWrapperRenderer")
            .getJSONArray("counterpart").getJSONObject(0).getJSONObject("counterpartRenderer")
            .getJSONObject("playlistPanelVideoRenderer").getJSONObject("thumbnail")
            .getJSONArray("thumbnails").getJSONObject(0)
        photo.remove("width"); photo.remove("height")
        assertEquals(square, YouTubePlayerArtworkResolver.counterpartCover(root, videoId))
        photo.put("url", "https://lh3.googleusercontent.com/album=s600-l90-rj")
        assertEquals(photo.getString("url"), YouTubePlayerArtworkResolver.counterpartCover(root, videoId))
        photo.put("url", "https://lh3.googleusercontent.com/unknown")
        assertNull(YouTubePlayerArtworkResolver.counterpartCover(root, videoId))
    }

    @Test fun videoIntrosDoNotHideAnExactSongCoverButWrongArtistsAndVersionsStillFail() {
        val extendedVideo = video.copy(durationMs = 230_000)
        assertEquals(square, YouTubePlayerArtworkResolver.matchingSongCover(search(song()), extendedVideo))
        assertNull(YouTubePlayerArtworkResolver.matchingSongCover(search(song(artist = "Other Singer")), extendedVideo))
        assertNull(YouTubePlayerArtworkResolver.matchingSongCover(search(song(title = "Song 1 (Live)")), extendedVideo))
        assertEquals("A square crop does not prove that the upload is an audio-only edit", square,
            YouTubePlayerArtworkResolver.matchingSongCover(search(song()), extendedVideo.copy(coverUrl = square)))
    }

    @Test fun missingThumbnailAndImportedYoutubeReferencesStillFindTheNativeSquare() = runBlocking {
        val resolver = YouTubePlayerArtworkResolver({ assertEquals(videoId, it); linked().toString() }, { error("No search") })
        assertEquals(square, resolver.resolve(video.copy(coverUrl = "")))
        assertEquals(square, resolver.resolve(video.copy(id = "imported-song", audioUrl = "https://music.youtube.com/watch?v=$videoId")))
    }

    @Test fun nativeSongProofAndPersistentCoversAvoidRequestsAndRemainContextIsolated() = runBlocking {
        val saved = java.util.concurrent.ConcurrentHashMap<String, String>()
        var context = "account-a|IN|hi"
        val calls = AtomicInteger()
        fun resolver() = YouTubePlayerArtworkResolver({ calls.incrementAndGet(); linked().toString() }, { error("No search") },
            { context }, { saved[it] }, { key, value -> saved[key] = value.orEmpty() })
        val first = resolver()
        assertEquals(square, first.resolve(video, square))
        assertEquals(0, calls.get())
        assertEquals(square, resolver().resolve(video.copy(album = "Enriched release", coverUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")))
        assertEquals(0, calls.get())
        context = "account-b|IN|hi"
        assertEquals(square, resolver().resolve(video)); assertEquals(1, calls.get())
        context = "account-b|GB|en"
        assertEquals(square, resolver().resolve(video)); assertEquals(2, calls.get())
    }

    @Test fun oneCancelledConsumerCannotCancelAnotherVisibleCardsSharedLookup() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val resolver = YouTubePlayerArtworkResolver({ calls.incrementAndGet(); entered.complete(Unit); release.await(); linked().toString() }, { error("No search") })
        val first = async { resolver.resolve(video) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(video) }
        delay(100)
        first.cancelAndJoin(); release.complete(Unit)
        assertEquals(square, second.await()); assertEquals(1, calls.get())
    }

    @Test fun oldContextCannotPublishAndRejectedDecodedCoverCanRetryAfterItsShortNegativeTtl() = runBlocking {
        val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        var context = "old"
        val calls = AtomicInteger()
        val resolver = YouTubePlayerArtworkResolver({ calls.incrementAndGet(); started.complete(Unit); release.await(); linked().toString() }, { error("No search") }, { context })
        val old = async { resolver.resolve(video) }; started.await(); context = "new"; release.complete(Unit)
        assertNull(old.await()); assertNull(resolver.peek(video))
        assertEquals(square, resolver.resolve(video)); resolver.reject(video, square)
        assertNull(resolver.resolve(video)); assertEquals(2, calls.get())
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61))
        assertEquals(square, resolver.resolve(video)); assertEquals(3, calls.get())
    }

    @Test fun metadataArtworkEditsCannotDiscardAnAlreadyDecodedCurrentCover() {
        val cover = ResolvedPlayerCover(playerCoverIdentity(video), square)
        val enriched = video.copy(album = "Enriched release", coverUrl = "https://lh3.googleusercontent.com/video-crop=w600-h600",
            primaryArtists = listOf(video.artist), isrc = "USABC2400001")
        val state = PlayerUiState(currentTrack = enriched, queue = listOf(video))
        assertEquals(square, playerCoverState(state, cover).currentTrack!!.coverUrl)
        assertEquals(state.queue, playerCoverState(state, cover).queue)
        val other = state.copy(currentTrack = enriched.copy(title = "Other recording"))
        assertSame(other, playerCoverState(other, cover))
    }

}
