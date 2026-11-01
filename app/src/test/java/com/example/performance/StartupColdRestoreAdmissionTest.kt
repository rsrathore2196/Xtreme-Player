package com.example.performance

import android.app.Application
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// A separate instrumentation sandbox keeps the process's one-shot readiness
// fences cold instead of borrowing completed fences from other scheduler tests.
@Config(sdk = [32], application = Application::class, instrumentedPackages = ["com.example.performance"])
class StartupColdRestoreAdmissionTest {
    @Test fun requiredLocalRestorationCannotWaitOnTheReadinessSignalItStillOwns() = runTest {
        val coordinator = BackgroundWorkCoordinator
        @Suppress("UNCHECKED_CAST")
        val state = BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
            .apply { isAccessible = true }.get(null) as MutableStateFlow<StartupReadiness>
        val pending = BackgroundWorkCoordinator::class.java.getDeclaredField("initialPresentationPending")
            .apply { isAccessible = true }.get(null) as AtomicBoolean
        listOf("homeReady", "cachedHomeReady").forEach { name ->
            val fence = BackgroundWorkCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(null) as kotlinx.coroutines.CompletableDeferred<*>
            assertFalse("$name must start cold in this fixture", fence.isCompleted)
        }
        coordinator.firstFrameRendered()
        coordinator.foreground(true)
        coordinator.beginInitialPresentation()
        coordinator.scrolling(true)
        var restored = false
        val restore = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                restored = true
                coordinator.cachedHomeRestored()
                coordinator.homeContentReady()
            }
        }
        try {
            runCurrent()
            assertFalse(restored)
            state.value = state.value.expirePreparation()
            pending.set(false)
            coordinator.scrolling(false)
            coordinator.interaction()
            advanceTimeBy(100); runCurrent()
            assertTrue("Required restoration must release its own Home prerequisites", restored)
            restore.join()
        } finally {
            restore.cancelAndJoin()
            pending.set(false)
            coordinator.scrolling(false)
            coordinator.foreground(false)
        }
    }
}
