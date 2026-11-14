package com.example

import android.app.Application
import android.content.Context
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.*
import androidx.test.core.app.ApplicationProvider
import com.example.playback.BitPerfectAudioSink
import com.example.playback.CrossfadeController
import com.example.service.PolicyResolvingMediaSource
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real Media3 periods, WAV extraction, decoding and AudioTrack output; no network audio. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CrossfadeMedia3PlaybackTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sources = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val players = mutableListOf<ExoPlayer>()
    private val opened = CopyOnWriteArrayList<Pair<String, Long>>()
    private val resolutions = ConcurrentHashMap<String, AtomicInteger>()
    private val pcmOutputs = ConcurrentHashMap<String, AtomicInteger>()
    private val failures = CopyOnWriteArrayList<PlaybackException>()

    @After fun cleanup() = runBlocking(Dispatchers.Main) {
        players.filterNot { it.isReleased }.forEach { it.release() }
        scope.cancel(); sources.cancel()
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
        withTimeout(timeoutMs) {
            while (!condition()) {
                failures.firstOrNull()?.let { throw AssertionError("Actual Media3 decoding failed", it) }
                delay(20)
            }
        }
    }

    @Test fun nextPeriodResolvesAndBuffersBeforeTheCurrentRecordingIsNearItsEndAndSeekingOpensTheRightBytes() = runBlocking(Dispatchers.Main) {
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

    @Test fun crossfadeRunsTwoRealDecodedPlayersWithBlendedGainsThenKeepsTheSessionPlayerOnTheNextTrack() = runBlocking(Dispatchers.Main) {
        val factory = factory(mapOf("first" to 60, "second" to 60))
        val primary = player("primary", factory)
        CrossfadeController.enableQueueReadAhead(primary)
        primary.volume = .8f
        val controller = CrossfadeController(primary, scope, createTail = { player("tail", factory, true) })
        try {
            primary.setMediaItems(listOf(item("first"), item("second")), 0, 60_000L - CrossfadeController.WARM_MS)
            primary.prepare(); primary.play(); controller.setEnabled(true)
            var overlap = false
            var overlappingTail: ExoPlayer? = null
            val envelope = mutableListOf<Pair<Float, Float>>()
            val trace = mutableListOf<String>()
            var tracedAt = Long.MIN_VALUE
            try {
                until(40_000) {
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
                trace.forEach { println("Crossfade state: $it") }
                throw error
            }
            assertTrue("Both independently decoded sources must genuinely overlap", overlap)
            // Device scheduling may skip polling samples; validate the actual
            // simultaneous gains instead of assuming a fixed polling frame rate.
            envelope.forEach { (incoming, outgoing) ->
                assertEquals(.8f * .8f, incoming * incoming + outgoing * outgoing, .01f)
            }
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
        val bytes = ByteArray(44 + frames * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            repeat(16) { index -> putShort((if (index < 8) value else -value).toShort()) }
        }
        // Duplicate the PCM period in blocks instead of spending test time generating millions of samples.
        var filled = 32
        while (filled < frames * 2) {
            val count = minOf(filled, frames * 2 - filled)
            System.arraycopy(bytes, 44, bytes, 44 + filled, count)
            filled += count
        }
        return bytes
    }
}
