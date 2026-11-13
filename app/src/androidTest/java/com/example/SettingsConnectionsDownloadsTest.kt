package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppThemeMode
import com.example.data.local.UserProfile
import com.example.playback.AudioEffectsState
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.settings.AccountsConnectionsPage
import com.example.ui.screens.settings.DownloadsPage
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsConnectionsDownloadsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun settingsPlacesConnectionsAfterProfileAndDownloadsBeforeBackup() {
        compose.setContent { MyApplicationTheme {
            SettingsScreen(AudioEffectsState(), true, AppThemeMode.DARK, UserProfile(), onSelectThemeMode = {},
                onToggleDarkMode = {}, onCrystalClarityToggle = {}, onToggleEqualizer = {}, onOpenEqualizer = {}, onSelectPreset = {})
        } }
        val profile = compose.onNodeWithTag("main_profile_preview_bar").fetchSemanticsNode().boundsInRoot.top
        val accounts = compose.onNodeWithTag("settings_cat_accounts").fetchSemanticsNode().boundsInRoot.top
        val appearance = compose.onNodeWithTag("settings_cat_themes").fetchSemanticsNode().boundsInRoot.top
        assertTrue(profile < accounts && accounts < appearance)
        compose.onNodeWithTag("settings_cat_backup").performScrollTo()
        val downloads = compose.onNodeWithTag("settings_cat_downloads").fetchSemanticsNode().boundsInRoot.top
        val backup = compose.onNodeWithTag("settings_cat_backup").fetchSemanticsNode().boundsInRoot.top
        assertTrue(downloads < backup)
        compose.onNodeWithTag("settings_cat_downloads").performScrollTo().performClick()
        compose.onNodeWithTag("downloads_page").assertExists()
    }
    @Test fun accountsOffersOnlyTheThreeRequestedServices() {
        compose.setContent { MyApplicationTheme { AccountsConnectionsPage() } }
        compose.onNodeWithTag("connect_youtube").assertExists()
        compose.onNodeWithTag("connect_lastfm").performScrollTo().assertExists()
        compose.onNodeWithTag("connect_spotify").performScrollTo().assertExists()
        compose.onNodeWithText("Discord").assertDoesNotExist()
        compose.onNodeWithText("ListenBrainz").assertDoesNotExist()
        compose.onNodeWithTag("connect_youtube").performScrollTo()
        screenshot("accounts-connections-review.png")
    }
    @Test fun downloadsShowsStorageAndExposesNetworkControls() {
        compose.setContent { MyApplicationTheme { DownloadsPage() } }
        compose.onNodeWithTag("downloads_wifi_only").assertExists()
        compose.onNodeWithText("Download quality").assertExists()
        compose.onNodeWithText("Find and play your saved tracks in Library → Downloads.").assertDoesNotExist()
        compose.onNodeWithTag("download_storage_summary").assertExists()
        compose.onNodeWithText("Search downloads").assertDoesNotExist()
        compose.onNodeWithText("Play downloaded").assertDoesNotExist()
        screenshot("downloads-review.png")
    }
    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
