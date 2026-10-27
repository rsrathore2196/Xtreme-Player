package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.example.data.model.MusicTrack
import com.example.ui.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.CopyOnWriteArrayList

/** Actual encoded PNG bodies pass through Coil's HTTP fetcher and Android bitmap decoder. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkResolutionDecodeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun png(dimension: Int) = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
            compress(Bitmap.CompressFormat.PNG, 100, out)
            recycle()
        }
        out.toByteArray()
    }

    private suspend fun fixture(images: Map<String, Int>, block: suspend (ImageLoader, List<String>) -> Unit) {
        val bytes = images.mapValues { png(it.value) }
        val fetched = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val cacheOnly = request.cacheControl.onlyIfCached
            if (!cacheOnly) fetched += url
            val body = bytes[url]?.takeUnless { cacheOnly }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (cacheOnly) 504 else if (body == null) 404 else 200).message("Artwork fixture")
                .header("Cache-Control", "public, max-age=86400")
                .body((body ?: byteArrayOf()).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val directory = File(context.cacheDir, "artwork-resolution-${System.nanoTime()}").apply { mkdirs() }
        val loader = ImageLoader.Builder(context).components { add(ArtworkResolutionInterceptor()) }
            .interceptorDispatcher(Dispatchers.IO).fetcherDispatcher(Dispatchers.IO)
            .decoderDispatcher(Dispatchers.IO).transformationDispatcher(Dispatchers.IO)
            .okHttpClient(client).networkObserverEnabled(false).respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(48 * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(directory).maxSizeBytes(4L * 1024 * 1024).build() }.build()
        try { block(loader, fetched) }
        finally { loader.shutdown(); directory.deleteRecursively() }
    }

    @Test fun cachedNative480pxIsOnlyAPreviewAndTheSharpResultSurvivesHeaderAndPaletteRequests() = runBlocking {
        val original = "https://lh3.googleusercontent.com/album=w60-h60-l90-rj"
        val primary = providerArtworkUrl(original, 2000)
        val sharper = youtubeArtworkAlternatives("yt_abcdefghijk", emptyList()).first()
        fixture(mapOf(primary to 480, sharper to 1600)) { loader, fetched ->
            val cached = loader.execute(ImageRequest.Builder(context).data(primary).size(480)
                .precision(Precision.INEXACT).allowHardware(false).build()) as SuccessResult
            assertEquals(480, (cached.drawable as BitmapDrawable).bitmap.width)
            val previews = CopyOnWriteArrayList<Int>()
            val track = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180000, original, "")
            val request = buildOptimizedImageRequest(context, original, 2000, track,
                decodeSize = IntSize(1600, 1600), currentTrack = true).newBuilder().allowHardware(false)
                .target(onStart = { drawable -> (drawable as? BitmapDrawable)?.bitmap?.width?.let(previews::add) }).build()
            val full = loader.execute(request) as SuccessResult
            assertTrue("Cached cover should be shown during the upgrade: $previews", 480 in previews)
            assertEquals(sharper, full.request.data)
            val fullBitmap = (full.drawable as BitmapDrawable).bitmap
            assertEquals(1600, fullBitmap.width)
            assertEquals(1600, fullBitmap.height)
            assertEquals(listOf(primary, sharper), fetched)
            val header = playerHeaderArtworkRequest(full.request)
            val palette = playerPaletteRequest(full.request)
            assertEquals(sharper, header.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.providerUrl)
            assertEquals(sharper, palette.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.providerUrl)
            assertTrue(loader.execute(header) is SuccessResult)
            assertTrue(loader.execute(palette) is SuccessResult)
            val again = loader.execute(request) as SuccessResult
            assertEquals(1600, (again.drawable as BitmapDrawable).bitmap.width)
            assertEquals("Small derivatives must not cause downloads or downgrade the final cover", listOf(primary, sharper), fetched)
        }
    }

    @Test fun genuinelyLimitedProviderIsNeverUpscaledOrDownloadedAgain() = runBlocking {
        val original = "https://c.saavncdn.com/cover_150x150.jpg"
        val native = providerArtworkUrl(original, 2000)
        fixture(mapOf(native to 500)) { loader, fetched ->
            val track = MusicTrack("online_song", "Song", "Artist", "Album", 180000, original, "")
            val request = buildOptimizedImageRequest(context, original, 2000, track,
                decodeSize = IntSize(1600, 1600), currentTrack = true).newBuilder().allowHardware(false).build()
            repeat(2) {
                val result = loader.execute(request) as SuccessResult
                val bitmap = (result.drawable as BitmapDrawable).bitmap
                assertEquals(500, bitmap.width)
                assertEquals(500, bitmap.height)
                assertFalse(result.isSampled)
            }
            assertEquals(listOf(native), fetched)
        }
    }

    @Test fun heroFetchesAnAppropriateSourceButDecodesOnlyEnoughPixelsForTheViewport() = runBlocking {
        val original = "https://lh3.googleusercontent.com/hero=w60-h60-l90-rj"
        val source = providerArtworkUrl(original, 2000)
        fixture(mapOf(source to 2000)) { loader, fetched ->
            val request = buildOptimizedImageRequest(context, original, ImageConfig.HERO_BANNER_SIZE, null,
                decodeSize = IntSize(1800, 720), startupPreparation = true).newBuilder().allowHardware(false).build()
            assertEquals(source, request.data)
            val result = loader.execute(request) as SuccessResult
            val bitmap = (result.drawable as BitmapDrawable).bitmap
            assertTrue(bitmap.width in 1800..2000)
            assertTrue(bitmap.height in 720..2000)
            assertEquals(listOf(source), fetched)
        }
    }
}
