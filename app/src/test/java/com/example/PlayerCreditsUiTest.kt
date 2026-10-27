package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.ExpandedPlayerScreen
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.ExtractedTrackColors
import com.example.ui.util.TrackPaletteCache
import com.example.ui.util.playerPaletteKey
import com.github.takahirom.roborazzi.*
import org.junit.Before
import org.junit.After
import com.example.playback.AudioSignalPath
import com.example.playback.NetworkQualityMonitor
import com.example.playback.PlaybackSource
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
class PlayerCreditsUiTest {
    @get:Rule val compose = createComposeRule()
    @Before @After fun resetSignalAndMonitor() {
        AudioSignalPath.reset()
        TrackPaletteCache.clear()
        val field = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? NetworkQualityMonitor)?.let {
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }

    @Test fun portraitShowsTwoSingersAndKeepsQualityInTheBadgeOnly() = checkCredits()

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeShowsTwoSingersAndKeepsQualityInTheBadgeOnly() = checkCredits()

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun compactLandscapeKeepsLargerPlayerTextAndControlsVisible() = checkCredits()

    @Test fun brightArtworkUsesDarkCreditsWithoutRecoloringThePlayer() = checkBrightCredits()

    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeCreditsAlsoAdaptToBrightArtwork() = checkBrightCredits()

    private fun checkBrightCredits() {
        val song = MusicTrack("bright-credits", "Bright Song", "Main Artist", "Bright Album", 180_000, "", "",
            singers = "Lead Singer", writer = "Song Writer")
        TrackPaletteCache.put(playerPaletteKey(song, true, Color.White, Color.Black),
            ExtractedTrackColors(Color.White, Color.White, Color.White))
        var theme by mutableStateOf(DarkAppColors.copy(dynamicGlassEnabled = true, dynamicAlbumGlow = true))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides theme, LocalDynamicGlassEnabled provides true) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = song), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("flip_album_art_card").performClick()
        compose.mainClock.advanceTimeBy(1200)
        fun assertCredits() {
            for (text in listOf("TRACK CREDITS & DETAILS", "Bright Song", "Bright Album", "Lead Singer")) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onAllNodesWithText(text, useUnmergedTree = true)
                    .filterToOne(hasAnyAncestor(hasTestTag("flip_album_art_card")))
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                org.junit.Assert.assertEquals("Credits must contrast with the white album backdrop", Color.Black,
                    results.single().layoutInput.style.color)
            }
            val header = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("NOW PLAYING").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(header) }
            org.junit.Assert.assertEquals("Other player text must stay unchanged", Color.White, header.single().layoutInput.style.color)
        }
        assertCredits()
        compose.runOnIdle { theme = LightAppColors.copy(dynamicGlassEnabled = true, dynamicAlbumGlow = true) }
        compose.mainClock.advanceTimeBy(800)
        assertCredits()
    }

    private fun checkCredits() {
        val song = MusicTrack("credits-track", "Song Title", "Composer, Producer, Lead", "Album Name",
            180_000, "", "", singers = "Lead, Guest, Third Singer", qualityBadge = "AAC • 320 kbps")
        // Requested/catalog quality alone is never verification of the playing stream.
        AudioSignalPath.source(song.id, "JioSaavn", sourceId = PlaybackSource.jio.id)
        AudioSignalPath.format(song.id, Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setAverageBitrate(320000).setSampleRate(44100).setChannelCount(2).build(), 0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(
                    dynamicGlassEnabled = false, dynamicAlbumGlow = false), LocalDynamicGlassEnabled provides false) {
                    ExpandedPlayerScreen(PlayerUiState(currentTrack = song, qualityBadge = "AAC • 320 kbps"),
                        onMinimize = {}, onPlayPause = {}, onSeekTo = {}, onSkipNext = {}, onSkipPrevious = {},
                        onToggleShuffle = {}, onToggleRepeat = {}, onToggleFavorite = {}, onOpenQueue = {},
                        onAddToPlaylist = {}, isScreenVisible = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Lead, Guest").assertIsDisplayed()
        val landscape = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
            .resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        assertFont("NOW PLAYING", if (landscape) 13f else 11f)
        assertFont("Lead, Guest", if (landscape) 16f else 14f)
        compose.onNodeWithText("Composer, Producer, Lead • Album Name").assertDoesNotExist()
        compose.onNodeWithTag("expanded_player_play_pause").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("player_minimize_button").assertIsDisplayed()
        compose.onNodeWithTag("player_options_button").performClick()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("player_audio_upgrade").assertExists()
        compose.onNodeWithText("Add to Queue").performClick()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("flip_album_art_card").performClick()
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithText("Audio Fidelity").assertDoesNotExist()
        compose.onNodeWithText("Quality").assertDoesNotExist()
        // Full credits and album details remain available behind the artwork.
        compose.onNodeWithText("Lead, Guest, Third Singer").assertExists()
        compose.onNodeWithText("Album Name").assertExists()
        val file = java.io.File.createTempFile("player-credits-", ".png")
        try {
            compose.onNodeWithTag("flip_album_art_card").captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val image = javax.imageio.ImageIO.read(file)
            for ((x, y) in listOf(2 to image.height / 2, image.width - 3 to image.height / 2,
                image.width / 2 to 1, image.width / 2 to image.height - 2)) {
                org.junit.Assert.assertEquals("Credits must share the player backdrop without a separate card edge",
                    0xff101010.toInt(), image.getRGB(x, y))
            }
        } finally { file.delete() }
        assertFont(if (landscape) "Album" else "Album / Movie", if (landscape) 13f else 11f)
        assertFont("Album Name", if (landscape) 14f else 12f)
        compose.onAllNodesWithText("AAC • 320 kbps").assertCountEquals(1)
    }

    private fun assertFont(text: String, size: Float) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        org.junit.Assert.assertEquals(size, results.single().layoutInput.style.fontSize.value, .01f)
    }
}
