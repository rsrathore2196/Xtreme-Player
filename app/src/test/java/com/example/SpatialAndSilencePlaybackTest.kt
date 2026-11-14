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

    @Test fun spatialMatchesWidthDelayCrossfeedAndOutputGain() {
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

    private fun skippedFrames(silentFrames: Int,
        processor: androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor = BitPerfectAudioSink.silenceProcessor()): Long {
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
        tail.position(tail.limit())
        assertTrue(processor.isEnded)
        assertEquals(silentFrames + 4096L, outputFrames + processor.skippedFrames)
        return processor.skippedFrames
    }

    @Test fun skipSilenceKeepsShortMusicalPausesAndReallyShortensLongGaps() {
        assertEquals(0L, skippedFrames(24000))
        assertTrue(skippedFrames(144000) > 48000)
    }
    @Test fun actualSkippedFramesAndEosResetAcrossSuccessiveTracksAndEnableChanges() {
        val processor = BitPerfectAudioSink.silenceProcessor()
        val first = skippedFrames(144000, processor)
        assertTrue(first > 48000)
        processor.setEnabled(false)
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); processor.flush()
        assertFalse(processor.isActive)
        assertEquals(0L, processor.skippedFrames)
        assertEquals(first, skippedFrames(144000, processor))
        assertEquals(0L, skippedFrames(24000, processor))
    }

    private class SinkProbe {
        var pending = false
        var processorPending = false
        var partial = false
        var releases = 0
        var drains = 0
        var skip = false
        var volume = 1f
        var frames = 0
        var retained: ByteBuffer? = null
        var configured: Format? = null
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "hasPendingData" -> pending
                "isEnded" -> !pending && !processorPending && retained == null
                "configure" -> { configured = args!![0] as Format; null }
                "flush" -> { pending = false; processorPending = false; retained = null; null }
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

    @Test fun processorEosMustFinishEvenWhenAudioTrackHasNoPendingFrames() {
        val original = SinkProbe()
        val replacements = mutableListOf<SinkProbe>()
        val sink = BitPerfectAudioSink(app, original.sink, sharedFactory = { _, _ ->
            SinkProbe().also(replacements::add).sink
        })
        sink.configure(pcm(C.ENCODING_PCM_16BIT), 0, null)
        original.processorPending = true
        sink.setSkipSilenceEnabled(true)
        val next = ByteBuffer.allocateDirect(64)
        assertFalse(sink.handleBuffer(next, 0, 1))
        assertEquals(0, next.position())
        assertEquals(1, original.drains)
        assertEquals(0, original.releases)
        original.processorPending = false
        assertTrue(sink.handleBuffer(next, 0, 1))
        assertEquals(1, original.releases)
        assertTrue(replacements.single().skip)
        sink.release()
    }

    @Test fun nextFormatDrivesProcessorEosAndFlushCannotRequeueStaleConfiguration() {
        val original = SinkProbe()
        val replacements = mutableListOf<SinkProbe>()
        val sink = BitPerfectAudioSink(app, original.sink, sharedFactory = { _, _ ->
            SinkProbe().also(replacements::add).sink
        })
        sink.configure(pcm(C.ENCODING_PCM_16BIT), 0, null)
        original.processorPending = true
        val mono = pcm(C.ENCODING_PCM_16BIT, 1)
        sink.configure(mono, 0, null)
        val buffer = ByteBuffer.allocateDirect(64)
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertTrue(original.drains > 0)
        assertEquals(2, original.configured!!.channelCount)
        original.processorPending = false
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        val active = replacements.single()
        assertEquals(1, active.configured!!.channelCount)
        active.pending = true
        sink.configure(pcm(C.ENCODING_PCM_16BIT), 0, null)
        sink.setSkipSilenceEnabled(true)
        sink.flush()
        val flushed = replacements.last()
        assertEquals(2, flushed.configured!!.channelCount)
        assertTrue(flushed.skip)
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(64), 100, 1))
        assertEquals(2, flushed.configured!!.channelCount)
        sink.reset()
        sink.configure(mono, 0, null)
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(64), 200, 1))
        sink.release()
    }

    @Test fun aNewerFormatSupersedesAnOlderPendingConfigurationAfterTheDrainEnds() {
        val original = SinkProbe()
        val sink = BitPerfectAudioSink(app, original.sink)
        sink.configure(pcm(C.ENCODING_PCM_16BIT), 0, null)
        original.processorPending = true
        sink.configure(pcm(C.ENCODING_PCM_16BIT, 1), 0, null)
        original.processorPending = false
        sink.configure(pcm(C.ENCODING_PCM_16BIT, 6), 0, null)
        assertEquals(6, original.configured!!.channelCount)
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(96), 0, 1))
        assertEquals(6, original.configured!!.channelCount)
        sink.release()
    }

    @Test fun aPendingFormatCannotDiscardTheOldPartiallyWrittenPcmBuffer() {
        val original = SinkProbe()
        val replacements = mutableListOf<SinkProbe>()
        val sink = BitPerfectAudioSink(app, original.sink, sharedFactory = { _, _ ->
            SinkProbe().also(replacements::add).sink
        })
        sink.configure(pcm(C.ENCODING_PCM_16BIT), 0, null)
        val previous = ByteBuffer.allocateDirect(64)
        original.partial = true
        assertFalse(sink.handleBuffer(previous, 0, 1))
        sink.configure(pcm(C.ENCODING_PCM_16BIT, 1), 0, null)
        assertTrue(sink.handleBuffer(previous, 0, 1))
        assertEquals(previous.limit(), previous.position())
        assertEquals(0, original.releases)
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(32), 100, 1))
        assertEquals(1, original.releases)
        assertEquals(1, replacements.single().configured!!.channelCount)
        sink.release()
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

    @Test fun spatialAndSilenceProcessingReachAudioTrackTogetherAtBothOutputPrecisions() {
        for (precision in OutputPrecision.entries) for (spatial in listOf(false, true)) for (skip in listOf(false, true)) {
            PsychoacousticAudioProcessor.settings.set(AudioEffectsState())
            PsychoacousticAudioProcessor.spatialEnabled = spatial
            val output = java.io.ByteArrayOutputStream()
            val tracks = mutableListOf<android.media.AudioTrack>()
            val listener = org.robolectric.shadows.ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> output.write(data) }
            fun delegate(silence: Boolean) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(app)
                .setEnableFloatOutput(!silence)
                .setAudioProcessorChain(androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                    emptyArray<AudioProcessor>(), BitPerfectAudioSink.silenceProcessor(), androidx.media3.common.audio.SonicAudioProcessor()))
                .setAudioTrackProvider { config, attributes, session ->
                    androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackProvider.DEFAULT
                        .getAudioTrack(config, attributes, session).also(tracks::add)
                }.build()
            val sink = BitPerfectAudioSink(app, delegate(false), { precision }, { true }) { silence, _ -> delegate(silence) }
            org.robolectric.shadows.ShadowAudioTrack.addAudioDataListener(listener)
            try {
                sink.setListener(Proxy.newProxyInstance(AudioSink.Listener::class.java.classLoader,
                    arrayOf(AudioSink.Listener::class.java)) { _, _, _ -> null } as AudioSink.Listener)
                sink.setSkipSilenceEnabled(skip)
                sink.configure(pcm(), 0, null)
                sink.setVolume(.4f) // Crossfade gains must remain available on either output path.
                sink.play()
                val toneFrames = 2048
                val silentFrames = 144000
                val frames = toneFrames * 2 + silentFrames
                val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(toneFrames) { putFloat(.1f); putFloat(0f) }
                    repeat(silentFrames) { putLong(0) }
                    repeat(toneFrames) { putFloat(.1f); putFloat(0f) }
                    flip()
                }
                var attempts = 0
                while (input.hasRemaining()) {
                    assertTrue("PCM processing must make progress", attempts++ < 1000)
                    sink.handleBuffer(input, 0, 1)
                }
                repeat(10) {
                    sink.playToEndOfStream()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                        .idleFor(java.time.Duration.ofMillis(400))
                }
                val encoding = if (precision == OutputPrecision.FLOAT_32 && !skip) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                assertEquals("precision=$precision spatial=$spatial skip=$skip", encoding, tracks.single().audioFormat)
                val bytesPerFrame = if (encoding == C.ENCODING_PCM_FLOAT) 8 else 4
                val emitted = output.size() / bytesPerFrame
                if (skip) assertTrue("Long gaps must actually be removed", emitted < frames - 48000)
                else assertEquals(frames, emitted)
                val samples = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                samples.position(samples.limit() - bytesPerFrame)
                fun sample() = if (encoding == C.ENCODING_PCM_FLOAT) samples.float else samples.short / 32768f
                assertEquals(if (spatial) .1435f else .1f, sample(), .003f)
                assertEquals(if (spatial) -.0451f else 0f, sample(), .003f)
                assertTrue(sink.isEnded)
            } finally {
                sink.release()
                org.robolectric.shadows.ShadowAudioTrack.removeAudioDataListener(listener)
            }
        }
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
