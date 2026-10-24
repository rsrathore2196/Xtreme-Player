package com.example

import android.app.Application
import com.example.data.importer.PlaylistImportEngine
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportResponseLimitsTest {
    @Test fun cancellationBeforeIoAdmissionStillClosesTheResponse() = runBlocking {
        var closed = false
        val stream = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = 2L
            override fun source() = stream
        }
        val response = Response.Builder().request(Request.Builder().url("https://playlist.example").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            PlaylistImportEngine.readImportBody(response)
        }.join()
        assertTrue("Cancellation while entering IO must release the supplied response", closed)
    }

    @Test fun bothDeclaredAndChunkedOversizedBodiesAreRejectedAndClosed() = runBlocking {
        for (declared in listOf(-1L, 9L * 1024 * 1024)) {
            var closed = false
            val buffer = Buffer().write(ByteArray(8 * 1024 * 1024 + 1))
            val stream = object : ForwardingSource(buffer) {
                override fun close() { closed = true; super.close() }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = declared
                override fun source() = stream
            }
            val response = Response.Builder().request(Request.Builder().url("https://playlist.example").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            assertTrue(runCatching { PlaylistImportEngine.readImportBody(response) }.isFailure)
            assertTrue("An oversized response cannot retain its connection", closed)
        }
    }
}
