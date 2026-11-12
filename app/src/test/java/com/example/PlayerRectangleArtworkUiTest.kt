package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import com.example.ui.components.PlayerCoverArtwork
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerRectangleArtworkUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rectangleRetainsBothSidesAndBlendsIntoTheExistingBackdropInBothPlayerModes() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previous = Coil.imageLoader(context)
        val fetches = AtomicInteger()
        val image = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(android.graphics.Color.WHITE)
                drawRect(0f, 0f, 16f, 90f, Paint().apply { color = android.graphics.Color.RED })
                drawRect(144f, 0f, 160f, 90f, Paint().apply { color = android.graphics.Color.BLUE })
            }
        }
        val loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Any> {
                override fun create(data: Any, options: Options, imageLoader: ImageLoader) = Fetcher {
                    fetches.incrementAndGet()
                    DrawableResult(BitmapDrawable(context.resources, image), false, DataSource.MEMORY_CACHE)
                }
            })
        }.build()
        Coil.setImageLoader(loader)
        var fullScreen by mutableStateOf(false)
        var shown by mutableStateOf(false)
        var containerBounds: Rect? = null
        var imageBounds: Rect? = null
        val request = ImageRequest.Builder(context).data("https://cover.example/video").crossfade(false).build()
        val backdrop = Brush.verticalGradient(listOf(Color(0xffa43568), Color(0xff6b713a)))
        try {
            compose.setContent {
                Box(Modifier.size(200.dp, if (fullScreen) 320.dp else 200.dp).background(backdrop)
                    .onGloballyPositioned { containerBounds = it.boundsInRoot() }.testTag("rectangle_player")) {
                    PlayerCoverArtwork(request, Modifier.fillMaxSize(), fullScreen,
                        onReady = { shown = it }, onBoundsChanged = { imageBounds = it })
                }
            }
            compose.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                shown
            }
            fun verify(name: String) {
                val file = File("/tmp/player-rectangle-$name-${android.os.Build.VERSION.SDK_INT}.png")
                compose.onNodeWithTag("rectangle_player").captureRoboImage(file.absolutePath,
                    RoborazziOptions(taskType = RoborazziTaskType.Record))
                val rendered = javax.imageio.ImageIO.read(file)
                val centerY = rendered.height / 2
                val left = java.awt.Color(rendered.getRGB(rendered.width * 4 / 100, centerY))
                val right = java.awt.Color(rendered.getRGB(rendered.width * 96 / 100, centerY))
                assertTrue("The left edge of the video must not be cropped: $left", left.red > 240 && left.blue < 20)
                assertTrue("The right edge of the video must not be cropped: $right", right.blue > 240 && right.red < 20)
                assertEquals(android.graphics.Color.WHITE, rendered.getRGB(rendered.width / 2, centerY))
                val top = java.awt.Color(rendered.getRGB(rendered.width / 2, 2))
                val bottom = java.awt.Color(rendered.getRGB(rendered.width / 2, rendered.height - 3))
                assertTrue("Top margin reveals the unchanged backdrop", top.red in 155..170 && top.green in 45..62)
                assertTrue("Bottom margin reveals the unchanged backdrop", bottom.red in 100..115 && bottom.green in 105..120)
                val container = requireNotNull(containerBounds)
                val actualImage = requireNotNull(imageBounds)
                val expectedHeight = container.width * 90f / 160f
                assertEquals("Backdrop blending must use the fitted image, not its taller host",
                    expectedHeight, actualImage.height, .01f)
                assertEquals(container.top + (container.height - expectedHeight) / 2f, actualImage.top, .01f)
            }
            verify("normal")
            val before = fetches.get()
            compose.runOnIdle { fullScreen = true }
            compose.waitForIdle()
            verify("fullscreen")
            assertEquals("Changing layout reuses the same image source", before, fetches.get())
        } finally { Coil.setImageLoader(previous); loader.shutdown() }
    }
}
