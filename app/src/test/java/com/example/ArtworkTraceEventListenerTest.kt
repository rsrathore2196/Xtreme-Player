package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.decode.Decoder
import coil.fetch.Fetcher
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import com.example.ui.util.ArtworkTraceEventListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ArtworkTraceEventListenerTest {
    @Test fun requestCancellationClosesIncompleteFetchAndDecodeWithoutLeavingTraceWorkOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = ImageRequest.Builder(context).data("https://example.test/cover").build()
        val options = Options(context)
        val fetcher = Fetcher { null }
        val decoder = Decoder { null }
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val listener = ArtworkTraceEventListener { name ->
            opened.add(name)
            AutoCloseable { closed.add(name) }
        }
        listener.fetchStart(request, fetcher, options)
        listener.fetchEnd(request, fetcher, options, null)
        listener.decodeStart(request, decoder, options)
        listener.onCancel(request)
        listener.onCancel(request)
        assertEquals(listOf("Artwork fetch", "Artwork decode"), opened)
        assertEquals(opened, closed)
    }

    @Test fun failedFallbackAttemptsCloseTheirOwnSpansAndCanStartAnotherAttempt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = ImageRequest.Builder(context).data("https://example.test/cover").build()
        val options = Options(context)
        val fetcher = Fetcher { null }
        var running = 0
        val listener = ArtworkTraceEventListener {
            running++
            AutoCloseable { running-- }
        }
        repeat(2) {
            listener.fetchStart(request, fetcher, options)
            assertEquals(1, running)
            listener.onError(request, ErrorResult(null, request, java.io.IOException("Fixture failure")))
            assertEquals(0, running)
        }
    }
}
