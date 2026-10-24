package com.example

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OtherSettingsPreferences
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.playback.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DolbyAtmosAndEqTest {
    @After fun reset() { AudioEffectsManager.resetAll(false); PsychoacousticAudioProcessor.spatialEnabled = false }

    @Test fun defaultIsOffAndBothTheDecoderAndQualityMustPermitAtmos() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(OtherSettingsPreferences.playerExperienceSettings(app).dolbyAtmos)
        for (quality in AudioQualityCeiling.entries) {
            assertFalse(DolbyAtmosSupport.allowed(false, quality, true))
            assertFalse(DolbyAtmosSupport.allowed(true, quality, false))
            assertEquals(quality in setOf(AudioQualityCeiling.HIGH, AudioQualityCeiling.MAX),
                DolbyAtmosSupport.allowed(true, quality, true))
        }
        assertFalse(DolbyAtmosSupport.supportsTypes(listOf("audio/mp4a-latm", "audio/opus")))
        assertTrue(DolbyAtmosSupport.supportsTypes(listOf("audio/eac3")))
        assertTrue(DolbyAtmosSupport.supportsTypes(listOf("audio/eac3-joc")))
        OtherSettingsPreferences.setDolbyAtmos(app, true)
        assertTrue(OtherSettingsPreferences.playerExperienceSettings(app).dolbyAtmos)
        OtherSettingsPreferences.setDolbyAtmos(app, false)
        assertFalse(OtherSettingsPreferences.playerExperienceSettings(app).dolbyAtmos)
    }

    @Test fun cachedAtmosCannotBypassOffOrLowerQualityAndLabelsRequireRealDecoderInput() {
        val addon = PlaybackSource("test_atmos_source", PlaybackSourceKind.ADDON, "My Source", baseUrl = "https://audio.example")
        val off = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(addon) + PlaybackSource.builtIns)
        val on = off.copy(dolbyAtmos = true)
        val song = MusicTrack("song", "Song", "Artist", "Album", 180000, "cover", "")
        val body = """{"url":"https://audio.example/atmos.ec3","codec":"eac3-joc","bitrate":768000}"""
        assertNull(StreamingAddons.parseStream(body, addon.baseUrl, song, song, off, addon.id))
        val resolved = StreamingAddons.parseStream(body, addon.baseUrl, song, song, on, addon.id)!!
        assertFalse(resolved.isLossless)
        assertEquals("Custom stream", resolved.qualityBadge)
        assertTrue(StreamingAddons.isVerified(resolved.audioUrl, on))
        assertFalse(StreamingAddons.isVerified(resolved.audioUrl, off))
        assertFalse(StreamingAddons.allowsRegisteredTransport(resolved.audioUrl, off))
        assertFalse(StreamingAddons.allowsRegisteredTransport(resolved.audioUrl, on.copy(ceiling = AudioQualityCeiling.NORMAL)))
        assertNotEquals(on.key, off.key)
        assertTrue(on.onlyAtmosChanged(off.key))
        assertTrue(off.onlyAtmosChanged(on.key))
        assertFalse(on.onlyAtmosChanged(off.copy(ceiling = AudioQualityCeiling.HIGH).key))
        assertEquals(on.upgradePolicyKey, off.upgradePolicyKey) // Preserve the independent source-upgrade bookmark.
        assertEquals("auto", StreamingAddons.atmosParameter(true))
        assertEquals("off", StreamingAddons.atmosParameter(false, "off"))
        val actual = StreamQualityBadge.from(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setSampleRate(48000).setChannelCount(6).setAverageBitrate(768000).build())
        assertEquals("E-AC-3 JOC", actual.codec)
        assertEquals("Dolby Atmos", actual.badgeLabel)
        assertFalse(actual.lossless); assertEquals(0, actual.bitDepth)
        assertEquals(768, actual.bitrateKbps)
        assertFalse(StreamQualityBadge.from(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()).badgeLabel.contains("Dolby"))
    }

    @Test fun presetsUseBoundedTonalCurvesWithoutStackedBassOrPositiveMakeupGain() {
        AudioEffectsManager.setEnabled(true)
        val names = AudioEffectsManager.effectsState.value.availablePresets
        for (name in names) {
            AudioEffectsManager.applyPreset(name)
            val state = AudioEffectsManager.effectsState.value
            assertTrue(state.bands.all { it.levelMb in -500..500 })
            assertEquals(0, state.bassBoostStrength)
            assertEquals(0, state.loudnessGainMb)
            PsychoacousticAudioProcessor.settings.set(state)
            val processor = PsychoacousticAudioProcessor().apply {
                configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)); flush()
            }
            val input = ByteBuffer.allocateDirect(2048 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(2048) { val v = (.98 * sin(it * 2 * Math.PI * 1000 / 48000)).toFloat(); putFloat(v); putFloat(v) }; flip()
            }
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            var audible = false
            while (output.hasRemaining()) {
                val sample = output.float
                assertTrue("$name must remain finite and within PCM headroom", sample.isFinite() && sample in -1f..1f)
                if (kotlin.math.abs(sample) > .01f) audible = true
            }
            assertTrue("$name cannot mute the signal", audible)
        }
        AudioEffectsManager.applyPreset("Vocal Enhance")
        assertTrue(AudioEffectsManager.effectsState.value.bands.any { it.levelMb != 0.toShort() })
        AudioEffectsManager.applyPreset("Flat")
        assertTrue(AudioEffectsManager.effectsState.value.bands.all { it.levelMb == 0.toShort() })
    }
}
