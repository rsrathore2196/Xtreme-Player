package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.model.MusicTrack
import com.example.downloads.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineCollectionMembershipTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: OfflineDownloads
    private val tracks = (1..3).map { MusicTrack("$it", "Song $it", "Artist", "Album", 120_000, "", "https://example.test/$it") }

    @Before fun prepare() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        store = OfflineDownloads(context)
        store.load()
        tracks.forEach { store.put(DownloadRecord(it, DownloadStatus.COMPLETE)) }
    }

    @Test fun collectionKeysKeepSameNamedCollectionsSeparateAndSurviveReloading() = runBlocking {
        store.enqueue(tracks.take(2), "Mix", "playlist:a")
        store.enqueue(tracks.drop(1), "Mix", "playlist:b")
        val restored = OfflineDownloads(context).also { it.load() }
        assertEquals(listOf("1", "2"), restored.collections.value["playlist:a"])
        assertEquals(listOf("2", "3"), restored.collections.value["playlist:b"])
        assertTrue(restored.records.value.values.all { it.status == DownloadStatus.COMPLETE })
    }

    @Test fun removingSavedAudioDoesNotLoseTheFullCollectionMembership() = runBlocking {
        store.enqueue(tracks, "Mix", "playlist:a")
        store.remove("3")
        val restored = OfflineDownloads(context).also { it.load() }
        assertEquals(listOf("1", "2", "3"), restored.collections.value["playlist:a"])
        assertFalse(restored.records.value.containsKey("3"))
        assertFalse(collectionDownloadProgress(tracks.take(1), restored.records.value,
            restored.collections.value["playlist:a"], collectionComplete = false).complete)
    }

    @Test fun olderDownloadLedgersWithoutCollectionMembershipStillLoad() = runBlocking {
        val index = File(context.noBackupFilesDir, "offline_audio/index.json")
        index.writeText(JSONObject(index.readText()).apply { remove("collections") }.toString())
        val restored = OfflineDownloads(context).also { it.load() }
        assertTrue(restored.collections.value.isEmpty())
        assertEquals(tracks.map { it.id }.toSet(), restored.records.value.keys)
    }
}
