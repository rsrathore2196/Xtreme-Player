package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
        "https://is1-ssl.mzstatic.com/image/thumb/Music/cover/100x100bb.jpg", "audio")

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

    @Test fun fullScreenItunesArtworkHasNoTintOrOpacityMask() {
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
        // Allow the remembered direct iTunes request's small IO producer to publish.
        compose.mainClock.advanceTimeBy(800)
        val image = capture("flip_album_art_card")
        for (fraction in listOf(.05f, .2f, .5f, .8f, .95f)) {
            assertEquals("Cover pixels must remain unmodified", coverColor,
                image.getRGB(image.width / 2, (image.height * fraction).toInt()))
        }
    }
}
