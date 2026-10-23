package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.UserProfile
import com.example.ui.components.EditProfileSheet
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditProfileSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveStaysVisibleAndInPlaceWhileTheFormScrollsAndSavesCurrentValues() {
        var saved: UserProfile? = null
        val profile = UserProfile(name = "Listener", languages = listOf("English"), countryCode = "IN", country = "India")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
                EditProfileSheet(profile, onDismiss = {}, onSave = { saved = it })
            }
        } }
        val button = compose.onNodeWithTag("save_profile_button").assertIsDisplayed().assertIsEnabled()
        val initialBounds = button.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("edit_profile_name_input").performTextReplacement("Updated Listener")
        compose.onNodeWithTag("edit_profile_fields").performScrollToNode(hasText("Country (Regional Charts)"))
        button.assertIsDisplayed()
        assertEquals(initialBounds, button.fetchSemanticsNode().boundsInRoot)
        button.performClick()
        compose.runOnIdle {
            assertEquals("Updated Listener", saved?.name)
            assertEquals(profile.languages, saved?.languages)
            assertEquals("IN", saved?.countryCode)
        }
    }
}
