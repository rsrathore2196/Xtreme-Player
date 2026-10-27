package com.example

import android.app.Application
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.datasource.*
import com.example.data.remote.YouTubeAudioStreamService
import com.example.service.YouTubeMediaDataSource
import com.example.service.YouTubeStreamRefreshRequiredException
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class YouTubeRangeLoadingTest {
    private val url = "https://rr.googlevideo.com/videoplayback?itag=139"
    private val payload = ByteArray(10) { it.toByte() }
    private val specs = mutableListOf<DataSpec>()
    @Suppress("UNCHECKED_CAST")
    private val cache get() = YouTubeAudioStreamService::class.java.getDeclaredField("cache")
        .apply { isAccessible = true }.get(null) as LruCache<String, YouTubeAudioStreamService.AudioStream>
    @Before fun setup() {
        cache.evictAll()
        cache.put("abcdefghijk:96000:false", YouTubeAudioStreamService.AudioStream(url, 48, false, "representation",
            System.currentTimeMillis() + 120_000, emptyMap(), "AAC", 139, payload.size.toLong(), "abcdefghijk",
            true, 4, "native", selectionCapBps = 96_000))
    }
    @After fun cleanup() { cache.evictAll() }
    private fun source(maxResponse: Int = Int.MAX_VALUE, unknownTotal: Boolean = false, wrongStart: Boolean = false,
        ignoreRange: Boolean = false): YouTubeMediaDataSource = YouTubeMediaDataSource(RuntimeEnvironment.getApplication(), DataSource.Factory {
        var opened: DataSpec? = null
        var response = emptyMap<String, List<String>>()
        var body: ByteArrayDataSource? = null
        Proxy.newProxyInstance(HttpDataSource::class.java.classLoader, arrayOf(HttpDataSource::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> {
                    val spec = args!![0] as DataSpec
                    specs += spec; opened = spec
                    assertTrue("No request may start beyond the recording", spec.position < payload.size)
                    val start = spec.position.toInt()
                    val requested = spec.length.takeIf { it >= 0 } ?: (payload.size - start).toLong()
                    val count = minOf(payload.size - start, requested.toInt(), maxResponse)
                    response = if (ignoreRange) mapOf("Content-Length" to listOf(payload.size.toString())) else mapOf(
                        "Content-Range" to listOf("bytes ${start + if (wrongStart) 1 else 0}-${start + count - 1}/${if (unknownTotal) "*" else payload.size}"),
                        "Content-Length" to listOf(count.toString()))
                    body = ByteArrayDataSource(payload.copyOfRange(start, start + count))
                    body!!.open(spec.buildUpon().setPosition(0).setLength(count.toLong()).build())
                }
                "getResponseCode" -> if (ignoreRange) 200 else 206
                "getResponseHeaders" -> response
                "getUri" -> opened?.uri
                "read" -> body!!.read(args!![0] as ByteArray, args[1] as Int, args[2] as Int)
                "close" -> { body?.close(); null }
                "addTransferListener", "setRequestProperty", "clearRequestProperty", "clearAllRequestProperties" -> null
                else -> error("Unexpected HTTP call ${method.name}")
            }
        } as HttpDataSource
    })
    private fun readAll(source: DataSource): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    @Test fun overlongRequestAtTheEndOfARecordingStopsAtItsVerifiedLength() {
        val source = source()
        try {
            val opened = source.open(DataSpec.Builder().setUri(url).setPosition(8).setLength(6).build())
            assertEquals(2L, opened)
            assertArrayEquals(payload.copyOfRange(8, 10), readAll(source))
            assertEquals(1, specs.size)
        } finally { source.close() }
    }

    @Test fun cappedValidRangesPreserveEveryByteAcrossChunkBoundaries() {
        val source = source(maxResponse = 2)
        try {
            assertEquals(payload.size.toLong(), source.open(DataSpec(Uri.parse(url))))
            assertArrayEquals(payload, readAll(source))
            assertEquals(listOf(0L, 2L, 4L, 6L, 8L), specs.map { it.position })
        } finally { source.close() }
    }

    @Test fun unknownResponseTotalCanUseTheVerifiedRepresentationLength() {
        val source = source(unknownTotal = true)
        try {
            source.open(DataSpec(Uri.parse(url)))
            assertArrayEquals(payload, readAll(source))
        } finally { source.close() }
    }

    @Test fun ignoredRangesWithAnIntactFullBodyStillReachTheExistingExtractorPath() {
        val source = source(ignoreRange = true)
        try {
            source.open(DataSpec(Uri.parse(url)))
            assertArrayEquals(payload, readAll(source))
            assertEquals(2, specs.size)
        } finally { source.close() }
    }

    @Test fun inconsistentRangesMustStillFailBeforeAnyWrongBytesReachTheDecoder() {
        val source = source(wrongStart = true)
        try {
            assertThrows(YouTubeStreamRefreshRequiredException::class.java) { source.open(DataSpec(Uri.parse(url))) }
            assertEquals(1, specs.size)
            assertNull("An inconsistent representation must not be reused", YouTubeAudioStreamService.cachedForUrl(url))
        } finally { source.close() }
    }

    @Test fun seekingBeyondTheVerifiedFileFailsWithoutMakingACdnRequest() {
        val source = source()
        try {
            assertThrows(DataSourceException::class.java) {
                source.open(DataSpec.Builder().setUri(url).setPosition(11).build())
            }
            assertTrue(specs.isEmpty())
        } finally { source.close() }
    }
}
