package com.example.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.example.data.remote.YouTubeAudioStreamService
import java.io.EOFException
import java.io.IOException

/** Honors InnerTubeX's bounded-range contract beneath Media3's existing cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class YouTubeMediaDataSource(private val context: Context, private val upstream: DataSource.Factory,
    private val audioHttp: DataSource.Factory = upstream) : DataSource {
    private companion object { val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)") }
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    private var original: DataSpec? = null
    private var audio: YouTubeAudioStreamService.AudioStream? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = C.LENGTH_UNSET.toLong()

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
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else audio?.contentLength?.let { (it - position).coerceAtLeast(0L) } ?: C.LENGTH_UNSET.toLong()
        if (remaining == 0L) return 0L
        openChecked()
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
        val source = (if (stream == null) upstream else audioHttp).createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        val available = source.open(spec)
        if (stream != null && !YouTubeAudioStreamService.isMediaUrl(source.uri?.toString().orEmpty()))
            throw IOException("Invalid audio CDN redirect")
        if (stream?.useRangeChunks == true) {
            val value = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
                ?.value?.firstOrNull()
            val range = value?.let { CONTENT_RANGE.matchEntire(it) }
            if (range != null) {
                if ((source as? HttpDataSource)?.responseCode != 206)
                    throw IOException("Audio CDN returned an inconsistent range status")
                val end = range.groupValues[2].toLongOrNull()
                    ?: throw IOException("Invalid audio range end")
                val total = range.groupValues[3].toLongOrNull()
                    ?: throw IOException("Invalid audio content length")
                if (range.groupValues[1].toLongOrNull() != position || end < position || total <= end ||
                    end > position + size - 1 || stream.contentLength?.let { it != total } == true)
                    throw IOException("Audio CDN returned an inconsistent byte range")
                // HTTP permits a server to cap a valid range below our requested
                // chunk size. Read exactly those bytes, then request the next range.
                size = end - position + 1
                if (remaining < 0) remaining = total - position
            } else {
                val declaredLength = source.responseHeaders.entries.firstOrNull {
                    it.key.equals("Content-Length", true)
                }?.value?.firstOrNull()?.toLongOrNull()
                // A 200 full-body response is valid when Range is ignored: Media3's
                // DefaultHttpDataSource skips spec.position itself. Accept only a
                // known complete representation; never assume partial bytes start at zero.
                if ((source as? HttpDataSource)?.responseCode != 200 || stream.contentLength == null ||
                    declaredLength != stream.contentLength || available < size)
                    throw IOException("Audio CDN did not honor the requested range")
                // Reopen at most once with the remaining known length after this
                // chunk; repeatedly skipping a full body would waste data and CPU.
                audio = stream.copy(useRangeChunks = false)
            }
        }
        if (remaining < 0 && available >= 0 && stream?.useRangeChunks != true) remaining = available
        chunkRemaining = if (size >= 0) size else available
    }
    private fun openChecked() {
        try { openChunk() } catch (error: IOException) { throw recoveryFailure(error) }
    }
    private fun recoveryFailure(error: IOException): IOException {
        val stream = audio ?: return error // Other providers keep their existing Media3 behavior.
        val status = generateSequence<Throwable>(error) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (status !in setOf(401, 403, 410) || Thread.currentThread().isInterrupted) return error
        // Extraction belongs to the controller/service's cancellable coroutine.
        // Never block this synchronous loader callback on WebView/network recovery.
        YouTubeAudioStreamService.reject(stream.videoId, stream.url, stream.clientId)
        return YouTubeStreamRefreshRequiredException(error)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (original?.uri?.scheme == "xtreme-sabr") return requireNotNull(delegate).read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            if (chunkRemaining == 0L) { closeDelegate(); openChecked() }
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
        } catch (error: IOException) { throw recoveryFailure(error) }
    }
    private fun closeDelegate() { try { delegate?.close() } finally { delegate = null } }
    override fun getUri(): Uri? = delegate?.uri ?: original?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { closeDelegate() } finally { original = null; audio = null } }
}
