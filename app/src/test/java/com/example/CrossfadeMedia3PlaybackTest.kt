package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.*
import androidx.test.core.app.ApplicationProvider
import com.example.playback.BitPerfectAudioSink
import com.example.playback.CrossfadeController
import com.example.service.PolicyResolvingMediaSource
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real Media3 periods, WAV extraction, decoding and AudioTrack output; no network audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class,
    shadows = [CrossfadeMedia3PlaybackTest.ClockedAudioTrack::class])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CrossfadeMedia3PlaybackTest {
    /** The stock shadow treats every written frame as already played. Model the
     * output clock so real Media3 buffering and overlapping decoders can run. */
    @Implements(android.media.AudioTrack::class)
    class ClockedAudioTrack : ShadowAudioTrack() {
        @RealObject private lateinit var track: android.media.AudioTrack
        private var played = 0L
        private var startedAt = 0L
        private var running = false

        @Synchronized @Implementation override fun getPlaybackHeadPosition(): Int {
            val available = super.getPlaybackHeadPosition().toLong()
            val elapsedFrames = if (running)
                (android.os.SystemClock.elapsedRealtime() - startedAt) * track.sampleRate / 1_000L
                else 0L
            return minOf(available, played + elapsedFrames).toInt()
        }
        @Synchronized @Implementation override fun play() {
            if (!running) { startedAt = android.os.SystemClock.elapsedRealtime(); running = true }
            super.play()
        }
        @Synchronized @Implementation fun pause() {
            played = getPlaybackHeadPosition().toLong(); running = false
            Shadow.directlyOn<Unit, android.media.AudioTrack>(track, android.media.AudioTrack::class.java, "pause")
        }
        @Synchronized @Implementation override fun flush() {
            super.flush(); played = 0L; startedAt = android.os.SystemClock.elapsedRealtime()
        }
    }
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sources = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val players = mutableListOf<ExoPlayer>()
    private val opened = CopyOnWriteArrayList<Pair<String, Long>>()
    private val resolutions = ConcurrentHashMap<String, AtomicInteger>()
    private val pcmOutputs = ConcurrentHashMap<String, AtomicInteger>()
    private val failures = CopyOnWriteArrayList<PlaybackException>()

    @Before fun decoder() {
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(android.media.MediaFormat.createAudioFormat(MimeTypes.AUDIO_RAW, 8_000, 1))
            .setIsEncoder(false).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("OMX.google.raw.decoder")
            .setIsEncoder(false).setCapabilities(capabilities).build())
        MediaCodecUtil.clearDecoderInfoCache()
    }
    @After fun cleanup() {
        players.filterNot { it.isReleased }.forEach { it.release() }
        scope.cancel(); sources.cancel()
        ShadowMediaCodecList.reset(); MediaCodecUtil.clearDecoderInfoCache()
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("memory://unresolved/$id.wav")
        .setMimeType(MimeTypes.AUDIO_WAV).build()

    private fun factory(durations: Map<String, Int>): MediaSource.Factory {
        val bodies = durations.mapValues { (id, seconds) -> wav(seconds, if (id == "first") 4000 else 8000) }
        val transport = DefaultMediaSourceFactory(context).setDataSourceFactory {
            var delegate: ByteArrayDataSource? = null
            object : DataSource {
                override fun addTransferListener(listener: TransferListener) {}
                override fun open(spec: DataSpec): Long {
                    val id = spec.uri.lastPathSegment!!.removeSuffix(".wav")
                    opened.add(id to spec.position)
                    return ByteArrayDataSource(bodies.getValue(id)).also { delegate = it }.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
                override fun getUri() = delegate?.uri
                override fun close() { delegate?.close(); delegate = null }
            }
        }
        return object : MediaSource.Factory by transport {
            override fun createMediaSource(item: MediaItem): MediaSource {
                if (item.localConfiguration?.uri?.host == "resolved") return transport.createMediaSource(item)
                return PolicyResolvingMediaSource(item, transport, sources) { requested ->
                    resolutions.getOrPut(requested.mediaId) { AtomicInteger() }.incrementAndGet()
                    delay(30) // A cancellable provider response, not an instant prepared stub.
                    requested.buildUpon().setUri("memory://resolved/${requested.mediaId}.wav").build()
                }
            }
        }
    }

    private fun player(name: String, factory: MediaSource.Factory, tail: Boolean = false): ExoPlayer {
        val renderer = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val sink = BitPerfectAudioSink(context, reportSignalPath = false, allowDirect = { false })
                return object : AudioSink by sink {
                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        if (buffer.hasRemaining()) pcmOutputs.getOrPut(name) { AtomicInteger() }.incrementAndGet()
                        return sink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    }
                }
            }
        }.forceDisableMediaCodecAsynchronousQueueing()
        return ExoPlayer.Builder(context, renderer).setMediaSourceFactory(factory).apply {
            if (tail) setLoadControl(CrossfadeController.tailLoadControl())
        }.build().also { p ->
            players.add(p)
            p.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failures.add(error) } })
            if (tail) p.pauseAtEndOfMediaItems = true
        }
    }

    private suspend fun until(timeoutMs: Long = 12_000, condition: () -> Boolean) {
        // Media3's audio position tracker also samples System.nanoTime(). Advance
        // Android's shadow clock at the same rate, rather than racing it 4x ahead.
        val startedAt = System.nanoTime()
        val clockStartedAt = android.os.SystemClock.elapsedRealtime()
        withTimeout(timeoutMs) {
            while (!condition()) {
                val target = clockStartedAt + (System.nanoTime() - startedAt) / 1_000_000L
                val advance = target - android.os.SystemClock.elapsedRealtime()
                if (advance > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(advance))
                else shadowOf(Looper.getMainLooper()).idle()
                failures.firstOrNull()?.let { throw AssertionError("Actual Media3 decoding failed", it) }
                delay(5)
            }
        }
    }

    @Test fun nextPeriodResolvesAndBuffersBeforeTheCurrentRecordingIsNearItsEndAndSeekingOpensTheRightBytes() = runBlocking {
        val player = player("primary", factory(mapOf("first" to 300, "second" to 60)))
        CrossfadeController.enableQueueReadAhead(player)
        player.setMediaItems(listOf(item("first"), item("second"))); player.prepare(); player.play()
        until { opened.any { it.first == "second" } && player.isPlaying }
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue("Prefetch must occur while the current track still has minutes to play", player.currentPosition < 30_000)
        assertEquals(1, resolutions.getValue("second").get())
        player.seekTo(120_000L)
        until { player.isPlaying && player.currentPosition >= 120_000 && opened.any { it.first == "first" && it.second > 1_800_000L } }
        assertTrue(player.currentPosition < 130_000)
        assertTrue(pcmOutputs.getValue("primary").get() > 0)
        println("Read-ahead/seek evidence: position=${player.currentPosition}, resolved=$resolutions, opens=$opened, pcm=$pcmOutputs")
    }

    @Test fun crossfadeRunsTwoRealDecodedPlayersWithBlendedGainsThenKeepsTheSessionPlayerOnTheNextTrack() = runBlocking {
        val factory = factory(mapOf("first" to 60, "second" to 60))
        val primary = player("primary", factory)
        CrossfadeController.enableQueueReadAhead(primary)
        primary.volume = .8f
        val controller = CrossfadeController(primary, scope, createTail = { player("tail", factory, true) })
        try {
            primary.setMediaItems(listOf(item("first"), item("second")), 0, 52_000L)
            primary.prepare(); primary.play(); controller.setEnabled(true)
            var overlap = false
            var overlappingTail: ExoPlayer? = null
            val envelope = mutableListOf<Pair<Float, Float>>()
            val trace = mutableListOf<String>()
            var tracedAt = Long.MIN_VALUE
            try {
                until(15_000) {
                    val tail = players.drop(1).lastOrNull { !it.isReleased }
                    if (tail != null && primary.currentMediaItemIndex == 1 && primary.isPlaying && tail.isPlaying &&
                        primary.volume in .01f.. .79f && tail.volume in .01f.. .79f) {
                        overlap = true; overlappingTail = tail; envelope.add(primary.volume to tail.volume)
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (tracedAt == Long.MIN_VALUE || now - tracedAt >= 500L) {
                        tracedAt = now
                        trace.add(players.mapIndexed { index, p ->
                            if (p.isReleased) "$index:released" else "$index:${p.currentMediaItemIndex}@${p.currentPosition}/${p.duration} " +
                                "state=${p.playbackState} playing=${p.isPlaying} buffer=${p.totalBufferedDuration} gain=${p.volume} uri=${p.currentMediaItem?.localConfiguration?.uri}"
                        }.joinToString("; "))
                    }
                    overlap && overlappingTail?.isReleased == true && primary.currentPosition >= 3_000L
                }
            } catch (error: Exception) {
                println("Crossfade state evidence: $trace")
                throw error
            }
            assertTrue("Both independently decoded sources must genuinely overlap", overlap)
            assertTrue(envelope.size > 5)
            assertTrue(pcmOutputs.getValue("primary").get() > 0)
            assertTrue(pcmOutputs.getValue("tail").get() > 0)
            assertEquals(1, primary.currentMediaItemIndex)
            assertTrue(primary.isPlaying)
            assertEquals(.8f, primary.volume, .001f)
            assertEquals(listOf("first", "second"), (0 until primary.mediaItemCount).map { primary.getMediaItemAt(it).mediaId })
            println("Crossfade PCM evidence: envelopeSamples=${envelope.size}, position=${primary.currentPosition}, pcm=$pcmOutputs, opens=$opened")
        } finally { controller.close() }
    }

    private fun wav(seconds: Int, value: Int): ByteArray {
        val frames = seconds * 8_000
        return ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { index -> putShort((if (index % 16 < 8) value else -value).toShort()) }
        }.array()
    }
}
