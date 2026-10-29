package com.example

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.StartupReadiness
import com.example.performance.StartupStatus
import com.example.performance.StartupTask
import com.example.ui.components.AutomaticAppUpdateCheck
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomaticAppUpdateCheckTest {
    @get:Rule val compose = createComposeRule()

    @Test fun startupSchedulesOneCheckWithoutHomeAboutOrNotificationPermissionAndSurvivesResume() {
        val coordinator = BackgroundWorkCoordinator
        @Suppress("UNCHECKED_CAST")
        val state = coordinator.javaClass.getDeclaredField("_startupReadiness").apply { isAccessible = true }
            .get(null) as MutableStateFlow<StartupReadiness>
        state.value = StartupReadiness()
        coordinator.firstFrameRendered()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val claimed = AtomicBoolean()
        var checks = 0
        val session = Any()
        var selected by mutableStateOf("Search")
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AutomaticAppUpdateCheck(session, { claimed.compareAndSet(false, true) }, { checks++ })
                Text(selected)
            }
        }
        compose.waitForIdle()
        assertEquals("No optional startup request before preparation completes", 0, checks)
        compose.runOnIdle { state.value = StartupReadiness(StartupTask.entries.associateWith { StartupStatus.READY }) }
        compose.waitForIdle()
        assertEquals(1, checks)
        compose.runOnIdle { selected = "Library" }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals("Tab changes and permission/lifecycle pauses cannot duplicate the session check", 1, checks)
    }
}
