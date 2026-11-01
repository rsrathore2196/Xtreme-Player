package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = XtremeMusicApp::class)
class LaunchBootstrapTest {
    private fun initialized(app: XtremeMusicApp, name: String) =
        (app.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(app) as Lazy<*>).isInitialized()

    @Test fun applicationCreationDoesNotOpenPlaybackDatabaseOrLaunchPreferences() {
        val app = ApplicationProvider.getApplicationContext<XtremeMusicApp>()
        assertNull(app.launchSettings.value)
        assertNull(app.startupSettings)
        assertFalse(initialized(app, "database"))
        assertFalse(initialized(app, "repository"))
        assertFalse(initialized(app, "playbackManager"))
    }

    @Test fun activityBootstrapRestoresAppearanceAndProfileWithoutWaitingForOtherSubsystems() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XtremeMusicApp>()
        val profile = UserProfile(name = "Returning User", countryCode = "CA", country = "Canada",
            languages = listOf("English"), isOnboardingCompleted = true)
        withContext(Dispatchers.IO) {
            ThemePreferences.setThemeMode(app, AppThemeMode.DARK)
            UserProfilePreferences.saveUserProfile(app, profile)
        }
        app.prepareLaunchSettings()
        val snapshot = withTimeout(5_000) { app.launchSettings.filterNotNull().first() }
        assertEquals(AppThemeMode.DARK, snapshot.themeMode)
        assertEquals(profile, snapshot.profile)
        assertFalse(initialized(app, "database"))
        assertFalse(initialized(app, "playbackManager"))
        app.prepareLaunchSettings()
        assertSame("Warm Activity creation uses the loaded appearance snapshot", snapshot, app.launchSettings.value)
    }

    @Test fun freshInstallKeepsTheRealOnboardingState() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XtremeMusicApp>()
        app.prepareLaunchSettings()
        val snapshot = withTimeout(5_000) { app.launchSettings.filterNotNull().first() }
        assertFalse(snapshot.profile.isOnboardingCompleted)
        assertFalse(initialized(app, "playbackManager"))
    }
}
