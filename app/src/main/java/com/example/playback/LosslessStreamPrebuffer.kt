package com.example.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.wav.WavExtractor
import com.example.service.MusicCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs

/** Validate and warm the shared Media3 cache, without another player, decoder or audio focus. */
internal object LosslessStreamPrebuffer {
    private val worker = Mutex()
    data class AudioInfo(val codec: String, val bitrateKbps: Int, val sampleRate: Int, val bitDepth: Int) {
        val isHiRes: Boolean get() = sampleRate >= 44_100 && bitDepth >= 16 &&
            (bitDepth > 16 || sampleRate > 48_000)
        val badge: String get() = if (isHiRes) "Hi-Res Lossless" else "Lossless"
    }
    data class Prepared(val audio: AudioInfo, val fromMs: Long, val untilMs: Long) {
        fun covers(positionMs: Long): Boolean = positionMs >= fromMs && untilMs - positionMs >= 750L
    }

    /** Only properties reported by the actual container qualify for a hi-res badge. */
    fun audioInfo(format: Format, fallbackBitrateKbps: Int = 0): AudioInfo? {
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            else -> return null
        }
        val bits = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        if (format.sampleRate <= 0 || format.channelCount <= 0 || format.drmInitData != null) return null
        val bitrate = format.averageBitrate.takeIf { it > 0 }?.div(1000) ?: fallbackBitrateKbps
        return AudioInfo(codec, bitrate.coerceAtLeast(0), format.sampleRate, bits)
    }

    suspend fun prepare(context: Context, item: MediaItem, expectedDurationMs: Long,
        currentPosition: () -> Long, stillCurrent: () -> Boolean): Prepared? = withContext(Dispatchers.IO) {
        worker.withLock {
            if (!NetworkQualityMonitor.getInstance(context).snapshot().allowsLosslessUpgrade) return@withLock null
            if (!withContext(Dispatchers.Main.immediate) { stillCurrent() }) return@withLock null
            val uri = item.localConfiguration?.uri ?: return@withLock null
            val source = MusicCache.createCacheDataSourceFactory(context.applicationContext,
                connectTimeoutMs = 3_000, readTimeoutMs = 3_000).createDataSource()
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            val jobContext = currentCoroutineContext()
            var bytes = 0L
            val reader = DataReader { buffer, offset, length ->
                jobContext.ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline || bytes >= 8L * 1024 * 1024)
                    throw IOException("Optional lossless prebuffer exceeded its budget")
                source.read(buffer, offset, minOf(length.toLong(), 8L * 1024 * 1024 - bytes).toInt())
                    .also { if (it > 0) bytes += it }
            }
            var opened = false
            var fullLength = C.LENGTH_UNSET.toLong()
            fun openAt(position: Long): DefaultExtractorInput {
                jobContext.ensureActive()
                // This is optional preparation, not the audible stream. Never
                // open another Archive range after a capped-network handover.
                if (!NetworkQualityMonitor.getInstance(context).snapshot().allowsLosslessUpgrade)
                    throw IOException("Lossless prebuffer no longer permitted by the active network")
                if (opened) source.close()
                opened = true
                val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                if (remaining >= 0L) fullLength = position + remaining
                return DefaultExtractorInput(reader, position, fullLength)
            }
            val extractors = arrayOf(FlacExtractor(), WavExtractor(), Mp4Extractor())
            try {
                var input = openAt(0L)
                val extractor = extractors.firstOrNull { candidate ->
                    try { candidate.sniff(input) } finally { input.resetPeekPosition() }
                } ?: return@withLock null
                var format: Format? = null
                var seekMap: SeekMap? = null
                var firstSampleUs = Long.MAX_VALUE
                var lastSampleUs = Long.MIN_VALUE
                val discard = DummyTrackOutput()
                val output = object : TrackOutput {
                    override fun format(value: Format) { format = value }
                    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int =
                        discard.sampleData(input, length, allowEndOfInput, part)
                    override fun sampleData(data: ParsableByteArray, length: Int, part: Int) =
                        discard.sampleData(data, length, part)
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                        if (crypto != null) throw IOException("Encrypted lossless audio is unsupported")
                        firstSampleUs = minOf(firstSampleUs, timeUs)
                        lastSampleUs = maxOf(lastSampleUs, timeUs)
                    }
                }
                extractor.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        if (type == C.TRACK_TYPE_VIDEO) throw IOException("Lossless candidate is not audio-only")
                        return if (type == C.TRACK_TYPE_AUDIO) output else discard
                    }
                    override fun endTracks() = Unit
                    override fun seekMap(value: SeekMap) { seekMap = value }
                })
                val nextPosition = PositionHolder()
                fun step(): Boolean {
                    jobContext.ensureActive()
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Lossless prebuffer timed out")
                    return when (extractor.read(input, nextPosition)) {
                        Extractor.RESULT_END_OF_INPUT -> false
                        Extractor.RESULT_SEEK -> { input = openAt(nextPosition.position); true }
                        else -> true
                    }
                }
                while (format == null || seekMap == null) if (!step()) return@withLock null
                val verifiedFormat = format ?: return@withLock null
                val info = audioInfo(verifiedFormat) ?: return@withLock null
                if (verifiedFormat.sampleMimeType != MimeTypes.AUDIO_RAW &&
                    MediaCodecUtil.getDecoderInfos(verifiedFormat.sampleMimeType!!, false, false)
                        .none { it.isFormatSupported(verifiedFormat) }) return@withLock null
                val map = seekMap ?: return@withLock null
                if (map.durationUs <= 0L || expectedDurationMs <= 0L ||
                    abs(map.durationUs / 1000 - expectedDurationMs) > 2_000L) return@withLock null
                val bitrate = info.bitrateKbps.takeIf { it > 0 } ?: if (fullLength > 0L)
                    (fullLength * 8 / (map.durationUs / 1000).coerceAtLeast(1L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0

                // Seek using the real container index, never a bitrate-to-offset estimate.
                repeat(2) {
                    val position = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    } ?: return@withLock null
                    if (map.durationUs / 1000 - position < 2_000L || !map.isSeekable) return@withLock null
                    val point = map.getSeekPoints(position * 1000).first
                    input = openAt(point.position)
                    extractor.seek(point.position, point.timeUs)
                    firstSampleUs = Long.MAX_VALUE; lastSampleUs = Long.MIN_VALUE
                    while (lastSampleUs < (position + 1_500L) * 1000) if (!step()) return@withLock null
                    val warmed = Prepared(info.copy(bitrateKbps = bitrate), firstSampleUs / 1000, lastSampleUs / 1000)
                    val latest = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    } ?: return@withLock null
                    if (warmed.covers(latest)) return@withLock warmed
                }
                null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            finally {
                extractors.forEach { it.release() }
                if (opened) source.close()
            }
        }
    }
}
