package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
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
