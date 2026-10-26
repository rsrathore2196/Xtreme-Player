package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.SuccessResult
import com.example.data.local.OtherSettingsPreferences
import com.example.data.model.MusicTrack
import com.example.playback.AudioSignalPath
import com.example.playback.NetworkQualityMonitor
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.playerAppearanceColors
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerAppearanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var previousLoader: ImageLoader? = null
    private var testLoader: ImageLoader? = null
    private val coverColor = android.graphics.Color.rgb(24, 176, 96)
    private val track = MusicTrack("appearance", "Title", "Artist", "Album", 180_000,
        "https://lh3.googleusercontent.com/cover=w120-h120", "audio")

    @Before fun prepare() { OtherSettingsPreferences.setFullScreenAlbumArt(context, false) }
    @After fun cleanup() {
        previousLoader?.let { Coil.setImageLoader(it) }
        testLoader?.shutdown()
        OtherSettingsPreferences.setFullScreenAlbumArt(context, false)
        AudioSignalPath.reset()
        val field = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? NetworkQualityMonitor)?.let {
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }
    private fun capture(tag: String): java.awt.image.BufferedImage {
        val file = java.io.File.createTempFile("player-appearance-", ".png")
        return try {
            compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            javax.imageio.ImageIO.read(file)
        } finally { file.delete() }
    }
    @Test fun favoritesRemainNeutralAndFunctionalInBothAppColorModes() {
        var colors by mutableStateOf(DarkAppColors.copy(primaryAccent = androidx.compose.ui.graphics.Color.Magenta,
            dynamicGlassEnabled = true, dynamicAlbumGlow = false))
        var state by mutableStateOf(PlayerUiState(currentTrack = track, isFavorite = true))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides true) {
                ExpandedPlayerScreen(state, {}, {}, {}, {}, {}, {}, {}, { state = state.copy(isFavorite = !state.isFavorite) }, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        compose.mainClock.advanceTimeBy(800)
        fun assertNeutral() {
            val image = capture("player_favorite_toggle")
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = java.awt.Color(image.getRGB(x, y), true)
                if (color.alpha > 100) assertTrue("Heart must not gain an accent or pink fill",
                    maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) <= 3)
            }
        }
        assertNeutral()
        compose.onNodeWithTag("player_favorite_toggle").performClick()
        compose.runOnIdle { assertFalse(state.isFavorite); colors = LightAppColors.copy(primaryAccent = androidx.compose.ui.graphics.Color.Red,
            dynamicGlassEnabled = true, dynamicAlbumGlow = false) }
        compose.mainClock.advanceTimeBy(800)
        assertNeutral()
        compose.onNodeWithTag("player_favorite_toggle").performClick()
        compose.runOnIdle { assertTrue(state.isFavorite) }
        assertEquals(playerAppearanceColors(DarkAppColors).textPrimary, playerAppearanceColors(LightAppColors).textPrimary)
    }

    @Test fun fullScreenProviderArtworkBlendsItsEdgesWithoutTintingItsCenter() {
        previousLoader = context.imageLoader
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(coverColor) }
        testLoader = ImageLoader.Builder(context).components {
            add(Interceptor { chain -> SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY_CACHE) })
        }.build().also { Coil.setImageLoader(it) }
        OtherSettingsPreferences.setFullScreenAlbumArt(context, true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false,
                dynamicAlbumGlow = false), LocalDynamicGlassEnabled provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = track), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        // Allow the direct provider image request to publish.
        compose.mainClock.advanceTimeBy(800)
        // The manually advanced animation clock does not await Coil's real IO
        // dispatcher. Wait for the cover to draw before checking its edge pixels.
        compose.waitUntil(3_000) {
            compose.mainClock.advanceTimeByFrame()
            val ready = capture("flip_album_art_card")
            ready.getRGB(ready.width / 2, ready.height / 2) == coverColor
        }
        val image = capture("flip_album_art_card")
        for (fraction in listOf(.3f, .5f, .7f)) {
            assertEquals("The center of the cover must remain unmodified", coverColor,
                image.getRGB(image.width / 2, (image.height * fraction).toInt()))
        }
        val backdrop = java.awt.Color(0xff101010.toInt())
        for (fraction in listOf(.001f, .999f)) {
            val pixel = java.awt.Color(image.getRGB(image.width / 2,
                (image.height * fraction).toInt().coerceIn(0, image.height - 1)))
            assertTrue("The top and bottom must dissolve into the backdrop",
                kotlin.math.abs(pixel.green - backdrop.green) < 8)
        }
        for (fraction in listOf(.08f, .9f)) {
            val pixel = java.awt.Color(image.getRGB(image.width / 2, (image.height * fraction).toInt()))
            assertTrue("The edge blend must be gradual, not a cropped boundary",
                pixel.green in (backdrop.green + 20) until (android.graphics.Color.green(coverColor) - 20))
        }
        compose.onNodeWithTag("flip_album_art_card").performClick()
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        compose.onNodeWithText("TRACK CREDITS & DETAILS").assertIsDisplayed()
        val credits = capture("flip_album_art_card")
        for ((x, y) in listOf(credits.width / 2 to 1, credits.width / 2 to credits.height - 2,
            2 to credits.height / 2, credits.width - 3 to credits.height / 2)) {
            val pixel = java.awt.Color(credits.getRGB(x, y))
            assertTrue("Full-screen credits must reveal the same backdrop without a fill or border",
                kotlin.math.abs(pixel.red - backdrop.red) <= 2 && kotlin.math.abs(pixel.green - backdrop.green) <= 2 &&
                    kotlin.math.abs(pixel.blue - backdrop.blue) <= 2)
        }
    }

    private fun assertClearPlayerControls() {
        var colors by mutableStateOf(DarkAppColors.copy(primaryAccent = Color.Magenta))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val backdrop = rememberLiquidGlassBackdrop()
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides true,
                LocalLiquidGlassBackdrop provides backdrop) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().xtremeSharedBackdropSource().background(Color(0xffc82c60)))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.size(72.dp).playerLiquidGlassButton(colors, isActive = true)
                            .testTag("clear_secondary"))
                        Box(Modifier.size(104.dp).playerPrimaryLiquidGlassButton(colors)
                            .testTag("clear_primary"))
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val dark = listOf("clear_secondary", "clear_primary").map(::capture)
        compose.runOnIdle { colors = LightAppColors.copy(primaryAccent = Color.Blue) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        listOf("clear_secondary", "clear_primary").forEachIndexed { index, tag ->
            val light = capture(tag)
            val center = java.awt.Color(light.getRGB(light.width / 2, light.height / 2))
            assertTrue("Player controls must reveal the backdrop, not a solid black fill", center.red > 160)
            assertTrue("Player controls must not add a white wash", center.green < 100)
            assertEquals(dark[index].width, light.width)
            assertEquals(dark[index].height, light.height)
            for (y in 0 until light.height) for (x in 0 until light.width) {
                assertEquals("App theme and accent must not tint player controls",
                    dark[index].getRGB(x, y), light.getRGB(x, y))
            }
        }
    }

    @Test fun playerControlsUseClearBackdropGlassInBothColorModes() = assertClearPlayerControls()

    @Test @Config(sdk = [31])
    fun clearPlayerControlsAlsoWorkOnAndroid12() = assertClearPlayerControls()
}
