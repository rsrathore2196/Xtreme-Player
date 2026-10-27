package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniPlayerPresentationUiTest {
    @get:Rule val compose = createComposeRule()
    private val song = MusicTrack("mini", "Song", "Artist", "Album", 180_000, "", "")

    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun capture(tag: String): BufferedImage {
        val file = File.createTempFile("mini-presentation-", ".png")
        try {
            compose.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            return ImageIO.read(file)
        } finally { file.delete() }
    }

    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    @Test fun playbackButtonsDoNotChangeWithTheAccentAndKeepTheirOwnClickActions() {
        var colors by mutableStateOf(DarkAppColors)
        var playing by mutableStateOf(false)
        var playTaps = 0
        var skips = 0
        var opens = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors,
                LocalDynamicGlassEnabled provides colors.dynamicGlassEnabled,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                Box(Modifier.fillMaxSize().background(colors.scaffoldBackground).padding(16.dp)) {
                    MiniPlayer(PlayerUiState(currentTrack = song, isPlaying = playing),
                        { playTaps++; playing = !playing }, { skips++ }, {}, { opens++ })
                }
            }
        } }
        for (base in listOf(DarkAppColors, LightAppColors)) for (active in listOf(false, true)) {
            compose.runOnIdle { colors = base.copy(primaryAccent = Color.Magenta, secondaryAccent = Color.Magenta); playing = active }
            settle()
            val before = listOf("mini_player_play_pause", "mini_player_next").associateWith { pixels(capture(it)) }
            compose.runOnIdle { colors = colors.copy(primaryAccent = Color.Green, secondaryAccent = Color.Green) }
            settle()
            before.forEach { (tag, image) ->
                val after = capture(tag)
                val result = pixels(after)
                assertEquals(image.size, result.size)
                for (y in 0 until after.height) for (x in 0 until after.width) {
                    val dx = x - after.width / 2f
                    val dy = y - after.height / 2f
                    if (dx * dx + dy * dy >= (after.width / 2f - 3f) * (after.width / 2f - 3f)) continue
                    val index = y * after.width + x
                    for (shift in listOf(16, 8, 0)) {
                        // Clear glass still transmits the unchanged dock's very faint ambient tint.
                        assertTrue("$tag cannot acquire an accent fill or icon tint",
                            kotlin.math.abs(((image[index] shr shift) and 255) - ((result[index] shr shift) and 255)) <= 6)
                    }
                }
            }
        }
        compose.onNodeWithTag("mini_player_play_pause").performClick()
        compose.onNodeWithTag("mini_player_next").performClick()
        compose.runOnIdle { assertEquals(1, playTaps); assertEquals(1, skips); assertEquals(0, opens) }
    }

    @Test fun longTitlesScrollAutomaticallyWhileShortTitlesStayStill() {
        var track by mutableStateOf(song.copy(title = "Northern lights across the ocean with a very long unforgettable song title"))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                Box(Modifier.fillMaxSize().padding(16.dp)) { MiniPlayer(PlayerUiState(currentTrack = track), {}, {}, {}, {}) }
            }
        } }
        compose.mainClock.advanceTimeBy(400)
        val start = pixels(capture("mini_player_track_title"))
        compose.mainClock.advanceTimeBy(1_500)
        assertFalse("An overflowing title moves without focus or user input", start.contentEquals(pixels(capture("mini_player_track_title"))))
        compose.runOnIdle { track = song }
        settle()
        val short = pixels(capture("mini_player_track_title"))
        compose.mainClock.advanceTimeBy(1_500)
        assertArrayEquals("A fitting title must remain still", short, pixels(capture("mini_player_track_title")))
    }
}
