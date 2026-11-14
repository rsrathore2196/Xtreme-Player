package com.example

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.connections.*
import com.example.ui.screens.settings.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConnectionAppearanceLoginTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = ConnectionStore.get(context)
    private var original = ConnectionState()
    @Before fun prepare() = runBlocking(Dispatchers.IO) { store.load(); original = store.state.value; store.change { ConnectionState() } }
    @After fun restore() = runBlocking(Dispatchers.IO) { store.change { original } }

    @Test fun serviceCardsAndActionsKeepDistinctColorsInDarkAndLightThemes() {
        var colors by mutableStateOf(DarkAppColors)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides colors) { AccountsConnectionsPage() } } }
        for ((name, theme) in listOf("dark" to DarkAppColors, "light" to LightAppColors)) {
            compose.runOnIdle { colors = theme }
            for ((tag, channel) in listOf("youtube" to 0, "lastfm" to 0, "spotify" to 1)) {
                val card = compose.onNodeWithTag("connection_card_$tag").performScrollTo().assertIsDisplayed()
                val image = card.captureToImage().asAndroidBitmap()
                val pixel = image.getPixel(image.width / 2, 2)
                if (channel == 0) assertTrue(android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel))
                else assertTrue(android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel))
                File(context.cacheDir, "connection-$tag-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                compose.onNodeWithTag("connect_$tag").assertExists()
            }
        }
    }

    @Test fun connectedYouTubeHidesConnectAndRestoresItAfterRemoval() {
        runBlocking { store.change { it.copy(youtube = listOf(YouTubeConnection("account", "Listener", "fixture")), selectedYouTube = "account") } }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors) { AccountsConnectionsPage() } } }
        compose.onNodeWithTag("connect_youtube").assertDoesNotExist()
        compose.onNodeWithText("Add account").assertDoesNotExist()
        compose.onNodeWithText("Library").assertIsDisplayed()
        runBlocking { store.change { it.copy(youtube = emptyList(), selectedYouTube = "") } }
        compose.onNodeWithTag("connect_youtube").assertIsDisplayed()
    }

    @Test fun directLoginShowsOnlyAccountFieldsAndPersistsTheSessionWithoutThePassword() {
        var visible by mutableStateOf(true)
        val initial = LastFmConnection(thresholdPercent = 75, nowPlaying = false)
        runBlocking { store.change { it.copy(lastFm = initial) } }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
            if (visible) LastFmConnectDialog(initial, { visible = false }) { username, password, previous ->
                assertEquals("listener", username)
                assertEquals("private-login-password", password)
                previous.copy(apiKey = "app-key", secret = "app-secret", session = "session-token", username = "Listener")
            }
        } } }
        compose.onNodeWithText("API key").assertDoesNotExist()
        compose.onNodeWithText("Shared secret").assertDoesNotExist()
        compose.onNodeWithTag("lastfm_password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("lastfm_sign_in").assertIsNotEnabled()
        compose.onNodeWithTag("lastfm_username").performTextInput(" listener ")
        compose.onNodeWithTag("lastfm_password").performTextInput("private-login-password")
        compose.onNodeWithTag("lastfm_sign_in").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { !visible }
        val reopened = ConnectionStore(context)
        runBlocking { reopened.load() }
        assertEquals(initial.copy(apiKey = "app-key", secret = "app-secret", session = "session-token", username = "Listener"), reopened.state.value.lastFm)
        compose.onNodeWithTag("lastfm_password").assertDoesNotExist()
    }

    @Test fun rejectedLoginKeepsTheExistingAccountAndShowsAnActionableError() {
        val initial = LastFmConnection("existing-key", "existing-secret", "existing-session", "Listener")
        runBlocking { store.change { it.copy(lastFm = initial) } }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
            LastFmConnectDialog(initial, {}) { _, _, _ -> throw LastFmException(4) }
        } } }
        compose.onNodeWithTag("lastfm_password").performTextInput("incorrect-password")
        compose.onNodeWithTag("lastfm_sign_in").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("lastfm_login_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Incorrect username or password. Please try again.").assertExists()
        assertEquals(initial, store.state.value.lastFm)
    }
}
