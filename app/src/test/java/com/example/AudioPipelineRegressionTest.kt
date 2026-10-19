package com.example

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import com.example.playback.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Proxy
import kotlin.math.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPipelineRegressionTest {
    @After fun clean() { PsychoacousticAudioProcessor.settings.set(AudioEffectsState()); AudioSignalPath.reset() }
    private fun processor(encoding: Int): PsychoacousticAudioProcessor = PsychoacousticAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48000, 2, encoding)); flush()
    }
    private fun bytes(value: ByteBuffer): ByteArray = ByteArray(value.remaining()).also(value::get)

    @Test fun disabledProcessorIsByteExactForEverySupportedPcmDepth() {
        for (encoding in intArrayOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)) {
            val processor = processor(encoding)
            val input = ByteArray(192) { (it * 37).toByte() }
            processor.queueInput(ByteBuffer.wrap(input))
            assertArrayEquals("Disabled DSP must not round PCM ($encoding)", input, bytes(processor.output))
            processor.reset()
        }
    }
    @Test fun floatDspLimitsBoostsWithoutNanOrClipping() {
        PsychoacousticAudioProcessor.settings.set(AudioEffectsState(isEnabled = true, crystalClarityEnabled = true,
            bassBoostStrength = 1000, virtualizerStrength = 1000, loudnessGainMb = 800,
            bands = listOf(60, 230, 910, 3600, 14000).mapIndexed { i, hz -> BandState(i.toShort(), hz, 1500) }))
        val processor = processor(C.ENCODING_PCM_FLOAT)
        repeat(20) { block ->
            val input = ByteBuffer.allocateDirect(2048 * 8).order(ByteOrder.LITTLE_ENDIAN)
            repeat(2048) { frame -> val value = sin(2 * PI * 80 * (frame + block * 2048) / 48000).toFloat() * 0.99f
                input.putFloat(value).putFloat(value) }
            input.flip(); processor.queueInput(input)
            assertFalse(input.hasRemaining())
            val result = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2048 * 8, result.remaining())
            while (result.hasRemaining()) { val value = result.float; assertTrue(value.isFinite()); assertTrue(abs(value) <= 1f) }
        }
    }
    @Test fun clarityShelfImprovesAirRelativeToMidrange() {
        fun rms(frequency: Int): Double {
            PsychoacousticAudioProcessor.settings.set(AudioEffectsState(isEnabled = true, crystalClarityEnabled = true))
            val processor = processor(C.ENCODING_PCM_FLOAT)
            val input = ByteBuffer.allocateDirect(48000 * 8).order(ByteOrder.LITTLE_ENDIAN)
            repeat(48000) { val value = (sin(2 * PI * frequency * it / 48000) * 0.1).toFloat(); input.putFloat(value).putFloat(value) }
            input.flip(); processor.queueInput(input)
            val result = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            repeat(48000) { frame -> val value = result.float; result.float
                if (frame > 24000) sum += value.toDouble() * value }
            return sqrt(sum / 23999)
        }
        assertTrue("Authentic high-shelf response", rms(16000) > rms(1000) * 1.05)
    }
    @Test fun liveToggleDoesNotNeedFlushOrLoseFrames() {
        val processor = processor(C.ENCODING_PCM_16BIT)
        val original = ByteArray(4096) { (it * 5).toByte() }
        processor.queueInput(ByteBuffer.wrap(original)); assertArrayEquals(original, bytes(processor.output))
        PsychoacousticAudioProcessor.settings.set(AudioEffectsState(isEnabled = true, crystalClarityEnabled = true))
        processor.queueInput(ByteBuffer.wrap(original)); val changed = bytes(processor.output)
        assertEquals(original.size, changed.size); assertFalse(original.contentEquals(changed))
        PsychoacousticAudioProcessor.settings.set(AudioEffectsState())
        processor.queueInput(ByteBuffer.wrap(original)); assertArrayEquals(original, bytes(processor.output))
    }
    @Test fun equalizerTogglesKeepTheExactBufferUntilEachPartialSinkWriteCompletes() {
        var retained: ByteBuffer? = null
        var calls = 0
        var lastBuffer: ByteBuffer? = null
        val shared = Proxy.newProxyInstance(AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    val buffer = args!![0] as ByteBuffer
                    retained?.let { assertSame("A pending write must retain its original buffer", it, buffer) }
                    lastBuffer = buffer
                    calls++
                    if (calls == 1 || calls == 3) {
                        buffer.position(buffer.position() + 64)
                        retained = buffer
                        false
                    } else {
                        buffer.position(buffer.limit())
                        retained = null
                        true
                    }
                }
                "hasPendingData", "isEnded" -> false
                else -> null
            }
        } as AudioSink
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val sink = BitPerfectAudioSink(app, shared)
        sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(48000)
            .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build(), 0, null)
        val first = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(sink.handleBuffer(first, 0L, 1))
        PsychoacousticAudioProcessor.settings.set(AudioEffectsState(isEnabled = true))
        assertTrue(sink.handleBuffer(first, 0L, 1))
        assertSame(first, lastBuffer)
        val second = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(sink.handleBuffer(second, 21_333L, 1))
        assertNotSame(second, lastBuffer)
        PsychoacousticAudioProcessor.settings.set(AudioEffectsState())
        assertTrue(sink.handleBuffer(second, 21_333L, 1))
        val third = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(sink.handleBuffer(third, 42_666L, 1))
        assertSame(third, lastBuffer)
        assertEquals(5, calls)
        sink.release()
    }
    @Test fun badgeUsesActualFormatNeverTheSelectedLosslessPreference() {
        val aac = StreamQualityBadge.from(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setSampleRate(44100).setAverageBitrate(320000).build())
        assertEquals("AAC • 320 kbps", aac.label); assertFalse(aac.lossless)
        val flac = StreamQualityBadge.from(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(44100).setPcmEncoding(C.ENCODING_PCM_16BIT).build())
        assertEquals("FLAC • 16-bit/44.1 kHz", flac.label); assertTrue(flac.lossless)
        val unknown = StreamQualityBadge.from(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(96000).setPcmEncoding(C.ENCODING_PCM_FLOAT).build())
        assertEquals(0, unknown.bitDepth); assertFalse(unknown.label.contains("32-bit"))
    }
    @Test fun staleAudioFormatCannotOverwriteNewTrack() {
        AudioSignalPath.source("new", "YouTube Music")
        AudioSignalPath.format("old", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).build(), 0)
        assertFalse(AudioSignalPath.state.value.quality.verified)
        assertEquals("new", AudioSignalPath.state.value.trackId)
    }
    @Test fun nativeFloatRepackPreservesEveryTestedIntegerBit() {
        val values = intArrayOf(-8388608, -8388607, -1234567, -1, 0, 1, 1234567, 8388606, 8388607)
        val input = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { input.putFloat(it / 8388608f) }; input.flip()
        val output = ByteBuffer.allocateDirect(values.size * 3)
        BitPerfectPcm.repack24(input, output); output.flip()
        values.forEach { expected ->
            val a = output.get().toInt() and 255; val b = output.get().toInt() and 255; val c = output.get().toInt()
            assertEquals(expected, a or (b shl 8) or (c shl 16))
        }
        assertFalse(input.hasRemaining()); assertFalse(output.hasRemaining())
    }
    @Test fun nativeRepackRejectsFloatingPointSamplesWithoutExact24BitIdentity() {
        for (sample in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 1f, 0.000000001f)) {
            val input = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(sample).apply { flip() }
            try { BitPerfectPcm.repack24(input, ByteBuffer.allocateDirect(3)); fail("Unsafe float sample was accepted") }
            catch (_: IllegalArgumentException) { assertEquals(0, input.position()) }
        }
    }
    @Test fun olderAndroidKeepsSharedOutputAndCannotClaimBitPerfect() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val sink = BitPerfectAudioSink(app)
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setSampleRate(96000)
            .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_24BIT).build()
        assertNull(sink.preferredPcmEncoding(format)); assertFalse(AudioSignalPath.state.value.bitPerfect)
        sink.release()
    }
    @Test fun aCrossfadeTailCannotOverwriteThePrimarySignalPathOrEnableDsp() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        AudioSignalPath.source("primary", "JioSaavn")
        AudioSignalPath.format("primary", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setSampleRate(44100).setAverageBitrate(320000).build(), 320)
        val before = AudioSignalPath.state.value
        val dsp = PsychoacousticAudioProcessor.settings.get()
        val sink = BitPerfectAudioSink(app, reportSignalPath = false, allowDirect = { false })
        sink.setVolume(0.2f)
        sink.setPlaybackParameters(androidx.media3.common.PlaybackParameters(1.25f))
        sink.release()
        assertEquals(before, AudioSignalPath.state.value)
        assertEquals(dsp, PsychoacousticAudioProcessor.settings.get())
    }
    @Test fun settingsAdaptToExistingPerNetworkProfiles() {
        assertEquals(AudioQualityPreference.MAX_LOSSLESS, AudioQualityPreference.from(AudioQualityCeiling.MAX.audioQuality))
        assertEquals(320000, AudioQualityCeiling.HIGH.maxBitrateBps)
        assertEquals(160000, AudioQualityCeiling.NORMAL.maxBitrateBps)
        assertEquals(96000, AudioQualityCeiling.DATA_SAVER.maxBitrateBps)
    }
}
