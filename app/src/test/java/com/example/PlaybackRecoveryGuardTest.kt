package com.example

import com.example.playback.PlaybackRecoveryGuard
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryGuardTest {
    @Test fun onlyTwoRecoveryStagesPerSelection() {
        val guard = PlaybackRecoveryGuard()
        guard.select("a")
        val first = guard.claim("a")!!
        assertEquals(1, guard.stage("a", first))
        guard.reject("a", first, "https://audio.example/expired.m4a")
        guard.select("a") // Replacing the current stream must not reset the budget.
        val second = guard.claim("a")!!
        assertEquals(2, guard.stage("a", second))
        assertEquals(setOf("https://audio.example/expired.m4a"), guard.rejectedUrls("a", second))
        assertNull(guard.claim("a"))
    }

    @Test fun initialTransportSelectionDoesNotSpendOrResetRecoveryStages() {
        val guard = PlaybackRecoveryGuard()
        guard.select("a")
        val initial = guard.claim("a", initialSelection = true)!!
        assertEquals(0, guard.stage("a", initial))
        val first = guard.claim("a", initialSelection = true)!!
        assertEquals(1, guard.stage("a", first))
        val second = guard.claim("a")!!
        assertEquals(2, guard.stage("a", second))
        assertNull(guard.claim("a", initialSelection = true))
    }

    @Test fun switchingAwayAndBackInvalidatesOldRecovery() {
        val guard = PlaybackRecoveryGuard()
        guard.select("a")
        val token = guard.claim("a")!!
        guard.select("b")
        guard.select("a")
        assertFalse(guard.isCurrent("a", token))
        assertNotNull(guard.claim("a"))
    }

    @Test fun explicitRetryRestoresTwoStagesAndInvalidatesPendingResult() {
        val guard = PlaybackRecoveryGuard()
        guard.select("a")
        val token = guard.claim("a")!!
        guard.select("a", newRequest = true)
        assertFalse(guard.isCurrent("a", token))
        assertNotNull(guard.claim("a"))
        assertNotNull(guard.claim("a"))
        assertNull(guard.claim("a"))
        assertNull(guard.claim("b"))
    }
}
