package com.example

import com.example.data.repository.CatalogBrowseContinuation
import com.example.data.repository.CatalogPage
import com.example.playback.CollectionPlaybackContinuation
import com.example.ui.viewmodel.collectionPlaybackPages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CollectionBrowseContinuationTest {
    @Test fun retriesKeepTheSameOpaqueCursorUntilTheQueueAcceptsThePage() = runBlocking {
        val first = CatalogBrowseContinuation("VLcollection", "category-params", "first-token", page = 2)
        val second = first.copy(token = "second-token", page = 3)
        val calls = mutableListOf<Pair<Int, CatalogBrowseContinuation?>>()
        val loader = collectionPlaybackPages(2, first) { number, cursor ->
            calls += number to cursor
            if (number == 2) CatalogPage(nextPage = 3, browseContinuation = second) else CatalogPage()
        }
        val result = loader.fetch(loader.page)
        // A discarded/cancelled native queue mutation retries the exact original page.
        loader.fetch(loader.page)
        assertEquals(listOf(2 to first, 2 to first), calls)
        assertEquals(2, loader.page)
        val committed = loader.after(result)!!
        val last = committed.fetch(committed.page)
        assertEquals(3 to second, calls.last())
        assertNull(committed.after(last))
    }

    @Test fun numericPlaylistPaginationKeepsItsExistingBehavior() = runBlocking {
        val pages = mutableListOf<Int>()
        val loader = CollectionPlaybackContinuation(2) { page -> pages += page; CatalogPage(nextPage = page + 1) }
        val next = loader.after(loader.fetch(loader.page))!!
        assertEquals(3, next.page)
        next.fetch(next.page)
        assertEquals(listOf(2, 3), pages)
    }

    @Test fun repeatedOrBackwardPageMarkersCannotCreateAPaginationLoop() {
        val loader = CollectionPlaybackContinuation(2) { CatalogPage() }
        assertNull(loader.after(CatalogPage(nextPage = 2)))
        assertNull(loader.after(CatalogPage(nextPage = 1)))
        assertNull(loader.after(CatalogPage()))
    }
}
