package com.example

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.StartupReadiness
import com.example.performance.StartupTask
import com.example.performance.StartupStatus
import com.example.ui.components.ObserveInitialContentReadiness
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupPresentationUiTest {
    @get:Rule val compose = createComposeRule()
    @Suppress("UNCHECKED_CAST")
    private val readiness get() = BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
        .apply { isAccessible = true }.get(null) as MutableStateFlow<StartupReadiness>

    @Before fun pending() {
        readiness.value = StartupReadiness()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("preparedContentDrawn")
            .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
    }

    @After fun releasePresentation() {
        BackgroundWorkCoordinator.foreground(false)
        (BackgroundWorkCoordinator::class.java.getDeclaredField("initialPresentationPending")
            .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
    }

    @Test fun unfinishedPreparationDoesNotBlockHomeTouchesOrRecomposeTheHost() {
        var taps = 0
        var hostCompositions = 0
        compose.setContent { MaterialTheme {
            SideEffect { hostCompositions++ }
            Box(Modifier.fillMaxSize()) {
                Text("Home", Modifier.testTag("home").fillMaxSize().clickable { taps++ })
                ObserveInitialContentReadiness()
            }
        } }
        compose.onNodeWithTag("home").performClick()
        compose.onNodeWithText("Curating your vibe").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, taps)
            readiness.value = readiness.value.copy(tasks = readiness.value.tasks +
                (StartupTask.RECENT_HISTORY to StartupStatus.READY))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("home").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, taps); assertEquals(1, hostCompositions) }
    }

    @Test fun backgroundReadinessStillFinishesFromTheActualContentDraw() {
        val coordinator = BackgroundWorkCoordinator
        coordinator.firstFrameRendered()
        coordinator.foreground(true)
        coordinator.beginInitialPresentation()
        coordinator.frame(20, 10)
        lateinit var view: android.view.View
        compose.setContent { MaterialTheme {
            view = LocalView.current
            Box(Modifier.fillMaxSize()) {
                Text("Home", Modifier.testTag("home"))
                ObserveInitialContentReadiness()
            }
        } }
        compose.runOnIdle {
            readiness.value = StartupReadiness(StartupTask.entries.associateWith {
                if (it == StartupTask.PRESENTATION) StartupStatus.PENDING else StartupStatus.READY
            })
        }
        compose.waitForIdle()
        compose.runOnIdle { view.viewTreeObserver.dispatchOnDraw() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
        compose.waitUntil(5_000L) { coordinator.startupReadiness.value.complete }
        compose.onNodeWithTag("home").assertIsDisplayed()
    }
}
