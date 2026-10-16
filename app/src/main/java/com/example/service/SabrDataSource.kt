package com.example.service

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Synchronous Media3 loader contract over InnerTubeX's asynchronous, disk-buffered audio flow. */
internal class SabrDataSource(private val context: Context) : BaseDataSource(true) {
    @Volatile private var reader: SabrSessionRegistry.Reader? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        checkWorkerThread()
        check(!opened) { "SABR data source is already open" }
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val acquired = blocking { SabrSessionRegistry.acquire(context, dataSpec.uri, dataSpec.position) }
        reader = acquired
        try {
            val length = acquired.contentLength
            if (length != null && dataSpec.position > length)
                throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            remaining = if (length == null) dataSpec.length else {
                val available = length - dataSpec.position
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
            }
            if (remaining != 0L) blocking { acquired.ready() }
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) { reader = null; acquired.close(); throw e }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkWorkerThread()
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = blocking {
            (reader ?: throw IOException("SABR data source is closed")).read(buffer, offset,
                if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
        }
        if (count > 0) {
            if (remaining >= 0) remaining -= count
            bytesTransferred(count)
        }
        return count
    }

    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = reader?.let {
        buildMap {
            put("Content-Type", listOf(it.mimeType))
            it.contentLength?.let { size -> put("Content-Length", listOf(size.toString())) }
        }
    }.orEmpty()

    override fun close() {
        reader?.close()
        reader = null
        uri = null
        if (opened) { opened = false; transferEnded() }
    }

    private fun checkWorkerThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("SABR I/O must run on the Media3 loader thread")
    }
    private fun <T> blocking(block: suspend () -> T): T = try { runBlocking(Dispatchers.IO) { block() } }
    catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw java.io.InterruptedIOException("SABR loading interrupted").apply { initCause(e) }
    } catch (e: CancellationException) { throw IOException("SABR loading canceled", e) }
}
