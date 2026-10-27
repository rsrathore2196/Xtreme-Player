package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.example.data.model.*
import com.example.playback.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.ExtractedTrackColors
import com.example.ui.util.TrackPaletteCache
import com.example.ui.util.playerPaletteKey
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
class PlayerLyricsContrastUiTest {
    @get:Rule val compose = createComposeRule()
    @Before @After fun reset() {
        TrackPaletteCache.clear()
        AudioSignalPath.reset()
        val field = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? NetworkQualityMonitor)?.let {
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }

    @Test fun portraitLyricsAndHeartAdaptToTheActualBackdropInEitherTheme() = checkContrast()
    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeLyricsAndHeartAdaptToTheActualBackdropInEitherTheme() = checkContrast()

    private fun checkContrast() {
        val song = MusicTrack("bright-player", "Song", "Artist", "Album", 180_000, "", "")
        val lyrics = TrackLyrics(song.id, song.title, song.artist, true,
            listOf(LyricLine(1_000, "Clearly readable lyrics", 4_000), LyricLine(8_000, "Upcoming lyrics", 4_000)))
        TrackPaletteCache.put(playerPaletteKey(song, true, Color.White, Color.Black),
            ExtractedTrackColors(Color.White, Color.White, Color.White))
        var theme by mutableStateOf(DarkAppColors.copy(dynamicGlassEnabled = true, dynamicAlbumGlow = true))
        var state by mutableStateOf(PlayerUiState(currentTrack = song, currentPositionMs = 1_500, isFavorite = true))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides theme, LocalDynamicGlassEnabled provides theme.dynamicGlassEnabled) {
                ExpandedPlayerScreen(state, {}, {}, {}, {}, {}, {}, {}, { state = state.copy(isFavorite = !state.isFavorite) }, {},
                    onAddToPlaylist = {}, lyrics = lyrics, isScreenVisible = false)
            }
        } }
        compose.mainClock.advanceTimeBy(800)
        assertHeart(Color.Black)
        compose.onNodeWithTag("player_favorite_toggle").performClick()
        compose.runOnIdle { assertFalse(state.isFavorite) }
        compose.onNodeWithTag("player_favorite_toggle").performClick()
        compose.runOnIdle { assertTrue(state.isFavorite) }
        val navigationUntil = com.example.performance.BackgroundWorkCoordinator::class.java.getDeclaredField("navigationUntil")
            .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicLong
        compose.runOnIdle { navigationUntil.set(0L) }
        compose.onNodeWithTag("player_lyrics_button").performClick()
        assertTrue("The tap must pause optional work before the transition's first frame",
            navigationUntil.get() > android.os.SystemClock.elapsedRealtime())
        compose.mainClock.advanceTimeBy(1_000)
        assertLyric(Color.Black)
        assertHeart(Color.Black)
        compose.runOnIdle { theme = LightAppColors.copy(dynamicGlassEnabled = true, dynamicAlbumGlow = true) }
        compose.mainClock.advanceTimeBy(800)
        assertLyric(Color.Black)
        assertHeart(Color.Black)
        compose.runOnIdle { theme = theme.copy(dynamicGlassEnabled = false) }
        compose.mainClock.advanceTimeBy(800)
        assertLyric(Color.White)
        assertHeart(Color.White)
    }

    private fun assertLyric(expected: Color) {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Clearly readable lyrics", useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        val actual = result.single().layoutInput.style.color
        assertEquals(expected.red, actual.red, .001f)
        assertEquals(expected.green, actual.green, .001f)
        assertEquals(expected.blue, actual.blue, .001f)
        assertTrue("Existing lyric fade opacity must remain visible", actual.alpha > 0f)
    }

    private fun assertHeart(expected: Color) {
        val file = java.io.File.createTempFile("player-heart-contrast-", ".png")
        try {
            compose.onNodeWithTag("player_favorite_toggle").assertIsDisplayed().captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val image = javax.imageio.ImageIO.read(file)
            val center = java.awt.Color(image.getRGB(image.width / 2, image.height / 2))
            assertEquals(expected.red * 255, center.red.toFloat(), 2f)
            assertEquals(expected.green * 255, center.green.toFloat(), 2f)
            assertEquals(expected.blue * 255, center.blue.toFloat(), 2f)
        } finally { file.delete() }
    }
}
