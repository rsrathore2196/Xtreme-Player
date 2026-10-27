package com.example

import android.app.Application
import com.example.playback.PlaybackDiagnostics
import com.example.playback.PlaybackDiagnostics.StartupStage
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class PlaybackStartupDiagnosticsTest {
    @Test fun selectionToAudioStagesUseMonotonicTimeAndLogOnlyOnce() {
        val trace = PlaybackDiagnostics.beginStartup("catalog_track")
        ShadowSystemClock.advanceBy(Duration.ofMillis(15))
        PlaybackDiagnostics.markStartup(trace, StartupStage.RESOLUTION_STARTED)
        ShadowSystemClock.advanceBy(Duration.ofMillis(60))
        PlaybackDiagnostics.markStartup(trace, StartupStage.CONTROLLER_READY)
        ShadowSystemClock.advanceBy(Duration.ofMillis(125))
        PlaybackDiagnostics.markStartup(trace, StartupStage.RESOLVED)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        PlaybackDiagnostics.markStartup(trace, StartupStage.PREPARE_REQUESTED)
        ShadowSystemClock.advanceBy(Duration.ofMillis(30))
        PlaybackDiagnostics.markStartup(trace, StartupStage.FORMAT_READY)
        PlaybackDiagnostics.markStartup(trace, StartupStage.DECODER_READY)
        ShadowSystemClock.advanceBy(Duration.ofMillis(50))
        PlaybackDiagnostics.markStartup(trace, StartupStage.FIRST_AUDIO, "catalog_track")
        val recorded = PlaybackDiagnostics.startup(trace)!!
        assertEquals(300L, recorded.stages[StartupStage.FIRST_AUDIO])
        assertEquals(200L, recorded.stages[StartupStage.RESOLVED])
        assertEquals(220L, recorded.stages[StartupStage.PREPARE_REQUESTED])
        assertFalse(recorded.pending)
        PlaybackDiagnostics.markStartup(trace, StartupStage.FIRST_AUDIO)
        PlaybackDiagnostics.markStartup(trace, StartupStage.CANCELLED)
        assertSame(recorded, PlaybackDiagnostics.startup(trace))
        assertEquals(1, ShadowLog.getLogsForTag("PlaybackStartup").count { it.msg.startsWith("trace=$trace,") })
    }

    @Test fun oldSelectionsAndUnrelatedAudioCannotCompleteTheNewTrace() {
        val old = PlaybackDiagnostics.beginStartup("old_track")
        PlaybackDiagnostics.markStartup(old, StartupStage.CANCELLED)
        val current = PlaybackDiagnostics.beginStartup("new_track")
        PlaybackDiagnostics.markStartup(old, StartupStage.FIRST_AUDIO, "old_track")
        PlaybackDiagnostics.markStartup(current, StartupStage.FIRST_AUDIO, "old_track")
        assertTrue(PlaybackDiagnostics.startupPending(current, "new_track"))
        assertFalse(PlaybackDiagnostics.startup(old)!!.stages.containsKey(StartupStage.FIRST_AUDIO))
        PlaybackDiagnostics.markStartup(current, StartupStage.FIRST_AUDIO, "new_track")
        assertFalse(PlaybackDiagnostics.startupPending(current, "new_track"))
    }

    @Test fun queueRepeatsHaveSeparateBoundedOccurrenceTraces() {
        val first = PlaybackDiagnostics.beginStartup("repeated_song", "transition")
        PlaybackDiagnostics.markStartup(first, StartupStage.FIRST_AUDIO)
        val second = PlaybackDiagnostics.beginStartup("repeated_song", "transition")
        assertNotEquals(first, second)
        assertTrue(PlaybackDiagnostics.startupPending(second, "repeated_song"))
        repeat(64) { PlaybackDiagnostics.beginStartup("catalog_$it") }
        assertNull(PlaybackDiagnostics.startup(first))
        assertNull(PlaybackDiagnostics.startup(second))
    }
}
