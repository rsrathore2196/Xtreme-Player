package com.example

import android.app.Application
import com.example.data.model.*
import com.example.data.remote.LyricsSources
import com.example.data.remote.LyricsLookupPriority
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LyricsSchedulingRegressionTest {
    @Test fun sharedLookupPriorityFollowsVisibleReadersAndYieldsDuringNavigation() {
        BackgroundWorkCoordinator.firstFrameRendered()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("navigationUntil").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        val priority = LyricsLookupPriority()
        var visible = false
        val reader = { visible }
        priority.add(reader)
        assertFalse(priority.canRunImmediately())
        visible = true
        assertTrue("Opening the lyrics promotes an existing idle lookup", priority.canRunImmediately())
        val transition = BackgroundWorkCoordinator.navigationBusy()
        try { assertFalse("Visible lyrics CPU work must wait for transition completion", priority.canRunImmediately()) }
        finally { transition.close() }
        assertTrue(priority.canRunImmediately())
        priority.remove(reader)
        assertFalse("A departed reader must not leave a shared job permanently urgent", priority.canRunImmediately())
    }

    private val identity = SongIdentity(title = "Song", artist = "Artist", durationMs = 180000, trackId = "song")
    private val names = listOf("PaxSenix", "BetterLyrics", "BetterLyrics Portato", "BiniLyrics", "LyricsPlus",
        "PaxSenix Spotify", "PaxSenix Musixmatch", "Lrc.Red", "Musixmatch", "KuGou", "LrcLib", "Unison", "Genius", "YouTube captions")
    private fun lyrics(provider: String) = TrackLyrics("song", "Song", "Artist", true,
        listOf(LyricLine(1000, "This is a song", 2000), LyricLine(5000, "With original words", 2000)), provider = provider)

    @Test fun slowPreferredProvidersCannotStarveTheFinalFallbacks() = runTest {
        val attempted = mutableSetOf<String>()
        var concurrent = 0
        var peak = 0
        val sources = names.associateWith { name -> suspend {
            attempted.add(name)
            concurrent++; peak = maxOf(peak, concurrent)
            try {
                if (name == "Unison") lyrics(name) else { awaitCancellation() }
            } finally { concurrent-- }
        } }
        val emitted = mutableListOf<TrackLyrics>()
        val result = LyricsSources(identity, sources, 20_000L, { testScheduler.currentTime })
            .lookup(null, onCandidate = emitted::add)
        assertEquals("Unison", result?.provider)
        assertTrue(attempted.containsAll(listOf("PaxSenix", "Lrc.Red", "KuGou", "Unison")))
        assertTrue(testScheduler.currentTime <= 20_000L)
        assertTrue("Only two priority workers plus the fast fallback may run", peak <= 3)
        assertEquals(0, concurrent)
    }

    @Test fun cancellationStopsEveryPendingProviderAndPublishesNoLateSong() = runTest {
        var running = 0
        var publications = 0
        val sources = names.associateWith { suspend { running++; try { awaitCancellation() } finally { running-- } } }
        val job = launch { LyricsSources(identity, sources, nowMs = { testScheduler.currentTime })
            .lookup(null, onCandidate = { publications++ }) }
        runCurrent()
        assertTrue(running > 0)
        job.cancelAndJoin()
        assertEquals(0, running)
        assertEquals(0, publications)
    }

    @Test fun manualProviderSelectionStillUsesOnlyTheChosenProvider() = runTest {
        val attempted = mutableListOf<String>()
        val sources = names.associateWith { name -> suspend { attempted.add(name); lyrics(name) } }
        val result = LyricsSources(identity, sources, nowMs = { testScheduler.currentTime })
            .lookup(null, "KuGou") { }
        assertEquals(listOf("KuGou"), attempted)
        assertEquals("KuGou", result?.provider)
    }
}
