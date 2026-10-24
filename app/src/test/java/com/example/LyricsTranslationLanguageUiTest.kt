package com.example

import android.app.Application
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LyricsTranslationLanguages
import com.example.data.local.OtherSettingsPreferences
import com.example.ui.components.LyricsTranslationLanguagePopup
import com.example.ui.theme.MyApplicationTheme
import com.example.data.local.ThemePresets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h740dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsTranslationLanguageUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun searchSelectionIsOnlyAppliedOnSubmitAndTheLanguageIsPersisted() {
        var applied: String? = null
        var dismissed = false
        compose.setContent {
            MyApplicationTheme(customThemeState = ThemePresets.StudioNight.toCustomThemeState()) {
                LyricsTranslationLanguagePopup("", {
                    applied = it; OtherSettingsPreferences.setLyricsTranslationLanguage(context, it)
                }, { dismissed = true })
            }
        }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("lyrics_language_option_").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lyrics_language_search").performTextInput("Punjabi")
        compose.onNodeWithTag("lyrics_language_option_pa").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(applied); assertFalse(dismissed) }
        compose.onNodeWithTag("lyrics_language_submit").assertIsDisplayed().performClick()
        compose.waitUntil(3_000) { OtherSettingsPreferences.getLyricsTranslationLanguage(context) == "pa" }
        compose.runOnIdle { assertEquals("pa", applied); assertTrue(dismissed) }
        val observed = OtherSettingsPreferences.observeLyricsSettings(context)
        compose.waitUntil(3_000) { observed.value.translationLanguageTag == "pa" }
        assertEquals("pa", LyricsTranslationLanguages.effectiveTag(observed.value.translationLanguageTag, "en-US"))
        OtherSettingsPreferences.setLyricsTranslationLanguage(context, "")
        compose.waitUntil(3_000) { observed.value.translationLanguageTag.isEmpty() }
        assertEquals("en-US", LyricsTranslationLanguages.effectiveTag(observed.value.translationLanguageTag, "en-US"))
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun submitStaysVisibleInLandscapeAndUnsubmittedChangesAreDiscarded() {
        var submitted = false
        var dismissed = false
        compose.setContent {
            MyApplicationTheme(customThemeState = ThemePresets.CleanDay.toCustomThemeState()) {
                LyricsTranslationLanguagePopup("en", { submitted = true }, { dismissed = true })
            }
        }
        compose.onNodeWithTag("lyrics_language_search").performTextInput("French")
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("lyrics_language_option_fr").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lyrics_language_option_fr").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lyrics_language_submit").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(submitted) }
    }
}
