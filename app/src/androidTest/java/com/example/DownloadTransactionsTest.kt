package com.example

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.MusicTrack
import com.example.downloads.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class DownloadTransactionsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class MemoryIndex : DownloadIndex {
        var data: ByteArray? = null
        var writes = 0
        var fail = false
        override fun read(): ByteArray? {
            check(Looper.myLooper() != Looper.getMainLooper())
            return data
        }
        override fun write(bytes: ByteArray) {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (fail) throw IOException("Simulated storage failure")
            data = bytes
            writes++
        }
    }
    private fun record(id: String) = DownloadRecord(MusicTrack("transaction-$id", "Song", "Artist", "Album", 180_000,
        "", "https://example.test/audio"), DownloadStatus.PAUSED)

    @Test fun failedWritesPreservePublishedStateAndSavedFiles() = runBlocking {
        val index = MemoryIndex()
        val store = OfflineDownloads(context, index)
        val original = record("failure")
        withContext(Dispatchers.Main) { store.put(original) }
        val marker = withContext(Dispatchers.IO) { File(store.directory(original.track.id), "audio-marker").apply { writeText("saved") } }
        try {
            index.fail = true
            for (operation in listOf<suspend () -> Unit>(
                { store.put(original.copy(bytes = 42)) },
                { store.setPreferences(DownloadPreferences(false, DownloadQuality.STANDARD)) },
                { store.remove(original.track.id) }
            )) {
                try { withContext(Dispatchers.Main) { operation() }; fail("Expected storage failure") }
                catch (_: IOException) { }
                assertEquals(original, store.records.value[original.track.id])
                assertEquals(DownloadPreferences(), store.preferences.value)
                assertTrue(marker.isFile)
            }
            val reopened = OfflineDownloads(context, index)
            reopened.load()
            assertEquals(original, reopened.records.value[original.track.id])
        } finally { index.fail = false; store.remove(original.track.id) }
    }

    @Test fun clearingLargeCollectionUsesOneCommitAndKeepsMembership() = runBlocking {
        val index = MemoryIndex()
        val records = List(500) { record("batch-$it") }
        index.data = JSONObject().put("records", JSONArray(records.map(OfflineJson::record)))
            .put("collections", JSONObject().put("album", JSONArray(records.map { it.track.id }))).toString().toByteArray()
        val store = OfflineDownloads(context, index)
        withContext(Dispatchers.Main) { store.clear() }
        assertEquals(1, index.writes)
        assertTrue(store.records.value.isEmpty())
        assertEquals(records.map { it.track.id }, store.collections.value["album"])
        val reopened = OfflineDownloads(context, index)
        reopened.load()
        assertTrue(reopened.records.value.isEmpty())
    }

    @Test fun deletionWaitsForExistingReaderAndRejectsStaleReaders() = runBlocking(Dispatchers.IO) {
        val store = OfflineDownloads(context, MemoryIndex())
        val original = record("reader").copy(status = DownloadStatus.COMPLETE,
            plan = OfflinePlan("https://example.test/audio", "audio/wav", "one", true))
        store.put(original)
        val marker = File(store.directory(original.track.id), "audio-marker").apply { writeText("saved") }
        assertTrue(store.retainCache(original.track.id, original))
        try {
            store.remove(original.track.id)
            assertTrue(marker.isFile)
            assertFalse(store.retainCache(original.track.id, original))
        } finally { store.releaseOwner(original.track.id) }
        assertFalse(marker.exists())
        assertFalse(store.retainCache(original.track.id, original))
    }

    @Test fun unrelatedByteProgressDoesNotEmitSavedCollectionsOrTrackStatus() = runBlocking {
        val store = OfflineDownloads(context, MemoryIndex())
        val active = record("progress")
        val saved = record("saved").copy(status = DownloadStatus.COMPLETE,
            plan = OfflinePlan("https://example.test/audio", "audio/wav", "one", true))
        store.put(active)
        store.put(saved)
        val savedEvents = CopyOnWriteArrayList<List<DownloadRecord>>()
        val statuses = CopyOnWriteArrayList<DownloadStatus?>()
        val ready = CompletableDeferred<Unit>()
        val collectionJob = launch { store.observeSavedRecords().collect { savedEvents += it; ready.complete(Unit) } }
        val statusJob = launch(start = CoroutineStart.UNDISPATCHED) { store.observeStatus(active.track.id).toList(statuses) }
        try {
            withTimeout(10_000) { ready.await() }
            repeat(30) { bytes -> store.update(active.track.id, persist = false) { it.copy(bytes = bytes.toLong()) } }
            // A completion supplies an ordered barrier through the same observer.
            store.update(active.track.id) { it.copy(status = DownloadStatus.COMPLETE, plan = saved.plan) }
            withTimeout(10_000) { store.observeSavedCount().first { it == 2 } }
            withTimeout(10_000) { while (savedEvents.lastOrNull()?.size != 2) yield() }
            assertEquals(listOf(listOf(saved), listOf(active.copy(bytes = 29, status = DownloadStatus.COMPLETE, plan = saved.plan), saved)
                .sortedWith(compareByDescending<DownloadRecord> { it.addedAt }.thenBy { it.track.id })), savedEvents.toList())
            withTimeout(10_000) { while (statuses.lastOrNull() != DownloadStatus.COMPLETE) yield() }
            assertEquals(listOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETE), statuses.toList())
        } finally { collectionJob.cancelAndJoin(); statusJob.cancelAndJoin(); store.clear() }
    }

    @Test fun preferenceEditsMergeAndChangingNetworkBatchesStopAndRequeue() = runBlocking {
        val index = MemoryIndex()
        val store = OfflineDownloads(context, index)
        store.load()
        try {
            coroutineScope {
                launch { store.updatePreferences { it.copy(wifiOnly = false) } }
                launch { store.updatePreferences { it.copy(quality = DownloadQuality.STANDARD) } }
            }
            assertEquals(DownloadPreferences(false, DownloadQuality.STANDARD), store.preferences.value)
            val active = listOf(record("reconfigure-queued"), record("reconfigure-active"))
            active.forEachIndexed { i, record -> store.put(record.copy(
                status = if (i == 0) DownloadStatus.QUEUED else DownloadStatus.DOWNLOADING, bytes = 512)) }
            val before = index.writes
            store.updatePreferences { it.copy(wifiOnly = true) }
            assertEquals(before + 2, index.writes)
            assertTrue(store.records.value.values.all { it.status == DownloadStatus.QUEUED && it.bytes == 512L })
            assertEquals(DownloadPreferences(true, DownloadQuality.STANDARD), store.preferences.value)
        } finally { store.clear() }
    }
}
