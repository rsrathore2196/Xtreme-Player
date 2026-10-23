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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.abs

/** Validate and warm the shared Media3 cache, without another player, decoder or audio focus. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object LosslessStreamPrebuffer {
    private val worker = Mutex()
    private fun currentCheck(automatic: Boolean) {
        if (automatic && !com.example.performance.BackgroundWorkCoordinator.optionalPlaybackWorkMayProceed())
            throw PreparationDeferred()
    }
    private class PreparationDeferred : RuntimeException()
    private class PreparationFailure(val reason: LosslessDiagnostics.Reason, message: String) : IOException(message)
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
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            else -> 0
        }
        if (format.sampleRate <= 0 || format.channelCount <= 0 || format.drmInitData != null) return null
        val bitrate = format.averageBitrate.takeIf { it > 0 }?.div(1000) ?: fallbackBitrateKbps
        return AudioInfo(codec, bitrate.coerceAtLeast(0), format.sampleRate, bits)
    }

    /** Prepare a small prefix through the same cache; no extra player or audio focus. */
    suspend fun warmSource(context: Context, item: MediaItem): Boolean = withContext(com.example.performance.AppDispatchers.Io) {
        val uri = item.localConfiguration?.uri ?: return@withContext false
        if (uri.scheme == "xtreme-sabr") return@withContext true // Native session owns its buffer.
        val source = MusicCache.createCacheDataSourceFactory(context, 3_000, 3_000).createDataSource()
        try {
            source.open(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
            val bytes = ByteArray(32 * 1024)
            var read = 0
            val deadline = android.os.SystemClock.elapsedRealtime() + 2_000L
            while (read < 128 * 1024 && android.os.SystemClock.elapsedRealtime() < deadline) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = source.read(bytes, 0, bytes.size)
                if (count < 0) break
                read += count
            }
            read > 0
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        finally { source.close() }
    }

    internal fun isAdaptiveSource(item: MediaItem): Boolean =
        item.localConfiguration?.mimeType in setOf(MimeTypes.APPLICATION_MPD, MimeTypes.APPLICATION_M3U8)

    /** Let Media3 prepare adaptive manifests/init segments; progressive extractors cannot sniff an MPD.
     * No second player, audio sink or audio focus. Decoder input is still verified at activation.
     */
    suspend fun prepareAdaptiveSource(context: Context, item: MediaItem, expectedDurationMs: Long,
        stillCurrent: () -> Boolean, durationToleranceMs: Long = 5_000L, automatic: Boolean = false,
        currentPosition: () -> Long = { 0L }): Boolean {
        if (automatic) return com.example.performance.BackgroundWorkCoordinator.startup(
            com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED, requireForeground = false,
            label = "Adaptive lossless preparation") {
            prepareAdaptiveSource(context, item, expectedDurationMs, stillCurrent, durationToleranceMs, currentPosition = currentPosition)
        }
        return withContext(com.example.performance.AppDispatchers.Io) {
            worker.withLock {
                val uri = item.localConfiguration?.uri ?: return@withLock false
                fun allowed(): Boolean {
                    val policy = NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot()
                    return policy.online && com.example.data.remote.StreamingAddons.isVerified(uri.toString(), policy)
                }
                if (!allowed()) return@withLock false
                data class Metadata(val formats: List<Format>, val durationMs: Long, val manifest: Any?)
                val upstream = MusicCache.createCacheDataSourceFactory(context, 5_000, 5_000)
                val bytes = java.util.concurrent.atomic.AtomicLong()
                val factory = androidx.media3.datasource.DataSource.Factory {
                    val delegate = upstream.createDataSource()
                    object : androidx.media3.datasource.DataSource by delegate {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (bytes.get() >= 4 * 1024 * 1024L) throw IOException("Adaptive preparation byte budget reached")
                            return delegate.read(buffer, offset, minOf(length.toLong(), 4 * 1024 * 1024L - bytes.get()).toInt())
                                .also { if (it > 0) bytes.addAndGet(it.toLong()) }
                        }
                    }
                }
                try {
                    val metadata = withContext(Dispatchers.Main.immediate) {
                        if (!stillCurrent()) return@withContext null
                        val helper = androidx.media3.exoplayer.offline.DownloadHelper.forMediaItem(item,
                            androidx.media3.common.TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, null, factory)
                        try {
                            withTimeoutOrNull(12_000L) { suspendCancellableCoroutine<Metadata> { continuation ->
                                helper.prepare(object : androidx.media3.exoplayer.offline.DownloadHelper.Callback {
                                    override fun onPrepared(value: androidx.media3.exoplayer.offline.DownloadHelper) {
                                        if (!continuation.isActive) return
                                        if (value.periodCount > 8 || (0 until value.periodCount).any {
                                            value.getTrackGroups(it).length > 8 || (0 until value.getTrackGroups(it).length).any { group ->
                                                value.getTrackGroups(it)[group].length > 16 }
                                        }) {
                                            continuation.resumeWithException(IOException("Adaptive track budget reached")); return
                                        }
                                        val formats = (0 until value.periodCount).flatMap { period ->
                                            val groups = value.getTrackGroups(period)
                                            (0 until groups.length).flatMap { group ->
                                                (0 until groups[group].length).map { groups[group].getFormat(it) }
                                            }
                                        }
                                        val duration = when (val manifest = value.manifest) {
                                            is androidx.media3.exoplayer.dash.manifest.DashManifest -> manifest.durationMs
                                            is androidx.media3.exoplayer.hls.HlsManifest -> manifest.mediaPlaylist.durationUs / 1000
                                            else -> C.TIME_UNSET
                                        }
                                        continuation.resume(Metadata(formats, duration, value.manifest))
                                    }
                                    override fun onPrepareError(value: androidx.media3.exoplayer.offline.DownloadHelper, error: IOException) {
                                        if (continuation.isActive) continuation.resumeWithException(error)
                                    }
                                })
                            } }
                        } finally { helper.release() }
                    } ?: run {
                        LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.LOOKUP_TIMEOUT, "adaptive-prebuffer")
                        return@withLock false
                    }
                    val supported = withContext(com.example.performance.AppDispatchers.CpuBounded) {
                        val audio = metadata.formats.filter { it.sampleMimeType?.startsWith("audio/") == true }
                        audio.isNotEmpty() && metadata.formats.none { it.sampleMimeType?.startsWith("video/") == true } &&
                            audio.any { format -> format.drmInitData == null && audioInfo(format) != null &&
                                (format.sampleMimeType == MimeTypes.AUDIO_RAW ||
                                    MediaCodecUtil.getDecoderInfos(format.sampleMimeType!!, false, false).any { it.isFormatSupported(format) }) }
                    }
                    if (!supported || metadata.durationMs <= 0L || expectedDurationMs > 0L &&
                        abs(metadata.durationMs - expectedDurationMs) > durationToleranceMs) return@withLock false
                    val position = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    } ?: return@withLock false
                    val ranges = withContext(com.example.performance.AppDispatchers.CpuBounded) {
                        val dash = metadata.manifest as? androidx.media3.exoplayer.dash.manifest.DashManifest
                        if (dash == null || dash.dynamic || dash.periodCount == 0) emptyList() else {
                            val periodIndex = (0 until dash.periodCount).lastOrNull { dash.getPeriod(it).startMs <= position } ?: 0
                            val period = dash.getPeriod(periodIndex)
                            val representation = period.adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }
                                .flatMap { it.representations }.filter { audioInfo(it.format) != null && it.format.drmInitData == null }
                                .maxByOrNull { it.format.bitrate }
                            val base = representation?.baseUrls?.firstOrNull()?.url
                            if (representation == null || base == null) emptyList() else buildList {
                                fun addRange(range: androidx.media3.exoplayer.dash.manifest.RangedUri?) {
                                    if (range != null) add(DataSpec.Builder().setUri(range.resolveUri(base)).setPosition(range.start)
                                        .setLength(range.length).build())
                                }
                                addRange(representation.initializationUri)
                                addRange(representation.indexUri)
                                representation.index?.let { index ->
                                    val duration = dash.getPeriodDurationUs(periodIndex)
                                    addRange(index.getSegmentUrl(index.getSegmentNum((position - period.startMs).coerceAtLeast(0L) * 1000, duration)))
                                }
                            }
                        }
                    }
                    for (range in ranges.distinctBy { Triple(it.uri, it.position, it.length) }.take(3)) {
                        if (!allowed() || !withContext(Dispatchers.Main.immediate) { stillCurrent() }) return@withLock false
                        currentCoroutineContext().ensureActive()
                        val source = factory.createDataSource()
                        try {
                            source.open(range)
                            val buffer = ByteArray(32 * 1024)
                            var read = 0
                            while (read < 1024 * 1024) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer, 0, minOf(buffer.size, 1024 * 1024 - read))
                                if (count < 0) break
                                read += count
                            }
                            if (read == 0) return@withLock false
                        } finally { source.close() }
                    }
                    allowed() && withContext(Dispatchers.Main.immediate) { stillCurrent() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val kind = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString(":") { it.javaClass.simpleName }
                    LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.PREBUFFER_FAILED, "adaptive-prebuffer:$kind")
                    false
                }
            }
        }
    }

    suspend fun prepare(context: Context, item: MediaItem, expectedDurationMs: Long,
        currentPosition: () -> Long, stillCurrent: () -> Boolean, durationToleranceMs: Long = 5_000L,
        allowUnknownDuration: Boolean = false, automatic: Boolean = false): Prepared? {
        val coordinator = com.example.performance.BackgroundWorkCoordinator
        suspend fun attempt(): Prepared? = coordinator.finishBoundedBatch {
            prepareAttempt(context, item, expectedDurationMs, currentPosition, stillCurrent,
                durationToleranceMs, allowUnknownDuration, automatic)
        }
        if (!automatic) return attempt()
        while (withContext(Dispatchers.Main.immediate) { stillCurrent() }) {
            try {
                return coordinator.startup(com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED, requireForeground = false,
                    label = "Automatic lossless prebuffer") { attempt() }
            } catch (_: PreparationDeferred) {
                // The byte cache survives; all extractor/cache locks and the
                // coordinator lease have closed. A gesture is not a bad FLAC.
            }
        }
        return null
    }

    private suspend fun prepareAttempt(context: Context, item: MediaItem, expectedDurationMs: Long,
        currentPosition: () -> Long, stillCurrent: () -> Boolean, durationToleranceMs: Long,
        allowUnknownDuration: Boolean, automatic: Boolean): Prepared? = withContext(com.example.performance.AppDispatchers.Io) {
        worker.withLock {
            fun checkpoint() {
                currentCheck(automatic)
            }
            checkpoint() // Recheck after waiting for the single preparation worker.
            LosslessDiagnostics.clear(item.mediaId)
            val uri = item.localConfiguration?.uri ?: return@withLock null
            fun allowed(): Boolean {
                if (uri.scheme in setOf("file", "content")) return true
                val policy = NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot()
                if (!policy.maximum || !policy.online) return false
                val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
                return if (host == "archive.org" || host.endsWith(".archive.org")) policy.allowsArchive
                    else com.example.data.remote.StreamingAddons.isVerified(uri.toString(), policy)
            }
            if (!allowed()) {
                LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "prebuffer")
                return@withLock null
            }
            if (!withContext(Dispatchers.Main.immediate) { stillCurrent() }) return@withLock null
            val source = MusicCache.createCacheDataSourceFactory(context.applicationContext,
                connectTimeoutMs = 5_000, readTimeoutMs = 5_000).createDataSource()
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            val maxBytes = 24L * 1024 * 1024
            val jobContext = currentCoroutineContext()
            var bytes = 0L
            val reader = DataReader { buffer, offset, length ->
                jobContext.ensureActive()
                checkpoint()
                if (SystemClock.elapsedRealtime() >= deadline || bytes >= maxBytes)
                    throw PreparationFailure(LosslessDiagnostics.Reason.PREBUFFER_FAILED, "Optional preparation budget reached")
                source.read(buffer, offset, minOf(length.toLong(), maxBytes - bytes).toInt())
                    .also { if (it > 0) bytes += it }
            }
            var opened = false
            var fullLength = C.LENGTH_UNSET.toLong()
            fun openAt(position: Long): DefaultExtractorInput {
                jobContext.ensureActive()
                checkpoint()
                // This is optional preparation, not the audible stream. Never
                // open another Archive range after a capped-network handover.
                if (!allowed())
                    throw PreparationFailure(LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "Active profile changed")
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
                } ?: throw PreparationFailure(LosslessDiagnostics.Reason.DECODER_UNSUPPORTED, "Unsupported audio container")
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
                    checkpoint()
                    if (SystemClock.elapsedRealtime() >= deadline) throw PreparationFailure(
                        LosslessDiagnostics.Reason.PREBUFFER_FAILED, "Lossless preparation budget reached")
                    return when (extractor.read(input, nextPosition)) {
                        Extractor.RESULT_END_OF_INPUT -> false
                        Extractor.RESULT_SEEK -> { input = openAt(nextPosition.position); true }
                        else -> true
                    }
                }
                while (format == null || seekMap == null) if (!step())
                    throw PreparationFailure(LosslessDiagnostics.Reason.METADATA_INCOMPLETE, "No complete audio format")
                val verifiedFormat = format ?: return@withLock null
                val info = audioInfo(verifiedFormat) ?: throw PreparationFailure(
                    if (verifiedFormat.sampleMimeType.isNullOrBlank() ||
                        (verifiedFormat.sampleMimeType in setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW) &&
                            (verifiedFormat.sampleRate <= 0 || verifiedFormat.channelCount <= 0)))
                        LosslessDiagnostics.Reason.METADATA_INCOMPLETE else LosslessDiagnostics.Reason.DECODER_UNSUPPORTED,
                    "Container did not contain a complete supported lossless format")
                if (verifiedFormat.sampleMimeType != MimeTypes.AUDIO_RAW &&
                    MediaCodecUtil.getDecoderInfos(verifiedFormat.sampleMimeType!!, false, false)
                        .none { it.isFormatSupported(verifiedFormat) }) throw PreparationFailure(
                            LosslessDiagnostics.Reason.DECODER_UNSUPPORTED, "No compatible decoder")
                val map = seekMap ?: return@withLock null
                val actualDuration = map.durationUs.takeIf { it > 0L }?.div(1000L)
                if (actualDuration != null && expectedDurationMs > 0L &&
                    abs(actualDuration - expectedDurationMs) > durationToleranceMs) throw PreparationFailure(
                        LosslessDiagnostics.Reason.RECORDING_MISMATCH, "Actual duration conflicts with the recording")
                if (actualDuration == null && !allowUnknownDuration) throw PreparationFailure(
                    LosslessDiagnostics.Reason.METADATA_INCOMPLETE, "Duration is unavailable without independent identity evidence")
                val bitrate = info.bitrateKbps.takeIf { it > 0 } ?: if (fullLength > 0L)
                    (fullLength * 8 / (actualDuration ?: expectedDurationMs).coerceAtLeast(1L))
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0

                // Seek using the real container index, never a bitrate-to-offset estimate.
                repeat(2) {
                    val position = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    } ?: return@withLock null
                    if ((actualDuration ?: expectedDurationMs) - position < 2_000L) return@withLock null
                    if (map.isSeekable) {
                        val point = map.getSeekPoints(position * 1000).first
                        input = openAt(point.position)
                        extractor.seek(point.position, point.timeUs)
                        firstSampleUs = Long.MAX_VALUE; lastSampleUs = Long.MIN_VALUE
                    }
                    // No fabricated byte offsets. For an unindexed file, continue
                    // reading real samples from the start within the same byte/time cap.
                    while (lastSampleUs < (position + 3_000L) * 1000) if (!step())
                        throw PreparationFailure(LosslessDiagnostics.Reason.PREBUFFER_FAILED, "Stream ended before the target position")
                    if (!map.isSeekable && position > 0L) {
                        if (actualDuration == null) throw PreparationFailure(LosslessDiagnostics.Reason.METADATA_INCOMPLETE,
                            "No safe seek index or complete duration")
                        // FLAC with neither a seek table nor HTTP Content-Length can
                        // become seekable after a bounded complete disk-cache fill.
                        // CacheDataSource records the real length at EOF, allowing
                        // Media3's native binary seeker on activation. Never start
                        // an unseekable replacement audibly at the beginning.
                        while (step()) Unit
                        source.close(); opened = false
                        if (!MusicCache.fullyCached(context, uri.toString())) throw PreparationFailure(
                            LosslessDiagnostics.Reason.PREBUFFER_FAILED, "Cannot guarantee position-preserving activation")
                    }
                    val warmed = Prepared(info.copy(bitrateKbps = bitrate), firstSampleUs / 1000, lastSampleUs / 1000)
                    val latest = withContext(Dispatchers.Main.immediate) {
                        if (stillCurrent()) currentPosition().coerceAtLeast(0L) else null
                    } ?: return@withLock null
                    if (warmed.covers(latest)) return@withLock warmed
                }
                LosslessDiagnostics.record(item.mediaId, LosslessDiagnostics.Reason.BUFFER_COVERAGE, "prebuffer")
                null
            } catch (e: CancellationException) { throw e }
            catch (e: PreparationDeferred) { throw e }
            catch (e: Exception) {
                val reason = when (e) {
                    is PreparationFailure -> e.reason
                    is SocketTimeoutException -> LosslessDiagnostics.Reason.NETWORK_TIMEOUT
                    else -> LosslessDiagnostics.Reason.PREBUFFER_FAILED
                }
                LosslessDiagnostics.record(item.mediaId, reason, "prebuffer")
                null
            }
            finally {
                extractors.forEach { it.release() }
                if (opened) source.close()
            }
        }
    }
}
