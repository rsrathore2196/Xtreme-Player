package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class StartupSettingsReuseTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun repeatedAndConcurrentConsumersShareTheSameLoadedSnapshot() = runBlocking {
        StartupSettingsCache(context).use { cache ->
            assertNull(cache.peek())
            val values = coroutineScope { List(8) { async(Dispatchers.IO) { cache.load() } }.awaitAll() }
            values.forEach { assertSame(values.first(), it) }
            assertSame(values.first(), cache.peek())
            assertSame(values.first(), cache.load())
        }
    }

    @Test fun themeAndProfileEditsInvalidateWarmReuseInsteadOfReturningOriginalStartupValues() = runBlocking {
        StartupSettingsCache(context).use { cache ->
            cache.load()
            ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT)
            val profile = UserProfile(name = "Changed", languages = listOf("Punjabi"),
                country = "Canada", countryCode = "CA", isOnboardingCompleted = true)
            UserProfilePreferences.saveUserProfile(context, profile)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(cache.peek())
            val updated = cache.load()
            assertEquals(AppThemeMode.LIGHT, updated.themeMode)
            assertEquals(profile, updated.profile)
            assertSame(updated, cache.peek())
        }
    }

    @Test fun unrelatedListeningStatisticsDoNotReopenTheSettingsSnapshot() = runBlocking {
        StartupSettingsCache(context).use { cache ->
            val loaded = cache.load()
            context.getSharedPreferences("xtreme_other_settings", Context.MODE_PRIVATE)
                .edit().putInt("total_played_count", 900).apply()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(loaded, cache.peek())
            OtherSettingsPreferences.setDynamicGlassEnabled(context, !loaded.dynamicGlass)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(cache.peek())
            assertEquals(!loaded.dynamicGlass, cache.load().dynamicGlass)
        }
    }

    @Test fun directBackupStylePreferenceWritesInvalidateTheRetainedTheme() = runBlocking {
        StartupSettingsCache(context).use { cache ->
            cache.load()
            context.getSharedPreferences("xtreme_theme_prefs", Context.MODE_PRIVATE)
                .edit().putString("selected_theme_mode", "dark").commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(cache.peek())
            assertEquals(AppThemeMode.DARK, cache.load().themeMode)
        }
    }
}
