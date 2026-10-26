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

    @Test fun selectedCoverAndColorsArePreparedBeforeOpeningAndReusedByVisibleRequests() = runBlocking {
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
        val track = MusicTrack("yt_abcdefghijk", "Track", "Artist", "Album", 180_000,
            "https://lh3.googleusercontent.com/catalog=w60-h60-l90-rj", "audio")
        val tracks = MutableStateFlow<MusicTrack?>(track)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            prepareCurrentPlayerArtwork(context, tracks, MutableStateFlow(true), scope) { loader }
            val ready = withTimeoutOrNull(5000) { while (TrackPaletteCache.get(track.id) == null) delay(10); true }
            assertTrue("Prefetch must finish: $events", ready == true)
            val key = playerPaletteKey(track, true, Color.White, Color.Black, providerArtworkUrl(track.coverUrl, 2000))
            assertNotNull("Opening can initialize the backdrop from its prepared palette", TrackPaletteCache.get(key))
            val request = buildOptimizedImageRequest(context, track.coverUrl, 2000, track,
                decodeSize = IntSize(300, 300), currentTrack = true)
            val visible = loader.execute(request) as SuccessResult
            assertTrue(visible.dataSource == DataSource.MEMORY_CACHE || visible.dataSource == DataSource.DISK)
            assertEquals("The high-resolution cover, palette and visible target share one source download: $events", 1, calls.get())
            tracks.value = track.copy(audioUrl = "new-provider", source = "JioSaavn", bitrateKbps = 320)
            delay(100)
            assertEquals("Audio-source changes must not repeat image downloads", 1, calls.get())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); loader.shutdown(); directory.deleteRecursively() }
    }
}
