package com.example.performance

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33], application = Application::class)
class RuntimeProfileInstallationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        (DeferredLibraryInitialization::class.java.getDeclaredField("profileStarted").apply { isAccessible = true }
            .get(null) as AtomicBoolean).set(false)
        scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
        BackgroundWorkCoordinator.foreground(false); BackgroundWorkCoordinator.touch(false)
        BackgroundWorkCoordinator.scrolling(false); BackgroundWorkCoordinator.windowFocus(true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }
    @After fun close() { scope.cancel(); BackgroundWorkCoordinator.foreground(false) }

    @Test fun firstFrameInstallationIsOffMainSingleFlightAndIndependentOfCoreHomeAndMaintenance() = runBlocking<Unit> {
        val draw = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val core = BackgroundWorkCoordinator.coreTabsPreparation()
        BackgroundWorkCoordinator.foreground(true)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        try {
            val job = DeferredLibraryInitialization.installProfileAfterFirstDraw(app, scope, awaitDraw = { draw.await() }) {
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                calls.incrementAndGet(); true
            }!!
            delay(100); assertEquals(0, calls.get())
            assertNull(DeferredLibraryInitialization.installProfileAfterFirstDraw(app, scope) { fail("Duplicate install"); true })
            draw.complete(Unit)
            withTimeout(3_000) { job.join() }
            assertEquals(1, calls.get())
            assertNull(DeferredLibraryInitialization.installProfileAfterFirstDraw(app, scope) { fail("Repeated foreground install"); true })
        } finally { core.close() }
    }

    @Test fun inputAndPlaybackDelayInstallationWithoutHoldingAnAdmissionPermit() = runBlocking<Unit> {
        BackgroundWorkCoordinator.foreground(true); BackgroundWorkCoordinator.touch(true)
        val calls = AtomicInteger()
        val job = DeferredLibraryInitialization.installProfileAfterFirstDraw(app, scope, awaitDraw = {}) { calls.incrementAndGet(); true }!!
        delay(100); assertEquals(0, calls.get())
        val active = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val playback = async { com.example.recommendation.DiscoveryRequestCoordinator.playback { active.complete(Unit); release.await() } }
        try {
            active.await(); BackgroundWorkCoordinator.touch(false)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); delay(100)
            assertEquals(0, calls.get())
            release.complete(Unit); playback.join()
            withTimeout(3_000) { job.join() }; assertEquals(1, calls.get())
        } finally { release.complete(Unit); playback.cancelAndJoin(); job.cancelAndJoin() }
    }

    @Test fun transientFailureAllowsOneLaterForegroundAttemptWithoutATimerLoop() = runBlocking<Unit> {
        val calls = AtomicInteger()
        DeferredLibraryInitialization.installProfileAfterFirstDraw(app, scope, awaitDraw = {}) { calls.incrementAndGet(); false }!!.join()
        delay(100); assertEquals(1, calls.get())
        DeferredLibraryInitialization.installProfileAfterFirstDraw(app, scope, awaitDraw = {}) { calls.incrementAndGet(); true }!!.join()
        assertEquals(2, calls.get())
    }
}
