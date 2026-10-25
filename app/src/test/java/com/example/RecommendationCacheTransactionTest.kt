package com.example

import com.example.data.cache.RecommendationCacheEntry
import com.example.data.cache.persistRecommendationBatch
import com.example.data.cache.persistRecommendationCheckpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecommendationCacheTransactionTest {
    private fun entry(version: Long) = RecommendationCacheEntry("recording", "[]", version, version + 1000)

    @Test fun failedAndCancelledTransactionsRetainTheirPendingEntries() = runTest {
        val original = entry(1)
        val pending = linkedMapOf(original.cacheKey to original)
        for (error in listOf(java.io.IOException("Disk full"), CancellationException("Stopped"))) {
            try { persistRecommendationBatch(pending) { throw error }; fail("Expected failure") }
            catch (caught: Exception) { assertSame(error, caught) }
            assertSame(original, pending[original.cacheKey])
        }
        assertTrue(persistRecommendationBatch(pending) { assertEquals(listOf(original), it) })
        assertTrue(pending.isEmpty())
    }

    @Test fun acknowledgingAnOlderTransactionDoesNotRemoveANewerMatch() = runTest {
        val old = entry(1)
        val newer = entry(2)
        val pending = linkedMapOf(old.cacheKey to old)
        persistRecommendationBatch(pending) { synchronized(pending) { pending[newer.cacheKey] = newer } }
        assertSame(newer, pending[newer.cacheKey])
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun checkpointCannotBeOverwrittenByAnOlderBatchAlreadyCommitting() = runTest {
        val old = entry(1); val newer = entry(2)
        val pending = linkedMapOf(old.cacheKey to old)
        val persistence = Mutex()
        val started = CompletableDeferred<Unit>()
        val finishOld = CompletableDeferred<Unit>()
        var stored: RecommendationCacheEntry? = null
        val batch = async { persistRecommendationBatch(pending, persistence) {
            started.complete(Unit); finishOld.await(); stored = it.single()
        } }
        started.await()
        pending[newer.cacheKey] = newer
        val checkpoint = async { persistRecommendationCheckpoint(pending, newer, persistence) { stored = it.single() } }
        runCurrent()
        assertFalse(checkpoint.isCompleted)
        finishOld.complete(Unit)
        assertTrue(batch.await())
        assertTrue(checkpoint.await())
        assertSame(newer, stored)
        assertTrue(pending.isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun obsoleteCheckpointIsSkippedAfterWaitingForThePersistenceLane() = runTest {
        val old = entry(1); val newer = entry(2)
        val pending = linkedMapOf(old.cacheKey to old)
        val persistence = Mutex(locked = true)
        var commits = 0
        val checkpoint = async { persistRecommendationCheckpoint(pending, old, persistence) { commits++ } }
        runCurrent()
        pending[newer.cacheKey] = newer
        persistence.unlock()
        assertFalse(checkpoint.await())
        assertEquals(0, commits)
        assertSame(newer, pending[newer.cacheKey])
    }
}
