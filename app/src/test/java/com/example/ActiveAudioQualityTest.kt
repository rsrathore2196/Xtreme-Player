package com.example

import android.app.Application
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.example.playback.AudioSignalPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class ActiveAudioQualityTest {
    @After fun clear() = AudioSignalPath.reset()

    @Test fun resolvingProviderIdentityAfterAudioStartsCannotEraseActualDecoderQuality() {
        val uri = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000"
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(128_000)
            .setSampleRate(44100).setChannelCount(2).build()
        AudioSignalPath.verifiedSource("yt_abcdefghijk", "YouTube Music", format, 128, uri = uri)
        val before = AudioSignalPath.state.value.quality
        assertTrue(before.verified)
        AudioSignalPath.source("yt_abcdefghijk", "YouTube Music", sourceId = "youtube", uri = uri)
        assertEquals(before, AudioSignalPath.state.value.quality)
        assertEquals("youtube", AudioSignalPath.state.value.sourceId)
        assertFalse(AudioSignalPath.state.value.quality.badgeLabel.contains("Resolving"))
    }

    @Test fun changingTheActualTransportStillClearsPreviousSourceMeasurements() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setSampleRate(96000)
            .setChannelCount(2).setPcmEncoding(androidx.media3.common.C.ENCODING_PCM_24BIT).build()
        AudioSignalPath.verifiedSource("song", "Lossless", format, 2000, "addon", uri = "https://source/song.flac")
        AudioSignalPath.source("song", "JioSaavn", "jiosaavn", uri = "https://aac.saavncdn.com/song_320.mp4")
        assertFalse(AudioSignalPath.state.value.quality.verified)
        assertEquals("Resolving audio", AudioSignalPath.state.value.quality.badgeLabel)
    }
}
