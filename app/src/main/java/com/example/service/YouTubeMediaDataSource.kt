package com.example.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.example.data.local.AudioQualityPreferences
import com.example.data.remote.YouTubeAudioStreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.IOException

/** Honors InnerTubeX's bounded-range contract beneath Media3's existing cache. */
internal class YouTubeMediaDataSource(private val context: Context, private val upstream: DataSource.Factory) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    private var original: DataSpec? = null
    private var audio: YouTubeAudioStreamService.AudioStream? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = C.LENGTH_UNSET.toLong()
    private var recoveries = 0

    override fun addTransferListener(listener: TransferListener) {
        listeners.add(listener); delegate?.addTransferListener(listener)
    }
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme == "xtreme-sabr") {
            val source = SabrDataSource(context)
            listeners.forEach(source::addTransferListener)
            delegate = source
            original = dataSpec
            remaining = source.open(dataSpec)
            return remaining
        }
        original = dataSpec
        audio = YouTubeAudioStreamService.cachedForUrl(dataSpec.uri.toString())?.takeUnless { it.isHls }
        recoveries = 0
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else audio?.contentLength?.let { (it - position).coerceAtLeast(0L) } ?: C.LENGTH_UNSET.toLong()
        if (remaining == 0L) return 0L
        openRecovering()
        return remaining
    }
    private fun openChunk() {
        val base = requireNotNull(original)
        val stream = audio
        var size = if (stream?.useRangeChunks == true) {
            if (remaining < 0) stream.rangeChunkSize else minOf(remaining, stream.rangeChunkSize)
        } else remaining
        val spec = if (stream == null) base else base.buildUpon().setUri(stream.url)
            .setPosition(position).setLength(size).setHttpRequestHeaders(base.httpRequestHeaders + stream.headers +
                mapOf("Accept-Encoding" to "identity")).build()
        val source = upstream.createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        val available = source.open(spec)
        if (stream != null && !YouTubeAudioStreamService.isMediaUrl(source.uri?.toString().orEmpty()))
            throw IOException("Invalid audio CDN redirect")
        if (stream?.useRangeChunks == true) {
            val value = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
                ?.value?.firstOrNull()
            val range = value?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it) }
                ?: throw IOException("Audio CDN did not honor the requested range")
            val end = range.groupValues[2].toLong()
            val total = range.groupValues[3].toLong()
            if (range.groupValues[1].toLong() != position || end < position || total <= end ||
                end != minOf(position + size - 1, total - 1) ||
                stream.contentLength?.let { it != total } == true)
                throw IOException("Audio CDN returned an inconsistent byte range")
            // A valid final 206 response can be shorter than the requested chunk,
            // especially when extraction did not publish a content length.
            size = end - position + 1
            if (remaining < 0) remaining = total - position
        }
        if (remaining < 0 && available >= 0 && stream?.useRangeChunks != true) remaining = available
        chunkRemaining = if (size >= 0) size else available
    }
    private fun openRecovering() {
        while (true) try { openChunk(); return }
        catch (error: IOException) { recover(error) }
    }
    private fun recover(error: IOException) {
        val stream = audio ?: throw error // Other providers keep their existing Media3 behavior.
        if (recoveries++ >= 2 || Thread.currentThread().isInterrupted) throw error
        val status = generateSequence<Throwable>(error) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val expired = status in setOf(401, 403, 410)
        val transient = status == null || status in setOf(408, 429) || status in 500..599
        if (!expired && !transient) throw error
        closeDelegate()
        if (expired) {
            val videoId = stream.videoId
            val failedRoot = YouTubeAudioStreamService.playbackUri("yt_$videoId", stream.url)
            YouTubeAudioStreamService.reject(videoId, stream.url, stream.clientId)
            if (!YouTubeAudioStreamService.isVideoId(videoId)) throw error
            val refreshed = try {
                runBlocking(Dispatchers.IO) {
                    YouTubeAudioStreamService.resolve(videoId, AudioQualityPreferences.getSelectedQuality(context),
                        forceRefresh = true, hlsOnly = false, excludedClient = stream.clientId,
                        maxBitrateBps = stream.selectionCapBps)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Audio stream refresh interrupted", e)
            } catch (e: CancellationException) {
                throw IOException("Audio stream refresh canceled or timed out", e)
            } catch (e: Exception) {
                throw IOException("Couldn't refresh the audio stream", e)
            }
            // Cached bytes/extractor offsets are valid only for the same representation.
            // A format change is recovered by the player's existing time-based retry.
            if (refreshed.isHls || refreshed.isSabr || refreshed.cacheKey != stream.cacheKey) {
                SabrSessionRegistry.recordReplacement(failedRoot, refreshed)
                throw SabrStreamChangedException(refreshed)
            }
            audio = refreshed
        } else {
            try { Thread.sleep(200L * recoveries) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw error }
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (original?.uri?.scheme == "xtreme-sabr") return requireNotNull(delegate).read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        while (true) {
            try {
                if (chunkRemaining == 0L) { closeDelegate(); openRecovering() }
                val size = if (chunkRemaining >= 0) minOf(length.toLong(), chunkRemaining).toInt() else length
                val count = requireNotNull(delegate).read(buffer, offset, size)
                if (count == C.RESULT_END_OF_INPUT) {
                    if (remaining > 0) throw EOFException("Audio response ended before its declared length")
                    remaining = 0L; return count
                }
                position += count
                if (remaining >= 0) remaining -= count
                if (chunkRemaining >= 0) chunkRemaining -= count
                return count
            } catch (error: IOException) { recover(error); openRecovering() }
        }
    }
    private fun closeDelegate() { try { delegate?.close() } finally { delegate = null } }
    override fun getUri(): Uri? = delegate?.uri ?: original?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { closeDelegate() } finally { original = null; audio = null } }
}
