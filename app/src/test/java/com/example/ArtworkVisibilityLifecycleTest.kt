package com.example

import com.example.ui.util.ArtworkVisibility
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ArtworkVisibilityLifecycleTest {
    @Test fun hiddenArtworkHasNoPreparationPermit() = runBlocking {
        val visibility = ArtworkVisibility(false)
        assertFalse(visibility.permitted.first())
        visibility.active.value = true
        assertTrue(visibility.permitted.first())
        visibility.active.value = false
        assertFalse(visibility.permitted.first())
    }
    @Test fun aHiddenRequestCanBeCancelledWithoutWaitingForAnotherTab() = runBlocking {
        val visibility = ArtworkVisibility(false)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { visibility.permitted.first { it } }
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
    }
    @Test fun returningToVisibleContentResumesItsExistingRequest() = runBlocking {
        val visibility = ArtworkVisibility(false)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { visibility.permitted.first { it } }
        visibility.active.value = true
        assertTrue(waiting.await())
    }
}
