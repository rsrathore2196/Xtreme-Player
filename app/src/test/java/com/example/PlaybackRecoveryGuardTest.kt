package com.example

import com.example.playback.PlaybackRecoveryGuard
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryGuardTest {
    @Test fun onlyOneRecoveryAttemptPerSelection() {
        val guard = PlaybackRecoveryGuard()
        guard.select("a")
        assertNotNull(guard.claim("a"))
        guard.select("a") // Replacing the current stream must not reset the budget.
        assertNull(guard.claim("a"))
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

    @Test fun explicitRetryAllowsOneNewAttemptAndInvalidatesPendingResult() {
        val guard = PlaybackRecoveryGuard()
        guard.select("a")
        val token = guard.claim("a")!!
        guard.select("a", newRequest = true)
        assertFalse(guard.isCurrent("a", token))
        assertNotNull(guard.claim("a"))
        assertNull(guard.claim("b"))
    }
}
