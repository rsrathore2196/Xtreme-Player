package com.example

import com.example.data.cache.RecommendationCacheEntry
import com.example.data.cache.persistRecommendationBatch
import kotlinx.coroutines.CancellationException
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
}
