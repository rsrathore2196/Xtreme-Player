package com.example

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.example.playback.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EqualizerSignalTest {
    private fun base() = AudioEffectsState(isEnabled = true,
        bands = listOf(60, 230, 910, 3600, 14000).mapIndexed { i, f -> BandState(i.toShort(), f, 0) })
    private fun preset(name: String) = AudioEffectsManager.presetState(base(), name)
    @After fun reset() { PsychoacousticAudioProcessor.settings.set(AudioEffectsState()); PsychoacousticAudioProcessor.spatialEnabled = false }
    private fun processor(rate: Int = 48000, channels: Int = 2, encoding: Int = C.ENCODING_PCM_FLOAT) =
        PsychoacousticAudioProcessor().apply { configure(AudioProcessor.AudioFormat(rate, channels, encoding)); flush() }
    private fun tone(frequency: Double, rate: Int = 48000, frames: Int = rate / 2, channels: Int = 2,
        amplitude: Double = .05, onlyFirstChannel: Boolean = false): ByteBuffer =
        ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame -> repeat(channels) { channel ->
                putFloat(if (onlyFirstChannel && channel > 0) 0f else (amplitude * sin(2 * PI * frequency * frame / rate)).toFloat())
            } }; flip()
        }
    private fun bytes(buffer: ByteBuffer) = ByteArray(buffer.remaining()).also(buffer::get)

    @Test fun offAndNeutralFlatAreByteExactForEverySupportedPcmEncodingAndChannelLayout() {
        val formats = listOf(C.ENCODING_PCM_8BIT to 1, C.ENCODING_PCM_16BIT to 2,
            C.ENCODING_PCM_24BIT to 3, C.ENCODING_PCM_32BIT to 4, C.ENCODING_PCM_FLOAT to 4,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to 2, C.ENCODING_PCM_24BIT_BIG_ENDIAN to 3, C.ENCODING_PCM_32BIT_BIG_ENDIAN to 4)
        for ((encoding, size) in formats) for (channels in listOf(1, 2, 6, 8)) {
            val original = ByteArray(64 * channels * size) { ((it * 73 + 19) and 255).toByte() }
            for (state in listOf(preset("Deep Sub").copy(isEnabled = false, crystalClarityEnabled = true,
                bassBoostStrength = 1000, virtualizerStrength = 1000, loudnessGainMb = 800), preset("Flat"))) {
                PsychoacousticAudioProcessor.settings.set(state)
                val eq = processor(channels = channels, encoding = encoding)
                assertFalse(eq.isProcessing)
                eq.queueInput(ByteBuffer.allocateDirect(original.size).put(original).apply { flip() })
                assertArrayEquals("encoding=$encoding channels=$channels", original, bytes(eq.output))
            }
        }
    }

    @Test fun sinkStyleBypassClearsOldFilterHistoryBeforeTheSamePresetIsReenabled() {
        val state = preset("Bass Booster")
        PsychoacousticAudioProcessor.settings.set(state)
        val eq = processor()
        eq.queueInput(tone(60.0)); eq.output
        PsychoacousticAudioProcessor.settings.set(state.copy(isEnabled = false))
        assertFalse(eq.isProcessing) // The sink skips queueInput in this state.
        PsychoacousticAudioProcessor.settings.set(state)
        eq.queueInput(ByteBuffer.allocateDirect(4096 * 8))
        val output = eq.output.order(ByteOrder.LITTLE_ENDIAN)
        while (output.hasRemaining()) assertEquals("No stale note or DC tail", 0f, output.float, 0f)
    }

    @Test fun enablingABoostDoesNotIntroduceAnAbruptPreampVolumeStep() {
        PsychoacousticAudioProcessor.settings.set(preset("Bass Booster"))
        val eq = processor()
        val input = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(4096 * 2) { putFloat(.4f) }; flip()
        }
        eq.queueInput(input)
        val output = eq.output.order(ByteOrder.LITTLE_ENDIAN)
        val left = output.float; val right = output.float
        assertEquals("Start the gain transition smoothly at the buffer boundary", .4f, left, .02f)
        assertEquals(left, right, 0f)
    }

    private fun gainDb(state: AudioEffectsState, frequency: Double): Double {
        PsychoacousticAudioProcessor.settings.set(state)
        val eq = processor()
        eq.queueInput(tone(frequency))
        val output = eq.output.order(ByteOrder.LITTLE_ENDIAN)
        var energy = 0.0; var count = 0
        repeat(24000) { frame ->
            val value = output.float; output.float
            if (frame >= 12000) { energy += value * value; count++ }
        }
        return 20 * log10(sqrt(energy / count) / (.05 / sqrt(2.0)))
    }

    @Test fun presetsHaveAudibleIntendedContoursWithoutTheOldSummedBoostVolumePenalty() {
        assertTrue(gainDb(preset("Bass Booster"), 60.0) - gainDb(preset("Bass Booster"), 910.0) > 3)
        assertTrue(gainDb(preset("Vocal & Podcast"), 3600.0) - gainDb(preset("Vocal & Podcast"), 60.0) > 3)
        assertTrue(gainDb(preset("Treble Reducer"), 14000.0) - gainDb(preset("Treble Reducer"), 910.0) < -2)
        assertTrue("EDM bass must not be unnecessarily attenuated by unrelated treble boosts",
            gainDb(preset("Electronic / EDM"), 60.0) in -1.0..0.1)
    }

    @Test fun everyPresetIsFiniteAndBoundedAcrossSourceRatesAndSurroundChannelsRemainIsolated() {
        for (name in base().availablePresets) for (rate in listOf(16000, 44100, 48000, 96000, 192000)) {
            PsychoacousticAudioProcessor.settings.set(preset(name))
            PsychoacousticAudioProcessor.spatialEnabled = true
            val eq = processor(rate, 6)
            eq.queueInput(tone(910.0, rate, frames = 4096, channels = 6, amplitude = .999, onlyFirstChannel = true))
            val output = eq.output.order(ByteOrder.LITTLE_ENDIAN)
            var audible = false
            repeat(4096) { repeat(6) { channel ->
                val sample = output.float
                assertTrue("$name $rate Hz", sample.isFinite() && sample in -1f..1f)
                if (channel > 0) assertEquals("Surround EQ must not mix channels", 0f, sample, 0f)
                else if (abs(sample) > .01f) audible = true
            } }
            assertTrue(audible)
        }
    }

    @Test fun turningEqOffPreservesIndependentSpatialAndSpatialDoesNotStackPresetWidening() {
        PsychoacousticAudioProcessor.spatialEnabled = true
        fun render(state: AudioEffectsState): ByteArray {
            PsychoacousticAudioProcessor.settings.set(state)
            val eq = processor()
            val input = tone(200.0)
            // Feed a stereo signal with side information.
            for (i in 0 until input.limit() / 8) input.putFloat(i * 8 + 4, -input.getFloat(i * 8))
            eq.queueInput(input)
            return bytes(eq.output)
        }
        assertArrayEquals(render(base().copy(isEnabled = false)), render(preset("Crystal Clarity").copy(isEnabled = false)))
        val gaming = preset("Gaming & Spatial")
        assertArrayEquals(render(gaming.copy(virtualizerStrength = 0)), render(gaming))
        PsychoacousticAudioProcessor.spatialEnabled = false
        assertFalse(render(gaming.copy(virtualizerStrength = 0)).contentEquals(render(gaming)))
    }

    @Test fun aboveNyquistBandsDoNotMoveIntoTheAudibleMidrange() {
        val state = base().copy(bands = base().bands.map { it.copy(levelMb = if (it.centerFreqHz == 14000) 1500 else 0) })
        PsychoacousticAudioProcessor.settings.set(state)
        val eq = processor(16000)
        assertFalse(eq.isProcessing)
        val input = tone(3600.0, 16000, amplitude = .99)
        val expected = bytes(input.duplicate())
        eq.queueInput(input)
        assertArrayEquals(expected, bytes(eq.output))
    }
}
