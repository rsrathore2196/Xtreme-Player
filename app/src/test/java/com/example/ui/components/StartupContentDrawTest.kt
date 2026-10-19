package com.example.ui.components

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class StartupContentDrawTest {
    @Test fun aViewTraversalAcknowledgesPreparedContentWithoutAComposeParentRedraw() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = FrameLayout(controller.get())
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        try {
            var draws = 0
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { view.awaitStartupContentDraw { draws++ } }
            assertFalse(waiting.isCompleted)
            // The View tree draws even if its Compose parent's recorded layer is
            // reused. No parent drawWithContent or animation callback is invoked.
            view.viewTreeObserver.dispatchOnDraw()
            assertEquals(1, draws)
            assertFalse("Continue after the draw traversal, not inside it", waiting.isCompleted)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertTrue(waiting.isCompleted)
            view.viewTreeObserver.dispatchOnDraw()
            assertEquals("The observer is one-shot", 1, draws)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cancellingOnPauseRemovesTheObserverAndAllowsAFreshDrawOnResume() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = FrameLayout(controller.get())
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        try {
            var draws = 0
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { view.awaitStartupContentDraw { draws++ } }
            cancelled.cancelAndJoin()
            shadowOf(Looper.getMainLooper()).idle()
            view.viewTreeObserver.dispatchOnDraw()
            assertEquals(0, draws)
            val resumed = launch(start = CoroutineStart.UNDISPATCHED) { view.awaitStartupContentDraw { draws++ } }
            view.viewTreeObserver.dispatchOnDraw()
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertTrue(resumed.isCompleted)
            assertEquals(1, draws)
        } finally { controller.pause().stop().destroy() }
    }
}
