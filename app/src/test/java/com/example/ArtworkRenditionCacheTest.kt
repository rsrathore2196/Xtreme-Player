package com.example

import android.app.Application
import com.example.data.cache.RecommendationCacheEntry
import com.example.ui.util.ArtworkRenditionCache
import com.example.ui.util.ArtworkRequestMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtworkRenditionCacheTest {
    @Test fun aNewLoaderRestoresTheSameChoiceWithoutRepeatedReadsOrWrites() = runTest {
        val entries = mutableMapOf<String, RecommendationCacheEntry>()
        var reads = 0; var writes = 0
        fun cache() = ArtworkRenditionCache({ reads++; entries[it] }, { key, url, ttl ->
            writes++; entries[key] = RecommendationCacheEntry(key, url, 100L, 100L + ttl)
        }, now = { 100L })
        val first = cache()
        first.remember("key", "https://covers.example/resolved.jpg")
        val restored = cache()
        repeat(4) {
            assertEquals("https://covers.example/resolved.jpg", restored.get("key"))
            restored.remember("key", "https://covers.example/resolved.jpg")
        }
        assertEquals(1, reads); assertEquals(1, writes)
    }

    @Test fun expiredChoicesAndMissesDoNotKeepReadingTheDatabase() = runTest {
        var now = 100L; var reads = 0
        val cache = ArtworkRenditionCache({ key -> reads++; RecommendationCacheEntry(key, "expired", 0L, 99L) },
            { _, _, _ -> }, now = { now })
        repeat(4) { assertNull(cache.get("key")) }
        assertEquals(1, reads)
        now += 60_001L
        assertNull(cache.get("key")); assertEquals(2, reads)
        cache.remember("key", "new")
        assertEquals("new", cache.peek("key"))
    }

    @Test fun concurrentReadersShareOneLookupAndCancellationDoesNotPoisonTheChoice() = runTest {
        var reads = 0
        val release = CompletableDeferred<Unit>()
        val cache = ArtworkRenditionCache({ key -> reads++; release.await(); RecommendationCacheEntry(key, "url", 0, Long.MAX_VALUE) },
            { _, _, _ -> })
        val jobs = List(5) { async { cache.get("key") } }
        release.complete(Unit)
        assertEquals(List(5) { "url" }, jobs.awaitAll()); assertEquals(1, reads)
        var fail = true
        val retry = ArtworkRenditionCache({ key ->
            if (fail) throw CancellationException("Hidden")
            RecommendationCacheEntry(key, "recovered", 0, Long.MAX_VALUE)
        }, { _, _, _ -> })
        assertTrue(async { try { retry.get("key"); false } catch (_: CancellationException) { true } }.await())
        fail = false
        assertEquals("recovered", retry.get("key"))
    }

    @Test fun recordingSignedSourceViewportAndCropPolicyHaveSeparateKeys() {
        val metadata = ArtworkRequestMetadata("https://covers.example/a.jpg?signature=first", 600, trackId = "yt_abcdefghijk")
        val key = ArtworkRenditionCache.key(metadata, 300, 300)
        assertEquals(key, ArtworkRenditionCache.key(metadata.copy(visibleContent = false), 300, 300))
        for (other in listOf(metadata.copy(trackId = "yt_lmnopqrstuv"),
            metadata.copy(providerUrl = "https://covers.example/a.jpg?signature=second"),
            metadata.copy(requireSquareSongArtwork = true), metadata.copy(preserveAspectRatio = true),
            metadata.copy(landscapeArtwork = true), metadata.copy(allowVideoFallback = false))) {
            assertNotEquals(key, ArtworkRenditionCache.key(other, 300, 300))
        }
        assertNotEquals(key, ArtworkRenditionCache.key(metadata, 600, 600))
    }

    @Test fun anOlderDiskLookupCannotReplaceANewlyResolvedChoice() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cache = ArtworkRenditionCache({ started.complete(Unit); release.await(); null }, { _, _, _ -> })
        val lookup = async { cache.get("key") }
        started.await()
        cache.remember("key", "new")
        release.complete(Unit)
        assertEquals("new", lookup.await())
        assertEquals("new", cache.peek("key"))
    }
}
