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
        assertEquals("prepared", withTimeout(2_000) {
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                requireForeground = false) { "prepared" }
        })
    }
}
