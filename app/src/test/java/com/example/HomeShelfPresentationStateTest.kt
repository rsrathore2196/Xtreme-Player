package com.example

import com.example.data.cache.readBoundedHomeCache
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.ui.viewmodel.*
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class HomeShelfPresentationStateTest {
    private fun shelf(id: String) = ServerHomeShelf(id, id, tracks = listOf(
        MusicTrack(id, id, "Artist", "Album", 180_000, "", "")))
    @Test fun initialSlotsContainOnlyLoadingShapesWithoutDummySongs() {
        val cards = initialHomeSkeletons()
        assertEquals(3, cards.size)
        assertEquals(3, cards.map { it.key }.toSet().size)
        assertTrue(cards.all { it.status == ShelfLoadStatus.LOADING && it.shelf == null })
    }
    @Test fun theFirstNativeShelfPublishesImmediatelyWithoutAMinimumCount() {
        val initial = initialHomeSkeletons()
        val cards = presentNativeShelves(initial, listOf(shelf("one")), ShelfLoadStatus.SUCCESS)
        assertEquals(1, cards.size)
        assertEquals(initial[0].key, cards[0].key)
        assertEquals(ShelfLoadStatus.SUCCESS, cards[0].status)
    }
    @Test fun laterPagesKeepPriorCardInstancesAndScrollKeys() {
        val first = presentNativeShelves(initialHomeSkeletons(), listOf(shelf("one")), ShelfLoadStatus.SUCCESS)
        val expanded = presentNativeShelves(first, listOf(shelf("one"), shelf("two")), ShelfLoadStatus.SUCCESS)
        assertSame(first[0], expanded[0])
        assertEquals(2, expanded.map { it.key }.toSet().size)
    }
    @Test fun refreshReorderingPreservesEachNativeShelfIdentity() {
        val first = presentNativeShelves(initialHomeSkeletons(), listOf(shelf("one"), shelf("two")), ShelfLoadStatus.SUCCESS)
        val refreshed = presentNativeShelves(first, listOf(shelf("two"), shelf("one")), ShelfLoadStatus.SUCCESS)
        assertEquals(first[1].key, refreshed[0].key)
        assertEquals(first[0].key, refreshed[1].key)
    }
    @Test fun failedOrEmptyRequestsRemoveLoadingWithoutCreatingErrorCards() {
        assertTrue(presentNativeShelves(initialHomeSkeletons(), emptyList(), ShelfLoadStatus.ERROR, "Offline").isEmpty())
        assertTrue(presentNativeShelves(initialHomeSkeletons(), emptyList(), ShelfLoadStatus.EMPTY).isEmpty())
    }
    @Test fun aPendingPageRetainsExistingCardsAndHandsItsStableSlotToNewContent() {
        val first = presentNativeShelves(initialHomeSkeletons(), listOf(shelf("one")), ShelfLoadStatus.SUCCESS)
        val loading = presentNativeShelves(first, listOf(shelf("one")), ShelfLoadStatus.LOADING)
        assertSame(first[0], loading[0]); assertEquals(ShelfLoadStatus.LOADING, loading.last().status)
        val finished = presentNativeShelves(loading, listOf(shelf("one"), shelf("two")), ShelfLoadStatus.SUCCESS)
        assertEquals(loading.last().key, finished.last().key)
        val failure = presentNativeShelves(loading, listOf(shelf("one")), ShelfLoadStatus.ERROR)
        assertEquals(first, failure)
    }
    @Test fun threeReadyShelvesAndAnotherLoadingSourceAlwaysHaveUniqueKeys() {
        val initial = initialHomeSkeletons()
        val cards = presentNativeShelves(initial, (1..3).map { shelf("s$it") }, ShelfLoadStatus.LOADING)
        assertEquals(4, cards.size)
        assertEquals(cards.size, cards.map { it.key }.toSet().size)
        assertEquals(initial.map { it.key }, cards.take(3).map { it.key })
        assertEquals("native_pending_page", cards.last().key)
    }
    @Test fun simultaneousSourcesHandOffSlotsWithoutReusingAnAssignedSuccessfulKey() {
        var cards = initialHomeSkeletons()
        for (count in 1..6) {
            val before = cards.filter { it.shelf != null }.associate { it.shelf!!.id to it.key }
            cards = presentNativeShelves(cards, (1..count).map { shelf("s$it") }, ShelfLoadStatus.LOADING)
            assertEquals(cards.size, cards.map { it.key }.distinct().size)
            cards.filter { it.shelf != null }.forEach { card -> before[card.shelf!!.id]?.let { assertEquals(it, card.key) } }
        }
        val successful = cards.filter { it.shelf != null }
        val done = presentNativeShelves(cards, successful.map { it.shelf!! }, ShelfLoadStatus.SUCCESS)
        assertEquals(successful, done)
    }
    @Test fun refreshAndReorderingReserveExistingSuccessKeysBeforeAllocatingLoadingKeys() {
        val old = presentNativeShelves(initialHomeSkeletons(), listOf(shelf("one"), shelf("two"), shelf("three")), ShelfLoadStatus.LOADING)
        val reordered = presentNativeShelves(old, listOf(shelf("new"), shelf("three"), shelf("one"), shelf("two")), ShelfLoadStatus.LOADING)
        assertEquals(reordered.size, reordered.map { it.key }.distinct().size)
        for (card in old.filter { it.shelf != null }) assertEquals(card.key, reordered.first { it.shelf?.id == card.shelf!!.id }.key)
        val failed = presentNativeShelves(reordered, reordered.mapNotNull { it.shelf }, ShelfLoadStatus.ERROR)
        assertEquals(reordered.filter { it.shelf != null }, failed)
    }
    @Test fun aPendingFallbackCannotCollideWithAnExistingSuccessfulFallbackKey() {
        val old = listOf(HomeShelfPresentation("native_pending_page", shelf("one"), ShelfLoadStatus.SUCCESS))
        val cards = presentNativeShelves(old, listOf(shelf("one")), ShelfLoadStatus.LOADING)
        assertEquals("native_pending_page", cards.first().key)
        assertEquals("native_pending_page_1", cards.last().key)
    }
    @Test fun smallCacheReadsUseSmallChunksAndPreserveUnicode() {
        val text = "{\"title\":\"Music 🎵\"}"
        var largestRead = 0
        val stream = object : ByteArrayInputStream(text.toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                return super.read(bytes, offset, length)
            }
        }
        assertEquals(text, readBoundedHomeCache(stream, 8 * 1024 * 1024))
        assertTrue(largestRead <= 8192)
    }
    @Test fun oversizedCacheReadsStopAtTheExistingLimitPlusOneByte() {
        val stream = ByteArrayInputStream(ByteArray(4096) { 65 })
        assertNull(readBoundedHomeCache(stream, 1024))
        assertEquals(4096 - 1025, stream.available())
    }
    @Test fun exactBoundaryAndEmptyCacheTextAreHandledWithoutALargeAllocation() {
        assertEquals("test", readBoundedHomeCache(ByteArrayInputStream("test".toByteArray()), 4))
        assertEquals("", readBoundedHomeCache(ByteArrayInputStream(byteArrayOf()), 4))
    }
}
