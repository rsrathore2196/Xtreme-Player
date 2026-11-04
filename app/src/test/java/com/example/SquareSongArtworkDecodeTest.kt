package com.example

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.request.SuccessResult
import com.example.data.model.MusicTrack
import com.example.ui.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SquareSongArtworkDecodeTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @org.junit.Before fun prepareMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @OptIn(ExperimentalCoroutinesApi::class)
    @org.junit.After fun resetMain() { Dispatchers.resetMain() }
    @Test fun strictSongCoverNeverUsesVideoFallbackOrCropsAnUnexpectedRectangleIntoSuccess() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bytes = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
            }; out.toByteArray()
        }
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val cached = chain.request().cacheControl.onlyIfCached
            if (!cached) fetched.add(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (cached) 504 else 200)
                .message("fixture").body((if (cached) byteArrayOf() else bytes).toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(app).components { add(ArtworkResolutionInterceptor()) }
            .okHttpClient(client).networkObserverEnabled(false).build()
        val song = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180_000,
            "https://lh3.googleusercontent.com/claimed-song=w600-h600-l90-rj", "audio")
        try {
            val request = buildOptimizedImageRequest(app, song.coverUrl, 450, song,
                decodeSize = IntSize(300, 300), requireSquareSongArtwork = true, visibleThumbnail = true)
            assertFalse(request.parameters.value<ArtworkRequestMetadata>(ARTWORK_METADATA_PARAMETER)!!.allowVideoFallback)
            assertFalse(loader.execute(request) is SuccessResult)
            assertTrue(fetched.isNotEmpty())
            assertFalse(fetched.any { it.contains("ytimg") })
            fetched.clear()
            val blank = buildOptimizedImageRequest(app, "", 450, song, requireSquareSongArtwork = true)
            assertEquals("", blank.data)
            assertFalse(loader.execute(blank) is SuccessResult)
            assertTrue("The placeholder is never a synthetic YouTube video still", fetched.isEmpty())
        } finally { loader.shutdown() }
    }
}
