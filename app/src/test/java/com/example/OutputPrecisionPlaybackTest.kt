package com.example

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.core.app.ApplicationProvider
import com.example.playback.*
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OutputPrecisionPlaybackTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun format(encoding: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding).setSampleRate(48000).setChannelCount(2).build()
    private fun shorts(vararg values: Int) = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putShort(it.toShort()) }; flip() }
    @After fun reset() {
        PsychoacousticAudioProcessor.settings.set(AudioEffectsState())
        PsychoacousticAudioProcessor.spatialEnabled = false
        AudioSignalPath.reset()
    }

    @Test fun pcm16IsDefaultAndUnknownStoredValuesAreSafe() {
        assertEquals(OutputPrecision.PCM_16, OutputPrecision.fromStored(null))
        assertEquals(OutputPrecision.PCM_16, OutputPrecision.fromStored("obsolete"))
        assertEquals(OutputPrecision.FLOAT_32, OutputPrecision.fromStored("FLOAT_32"))
    }

    @Test fun floatRequiresRequestedCompatibleExternalRouteAndSilenceOff() {
        assertFalse(OutputPrecisionPolicy.allowsFloat(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, intArrayOf(C.ENCODING_PCM_FLOAT)))
        assertFalse(OutputPrecisionPolicy.allowsFloat(null, intArrayOf(C.ENCODING_PCM_FLOAT)))
        assertFalse(OutputPrecisionPolicy.allowsFloat(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, intArrayOf(), directFloatSupported = true))
        assertTrue(OutputPrecisionPolicy.allowsFloat(AudioDeviceInfo.TYPE_USB_DEVICE, intArrayOf(), directFloatSupported = true))
        for (type in listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HDMI)) {
            assertTrue(OutputPrecisionPolicy.allowsFloat(type, intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)))
            assertFalse(OutputPrecisionPolicy.allowsFloat(type, intArrayOf(C.ENCODING_PCM_16BIT)))
        }
        for (precision in OutputPrecision.entries) for (supported in listOf(false, true)) for (skip in listOf(false, true)) {
            val expected = if (precision == OutputPrecision.FLOAT_32 && supported && !skip) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
            assertEquals(expected, OutputPrecisionPolicy.encoding(precision, supported, skip))
        }
    }

    @Test fun allPcm16SamplesRoundTripThroughFloatExactlyAndReuseStorage() {
        val source = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in -32768..32767) source.putShort(sample.toShort())
        source.flip()
        val toFloat = OutputPcmConverter()
        val converted = toFloat.convert(source, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        val restored = OutputPcmConverter().convert(converted, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT)
        for (sample in -32768..32767) assertEquals(sample.toShort(), restored.short)
        assertFalse(restored.hasRemaining())
        assertSame(converted, toFloat.convert(shorts(0, 1), C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT))
    }

    @Test fun highResolutionSamplesAreNotQuantizedTo16BeforeFloatOutput() {
        val input = ByteBuffer.allocateDirect(9).apply { put(byteArrayOf(1, 0, 0, -1, -1, 127, 0, 0, -128)); flip() }
        val result = OutputPcmConverter().convert(input, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT)
        assertEquals(1f / 8388608f, result.float, 0f)
        assertEquals(8388607f / 8388608f, result.float, 0f)
        assertEquals(-1f, result.float, 0f)
    }

    @Test fun pcm16ConversionClampsOverloadsAndSilencesNonFiniteSamples() {
        val source = ByteBuffer.allocateDirect(7 * 4).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(-2f, -1f, .5f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY).forEach(source::putFloat)
        source.flip()
        val output = OutputPcmConverter().convert(source, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT)
        for (expected in listOf(-32768, -32768, 16384, 32767, 32767, 0, 0)) assertEquals(expected.toShort(), output.short)
    }

    private class Probe {
        var encoding = C.ENCODING_INVALID
        var pending = false
        var partial = false
        var failInitialization = false
        var releases = 0
        var volume = 1f
        var held: ByteBuffer? = null
        val data = mutableListOf<Byte>()
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> { encoding = (args!![0] as Format).pcmEncoding; null }
                "hasPendingData" -> pending
                "getCurrentPositionUs" -> 250_000L
                "setVolume" -> { volume = args!![0] as Float; null }
                "release" -> { releases++; null }
                "handleBuffer" -> {
                    if (failInitialization) throw AudioSink.InitializationException(0, 48000, 12, 1024, formatForFailure(), false, null)
                    val buffer = args!![0] as ByteBuffer
                    held?.let { assertSame("Partial write buffer changed", it, buffer) }
                    val bytes = if (partial) 2 else buffer.remaining()
                    repeat(bytes) { data.add(buffer.get()) }
                    if (partial) { partial = false; held = buffer; false } else { held = null; true }
                }
                else -> null
            }
        } as AudioSink
        private fun formatForFailure() = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding).setSampleRate(48000).setChannelCount(2).build()
    }

    @Test fun liveToggleDrainsPartialAudioAndKeepsSourceFramesAndVolume() {
        var precision = OutputPrecision.PCM_16
        val original = Probe()
        val replacements = mutableListOf<Probe>()
        val sink = BitPerfectAudioSink(app, original.sink, { precision }, { true }) { _, _ -> Probe().also(replacements::add).sink }
        try {
            sink.configure(format(C.ENCODING_PCM_16BIT), 0, null); sink.setVolume(.6f); sink.play()
            val first = shorts(1000, -1000, 2000, -2000)
            original.partial = true
            assertFalse(sink.handleBuffer(first, 0, 1))
            precision = OutputPrecision.FLOAT_32
            assertTrue(sink.handleBuffer(first, 0, 1))
            assertTrue(replacements.isEmpty())
            original.pending = true
            val next = shorts(16384, -16384, 0, 32767)
            assertFalse(sink.handleBuffer(next, 42, 1))
            assertEquals(0, next.position()); assertEquals(0, original.releases)
            original.pending = false
            assertTrue(sink.handleBuffer(next, 42, 1))
            assertEquals(1, original.releases)
            val replacement = replacements.single()
            assertEquals(C.ENCODING_PCM_FLOAT, replacement.encoding)
            assertEquals(.6f, replacement.volume, 0f)
            val output = ByteBuffer.wrap(replacement.data.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(.5f, output.float, 0f); assertEquals(-.5f, output.float, 0f)
            assertEquals(0f, output.float, 0f); assertEquals(32767f / 32768f, output.float, 0f)
            assertFalse(output.hasRemaining())
            precision = OutputPrecision.PCM_16
            assertTrue(sink.handleBuffer(shorts(9, -9), 84, 1))
            assertEquals(C.ENCODING_PCM_16BIT, replacements.last().encoding)
            assertEquals(4, replacements.last().data.size)
        } finally { sink.release() }
    }

    @Test fun routeLossFallsBackWithoutChangingSavedFloatPreference() {
        var supported = true
        val original = Probe()
        val replacements = mutableListOf<Probe>()
        val sink = BitPerfectAudioSink(app, original.sink, { OutputPrecision.FLOAT_32 }, { supported }) { _, _ -> Probe().also(replacements::add).sink }
        try {
            sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            assertTrue(sink.handleBuffer(shorts(1, -1), 0, 1))
            assertEquals(C.ENCODING_PCM_FLOAT, original.encoding)
            assertTrue(replacements.isEmpty())
            supported = false
            assertTrue(sink.handleBuffer(shorts(2, -2), 42, 1))
            assertEquals(C.ENCODING_PCM_16BIT, replacements.last().encoding)
            supported = true
            assertTrue(sink.handleBuffer(shorts(3, -3), 84, 1))
            assertEquals(C.ENCODING_PCM_FLOAT, replacements.last().encoding)
        } finally { sink.release() }
    }

    @Test fun failedFloatAudioTrackCreationRetriesTheSameSamplesAsPcm16() {
        val original = Probe().apply { failInitialization = true }
        val replacements = mutableListOf<Probe>()
        val sink = BitPerfectAudioSink(app, original.sink, { OutputPrecision.FLOAT_32 }, { true }) { _, _ ->
            Probe().also(replacements::add).sink
        }
        try {
            sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
            assertTrue(sink.handleBuffer(shorts(1000, -1000), 0, 1))
            assertEquals(1, replacements.size)
            assertEquals(1, original.releases)
            assertEquals(C.ENCODING_PCM_16BIT, replacements.last().encoding)
            val output = ByteBuffer.wrap(replacements.last().data.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1000.toShort(), output.short); assertEquals((-1000).toShort(), output.short)
            assertTrue(sink.handleBuffer(shorts(2000, -2000), 42, 1))
            assertEquals("No repeated attempts on a rejected route", 1, replacements.size)
        } finally { sink.release() }
    }

    @Test fun actualMedia3AudioTrackReceivesSelectedEncodingForPcm16DecoderInput() {
        for ((mode, supported, skip, expected) in listOf(
            listOf(OutputPrecision.PCM_16, true, false, C.ENCODING_PCM_16BIT),
            listOf(OutputPrecision.FLOAT_32, true, false, C.ENCODING_PCM_FLOAT),
            listOf(OutputPrecision.FLOAT_32, false, false, C.ENCODING_PCM_16BIT),
            listOf(OutputPrecision.FLOAT_32, true, true, C.ENCODING_PCM_16BIT)
        )) {
            val tracks = mutableListOf<AudioTrack>()
            fun delegate(skipSilence: Boolean) = DefaultAudioSink.Builder(app).setEnableFloatOutput(!skipSilence)
                .setAudioTrackProvider { config, attributes, session ->
                    DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, session).also(tracks::add)
                }.build()
            val sink = BitPerfectAudioSink(app, delegate(false), { mode as OutputPrecision }, { supported as Boolean }) { silence, _ -> delegate(silence) }
            try {
                sink.setSkipSilenceEnabled(skip as Boolean)
                sink.configure(format(C.ENCODING_PCM_16BIT), 0, null)
                assertTrue(sink.handleBuffer(shorts(1000, -1000, 2000, -2000), 0, 1))
                assertEquals(expected as Int, tracks.single().audioFormat)
            } finally { sink.release() }
        }
    }
}
