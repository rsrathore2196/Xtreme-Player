package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import com.example.data.model.MusicTrack
import com.example.ui.components.InfiniteRadioHeroCard
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroArtworkFillUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun playlistVideoCoverFillsTheExistingRoundedHeroAndReusesItsCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = Coil.imageLoader(context)
        val requests = AtomicInteger()
        val complete = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val image = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(android.graphics.Color.GREEN)
                drawRect(350f, 0f, 1250f, 900f, Paint().apply { color = android.graphics.Color.MAGENTA })
            }
        }
        val loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Any> {
                override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                    requests.incrementAndGet()
                    DrawableResult(BitmapDrawable(context.resources, image), false, DataSource.MEMORY_CACHE)
                }
            })
        }.eventListener(object : coil.EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { complete.set(true) }
            override fun onError(request: ImageRequest, result: ErrorResult) { failure.set(result.throwable); complete.set(true) }
        }).build()
        Coil.setImageLoader(loader)
        var track by mutableStateOf(MusicTrack("online_playlist", "Song", "Artist", "Album", 180_000L,
            "https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", ""))
        try {
            // Exercise the real Coil engine/cache before the UI requests its viewport.
            val prepared = ImageRequest.Builder(context).data(track.coverUrl).size(1600, 900)
                .allowHardware(false).transformations(com.example.ui.util.HeroArtworkCropTransformation).build()
            val load = java.util.concurrent.CompletableFuture.supplyAsync { kotlinx.coroutines.runBlocking { loader.execute(prepared) } }
            compose.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); load.isDone }
            val decoded = load.get()
            assertTrue((decoded as? ErrorResult)?.throwable?.stackTraceToString(), decoded is SuccessResult)
            val fetches = requests.get()
            val cached = java.util.concurrent.CompletableFuture.supplyAsync { kotlinx.coroutines.runBlocking { loader.execute(prepared) } }
            compose.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); cached.isDone }
            assertTrue(cached.get() is SuccessResult)
            assertEquals(fetches, requests.get())
            complete.set(false)
            compose.setContent { MaterialTheme { InfiniteRadioHeroCard(track, null, false, onPlayInfiniteRadio = {}) } }
            compose.waitForIdle()
            compose.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); complete.get() }
            assertNull(failure.get()?.stackTraceToString(), failure.get())
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            val node = compose.onNodeWithTag("infinite_radio_hero_card")
            val before = node.fetchSemanticsNode().boundsInRoot
            val displayedFetches = requests.get()
            val file = File("/tmp/hero-cover-fill.png")
            node.captureRoboImage(file.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
            val rendered = ImageIO.read(file)
            for (x in listOf(rendered.width * 8 / 100, rendered.width * 92 / 100)) {
                val color = java.awt.Color(rendered.getRGB(x, rendered.height * 22 / 100))
                assertTrue("The cover must fill both sides of the existing card: $color", color.red > color.green)
            }
            compose.runOnIdle { track = track.copy(id = "online_second_playlist_occurrence", title = "Same album") }
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals(before, node.fetchSemanticsNode().boundsInRoot)
            assertEquals("Returning to the same artwork must reuse Coil's transformed cache", displayedFetches, requests.get())
        } finally { Coil.setImageLoader(previous); loader.shutdown() }
    }
}
