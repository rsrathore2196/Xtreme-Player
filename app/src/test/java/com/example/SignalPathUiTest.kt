package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.media3.common.C
import com.example.playback.*
import com.example.ui.components.AudioQualityBadge
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignalPathUiTest {
    @get:Rule val compose = createComposeRule()
    @After fun clear() { AudioSignalPath.reset() }
    private fun showBadge() {
        compose.setContent { MaterialTheme {
            AudioQualityBadge("playing", "Lossless", Color.DarkGray, Color.White, Color.White)
        } }
    }

    @Test fun actualQualityStaysVisibleWhenOnlyTheProviderIdChanges() {
        val uri = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000"
        val format = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
            .setAverageBitrate(128_000).setSampleRate(44100).build()
        AudioSignalPath.verifiedSource("playing", "YouTube Music", format, 128, uri = uri)
        showBadge()
        compose.onNodeWithText("AAC • 128 kbps").assertExists()
        compose.runOnIdle { AudioSignalPath.source("playing", "YouTube Music", sourceId = "youtube", uri = uri) }
        compose.onNodeWithText("AAC • 128 kbps").assertExists()
        compose.onNodeWithText("Resolving audio").assertDoesNotExist()
    }
    private fun playing() = SignalPathState(trackId = "playing", source = "YouTube Music",
        quality = StreamQualityBadge("OPUS", 160, 48000, verified = true), sourceChannels = 2,
        decoderName = "c2.android.opus.decoder", decoderEncoding = C.ENCODING_PCM_16BIT,
        dspEncoding = C.ENCODING_PCM_16BIT, dspRateHz = 48000,
        outputDevice = "Bluetooth TWS", outputType = "Bluetooth", inputRateHz = 48000, outputRateHz = 48000,
        outputEncoding = C.ENCODING_PCM_16BIT, bufferSizeFrames = 24000)

    @Test @Config(qualifiers = "w400dp-h800dp-port")
    fun portraitBadgeKeepsOriginalTextSize() = checkBadgeTextSize(10f)

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapeBadgeUsesLargerReadableText() = checkBadgeTextSize(12f)

    private fun checkBadgeTextSize(expected: Float) {
        AudioSignalPath.update { playing() }
        showBadge()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("OPUS • 160 kbps").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(expected, results.single().layoutInput.style.fontSize.value, .01f)
    }

    @Test fun badgeOpensPipelineWithActualStagesAndACompactCloseButton() {
        AudioSignalPath.update { playing() }
        showBadge()
        compose.onNodeWithText("OPUS • 160 kbps").performClick()
        compose.onNodeWithText("Audio Pipeline").assertExists()
        compose.onNodeWithText("Live signal path, decoder to output").assertExists()
        compose.onNodeWithTag("pipeline_0_Source").assertTextEquals("Source: YouTube Music")
        compose.onNodeWithTag("pipeline_0_Format").assertTextEquals("Format: OPUS")
        compose.onNodeWithTag("pipeline_0_Bit Depth").assertTextEquals("Bit Depth: —")
        compose.onNodeWithTag("pipeline_0_Bitrate").assertTextEquals("Bitrate: 160 kbps")
        compose.onNodeWithTag("pipeline_0_Sample Rate").assertTextEquals("Sample Rate: 48.0 kHz")
        compose.onNodeWithTag("pipeline_0_Channels").assertTextEquals("Channels: Stereo")
        compose.onNodeWithTag("pipeline_1_Decoder Name").assertTextEquals("Decoder Name: c2.android.opus.decoder")
        compose.onNodeWithText("Audio Enhancer").assertDoesNotExist()
        compose.onNodeWithTag("source_audio_enhancer").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
        val close = compose.onNodeWithTag("pipeline_close").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("pipeline_3_Buffers").performScrollTo().assertTextEquals("Buffers: 500 ms, 24000 frames")
        compose.onNodeWithTag("pipeline_3_Gain").performScrollTo().assertTextEquals("Gain: 0.0 dB (bypassed)")
        compose.onNodeWithTag("pipeline_4_System").performScrollTo()
            .assertTextEquals("System: Mixer / HAL format not exposed by Android")
        assertEquals(close, compose.onNodeWithTag("pipeline_close").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Close audio pipeline").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("signal_path_popup").assertDoesNotExist()
    }

    @Test fun hiResBadgeKeepsDetailedFlacMeasurementsInsideThePopup() {
        AudioSignalPath.update { playing().copy(source = "Custom lossless server", sourceChannels = 2,
            decoderName = "c2.android.flac.decoder", decoderEncoding = C.ENCODING_PCM_FLOAT,
            inputRateHz = 96000, outputRateHz = 96000, dspEncoding = C.ENCODING_PCM_FLOAT, dspRateHz = 96000,
            outputEncoding = C.ENCODING_PCM_FLOAT,
            quality = StreamQualityBadge("FLAC", 2831, 96000, 24, lossless = true, verified = true)) }
        showBadge()
        compose.onNodeWithText("Hi-Res Lossless").performClick()
        compose.onNodeWithTag("pipeline_0_Source").assertTextEquals("Source: Custom lossless server")
        compose.onNodeWithTag("pipeline_0_Format").assertTextEquals("Format: FLAC")
        compose.onNodeWithTag("pipeline_0_Bit Depth").assertTextEquals("Bit Depth: 24-bit")
        compose.onNodeWithTag("pipeline_0_Sample Rate").assertTextEquals("Sample Rate: 96.0 kHz")
        compose.onNodeWithTag("pipeline_0_Bitrate").assertTextEquals("Bitrate: 2831 kbps")
        compose.onNodeWithTag("pipeline_0_PCM Data Rate").assertTextEquals("PCM Data Rate: 4608 kbps")
    }

    @Test fun oldTrackOrUnverifiedFallbackCannotProduceLosslessBadges() {
        AudioSignalPath.update { playing().copy(trackId = "previous", quality =
            StreamQualityBadge("FLAC", 1000, 96000, 24, lossless = true, verified = true)) }
        showBadge()
        compose.onNodeWithText("Resolving audio").assertExists()
        compose.onNodeWithText("Hi-Res Lossless").assertDoesNotExist()
        compose.runOnIdle { AudioSignalPath.update { it.copy(trackId = "playing", quality = it.quality.copy(verified = false)) } }
        compose.onNodeWithText("Resolving audio").assertExists()
    }

    @Test fun popupStaysCenteredAsPlaybackFormatAndBadgeLengthChange() {
        AudioSignalPath.update { playing() }
        showBadge()
        compose.onNodeWithText("OPUS • 160 kbps").performClick()
        val before = compose.onNodeWithTag("signal_path_popup").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { AudioSignalPath.update { it.copy(source = "Internet Archive",
            quality = StreamQualityBadge("FLAC", 1700, 44100, 24, lossless = true, verified = true)) } }
        compose.onNodeWithTag("pipeline_0_Source").assertTextEquals("Source: Internet Archive")
        val after = compose.onNodeWithTag("signal_path_popup").fetchSemanticsNode().boundsInRoot
        assertEquals(before.center.x, after.center.x, 0.5f)
        assertEquals(before.width, after.width, 0.5f)
    }

    @Test fun unavailableBitrateAndMeasurementsStayUnknownRatherThanUsingTheQualitySetting() {
        AudioSignalPath.update { playing().copy(quality = StreamQualityBadge("OPUS", 0, 48000, verified = true)) }
        showBadge()
        compose.onNodeWithText("OPUS • 48.0 kHz").performClick()
        compose.onNodeWithTag("pipeline_0_Bitrate").assertTextEquals("Bitrate: —")
        compose.onNodeWithTag("pipeline_3_Measured").performScrollTo().assertTextEquals("Measured: Not measured")
        compose.onNodeWithTag("pipeline_4_Device Name").performScrollTo().assertTextEquals("Device Name: Bluetooth TWS")
    }
}
