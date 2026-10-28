package com.example

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun blackOnboardingRetainsTheDarkThemesAccentAndProfileChoices() = verify(false)
    @Test fun blackOnboardingRetainsTheLightThemesAccentAndProfileChoices() = verify(true)

    private fun verify(light: Boolean) {
        val inherited = if (light) LightAppColors else DarkAppColors
        var submitted: List<Any>? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides inherited) {
                OnboardingScreen(onComplete = { name, languages, country, code, flag ->
                    submitted = listOf(name, languages, country, code, flag)
                })
            }
        } }
        fun capture(tag: String, label: String): java.awt.image.BufferedImage {
            val file = File("/tmp/onboarding-${if (light) "light" else "dark"}-$label.png")
            compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath, RoborazziOptions(taskType = RoborazziTaskType.Record))
            return ImageIO.read(file)
        }
        fun assertCanvas(label: String) {
            val image = capture("onboarding_screen", label)
            assertEquals("Onboarding must retain a truly black canvas in either app mode", 0,
                image.getRGB(3, image.height / 2) and 0x00ffffff)
        }
        fun textColor(label: String): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        compose.onNodeWithText("Redefining High Fidelity").assertIsDisplayed()
        compose.onNodeWithText("WELCOME TO YOUR MUSIC").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_brand_logo").assertIsDisplayed()
        assertEquals("Brand accent must be inherited, not substituted", inherited.primaryAccent, textColor("Xtreme"))
        assertCanvas("welcome")
        compose.onNodeWithTag("start_listening_button").performScrollTo().performClick()
        compose.onNodeWithText("Make it yours.").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_details_logo").assertWidthIsEqualTo(70.4.dp).assertHeightIsEqualTo(70.4.dp)
        for (brand in listOf("Xtreme", "Player")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(brand).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(26.4.sp, layouts.single().layoutInput.style.fontSize)
        }
        compose.onNodeWithText("A LITTLE ABOUT YOU").assertDoesNotExist()
        for (name in listOf("English", "Hindi", "Punjabi", "Spanish", "Korean"))
            compose.onNodeWithTag("language_chip_$name").assertExists()
        compose.onNodeWithTag("language_chip_Tamil").assertDoesNotExist()
        compose.onNodeWithTag("language_chip_Telugu").assertDoesNotExist()
        assertCanvas("details")
        compose.onNodeWithTag("onboarding_name_input").performScrollTo().performTextInput("  Alex  ")
        compose.onNodeWithTag("language_chip_English").performScrollTo().performClick()
        compose.onNodeWithText("2 chosen").assertExists()
        val surface = capture("onboarding_languages_card", "card")
        assertTrue("Material cards must stand out from the black canvas", surface.getRGB(4, surface.height / 2) and 0x00ffffff != 0)
        compose.onNodeWithTag("onboarding_submit_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("Alex", listOf("Punjabi", "Hindi"), "India", "IN", "🇮🇳"), submitted)
    }
}
