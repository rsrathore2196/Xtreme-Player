package com.example.performance

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HeadlessOptionalAdmissionTest {
    @Test fun serviceEnrichmentDoesNotRequireAnActivityOrHomeInitialization() = runBlocking {
        BackgroundWorkCoordinator.foreground(false)
        // Robolectric rewinds uptime between tests while retaining this singleton.
        // Rebase only its completion sample; real admission/queue guards remain active.
        (BackgroundWorkCoordinator::class.java.getDeclaredField("lastOptionalCompleted").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        assertEquals("prepared", withTimeout(2_000) {
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                requireForeground = false) { "prepared" }
        })
    }
}
