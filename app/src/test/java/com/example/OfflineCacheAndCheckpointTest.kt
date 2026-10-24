package com.example

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.YouTubeAudioStreamService
import com.example.service.MusicCache
import com.example.service.PlaybackSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineCacheAndCheckpointTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val video = "abcdefghijk"
    private fun closeCache() {
        val cache = MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }
        (cache.get(null) as? androidx.media3.datasource.cache.SimpleCache)?.release()
        cache.set(null, null)
    }
    private fun clearExtractionMemory() {
        (YouTubeAudioStreamService::class.java.getDeclaredField("cache").apply { isAccessible = true }
            .get(null) as android.util.LruCache<*, *>).evictAll()
    }
    @Before fun setup() { closeCache(); context.cacheDir.resolve("xtreme_audio_cache").deleteRecursively(); clearExtractionMemory() }
    @After fun cleanup() { closeCache(); clearExtractionMemory() }

    private fun save(itag: Int, rate: Int, complete: Boolean = true, sabr: Boolean = false) {
        val cache = MusicCache.getSimpleCache(context)
        val key = "youtube:$video:$itag"
        val stream = YouTubeAudioStreamService.AudioStream("https://video.example/audio?token=secret", rate, false,
            key, 0L, mapOf("Cookie" to "private"), if (itag == 140) "AAC" else "Opus", itag,
            128L, video, false, 0L, "", cachedSabr = sabr)
        MusicCache.rememberRepresentation(context, stream)
        val span = cache.startReadWrite(key, 0L, 128L)
        try {
            val file = cache.startFile(key, 0L, 128L)
            file.writeBytes(ByteArray(if (complete) 128 else 64) { 42 })
            cache.commitFile(file, file.length())
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set("exo_len", 128L))
        } finally { cache.releaseHoleSpan(span) }
        val descriptor = cache.getContentMetadata(key).get("xtreme_audio_representation_v1", "")!!
        assertFalse(descriptor.contains("secret")); assertFalse(descriptor.contains("Cookie"))
        assertFalse(descriptor.contains("https://"))
    }

    @Test fun offlineSelectionSurvivesClosingTheCacheAndClearingAllExtractionMemory() = runBlocking(Dispatchers.IO) {
        save(140, 160); save(249, 48)
        closeCache(); clearExtractionMemory()
        val low = MusicCache.offlineAudio(context, video, 96_000, sabr = false)!!
        assertEquals(249, low.itag)
        assertEquals(48, low.bitrateKbps)
        assertTrue(low.headers.isEmpty())
        assertEquals(140, MusicCache.offlineAudio(context, video, 320_000, sabr = false)!!.itag)
        assertNull(MusicCache.offlineAudio(context, video, 32_000, sabr = false))
    }

    @Test fun incompleteBytesAndWrongTransportNeverBecomeOfflinePlayable() = runBlocking(Dispatchers.IO) {
        save(249, 48, complete = false)
        save(251, 160, sabr = true)
        closeCache(); clearExtractionMemory()
        assertNull(MusicCache.offlineAudio(context, video, 96_000, sabr = false))
        val sabr = MusicCache.offlineAudio(context, video, 320_000, sabr = true)!!
        assertTrue(sabr.isSabr)
        assertNull(sabr.sabrBootstrap)
        assertTrue(MusicCache.fullyCached(context, sabr.cacheKey, sabr.contentLength))
    }

    @Test fun offlineCacheMissesStillAllowLocalFilesButNeverStartHttp() = runBlocking<Unit>(Dispatchers.IO) {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        org.robolectric.Shadows.shadowOf(connectivity).setActiveNetworkInfo(null)
        assertFalse(com.example.playback.NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot().online)
        val file = context.filesDir.resolve("offline-local-audio").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val factory = MusicCache.createCacheDataSourceFactory(context, connectTimeoutMs = 100, readTimeoutMs = 100)
        val source = factory.createDataSource()
        try {
            source.open(androidx.media3.datasource.DataSpec(android.net.Uri.fromFile(file)))
            val bytes = ByteArray(4)
            assertEquals(4, source.read(bytes, 0, bytes.size))
            assertArrayEquals(file.readBytes(), bytes)
        } finally { source.close(); file.delete() }
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 100
            val remote = factory.createDataSource()
            try {
                assertThrows(java.io.IOException::class.java) {
                    remote.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("http://127.0.0.1:${server.localPort}/audio")))
                }
                assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().close() }
            } finally { remote.close() }
        }
    }

    @Test fun positionCheckpointsReuseEncodedQueueAndMetadataChangesInvalidateIt() {
        val items = List(1000) { index -> MediaItem.Builder().setMediaId("track-$index")
            .setUri("https://audio.example/$index").setMediaMetadata(MediaMetadata.Builder().setTitle("Title $index").build()).build() }
        val first = PlaybackSessionStore.encodedQueue(items)
        repeat(20) { assertSame(first, PlaybackSessionStore.encodedQueue(items)) }
        assertSame(first, PlaybackSessionStore.encodedQueue(items.toList()))
        val modified = items.toMutableList().apply { this[500] = this[500].buildUpon()
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Updated").build()).build() }
        val changed = PlaybackSessionStore.encodedQueue(modified)
        assertNotSame(first, changed)
        assertEquals("Updated", JSONArray(String(changed)).getJSONObject(500).getString("title"))
    }
}
