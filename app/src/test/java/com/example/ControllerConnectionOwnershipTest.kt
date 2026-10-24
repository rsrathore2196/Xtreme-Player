package com.example

import com.example.playback.completedConnectionValue
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException

class ControllerConnectionOwnershipTest {
    @Test(timeout = 1000) fun oldCompletionNeverWaitsOnTheNewIncompleteAttempt() {
        val old = SettableFuture.create<String>().apply { set("old") }
        val fresh = SettableFuture.create<String>()
        assertNull(completedConnectionValue(old, fresh, ownsGeneration = true))
        assertNull(completedConnectionValue(old, old, ownsGeneration = false))
        assertNull(completedConnectionValue(fresh, fresh, ownsGeneration = true))
        fresh.set("new")
        assertEquals("new", completedConnectionValue(fresh, fresh, ownsGeneration = true))
    }

    @Test(expected = ExecutionException::class) fun currentFailedCompletionKeepsItsActualFailure() {
        val future = SettableFuture.create<String>().apply { setException(IllegalStateException("connection failed")) }
        completedConnectionValue(future, future, ownsGeneration = true)
    }
}
