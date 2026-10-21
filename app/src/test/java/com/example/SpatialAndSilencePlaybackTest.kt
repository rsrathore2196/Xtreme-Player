package com.example

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import com.example.playback.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpatialAndSilencePlaybackTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun pcm(encoding: Int = C.ENCODING_PCM_FLOAT, channels: Int = 2) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(48000).setChannelCount(channels)
        .setPcmEncoding(encoding).build()
    @After fun reset() {
        PsychoacousticAudioProcessor.spatialEnabled = false
        PsychoacousticAudioProcessor.settings.set(AudioEffectsState())
        AudioSignalPath.reset()
    }

    @Test fun spatialMatchesBitChordWidthDelayCrossfeedAndOutputGain() {
        val spatial = StereoSpatializer()
        spatial.configure(48000, 2); spatial.setEnabled(true)
        repeat(600) { spatial.process(.1f, 0f) }
        assertEquals(.1435f, spatial.left, .00001f)
        assertEquals(-.0615f, spatial.right, .00001f) // Before the 15 ms delay arrives.
        repeat(400) { spatial.process(.1f, 0f) }
        assertEquals(.1435f, spatial.left, .00001f)
        assertEquals(-.0451f, spatial.right, .00001f)
        spatial.setEnabled(false)
        repeat(600) { spatial.process(.1f, 0f) }
        assertFalse(spatial.active)
        assertEquals(.1f, spatial.left, 0f); assertEquals(0f, spatial.right, 0f)
    }

    @Test fun stereoEffectWorksOnFloatWithoutEnablingOrChangingEqualizer() {
        val eq = AudioEffectsState(isEnabled = false, selectedPreset = "Flat")
        PsychoacousticAudioProcessor.settings.set(eq)
        PsychoacousticAudioProcessor.spatialEnabled = true
        val processor = PsychoacousticAudioProcessor().apply {
            configure(AudioProcessor.AudioFormat(pcm())); flush()
        }
        fun input() = ByteBuffer.allocateDirect(2048 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(2048) { putFloat(.1f); putFloat(0f) }; flip()
        }
        processor.queueInput(input())
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        output.position(1500 * 8)
        assertEquals(.1435f, output.float, .00001f)
        assertEquals(-.0451f, output.float, .00001f)
        assertSame(eq, PsychoacousticAudioProcessor.settings.get())
        PsychoacousticAudioProcessor.spatialEnabled = false
        processor.queueInput(input()); processor.output
        val original = input()
        val expected = ByteArray(original.remaining()).also(original::get)
        processor.queueInput(ByteBuffer.wrap(expected))
        val restored = processor.output
        val actual = ByteArray(restored.remaining())
        restored.get(actual)
        assertArrayEquals(expected, actual)
    }

    @Test fun monoAndMultichannelPcmRemainByteExactWhenOnlySpatialIsEnabled() {
        PsychoacousticAudioProcessor.spatialEnabled = true
        for (channels in listOf(1, 6)) {
            val processor = PsychoacousticAudioProcessor().apply {
                configure(AudioProcessor.AudioFormat(pcm(C.ENCODING_PCM_24BIT, channels))); flush()
            }
            val bytes = ByteArray(channels * 3 * 128) { (it * 13).toByte() }
            processor.queueInput(ByteBuffer.wrap(bytes))
            val output = processor.output
            assertArrayEquals(bytes, ByteArray(output.remaining()).also(output::get))
        }
    }

    private fun skippedFrames(silentFrames: Int): Long {
        val processor = BitPerfectAudioSink.silenceProcessor()
        processor.setEnabled(true)
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); processor.flush()
        var outputFrames = 0L
        val input = ByteBuffer.allocateDirect((silentFrames + 4096) * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(2048) { putShort(10000); putShort(10000) }
            repeat(silentFrames) { putInt(0) }
            repeat(2048) { putShort(10000); putShort(10000) }; flip()
        }
        while (input.hasRemaining()) {
            processor.queueInput(input)
            val output = processor.output
            outputFrames += output.remaining() / 4
            output.position(output.limit())
        }
        processor.queueEndOfStream()
        val tail = processor.output
        outputFrames += tail.remaining() / 4
        assertEquals(silentFrames + 4096L, outputFrames + processor.skippedFrames)
        return processor.skippedFrames
    }

    @Test fun skipSilenceKeepsShortMusicalPausesAndReallyShortensLongGaps() {
        assertEquals(0L, skippedFrames(24000))
        assertTrue(skippedFrames(144000) > 48000)
    }

    private class SinkProbe {
        var pending = false
        var partial = false
        var releases = 0
        var drains = 0
        var skip = false
        var volume = 1f
        var frames = 0
        var retained: ByteBuffer? = null
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "hasPendingData" -> pending
                "getCurrentPositionUs" -> 100_000L
                "getSkipSilenceEnabled" -> skip
                "setSkipSilenceEnabled" -> { skip = args!![0] as Boolean; null }
                "setVolume" -> { volume = args!![0] as Float; null }
                "playToEndOfStream" -> { drains++; null }
                "release" -> { releases++; null }
                "handleBuffer" -> {
                    val buffer = args!![0] as ByteBuffer
                    retained?.let { assertSame(it, buffer) }
                    if (partial) {
                        partial = false; retained = buffer; buffer.position(buffer.position() + 8); false
                    } else {
                        frames += buffer.remaining() / 8; buffer.position(buffer.limit()); retained = null; true
                    }
                }
                else -> null
            }
        } as AudioSink
    }

    @Test fun togglesDrainPartialWritesAndRestoreFloatWithoutReplacingTheTrackOrLosingPcm() {
        val original = SinkProbe()
        val created = mutableListOf<Pair<Boolean, SinkProbe>>()
        val sink = BitPerfectAudioSink(app, original.sink,
            outputPrecision = { OutputPrecision.FLOAT_32 }, floatRouteSupported = { true }) { skip, _ ->
            SinkProbe().also { created.add(skip to it) }.sink
        }
        sink.configure(pcm(), 0, null); sink.setVolume(.75f); sink.play()
        val first = ByteBuffer.allocateDirect(64)
        original.partial = true
        assertFalse(sink.handleBuffer(first, 0, 1))
        sink.setSkipSilenceEnabled(true)
        assertTrue(sink.handleBuffer(first, 0, 1))
        assertTrue(created.isEmpty())
        original.pending = true
        val next = ByteBuffer.allocateDirect(64)
        assertFalse(sink.handleBuffer(next, 167, 1))
        assertEquals(0, next.position()); assertEquals(0, original.releases)
        original.pending = false
        assertTrue(sink.handleBuffer(next, 167, 1))
        assertEquals(listOf(true), created.map { it.first })
        assertTrue(sink.getSkipSilenceEnabled())
        assertEquals(.75f, created.last().second.volume, 0f)
        assertEquals(1, original.releases)
        sink.setSkipSilenceEnabled(false)
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(64), 334, 1))
        assertEquals(listOf(true, false), created.map { it.first })
        assertFalse(sink.getSkipSilenceEnabled())
        assertEquals(8, created.last().second.frames)
        sink.release()
    }

    @Test fun actualMedia3SinkUsesSilenceProcessingAndRestoresDecoderFloatSupportWhenOff() {
        val sink = BitPerfectAudioSink(app, allowDirect = { false })
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm()))
        sink.setSkipSilenceEnabled(true)
        sink.configure(pcm(), 0, null)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, sink.getFormatSupport(pcm()))
        assertTrue(sink.getSkipSilenceEnabled())
        sink.setSkipSilenceEnabled(false)
        sink.configure(pcm(), 0, null)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm()))
        assertFalse(sink.getSkipSilenceEnabled())
        sink.release()
    }
}
