package com.example

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.example.playback.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AudioQualityBadgeClassificationTest {
    @After fun clear() { AudioSignalPath.reset() }
    private fun lossless(bits: Int, hz: Int) = StreamQualityBadge("FLAC", 1500, hz, bits, lossless = true, verified = true)

    @Test fun sourceDepthAndRateDetermineLosslessBadgeAcrossProviders() {
        for (bits in listOf(16, 24)) assertEquals("Lossless", lossless(bits, 44100).badgeLabel)
        assertEquals("Lossless", lossless(16, 48000).badgeLabel)
        for (hz in listOf(48000, 88200, 96000, 176400, 192000)) assertEquals("Hi-Res Lossless", lossless(24, hz).badgeLabel)
        assertEquals("ALAC • 24-bit/96.0 kHz", lossless(24, 96000).copy(codec = "ALAC").label)
        assertEquals("Hi-Res Lossless", lossless(24, 96000).copy(codec = "ALAC").badgeLabel)
    }

    @Test fun lossyCodecAndUnknownSourceDepthCannotBorrowDecoderFloatPrecision() {
        assertEquals("AAC • 320 kbps", StreamQualityBadge("AAC", 320, 96000, 24, false, true).badgeLabel)
        assertEquals("OPUS • 160 kbps", StreamQualityBadge("OPUS", 160, 48000, verified = true).badgeLabel)
        val floatSource = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(96000).build()
        val unknown = StreamQualityBadge.from(floatSource)
        assertEquals(0, unknown.bitDepth)
        assertFalse(unknown.badgeLabel.contains("Lossless"))
        assertFalse(lossless(24, 96000).copy(verified = false).badgeLabel.contains("Lossless"))
    }

    @Test fun popupKeepsCompressedBitrateSeparateFromUncompressedPcmDataRate() {
        val state = SignalPathState(quality = lossless(24, 44100).copy(bitrateKbps = 1658), sourceChannels = 2)
        assertEquals("Lossless", state.quality.badgeLabel)
        assertEquals(2116L, state.sourcePcmDataRateKbps)
        assertEquals(1658, state.quality.bitrateKbps)
        assertNull(state.copy(sourceChannels = 0).sourcePcmDataRateKbps)
        assertNull(state.copy(quality = state.quality.copy(lossless = false)).sourcePcmDataRateKbps)
    }

    @Test fun formatFromOldTransportCannotRestorePreviousDecoderTelemetry() {
        AudioSignalPath.source("song", "YouTube Music", uri = "https://old.example/audio")
        AudioSignalPath.decoder("song", "https://old.example/audio", "old.decoder")
        AudioSignalPath.source("song", "Internet Archive", uri = "https://archive.org/download/item/song.flac")
        AudioSignalPath.decoder("song", "https://old.example/audio", "late.old.decoder")
        assertEquals("", AudioSignalPath.state.value.decoderName)
        assertFalse(AudioSignalPath.state.value.quality.verified)
        AudioSignalPath.decoder("song", "https://archive.org/download/item/song.flac", "c2.android.flac.decoder")
        val current = AudioSignalPath.state.value
        AudioSignalPath.source("song", "Internet Archive", uri = current.streamUri)
        assertSame(current, AudioSignalPath.state.value)
        AudioSignalPath.source("next", "YouTube Music", uri = "https://next.example/audio")
        AudioSignalPath.decoder("song", current.streamUri, "late.decoder")
        assertEquals("next", AudioSignalPath.state.value.trackId)
        assertEquals("", AudioSignalPath.state.value.decoderName)
    }
    @Test fun decoderAndOutputConversionDoNotBorrowTheSourceHiResClaim() {
        val source = SignalPathState(quality = lossless(24, 96000), decoderEncoding = C.ENCODING_PCM_FLOAT,
            outputEncoding = C.ENCODING_PCM_16BIT)
        assertEquals("Hi-Res Lossless", source.quality.badgeLabel)
        assertTrue(source.sourcePrecisionReduced)
        assertFalse(source.copy(outputEncoding = C.ENCODING_PCM_FLOAT).sourcePrecisionReduced)
        assertTrue(source.copy(decoderEncoding = C.ENCODING_PCM_16BIT, outputEncoding = C.ENCODING_PCM_FLOAT).sourcePrecisionReduced)
        assertTrue(source.copy(quality = lossless(32, 96000), outputEncoding = C.ENCODING_PCM_FLOAT).sourcePrecisionReduced)
        assertFalse(source.copy(decoderEncoding = C.ENCODING_INVALID, outputEncoding = C.ENCODING_INVALID).sourcePrecisionReduced)
    }

}
