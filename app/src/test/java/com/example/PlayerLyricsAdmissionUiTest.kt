package com.example

import android.app.Application
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.components.rememberPlayerLyricsTransition
import com.example.ui.components.rememberLyricsViewportActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerLyricsAdmissionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun navigationJobs() = (BackgroundWorkCoordinator::class.java.getDeclaredField("navigationJobs")
        .apply { isAccessible = true }.get(null) as AtomicInteger).get()

    @Test fun lyricsResizingKeepsWorkPausedThroughTheLastFrameWithoutRecomposingEveryFrame() {
        val baseline = navigationJobs()
        val expansion = mutableFloatStateOf(0f)
        var expanded by mutableStateOf(false)
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var compositions = 0
        compose.setContent {
            if (mounted) {
                rememberLyricsViewportActivity(expansion, expanded, active)
                SideEffect { compositions++ }
                Box(Modifier.size(48.dp))
            }
        }
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        assertEquals(baseline + 1, navigationJobs())
        val movingCompositions = compositions
        for (fraction in listOf(0.25f, 0.75f, 0.9995f)) {
            compose.runOnIdle { expansion.floatValue = fraction }
            compose.waitForIdle()
            assertEquals("Intermediate geometry must not restart the admission effect", movingCompositions, compositions)
            assertEquals(baseline + 1, navigationJobs())
        }
        compose.runOnIdle { expansion.floatValue = 1f }
        compose.waitForIdle()
        assertEquals(baseline, navigationJobs())
        compose.runOnIdle { expanded = false; expansion.floatValue = 0.0005f }
        compose.waitForIdle()
        assertEquals("Returning controls still need admission on the last frame", baseline + 1, navigationJobs())
        compose.runOnIdle { expansion.floatValue = 0f }
        compose.waitForIdle()
        assertEquals(baseline, navigationJobs())
        compose.runOnIdle { expanded = true }
        compose.waitForIdle()
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        assertEquals("An inactive player releases its lease", baseline, navigationJobs())
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        assertEquals(baseline + 1, navigationJobs())
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        assertEquals("Disposal must not strand optional player work", baseline, navigationJobs())
    }

    @Test fun lyricsEntranceReversalAndDisposalHoldAndReleaseTheNavigationLease() {
        val baseline = navigationJobs()
        var lyrics by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var active by mutableStateOf(true)
        val frames = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (mounted) {
                val transition = rememberPlayerLyricsTransition(lyrics, active)
                SideEffect { frames.add("requested=$lyrics current=${transition.currentState} target=${transition.targetState} running=${transition.isRunning} leases=${navigationJobs()}") }
                val position = transition.animateFloat(transitionSpec = { tween(420) }, label = "test_lyrics_bounds") {
                    if (it) 1f else 0f
                }
                Box(Modifier.size(48.dp).graphicsLayer { translationX = position.value })
            }
        }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(baseline, navigationJobs())
        fun update(block: () -> Unit) {
            compose.runOnIdle(block)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            // Snapshot application and the transition's first frame are separate
            // turns when this host fixture deliberately freezes Compose's clock.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        update { lyrics = true }
        assertEquals("Foreground lyrics geometry owns admission: $frames", baseline + 1, navigationJobs())
        compose.mainClock.advanceTimeBy(100)
        update { lyrics = false }
        assertEquals("A rapid reversal must keep one lease", baseline + 1, navigationJobs())
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("Settled content must release background work", baseline, navigationJobs())
        update { lyrics = true }
        assertEquals(baseline + 1, navigationJobs())
        update { active = false }
        assertEquals("Covered/stopped player cannot retain admission", baseline, navigationJobs())
        update { active = true }
        assertEquals(baseline + 1, navigationJobs())
        update { mounted = false }
        assertEquals("Leaving the player cannot leak its lease", baseline, navigationJobs())
    }
}
