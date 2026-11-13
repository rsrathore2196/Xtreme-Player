package com.example

import com.example.ui.viewmodel.PlaybackLaunchOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLaunchOwnerTest {
    @Test fun delayedLookupCannotReplaceANewerTrackSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = PlaybackLaunchOwner()
        val gate = CompletableDeferred<String>()
        val played = mutableListOf<String>()
        try {
            val old = owner.prepare(scope, dispatcher, load = { withContext(NonCancellable) { gate.await() } }) { played += it }
            runCurrent()
            owner.current = scope.launch { played += "new-selection" }
            gate.complete("old-downloads")
            advanceUntilIdle()
            assertTrue(old.isCancelled)
            assertEquals(listOf("new-selection"), played)
        } finally { scope.cancel() }
    }

    @Test fun aNewCollectionRequestCancelsThePreviousPreparation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = PlaybackLaunchOwner()
        val played = mutableListOf<String>()
        try {
            val old = owner.prepare(scope, dispatcher, load = { awaitCancellation() }) { _: Nothing -> played += "old" }
            runCurrent()
            val newest = owner.prepare(scope, dispatcher, load = { "new-liked-songs" }) { played += it; true }
            advanceUntilIdle()
            assertTrue(old.isCancelled)
            assertTrue(newest.await())
            assertEquals(listOf("new-liked-songs"), played)
        } finally { scope.cancel() }
    }

    @Test fun storageFailureIsReportedToTheAwaitingCallerWithoutPlayingOrCancellingTheOwnerScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var played = false
        try {
            val request = PlaybackLaunchOwner().prepare(scope, dispatcher, load = { throw IOException("unavailable storage") }) { _: Nothing -> played = true }
            advanceUntilIdle()
            assertTrue(runCatching { request.await() }.exceptionOrNull() is IOException)
            assertFalse(played)
            assertTrue(scope.isActive)
        } finally { scope.cancel() }
    }
}
