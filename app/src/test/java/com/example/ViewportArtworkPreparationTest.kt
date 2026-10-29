package com.example

import android.app.Application
import com.example.ui.util.ArtworkVisibility
import com.example.ui.util.ViewportArtworkPreparation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ViewportArtworkPreparationTest {
    @Test fun preparationLimitsBothViewportSizeAndConcurrentWorkAndWaitsForActualCompletion() = runTest {
        val batch = ViewportArtworkPreparation(maximumRequests = 3)
        val tickets = (0..2).map { checkNotNull(batch.register("cover-$it")) }
        assertNull(batch.register("cover-0"))
        assertNull(batch.register("unseen-cover"))
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val jobs = tickets.map { ticket -> launch {
            try { ticket.load(visible = { false }) {
                active++; maximum = maxOf(maximum, active)
                try { release.await() } finally { active-- }
            } } finally { ticket.finish() }
        } }
        val complete = launch { batch.awaitCompletion() }
        runCurrent()
        assertEquals(2, maximum)
        assertFalse(complete.isCompleted)
        release.complete(Unit)
        jobs.forEach { it.join() }; complete.join()
        assertEquals(2, maximum)
        tickets.forEach { it.finish() }
        batch.close()
        assertTrue(tickets.none { it.allowed.value })
        assertNull(batch.register("after-close"))
    }

    @Test fun preparedVisibilityNeverMakesTheHiddenTabPermanentlyActive() = runTest {
        val batch = ViewportArtworkPreparation()
        val visibility = ArtworkVisibility(false)
        visibility.prepare(checkNotNull(batch.register("cover")))
        assertTrue(visibility.permitted.first())
        visibility.complete()
        batch.awaitCompletion()
        batch.close()
        assertFalse(visibility.permitted.first())
        visibility.active.value = true
        assertTrue(visibility.permitted.first())
    }

    @Test fun anAlreadyLoadedPainterDoesNotBlockPreparationOrNeedAnotherLoad() = runTest {
        val visibility = ArtworkVisibility(true)
        visibility.complete()
        visibility.active.value = false
        val batch = ViewportArtworkPreparation()
        visibility.prepare(checkNotNull(batch.register("cached")))
        val done = launch { batch.awaitCompletion() }
        runCurrent()
        assertTrue(done.isCompleted)
        batch.close()
        assertFalse(visibility.permitted.first())
    }
}
