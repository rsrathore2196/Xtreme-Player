package com.example

import android.app.Application
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeHeroArtworkResolver
import com.example.ui.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HeroArtworkSourceTest {
    @org.junit.Before fun prepareMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @org.junit.After fun restoreMain() { Dispatchers.resetMain() }
    private val track = MusicTrack("yt_abcdefghijk", "Track", "Artist", "Album", 180000,
        "https://lh3.googleusercontent.com/album=w600-h600-l90-rj", "audio")
    private val landscape = "https://lh3.googleusercontent.com/music-video=w1280-h720-l90-rj"
    private fun panel(id: String, image: String, width: Int, height: Int, type: String) = JSONObject()
        .put("videoId", id).put("thumbnail", JSONObject().put("thumbnails", JSONArray().put(JSONObject()
            .put("url", image).put("width", width).put("height", height))))
        .put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("watchEndpointMusicSupportedConfigs",
            JSONObject().put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", type)))))
    private fun related(primaryId: String = "abcdefghijk", square: Boolean = false) = JSONObject()
        .put("contents", JSONObject().put("playlistPanelVideoWrapperRenderer", JSONObject()
            .put("primaryRenderer", JSONObject().put("playlistPanelVideoRenderer", panel(primaryId, track.coverUrl, 600, 600, "MUSIC_VIDEO_TYPE_ATV")))
            .put("counterpart", JSONArray().put(JSONObject().put("counterpartRenderer", JSONObject().put("playlistPanelVideoRenderer",
                panel("zyxwvutsrqp", landscape, if (square) 720 else 1280, 720, "MUSIC_VIDEO_TYPE_OMV")))))))

    @Test fun onlyTheRequestedTracksLinkedLandscapeVideoCanBeUsed() {
        assertEquals(landscape, YouTubeHeroArtworkResolver.landscapeCover(related(), "abcdefghijk"))
        assertNull(YouTubeHeroArtworkResolver.landscapeCover(related(primaryId = "00000000001"), "abcdefghijk"))
        assertNull(YouTubeHeroArtworkResolver.landscapeCover(related(square = true), "abcdefghijk"))
    }
    @Test fun heroKeepsTheRectangleWhenRequestingLargerArtworkAndPlayerKeepsItsSquare() {
        val source = "https://lh3.googleusercontent.com/music-video=w544-h306-l90-rj"
        val context = ApplicationProvider.getApplicationContext<Application>()
        val size = IntSize(1000, 720)
        assertEquals(landscape, heroLandscapeSourceUrl(source, size))
        val hero = buildHeroArtworkRequest(context, source, size)
        val player = buildOptimizedImageRequest(context, track.coverUrl, ImageConfig.PLAYER_SCREEN_SIZE, track)
        assertEquals(landscape, hero.data)
        assertTrue(hero.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.landscapeArtwork)
        assertNull(hero.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.trackId)
        assertTrue(hero.transformations.isEmpty())
        assertTrue(hero.memoryCacheKey!!.key.startsWith("hero-landscape:"))
        assertFalse(player.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.landscapeArtwork)
        assertTrue(player.data.toString().contains("album="))
        assertEquals("https://lh3.googleusercontent.com/album=w600-h600-l90-rj", track.coverUrl)
    }
    @Test fun aKnownVideoFrameCanBeShownImmediatelyWithoutUsingTheAlbumCover() {
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", YouTubeHeroArtworkResolver.initial(track))
        assertNull(YouTubeHeroArtworkResolver.initial(track.copy(id = "online_song")))
    }
    @Test fun heroDiscoveryIsCachedAndDoesNotChangeTheSongOrItsAudio() = runBlocking {
        val calls = AtomicInteger()
        val resolver = YouTubeHeroArtworkResolver({ calls.incrementAndGet(); related().toString() }, { error("Known video needs no seed search") })
        val results = coroutineScope { List(4) { async { resolver.resolve(track) } }.awaitAll() }
        assertTrue(results.all { it == landscape }); assertEquals(1, calls.get())
        assertEquals(landscape, resolver.resolve(track.copy(audioUrl = "other-stream")))
        assertEquals(1, calls.get()); assertEquals("audio", track.audioUrl)
        assertTrue(track.coverUrl.contains("album="))
    }
    @Test fun cancellationDoesNotPoisonTheNextHeroRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var first = true
        val resolver = YouTubeHeroArtworkResolver({
            if (first) { entered.complete(Unit); awaitCancellation() }
            related().toString()
        }, { null })
        val request = async { resolver.resolve(track) }
        entered.await(); request.cancelAndJoin(); first = false
        assertEquals(landscape, resolver.resolve(track))
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun realImageLoadingPreservesTheLandscapeRequestAndReusesItsSeparateCache() = runBlocking {
        com.example.performance.BackgroundWorkCoordinator.foreground(false)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
        val context = ApplicationProvider.getApplicationContext<Application>()
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN)
                compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
            output.toByteArray()
        }
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val cachedOnly = request.cacheControl.onlyIfCached
            if (!cachedOnly) fetched.add(request.url.toString())
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (cachedOnly) 504 else 200).message("Native frame")
                .header("Cache-Control", "public, max-age=86400")
                .body((if (cachedOnly) byteArrayOf() else bytes).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val directory = java.io.File(context.cacheDir, "hero-native-${System.nanoTime()}")
        val loader = coil.ImageLoader.Builder(context).components { add(ArtworkResolutionInterceptor()) }
            .okHttpClient(client).networkObserverEnabled(false).respectCacheHeaders(false)
            .memoryCache { coil.memory.MemoryCache.Builder(context).maxSizeBytes(12 * 1024 * 1024).build() }
            .diskCache { coil.disk.DiskCache.Builder().directory(directory).maxSizeBytes(4L * 1024 * 1024).build() }.build()
        try {
            val request = buildHeroArtworkRequest(context, "https://lh3.googleusercontent.com/music-video=w544-h306-l90-rj",
                IntSize(1000, 720)).newBuilder().allowHardware(false).build()
            val image = withTimeout(5_000) { loader.execute(request) } as coil.request.SuccessResult
            val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
            assertTrue(bitmap.width > bitmap.height)
            assertEquals(landscape, image.request.data)
            assertEquals(listOf(landscape), fetched)
            assertTrue(withTimeout(5_000) { loader.execute(request) } is coil.request.SuccessResult)
            assertEquals(listOf(landscape), fetched)
        } finally { loader.shutdown(); directory.deleteRecursively() }
    }

    @Test fun heroFindsTheSameNativeVideoWhenTheRequestedTrackIsTheCounterpart() {
        assertEquals(landscape, YouTubeHeroArtworkResolver.landscapeCover(related(), "zyxwvutsrqp"))
    }

}
