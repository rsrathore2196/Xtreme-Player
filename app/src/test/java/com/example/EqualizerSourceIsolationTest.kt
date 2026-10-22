package com.example

import android.app.Application
import androidx.media3.common.PlaybackException
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EqualizerSourceIsolationTest {
    private fun applied() = runBlocking { withTimeout(2000) {
        while (PsychoacousticAudioProcessor.settings.get() != AudioEffectsManager.effectsState.value) delay(1)
    } }
    @Before fun ready() {
        // A fresh processor has no band snapshot until a real command arrives.
        // Resetting an already Flat/disabled manager is correctly a no-op.
        AudioEffectsManager.setEnabled(true)
        AudioEffectsManager.resetAll(false)
        applied()
    }
    @After fun clear() { AudioEffectsManager.resetAll(false); applied(); AudioSignalPath.reset() }

    @Test fun equalizerCannotReplaceWorking320KbpsJioAudioWithYoutube() = runBlocking {
        val song = MusicTrack("online_song", "Song", "Artist", "Album", 180000, "cover",
            "https://aac.saavncdn.com/song_320.mp4", bitrateKbps = 320, source = "JIOSAAVN")
        val resolver = StreamResolver(searchLossless = { fail("High skips Archive"); emptyList() },
            resolveYouTube = { _, _, _, _ -> fail("A working Jio source must not be replaced"); null },
        searchYouTube = { emptyList() }, resolveOpus = { _, _, _, _ -> null })
        val initial = resolver.resolve(song, AudioQuality.ULTRA_HD_320)
        AudioEffectsManager.setEnabled(true)
        AudioEffectsManager.setBandLevel(0, 500)
        applied()
        assertEquals(initial, resolver.resolve(song, AudioQuality.ULTRA_HD_320))
        AudioEffectsManager.setEnabled(false)
        applied()
        assertEquals(initial, resolver.resolve(song, AudioQuality.ULTRA_HD_320))
        assertEquals(320, initial.bitrateKbps)
    }

    @Test fun outputAndRuntimeFailuresCannotTriggerSourceFallback() {
        for (code in listOf(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK)) {
            assertTrue(PlaybackException("Audio pipeline failed", null, code).isAudioPipelineFailure())
        }
        assertFalse(PlaybackException("Network failed", null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).isAudioPipelineFailure())
        assertFalse(PlaybackException("Bad source format", null,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED).isAudioPipelineFailure())
    }
}
