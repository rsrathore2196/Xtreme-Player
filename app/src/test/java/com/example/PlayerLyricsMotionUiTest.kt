package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.example.data.model.LyricLine
import com.example.data.model.MusicTrack
import com.example.data.model.TrackLyrics
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerLyricsMotionUiTest {
    @get:Rule val compose = createComposeRule()
    private val lyrics = TrackLyrics("lyrics-motion", "Song", "Artist", true,
        List(40) { LyricLine(1_000 + it * 5_000L, "Vocal line $it", 4_000) })

    @Test fun minimizingMovesTheActiveLineAndReturningControlsContinuouslyWithoutASettlingJump() = checkMinimizing()

    @Test fun largerSystemTextKeepsTheSameSmoothReturnAndMeasuredHeaderInset() = checkMinimizing(fontScale = 1.4f)

    private fun checkMinimizing(fontScale: Float = 1f) {
        val song = MusicTrack(lyrics.trackId, "Song", "Artist", "Album", 180_000, "", "")
        var seek: Long? = null
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false,
                dynamicAlbumGlow = false), LocalDynamicGlassEnabled provides false,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = song, currentPositionMs = 61_500L),
                    {}, {}, { seek = it }, {}, {}, {}, {}, {}, {}, onAddToPlaylist = {}, lyrics = lyrics,
                    isScreenVisible = false, modifier = Modifier.testTag("lyrics_motion_review"))
            }
        } }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("player_lyrics_button").performClick()
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithText("Vocal line 12").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(700)
        fun vocalY() = compose.onNodeWithText("Vocal line 12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center.y
        fun capture(name: String) = compose.onNodeWithTag("lyrics_motion_review").captureRoboImage(
            "/tmp/lyrics-motion-$fontScale-$name.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        val original = vocalY()
        capture("normal")
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("expanded_player_play_pause").assertIsNotDisplayed()
        var previous = vocalY()
        assertTrue(previous > original)
        capture("maximized")
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        repeat(30) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val current = vocalY()
            assertTrue("The returning controls cannot make the vocal bounce (frame=$frame)", current <= previous + 1f)
            previous = current
            if (frame == 10) capture("returning")
        }
        compose.onNodeWithTag("expanded_player_play_pause").assertIsDisplayed()
        assertEquals("The vocal lands at its original center", original, vocalY(), 2f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals("No second centering animation may start after the controls land", original, vocalY(), 2f)
        capture("restored")
        compose.onNodeWithText("Vocal line 12").performClick()
        compose.runOnIdle { assertEquals(61_000L, seek) }
        // Reversing a partially completed maximize must still land on the same geometry.
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(700)
        assertEquals(original, vocalY(), 2f)
        compose.onNodeWithTag("expanded_player_play_pause").assertIsDisplayed()
    }

    @Test fun aRetainedHiddenLyricsViewStopsReadingTheLivePlaybackClockAndResumesAtTheCurrentVocal() {
        var active by mutableStateOf(true)
        var position = 61_500L
        var reads = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false) {
                Box(Modifier.size(360.dp, 600.dp)) {
                    SyncedLyricsView(lyrics, currentPositionMs = 61_500L, isPlaying = true,
                        currentPositionProvider = { reads++; position }, onSeekTo = {}, active = active)
                }
            }
        } }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { active = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val stopped = reads
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("An unplaced retained panel must not poll playback", stopped, reads)
        compose.runOnIdle { position = 71_500L; active = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertTrue(reads > stopped)
        compose.onNodeWithText("Vocal line 14").assertIsDisplayed()
    }
}
