package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.*
import com.example.data.model.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCachePersistenceTest {
    private data class Write(val value: String)
    private fun shelf(id: String) = ServerHomeShelf(id, id, tracks = listOf(MusicTrack("yt_abcdefghijk", id, "Singer", "Album", 180_000, "", "")))
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun oneTransientFailureRetriesWithoutAnotherSaveOrTimer() = runBlocking {
        val pending = linkedMapOf("home" to Write("latest")); var attempts = 0
        persistHomeCheckpoints(pending, { if (++attempts == 1) throw IOException("Transient") })
        assertEquals(2, attempts); assertTrue(pending.isEmpty())
    }
    @Test fun persistentFailureRemainsBoundedAndCannotBlockOtherFiles() = runBlocking {
        val pending = linkedMapOf("home" to Write("unavailable"), "profile" to Write("ready"))
        val attempts = mutableListOf<String>()
        persistHomeCheckpoints(pending, { attempts += it.value; if (it.value == "unavailable") throw IOException() })
        assertEquals(listOf("unavailable", "unavailable", "ready"), attempts)
        assertEquals(setOf("home"), pending.keys)
        persistHomeCheckpoints(pending, {})
        assertTrue(pending.isEmpty())
    }
    @Test fun anOlderCommitCannotAcknowledgeANewerQueuedCheckpoint() = runBlocking {
        val old = Write("old"); val newest = Write("new")
        val pending = linkedMapOf("home" to old)
        persistHomeCheckpoints(pending, { pending["home"] = newest })
        assertSame(newest, pending["home"])
        persistHomeCheckpoints(pending, { assertSame(newest, it) })
        assertTrue(pending.isEmpty())
    }
    @Test fun cancellationLeavesTheCheckpointPendingAndDoesNotRetry() = runBlocking {
        val pending = linkedMapOf("home" to Write("latest")); var attempts = 0
        try { persistHomeCheckpoints(pending, { attempts++; throw CancellationException() }); fail("Must cancel") }
        catch (_: CancellationException) { }
        assertEquals(1, attempts); assertEquals(1, pending.size)
    }
    @Test fun identicalSnapshotsReuseTheirSerializedMemoryEntry() = runBlocking {
        val cache = HomeCardCache(app)
        val saved = SavedHomeCards("IN|anonymous", 1, serverShelves = listOf(shelf("native")), serverFetchSucceeded = true)
        cache.saveHome(saved)
        val memory = HomeCardCache::class.java.getDeclaredField("memory").apply { isAccessible = true }.get(null) as android.util.LruCache<*, *>
        val before = memory.snapshot().values.last()
        cache.saveHome(saved)
        assertSame(before, memory.snapshot().values.last())
    }
    @Test fun overlappingSerializationAndDiskWritesRetainTheLatestCheckpoint() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = object : AbstractList<ServerHomeShelf>() {
            override val size = 1
            override fun get(index: Int): ServerHomeShelf {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return shelf("old")
            }
        }
        val cache = HomeCardCache(app)
        val old = async(Dispatchers.IO) { cache.saveHome(SavedHomeCards("IN|anonymous", 1, serverShelves = slow, serverFetchSucceeded = true)) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        val newest = async(Dispatchers.IO) { cache.saveHome(SavedHomeCards("IN|anonymous", 2, serverShelves = listOf(shelf("new")), serverFetchSucceeded = true)) }
        release.countDown(); old.await(); newest.await()
        assertEquals("new", cache.read("IN|anonymous")!!.serverShelves.single().id)
        val file = File(app.filesDir, "home_server_${HomeCacheIdentity.fileKey("IN|anonymous")}_v3.json")
        withTimeout(5_000) { while (runCatching { JSONObject(file.readText()).optLong("refreshedAt") }.getOrNull() != 2L) delay(10) }
        assertEquals("new", JSONObject(file.readText()).getJSONArray("serverShelves").getJSONObject(0).getString("id"))
    }
    @Test fun anOldAccountCacheWithoutResponseIdentityCannotBeRestoredAsPersonalized() = runBlocking {
        val key = "IN|account-a"
        File(app.filesDir, "home_server_${HomeCacheIdentity.fileKey(key)}_v3.json").writeText(
            """{"version":3,"key":"$key","success":true,"serverShelves":[{"id":"unverified","title":"Unverified","tracks":[]}]}""")
        val cache = HomeCardCache(app)
        assertTrue(cache.read(key)?.serverShelves.isNullOrEmpty())
        assertTrue(cache.peek(key)?.serverShelves.isNullOrEmpty())
    }
    @Test fun aWriteThatFailsBothImmediateAttemptsRecoversWithoutAnotherReadOrSave() = runBlocking {
        val key = "CA|anonymous"
        val target = File(app.filesDir, "home_server_${HomeCacheIdentity.fileKey(key)}_v3.json")
        target.delete(); assertTrue(target.mkdirs()) // A transiently unavailable destination.
        File(target, "busy").writeText("temporary")
        val cache = HomeCardCache(app)
        val previous = org.robolectric.shadows.ShadowLog.getLogsForTag("HomeCardCache").size
        cache.saveHome(SavedHomeCards(key, 99, serverShelves = listOf(shelf("recovered")), serverFetchSucceeded = true))
        withTimeout(5_000) {
            while (org.robolectric.shadows.ShadowLog.getLogsForTag("HomeCardCache").size == previous) delay(5)
        }
        assertTrue(target.deleteRecursively())
        // Inspect disk directly: readJson() itself must not be what re-enqueues the write.
        withTimeout(5_000) {
            while (withContext(Dispatchers.IO) { runCatching { JSONObject(target.readText()).optLong("refreshedAt") }.getOrNull() } != 99L) delay(10)
        }
        assertEquals("recovered", JSONObject(target.readText()).getJSONArray("serverShelves").getJSONObject(0).getString("id"))
    }

}
