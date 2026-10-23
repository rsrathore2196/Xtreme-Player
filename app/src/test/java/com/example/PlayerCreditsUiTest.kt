package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.ExpandedPlayerScreen
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerCreditsUiTest {
    @get:Rule val compose = createComposeRule()
    @Before @After fun resetSignalAndMonitor() {
        AudioSignalPath.reset()
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
        compose.onNodeWithText("Composer, Producer, Lead • Album Name").assertDoesNotExist()
        compose.onNodeWithTag("expanded_player_play_pause").assertHasClickAction()
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
        compose.onAllNodesWithText("AAC • 320 kbps").assertCountEquals(1)
    }
}
