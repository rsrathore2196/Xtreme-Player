package com.example

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioEnhancerRegressionTest {
    private fun applied() = runBlocking { withTimeout(2000) {
        while (PsychoacousticAudioProcessor.settings.get() != AudioEffectsManager.effectsState.value) delay(1)
    } }
    @Before fun ready() {
        AudioEffectsManager.resetAll(false)
        AudioEffectsManager.setAudioEnhancerEnabled(false)
        applied()
    }
    @After fun clear() {
        AudioEffectsManager.resetAll(false)
        AudioEffectsManager.setAudioEnhancerEnabled(false)
        applied()
        AudioSignalPath.reset()
    }
    @Test fun enhancerToggleDoesNotEnableOrChangeEqualizerAndEqChangesDoNotToggleEnhancer() {
        AudioEffectsManager.applyPreset("Bass Boost")
        val eq = AudioEffectsManager.effectsState.value
        AudioEffectsManager.setAudioEnhancerEnabled(true)
        assertEquals(eq.copy(audioEnhancerEnabled = true), AudioEffectsManager.effectsState.value)
        assertFalse(AudioEffectsManager.effectsState.value.isEnabled)
        AudioEffectsManager.setEnabled(true)
        AudioEffectsManager.setBandLevel(0, 500)
        AudioEffectsManager.setVirtualizer(350)
        AudioEffectsManager.applyPreset("Studio Master")
        assertTrue(AudioEffectsManager.effectsState.value.audioEnhancerEnabled)
        val changedEq = AudioEffectsManager.effectsState.value
        AudioEffectsManager.setAudioEnhancerEnabled(false)
        assertEquals(changedEq.copy(audioEnhancerEnabled = false), AudioEffectsManager.effectsState.value)
        assertTrue(AudioEffectsManager.effectsState.value.isEnabled)
    }
    @Test fun enhancerWorksWithEqOffAndDisablingBothRestoresOriginalPcmWithoutFlush() {
        val processor = PsychoacousticAudioProcessor().apply {
            configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); flush()
        }
        val original = ByteArray(16384) { (it * 7).toByte() }
        fun output(): ByteArray {
            processor.queueInput(ByteBuffer.wrap(original))
            val bytes = processor.output
            return ByteArray(bytes.remaining()).also(bytes::get)
        }
        assertArrayEquals(original, output())
        AudioEffectsManager.setAudioEnhancerEnabled(true); applied()
        assertFalse(AudioEffectsManager.effectsState.value.isEnabled)
        assertFalse(original.contentEquals(output()))
        AudioEffectsManager.setAudioEnhancerEnabled(false); applied()
        assertArrayEquals(original, output())
    }
    @Test fun sessionsAndEqResetPreserveTheGlobalEnhancerChoice() {
        AudioEffectsManager.setAudioEnhancerEnabled(true)
        AudioEffectsManager.attachAudioSession(11)
        AudioEffectsManager.resetAll(false)
        AudioEffectsManager.detachAudioSession()
        AudioEffectsManager.attachAudioSession(22)
        assertTrue(AudioEffectsManager.effectsState.value.audioEnhancerEnabled)
        assertFalse(AudioEffectsManager.effectsState.value.isEnabled)
        applied()
        assertTrue(AudioSignalPath.state.value.enhancerEnabled)
        assertFalse(AudioSignalPath.state.value.eqActive)
    }
    @Test fun enhancerCannotReplaceWorking320KbpsJioAudioWithYoutube() = runBlocking {
        val song = MusicTrack("online_song", "Song", "Artist", "Album", 180000, "cover",
            "https://aac.saavncdn.com/song_320.mp4", bitrateKbps = 320, source = "JIOSAAVN")
        val resolver = StreamResolver(searchLossless = { fail("High skips Archive"); emptyList() },
            resolveYouTube = { _, _, _ -> fail("A working Jio source must not be replaced"); null })
        val initial = resolver.resolve(song, AudioQuality.ULTRA_HD_320)
        AudioEffectsManager.setAudioEnhancerEnabled(true); applied()
        assertEquals(initial, resolver.resolve(song, AudioQuality.ULTRA_HD_320))
        AudioEffectsManager.setAudioEnhancerEnabled(false); applied()
        assertEquals(initial, resolver.resolve(song, AudioQuality.ULTRA_HD_320))
        assertEquals(320, initial.bitrateKbps)
    }
    @Test fun enhancerPreferenceIsPersistedSeparatelyFromNetworkQuality() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val network = AudioQualityPreferences.getNetworkSettings(context)
        AudioQualityPreferences.setAudioEnhancerEnabled(context, true)
        assertTrue(AudioQualityPreferences.getAudioEnhancerEnabled(context))
        assertEquals(network, AudioQualityPreferences.getNetworkSettings(context))
        AudioQualityPreferences.setAudioEnhancerEnabled(context, false)
        assertFalse(AudioQualityPreferences.getAudioEnhancerEnabled(context))
    }
}
