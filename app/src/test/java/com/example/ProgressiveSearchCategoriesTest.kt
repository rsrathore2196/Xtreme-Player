package com.example

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogPage
import com.example.data.repository.loadSearchCategories
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProgressiveSearchCategoriesTest {
    private val track = MusicTrack("online_one", "Song", "Artist", "Album", 180_000, "cover", "audio")
    @Test fun availableSongsPublishBeforeSlowSiblingCategoriesFinish() = runTest {
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<CatalogPage>()
        val result = async {
            loadSearchCategories({ kind ->
                if (kind == "Tracks") CatalogPage(tracks = listOf(track))
                else { release.await(); CatalogPage() }
            }, { if (it.tracks.isNotEmpty()) published.complete(it) })
        }
        assertEquals(listOf(track), published.await().tracks)
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(listOf(track), result.await().tracks)
    }

    @Test fun categoryFailuresAndTimeoutsPreserveSuccessfulResultsAndSettle() = runTest {
        val result = loadSearchCategories({ kind -> when (kind) {
            "Tracks" -> CatalogPage(tracks = listOf(track))
            "Albums" -> withTimeout(10) { awaitCancellation() }
            else -> throw java.io.IOException("Provider unavailable")
        } }, null)
        assertEquals(listOf(track), result.tracks)
        assertNotNull(result.warning)
    }

    @Test fun obsoleteSearchCancelsSiblingRequestsWithoutLatePublication() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = 0
        var publications = 0
        val result = launch {
            loadSearchCategories({ started.complete(Unit)
                try { awaitCancellation() } finally { cancelled++ }
            }, { publications++ })
        }
        started.await()
        result.cancelAndJoin()
        assertEquals(0, publications)
        assertTrue(cancelled > 0)
    }
}
