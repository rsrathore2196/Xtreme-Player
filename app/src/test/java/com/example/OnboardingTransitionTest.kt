package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import com.example.ui.screens.OnboardingStep
import com.example.ui.screens.RetainedOnboardingPages
import com.example.performance.BackgroundWorkCoordinator
import org.junit.After
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.screens.OnboardingScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun releaseAdmission() { BackgroundWorkCoordinator.foreground(false) }

    @Test fun preparedPagesAreReusedAndWorkersYieldForTheEntireTransition() {
        var step by mutableStateOf(OnboardingStep.STARTUP)
        val starts = mutableMapOf<OnboardingStep, Int>()
        val measures = mutableMapOf<OnboardingStep, Int>()
        compose.setContent {
            RetainedOnboardingPages(step) { page ->
                DisposableEffect(Unit) {
                    starts[page] = (starts[page] ?: 0) + 1
                    onDispose { }
                }
                Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                    measures[page] = (measures[page] ?: 0) + 1
                    val child = measurable.measure(constraints)
                    layout(child.width, child.height) { child.place(0, 0) }
                }.testTag("test_page_$page"))
            }
        }
        // Robolectric doesn't submit a real window traversal for a static tree.
        // Drive the same View draw acknowledgement used by device startup.
        repeat(3) {
            compose.runOnIdle { compose.activity.window.decorView.viewTreeObserver.dispatchOnDraw() }
            compose.waitForIdle()
        }
        compose.waitUntil(5_000) { starts[OnboardingStep.DETAILS] == 1 }
        // This bare ComponentActivity does not send MainActivity's first-draw
        // acknowledgement. Finish the prepared form's draw and expire any
        // prior fixture's touch cooldown before measuring this transition's lease.
        compose.runOnIdle {
            compose.activity.window.decorView.viewTreeObserver.dispatchOnDraw()
            BackgroundWorkCoordinator.firstFrameRendered()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
        }
        compose.waitForIdle()
        assertTrue("The prepared intro must be idle before starting the transition", BackgroundWorkCoordinator.navigationIsIdle())
        compose.onNodeWithTag("test_page_STARTUP").assertIsDisplayed()
        compose.onNodeWithTag("test_page_DETAILS").assertDoesNotExist()
        val preparedMeasures = measures[OnboardingStep.DETAILS]
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { step = OnboardingStep.DETAILS }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("test_page_DETAILS").assertExists()
        assertFalse("The slide must block optional background continuations", BackgroundWorkCoordinator.navigationIsIdle())
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertEquals("Animating a prepared page must not remeasure its form", preparedMeasures, measures[OnboardingStep.DETAILS])
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue("The transition lease must finish after the existing 220ms motion", BackgroundWorkCoordinator.navigationIsIdle())
        compose.onNodeWithTag("test_page_DETAILS").assertIsDisplayed()
        assertEquals(1, starts[OnboardingStep.DETAILS])
        compose.runOnIdle { step = OnboardingStep.STARTUP }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("test_page_STARTUP").assertIsDisplayed()
        assertEquals("Returning to the intro must reuse its composition", 1, starts[OnboardingStep.STARTUP])
    }

    @Test fun startAndBackTransitionsFinishPromptlyAndPreserveTheEnteredDetails() {
        var completedName: String? = null
        compose.setContent { OnboardingScreen(onComplete = { name, _, _, _, _ -> completedName = name }) }
        compose.onNodeWithTag("onboarding_brand_logo").assertIsDisplayed()
            .assertContentDescriptionEquals("Xtreme Player Logo")
        compose.onNodeWithText("Real Time Synced Lyrics").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("start_listening_button").performScrollTo().assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("start_listening_button").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("start_listening_button").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_details_logo").assertIsDisplayed()
        // IME/inset animations use Android's clock; don't freeze them while
        // exercising text entry and Back on Robolectric's paused looper.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("onboarding_name_input").performScrollTo().performTextInput("Alex")
        assertNull(completedName)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("onboarding_name_input").assertDoesNotExist()
        compose.onNodeWithTag("start_listening_button").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_name_input").assertTextContains("Alex")
        compose.onNodeWithTag("onboarding_submit_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Alex", completedName) }
    }
}
