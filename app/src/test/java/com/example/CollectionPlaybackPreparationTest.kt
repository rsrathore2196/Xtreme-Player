package com.example

import com.example.data.model.MusicTrack
import com.example.ui.util.CollectionPlaybackPreparation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectionPlaybackPreparationTest {
    private val tracks = List(2000) { MusicTrack("$it", "Song", "Artist", "Album", 180_000, "", "") }
    @Test fun shufflePreservesAllTracksAndRunsThroughTheSuppliedDispatcher() = runTest {
        val played = mutableListOf<Pair<MusicTrack, List<MusicTrack>>>()
        val preparation = CollectionPlaybackPreparation(this, StandardTestDispatcher(testScheduler)) { track, queue -> played += track to queue }
        preparation.shuffle(tracks)
        assertTrue(played.isEmpty())
        advanceUntilIdle()
        val (selected, queue) = played.single()
        assertEquals(tracks.toSet(), queue.toSet())
        assertEquals(tracks.size, queue.size)
        assertEquals(queue.first(), selected)
    }
    @Test fun newSelectionAndLeavingThePageCancelPendingShuffle() = runTest {
        val played = mutableListOf<String>()
        val preparation = CollectionPlaybackPreparation(this, StandardTestDispatcher(testScheduler)) { track, _ -> played += track.id }
        preparation.shuffle(tracks)
        preparation.play(tracks[17], tracks)
        advanceUntilIdle()
        assertEquals(listOf("17"), played)
        preparation.shuffle(tracks)
        preparation.cancel()
        advanceUntilIdle()
        assertEquals(listOf("17"), played)
        preparation.shuffle(emptyList())
        advanceUntilIdle()
        assertEquals(listOf("17"), played)
    }
}
