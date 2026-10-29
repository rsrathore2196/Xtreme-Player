package com.example

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.settings.AboutSocialCommunityButtonsRow
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutCommunityThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun buildVerificationRemainsWithoutTheRemovedAudioArchitectureDetails() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
                com.example.ui.screens.settings.AboutPage(isDarkMode = true)
            }
        } }
        compose.onNodeWithText("Build Verification").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("STABLE RELEASE").assertIsDisplayed()
        compose.onNodeWithText("Audio Architecture").assertDoesNotExist()
        compose.onNodeWithText("16-Bit PCM / 5-Band DSP").assertDoesNotExist()
    }

    @Test fun everyCommunityButtonUsesTheCurrentThemeWithReadableTextAndUnchangedLinks() {
        var colors by mutableStateOf(DarkAppColors.copy(primaryAccent = Color(0xFFB416CA)))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors) {
                Box(Modifier.width(390.dp)) { AboutSocialCommunityButtonsRow() }
            }
        } }
        val buttons = listOf("about_github_button", "about_telegram_channel_button", "about_telegram_discussion_button")
        fun checkTheme() {
            val samples = buttons.map { tag ->
                // Robolectric's native renderer has no device PixelCopy callback.
                // Capture the actual composed node with its supported draw path.
                val file = java.io.File.createTempFile("community-theme-", ".png")
                try {
                    compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
                        roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                    val pixels = javax.imageio.ImageIO.read(file)
                    // Sample the fill below the centered icon/text, not the glyphs
                    // (the longest label starts close to the capsule's left edge).
                    Color(pixels.getRGB(pixels.width / 2, (pixels.height * .80f).toInt()))
                } finally { file.delete() }
            }
            samples.forEach { sample ->
                assertEquals(colors.primaryAccent.red, sample.red, .13f)
                assertEquals(colors.primaryAccent.green, sample.green, .13f)
                assertEquals(colors.primaryAccent.blue, sample.blue, .13f)
            }
            listOf("GitHub", "Channel", "Discussion").forEach { label ->
                val layouts = mutableListOf<TextLayoutResult>()
                // Each clickable pill merges the divider and label semantics.
                // Inspect the actual label, not the first (translucent) divider.
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(contrastingContentColor(colors.primaryAccent), layouts.single().layoutInput.style.color)
            }
        }
        checkTheme()
        compose.runOnIdle { colors = LightAppColors.copy(primaryAccent = Color(0xFFADEEF2)) }
        checkTheme()
        val urls = listOf("https://github.com/rsrathore2196/Xtreme-Player",
            "https://t.me/XtremePlayerOfficiaI", "https://t.me/xtremeplayerofficial")
        buttons.zip(urls).forEach { (tag, url) ->
            compose.onNodeWithTag(tag).performClick()
            val intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
        }
    }
}
