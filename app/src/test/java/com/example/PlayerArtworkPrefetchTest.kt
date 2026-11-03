package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.SuccessResult
import com.example.data.model.MusicTrack
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerArtworkPrefetchTest {
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.firstFrameRendered()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("navigationUntil").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        TrackPaletteCache.clear()
    }
    @After fun cleanup() { BackgroundWorkCoordinator.foreground(false); TrackPaletteCache.clear(); Dispatchers.resetMain() }

    @Test fun selectedCoverAndColorsArePreparedBeforeOpeningAndReusedByVisibleRequests() = preparedCover(false)
    @Test fun selectedVideoUsesTheResolvedSongCoverForPrefetchPaletteAndPlayerWithoutChangingPlayback() = preparedCover(true)

    private fun preparedCover(videoThumbnail: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = AtomicInteger()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val png = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
            }; out.toByteArray()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val onlyCache = chain.request().cacheControl.onlyIfCached
            if (!onlyCache) calls.incrementAndGet()
            events.add("${chain.request().url} cacheOnly=$onlyCache")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (onlyCache) 504 else 200)
                .message("Artwork").header("Cache-Control", "public, max-age=86400")
                .body((if (onlyCache) byteArrayOf() else png).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val directory = File(context.cacheDir, "selected-provider-art").apply { deleteRecursively(); mkdirs() }
        val loader = ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain -> chain.proceed(chain.request).also { result ->
                events.add("result=${result.javaClass.simpleName} " + when (result) {
                    is SuccessResult -> "${result.request.data} sampled=${result.isSampled} drawable=${result.drawable.intrinsicWidth}x${result.drawable.intrinsicHeight}"
                    is coil.request.ErrorResult -> result.throwable.toString()
                })
            } })
            add(ArtworkResolutionInterceptor())
        }
            .okHttpClient(client).networkObserverEnabled(false).respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(16 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(directory).maxSizeBytes(4L * 1024 * 1024).build() }.build()
        val originalCover = "https://lh3.googleusercontent.com/catalog=w60-h60-l90-rj"
        val track = MusicTrack("yt_abcdefghijk", "Track", "Artist", "Album", 180_000,
            if (videoThumbnail) "https://i.ytimg.com/vi/abcdefghijk/hq720.jpg" else originalCover, "audio")
        val resolvedCover = java.util.concurrent.atomic.AtomicReference<ResolvedPlayerCover?>()
        val tracks = MutableStateFlow<MusicTrack?>(track)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            prepareCurrentPlayerArtwork(context, tracks, MutableStateFlow(true), scope,
                onCoverResolved = resolvedCover::set,
                resolveCover = { if (videoThumbnail) originalCover else null }
            ) { loader }
            val ready = withTimeoutOrNull(5000) { while (TrackPaletteCache.get(track.id) == null) delay(10); true }
            assertTrue("Prefetch must finish: $events", ready == true)
            val metrics = context.resources.displayMetrics
            val pixels = minOf(metrics.widthPixels, metrics.heightPixels).coerceIn(1, 3000)
            val source = mapTargetSizeToArtworkDimension(ImageConfig.PLAYER_SCREEN_SIZE, IntSize(pixels, pixels))
            val artworkTrack = playerCoverState(com.example.playback.PlayerUiState(currentTrack = track), resolvedCover.get()).currentTrack!!
            assertEquals(originalCover, artworkTrack.coverUrl)
            assertEquals(track.copy(coverUrl = originalCover), artworkTrack)
            assertFalse("Song-cover requests must not download video frames: $events",
                events.any { it.startsWith("https://i.ytimg.com") && it.endsWith("cacheOnly=false") })
            val key = playerPaletteKey(artworkTrack, true, Color.White, Color.Black, providerArtworkUrl(artworkTrack.coverUrl, source))
            assertNotNull("Opening can initialize the backdrop from its prepared palette", TrackPaletteCache.get(key))
            val request = buildOptimizedImageRequest(context, artworkTrack.coverUrl, ImageConfig.PLAYER_SCREEN_SIZE, artworkTrack,
                decodeSize = IntSize(300, 300), currentTrack = true)
            if (videoThumbnail) assertFalse(request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.allowVideoFallback)
            val visible = loader.execute(request) as SuccessResult
            assertTrue(visible.dataSource == DataSource.MEMORY_CACHE || visible.dataSource == DataSource.DISK)
            assertEquals("The high-resolution cover, palette and visible target share one source download: $events", 1, calls.get())
            tracks.value = track.copy(audioUrl = "new-provider", source = "JioSaavn", bitrateKbps = 320)
            delay(100)
            assertEquals("Audio-source changes must not repeat image downloads", 1, calls.get())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); loader.shutdown(); directory.deleteRecursively() }
    }
    @Test fun failedSongCoverDownloadDoesNotReplaceTheCurrentArtwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val finished = CompletableDeferred<coil.request.ImageResult>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404)
                .message("Missing artwork").body(byteArrayOf().toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain -> chain.proceed(chain.request).also { finished.complete(it) } })
        }.okHttpClient(client).networkObserverEnabled(false).build()
        val track = MusicTrack("yt_abcdefghijk", "Track", "Artist", "Album", 180000,
            "https://i.ytimg.com/vi/abcdefghijk/hq720.jpg", "audio")
        val published = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            prepareCurrentPlayerArtwork(context, MutableStateFlow(track), MutableStateFlow(true), scope,
                onCoverResolved = { published.incrementAndGet() },
                resolveCover = { "https://lh3.googleusercontent.com/missing=w600-h600-l90-rj" }
            ) { loader }
            assertTrue(withTimeout(5_000) { finished.await() } is coil.request.ErrorResult)
            assertEquals(0, published.get())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); loader.shutdown() }
    }

    @Test fun enrichedRecordingMetadataRetriesCoverSelectionWithoutChangingTheSelectedSong() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val loader = ImageLoader.Builder(context).components {
            add(object : coil.fetch.Fetcher.Factory<Any> {
                override fun create(data: Any, options: coil.request.Options, imageLoader: ImageLoader) = coil.fetch.Fetcher {
                    coil.fetch.DrawableResult(android.graphics.drawable.BitmapDrawable(context.resources, image), false, DataSource.MEMORY_CACHE)
                }
            })
        }.build()
        val unknown = MusicTrack("yt_abcdefghijk", "Track", "Unknown", "Album", 0,
            "https://i.ytimg.com/vi/abcdefghijk/hq720.jpg", "audio")
        val tracks = MutableStateFlow<MusicTrack?>(unknown)
        val firstLookup = CompletableDeferred<Unit>()
        val published = CompletableDeferred<ResolvedPlayerCover>()
        val calls = AtomicInteger()
        val square = "https://lh3.googleusercontent.com/actual-album=w600-h600-l90-rj"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            prepareCurrentPlayerArtwork(context, tracks, MutableStateFlow(true), scope,
                onCoverResolved = { published.complete(it) }, resolveCover = { song ->
                    calls.incrementAndGet()
                    if (song.artist == "Unknown") { firstLookup.complete(Unit); null } else square
                }, loaderFor = { loader })
            withTimeout(5_000) { firstLookup.await() }
            val enriched = unknown.copy(artist = "Verified Artist", durationMs = 180_000, primaryArtists = listOf("Verified Artist"))
            tracks.value = enriched
            val cover = withTimeout(5_000) { published.await() }
            assertEquals(square, cover.url)
            assertEquals(com.example.data.remote.playerCoverIdentity(enriched), cover.identity)
            assertEquals(2, calls.get())
            assertEquals("The source track and audio stay untouched", unknown.id, tracks.value!!.id)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); loader.shutdown() }
    }

}
