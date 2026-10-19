package com.example

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.StartupReadiness
import com.example.performance.StartupTask
import com.example.performance.StartupStatus
import com.example.ui.components.StartupInitializationOverlay
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LocalAppColors
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class StartupOverlayUiTest {
    @get:Rule val compose = createComposeRule()
    @Suppress("UNCHECKED_CAST")
    private val readiness get() = BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
        .apply { isAccessible = true }.get(null) as MutableStateFlow<StartupReadiness>

    @Before fun pending() {
        readiness.value = StartupReadiness()
        // Advance entrance, progress and exit transitions explicitly.
        compose.mainClock.autoAdvance = false
    }

    @Test fun lightThemeIsOpaqueReadableAndConsumesUnderlyingTouches() = checkOpaqueTheme(false)

    @Test fun darkThemeIsOpaqueReadableAndConsumesUnderlyingTouches() = checkOpaqueTheme(true)

    private fun checkOpaqueTheme(dark: Boolean) {
        var taps = 0
        val theme = androidx.compose.runtime.mutableStateOf(if (dark) DarkAppColors else LightAppColors)
        compose.setContent { MaterialTheme {
            val base = theme.value
            CompositionLocalProvider(LocalAppColors provides base.copy(primaryAccent = Color(0xFF1B9A72), secondaryAccent = Color(0xFF75BFA3))) {
                Box(Modifier.fillMaxSize()) {
                    Text("Home preview", Modifier.testTag("underlay").fillMaxSize().clickable { taps++ })
                    StartupInitializationOverlay(Modifier.fillMaxSize().testTag("startup_modal"), firstLaunch = !dark)
                }
            }
        } }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Curating your vibe").assertIsDisplayed()
        compose.onNodeWithText(if (dark) "Bringing your music back in sync" else "Finding music that feels like you").assertIsDisplayed()
        listOf("HOME", "RECENTS", "PLAYER").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Preparing your music").assertIsDisplayed()
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.onNodeWithTag("underlay").performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 12f)) }
        compose.runOnIdle { assertEquals(0, taps) }
        val artifacts = java.io.File("build/reports/startup-overlay").apply { mkdirs() }
        val record = RoborazziOptions(taskType = RoborazziTaskType.Record)
        fun captureOpaquePanel(name: String, expected: Color) {
            val file = java.io.File(artifacts, name)
            compose.onNodeWithTag("startup_initialization_panel").captureRoboImage(file.absolutePath, roborazziOptions = record)
            val pixels = javax.imageio.ImageIO.read(file)
            // Sample inside the solid bottom padding, clear of text/rings and
            // rounded corners. The preview must never show through the panel.
            val sample = Color(pixels.getRGB(pixels.width / 2, pixels.height - 12))
            assertEquals(1f, sample.alpha, .001f)
            assertEquals(expected.red, sample.red, .02f)
            assertEquals(expected.green, sample.green, .02f)
            assertEquals(expected.blue, sample.blue, .02f)
        }
        captureOpaquePanel(if (dark) "dark.png" else "light.png", theme.value.cardBackgroundElevated)
        val changed = if (dark) LightAppColors else DarkAppColors
        compose.runOnIdle {
            theme.value = changed
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("Curating your vibe").assertIsDisplayed()
        // Inspect the live text layout after a theme change. Native host captures
        // can reuse an animated RenderNode's earlier display list; each opaque
        // surface colour is checked separately on its actual first render above.
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Curating your vibe").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(changed.textPrimary, layouts.single().layoutInput.style.color)
    }

    @Test fun actualProgressDetailsStayIsolatedAndFinishedOverlayLeavesTheSameHomeMounted() {
        var hostCompositions = 0
        compose.setContent { MaterialTheme {
            androidx.compose.runtime.SideEffect { hostCompositions++ }
            Box(Modifier.fillMaxSize()) {
                Text("Retained Home", Modifier.testTag("home"))
                StartupInitializationOverlay(Modifier.fillMaxSize().testTag("startup_modal"))
            }
        } }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            val tasks = StartupReadiness().tasks.toMutableMap()
            tasks[StartupTask.RECENT_HISTORY] = StartupStatus.READY
            tasks[StartupTask.RECENT_ARTISTS] = StartupStatus.READY
            tasks[StartupTask.PLAYER_SESSION] = StartupStatus.RUNNING
            readiness.value = StartupReadiness(tasks)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Restoring your last session").assertIsDisplayed()
        compose.onNodeWithText("13%").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Task progress only recomposes the popup", 1, hostCompositions) }
        compose.runOnIdle { readiness.value = StartupReadiness(StartupTask.entries.associateWith { StartupStatus.READY }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("startup_modal").assertDoesNotExist()
        compose.onNodeWithTag("home").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, hostCompositions) }
    }
}
