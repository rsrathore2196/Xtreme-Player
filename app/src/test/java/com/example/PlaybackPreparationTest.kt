package com.example

import com.example.playback.resolveWhileConnecting
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPreparationTest {
    @Test fun controllerConnectionAndStreamLookupRunTogether() = runTest {
        var connected = false
        var lookedUp = false
        val stream = resolveWhileConnecting(connect = { delay(500); connected = true }) {
            delay(700); lookedUp = true; "verified-stream"
        }
        assertEquals("verified-stream", stream)
        assertTrue(connected && lookedUp)
        assertEquals("Startup waits for the slower task, not their sum", 700, testScheduler.currentTime)
    }
    @Test fun cancelledSelectionsCancelBothPendingTasks() = runTest {
        var active = 0
        val job = launch {
            resolveWhileConnecting(connect = { active++; try { awaitCancellation() } finally { active-- } }) {
                active++; try { awaitCancellation() } finally { active-- }
            }
        }
        runCurrent(); assertEquals(2, active)
        job.cancelAndJoin()
        assertEquals(0, active)
    }
    @Test fun aFailedConnectionCancelsLookupAndCannotCommitAResolvedSong() = runTest {
        var lookupCancelled = false
        var committed = false
        try {
            resolveWhileConnecting(connect = { delay(10); error("Connection failed") }) {
                try { awaitCancellation() } finally { lookupCancelled = true }
            }
            committed = true
        } catch (expected: IllegalStateException) { assertEquals("Connection failed", expected.message) }
        assertTrue(lookupCancelled)
        assertFalse(committed)
    }
}
