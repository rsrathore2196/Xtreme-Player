package com.example

import android.net.Uri
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import androidx.media3.database.StandaloneDatabaseProvider

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineDownloadsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store by lazy { OfflineDownloads(context) }
    private val ids = mutableSetOf<String>()
    private fun track(id: String) = MusicTrack("download-test-$id", "गीत · $id", "Artist", "Album", 500,
        "https://example.test/cover.png", "https://example.test/expired/audio.wav", bitrateKbps = 128,
        primaryArtists = listOf("Artist"), originalProviderTitle = "Original title", isrc = "INAAA1234567")
        .also { ids += it.id }
    @After fun cleanup() = runBlocking(Dispatchers.IO) { ids.forEach { store.remove(it) } }

    @Test fun metadataRoundTripsWithoutLosingRecordingIdentity() {
        val selected = track("metadata")
        val record = DownloadRecord(selected, DownloadStatus.PAUSED, 123, 456,
            plan = OfflinePlan(selected.audioUrl, "audio/wav", "representation", true), collection = "My playlist")
        assertEquals(record, OfflineJson.record(OfflineJson.record(record)))
    }
    @Test fun savedAudioSurvivesReopenAndIsReadWithoutAnyNetworkSource() = runBlocking(Dispatchers.IO) {
        val selected = track("persisted")
        val bytes = wave()
        val plan = OfflinePlan(selected.audioUrl, "audio/wav", "wave", true)
        val size = OfflineTransfer.save(store, selected.id, plan, DataSource.Factory { ByteArrayDataSource(bytes) })
        store.put(DownloadRecord(selected, DownloadStatus.COMPLETE, size, size, plan = plan))
        val reopened = OfflineDownloads(context)
        try {
            val local = checkNotNull(reopened.localTrack(selected))
            assertEquals("xtreme-offline", Uri.parse(local.audioUrl).scheme)
            assertTrue(local.isCached)
            assertArrayEquals(bytes, read(reopened, selected.id, plan, plan.uri))
            assertEquals(selected.id, reopened.records.value.keys.single { it == selected.id })
        } finally { reopened.releaseCache(selected.id) }
    }
    @Test fun actualPlayerPreparesOfflineAudioAndKeepsItsOfflineIdentity() = runBlocking(Dispatchers.IO) {
        val selected = track("player")
        val plan = OfflinePlan(selected.audioUrl, "audio/wav", "wave", true)
        val size = OfflineTransfer.save(store, selected.id, plan, DataSource.Factory { ByteArrayDataSource(wave()) })
        store.put(DownloadRecord(selected, DownloadStatus.COMPLETE, size, size, plan = plan))
        val local = checkNotNull(store.localTrack(selected))
        val ready = CompletableDeferred<Unit>()
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY) ready.complete(Unit) }
                    override fun onPlayerError(error: PlaybackException) { ready.completeExceptionally(error) }
                })
                setMediaSource(store.source(MediaItem.Builder().setMediaId(local.id).setUri(local.audioUrl).build()))
                prepare()
            }
        }
        try {
            withTimeout(120_000) { ready.await() }
            instrumentation.runOnMainSync { assertEquals("xtreme-offline", player!!.currentMediaItem!!.localConfiguration!!.uri.scheme) }
        } finally { instrumentation.runOnMainSync { player?.release() } }
    }
    @Test fun vodDownloadIncludesManifestSegmentsAndEncryptionKeys() = runBlocking(Dispatchers.IO) {
        val selected = track("hls")
        val manifest = "https://example.test/list.m3u8?expired=1"
        val key = ByteArray(16) { it.toByte() }
        val segment = ByteArray(1024) { (it % 255).toByte() }
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:1.0,\nsegment.aac\n#EXT-X-ENDLIST\n".toByteArray()
        val data = mapOf(manifest to playlist, "https://example.test/key.bin" to key, "https://example.test/segment.aac" to segment)
        val calls = AtomicInteger()
        val upstream = DataSource.Factory { routingDataSource { uri -> calls.incrementAndGet(); data[uri] ?: throw IOException("Unexpected request") } }
        val plan = OfflinePlan(manifest, MimeTypes.APPLICATION_M3U8, "vod", false)
        OfflineTransfer.save(store, selected.id, plan, upstream)
        val fetched = calls.get()
        assertTrue(fetched >= 3)
        assertArrayEquals(key, read(store, selected.id, plan, "https://example.test/key.bin"))
        assertArrayEquals(segment, read(store, selected.id, plan, "https://example.test/segment.aac"))
        assertArrayEquals(playlist, read(store, selected.id, plan, manifest))
        assertEquals(fetched, calls.get())
    }
    @Test fun partialDownloadCanResumeWithoutAppendingDifferentBytes() = runBlocking(Dispatchers.IO) {
        val selected = track("resume")
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val plan = OfflinePlan(selected.audioUrl, "audio/wav", "one-encode", true)
        val positions = mutableListOf<Long>()
        var interruptTransfer = true
        val upstream = DataSource.Factory {
            val delegate = ByteArrayDataSource(bytes)
            object : DataSource by delegate {
                var delivered = 0
                override fun open(dataSpec: DataSpec): Long { positions += dataSpec.position; return delegate.open(dataSpec) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (interruptTransfer && delivered >= 128 * 1024) throw IOException("Connection interrupted")
                    return delegate.read(buffer, offset, minOf(length, 8192)).also { if (it > 0) delivered += it }
                }
            }
        }
        try { OfflineTransfer.save(store, selected.id, plan, upstream); fail("Expected an interrupted transfer") } catch (_: IOException) { }
        interruptTransfer = false
        OfflineTransfer.save(store, selected.id, plan, upstream)
        assertTrue(positions.any { it > 0 })
        assertArrayEquals(bytes, read(store, selected.id, plan, plan.uri))
    }
    @Test fun missingDownloadIsNotReportedAsPlayable() = runBlocking(Dispatchers.IO) {
        val selected = track("missing")
        store.put(DownloadRecord(selected, DownloadStatus.COMPLETE, plan = OfflinePlan(selected.audioUrl, "audio/wav", "missing", true)))
        assertNull(store.localTrack(selected))
        assertEquals(DownloadStatus.FAILED, store.records.value[selected.id]?.status)
    }
    @Test fun downloadedQueueKeepsEveryItemOfflineAndSkipsUnfinishedTracks() = runBlocking(Dispatchers.IO) {
        val one = track("queue-one")
        val two = track("queue-two")
        val pending = track("queue-pending")
        val plan = OfflinePlan(one.audioUrl, "audio/wav", "wave", true)
        store.put(DownloadRecord(one, DownloadStatus.COMPLETE, plan = plan))
        store.put(DownloadRecord(two, DownloadStatus.COMPLETE, plan = plan.copy(uri = two.audioUrl)))
        store.put(DownloadRecord(pending, DownloadStatus.PAUSED))
        val queue = store.localQueue(listOf(two, pending, one))
        assertEquals(listOf(two.id, one.id), queue.map { it.id })
        assertTrue(queue.all { Uri.parse(it.audioUrl).scheme == "xtreme-offline" })
    }
    @Test fun dashDownloadIncludesManifestInitializationAndEveryAudioSegment() = runBlocking(Dispatchers.IO) {
        val selected = track("dash")
        val manifest = "https://example.test/album.mpd"
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT2S" minBufferTime="PT1S"><Period duration="PT2S"><AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2"><Representation id="audio" bandwidth="128000" audioSamplingRate="48000"><SegmentList timescale="1" duration="1"><Initialization sourceURL="init.mp4"/><SegmentURL media="one.m4s"/><SegmentURL media="two.m4s"/></SegmentList></Representation></AdaptationSet></Period></MPD>""".toByteArray()
        val init = byteArrayOf(1, 2, 3); val one = byteArrayOf(4, 5); val two = byteArrayOf(6, 7)
        val data = mapOf(manifest to xml, "https://example.test/init.mp4" to init,
            "https://example.test/one.m4s" to one, "https://example.test/two.m4s" to two)
        val plan = OfflinePlan(manifest, MimeTypes.APPLICATION_MPD, "dash", false)
        OfflineTransfer.save(store, selected.id, plan, DataSource.Factory { routingDataSource { data[it] ?: throw IOException("Unexpected segment") } })
        assertArrayEquals(xml, read(store, selected.id, plan, manifest))
        assertArrayEquals(init, read(store, selected.id, plan, "https://example.test/init.mp4"))
        assertArrayEquals(one, read(store, selected.id, plan, "https://example.test/one.m4s"))
        assertArrayEquals(two, read(store, selected.id, plan, "https://example.test/two.m4s"))
    }
    @Test fun offlineAudioIsNotRejectedByStreamingQualityOrProviderSettings() {
        val local = track("policy").copy(audioUrl = "xtreme-offline://audio/hash", bitrateKbps = 5000, isLossless = true)
        AudioQualityCeiling.entries.forEach { ceiling -> assertTrue(PlaybackSourcePolicy(ceiling, online = false, sources = emptyList()).acceptsRemoteTrack(local)) }
    }
    @Test fun removingDownloadAlsoRemovesItsPersistentMediaIndex() = runBlocking(Dispatchers.IO) {
        val selected = track("delete-index")
        val plan = OfflinePlan(selected.audioUrl, "audio/wav", "wave", true)
        val size = OfflineTransfer.save(store, selected.id, plan, DataSource.Factory { ByteArrayDataSource(wave()) })
        store.put(DownloadRecord(selected, DownloadStatus.COMPLETE, size, size, plan = plan))
        val uid = store.cache(selected.id).uid
        store.releaseCache(selected.id)
        val provider = StandaloneDatabaseProvider(context)
        fun indexCount(): Int = provider.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE name = ?", arrayOf("ExoPlayerCacheIndex${java.lang.Long.toHexString(uid)}"))
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        try {
            assertEquals(1, indexCount())
            store.remove(selected.id)
            assertEquals(0, indexCount())
            assertFalse(store.records.value.containsKey(selected.id))
        } finally { provider.close() }
    }
    private fun read(store: OfflineDownloads, id: String, plan: OfflinePlan, uri: String): ByteArray {
        val source = store.dataSource(id, plan.progressive).createDataSource()
        try {
            source.open(DataSpec(Uri.parse(uri)))
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; bytes.write(buffer, 0, count) }
            return bytes.toByteArray()
        } finally { source.close(); store.releaseCache(id) }
    }
    private fun routingDataSource(bytes: (String) -> ByteArray): DataSource = object : DataSource {
        private var source: ByteArrayDataSource? = null
        override fun open(spec: DataSpec): Long { source = ByteArrayDataSource(bytes(spec.uri.toString())); return source!!.open(spec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = source!!.read(buffer, offset, length)
        override fun addTransferListener(listener: TransferListener) { }
        override fun getUri(): Uri? = source?.uri
        override fun close() { source?.close(); source = null }
    }
    private fun wave(): ByteArray {
        val samples = 4000
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort(0) }
        return buffer.array()
    }
}
