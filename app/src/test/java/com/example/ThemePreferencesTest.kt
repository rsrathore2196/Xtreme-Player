package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class ThemePreferencesTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() = runBlocking {
        withContext(Dispatchers.IO) { app.getSharedPreferences("xtreme_theme_prefs", 0).edit().clear().commit() }
        Unit
    }
    private fun client(repository: ReactiveSettingsRepository, dark: Boolean = false): Context {
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val configured = app.createConfigurationContext(config)
        return object : ContextWrapper(configured) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = repository.preferences(name)
        }
    }

    @Test fun lightAndDarkSelectionsSurviveModeChangesAndRepositoryRecreation() = runBlocking {
        val dark = ThemePresets.EmeraldNebula.toCustomThemeState()
        val light = ThemePresets.CherryBlossom.toCustomThemeState()
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            ThemePreferences.saveThemeSelection(context, AppThemeMode.DARK, dark)
            assertEquals(ThemePresets.CleanDay.toCustomThemeState(), ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT))
            ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, light)
            assertEquals(dark, ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            assertEquals(light, ThemePreferences.setThemeMode(context, AppThemeMode.LIGHT))
            repository.awaitPendingWrites()
        }
        ReactiveSettingsRepository(app).use { reopened ->
            reopened.preload()
            val context = client(reopened)
            assertEquals(light, ThemePreferences.getCustomThemeState(context))
            assertEquals(dark, ThemePreferences.setThemeMode(context, AppThemeMode.DARK))
            reopened.awaitPendingWrites()
        }
    }

    @Test fun systemModeUsesTheSavedPaletteForEachBrightnessWithoutOverwritingEither() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val day = client(repository)
            val night = client(repository, true)
            val dark = ThemePresets.VelvetNoir.toCustomThemeState()
            val light = ThemePresets.SageMist.toCustomThemeState()
            ThemePreferences.saveThemeSelection(day, AppThemeMode.DARK, dark)
            ThemePreferences.saveThemeSelection(day, AppThemeMode.LIGHT, light)
            ThemePreferences.setThemeMode(day, AppThemeMode.SYSTEM)
            assertEquals(light, ThemePreferences.getCustomThemeState(day))
            assertEquals(dark, ThemePreferences.getCustomThemeState(night))
            assertEquals(light, ThemePreferences.getCustomThemeState(day))
            assertEquals(AppThemeMode.SYSTEM, ThemePreferences.getThemeMode(night))
            repository.awaitPendingWrites()
        }
    }

    @Test fun unchangedLegacyPresetsUpgradeButEditedAndImportedPalettesKeepTheirValues() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            for (preset in ThemePresets.allPresets) {
                val legacy = checkNotNull(ThemePresets.legacyPreset(preset.id, preset.name)).toCustomThemeState()
                ThemePreferences.saveThemeSelection(context, if (preset.isDark) AppThemeMode.DARK else AppThemeMode.LIGHT, legacy)
                // Old backups predate both per-mode selections and palette versioning.
                repository.preferences("xtreme_theme_prefs").edit().remove("palette_version").apply()
                assertEquals(preset.toCustomThemeState(), ThemePreferences.getCustomThemeState(context))
                val edited = legacy.copy(canvasColorHex = "#121218", bgGradientColorsHex = listOf("#121218", "#121218"))
                ThemePreferences.saveCustomThemeState(context, edited)
                assertEquals(edited, ThemePreferences.getCustomThemeState(context))
                assertEquals(edited, ThemePresets.upgradeLegacy(edited))
            }
            val imported = ThemePresets.MinimalIvory.toCustomThemeState().copy(presetId = "imported-palette",
                currentThemeName = "Imported", canvasColorHex = "#FFF9F0")
            ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT, imported)
            assertEquals(imported, ThemePreferences.getCustomThemeState(context))
            assertNull(ThemePresets.findByName(""))
            repository.awaitPendingWrites()
        }
    }

    @Test fun selectingThemePublishesModeAndPaletteInOnePreferenceSnapshot() = runBlocking {
        ReactiveSettingsRepository(app).use { repository ->
            repository.preload()
            val context = client(repository)
            val palette = ThemePresets.CyberpunkNeon.toCustomThemeState()
            val prefs = repository.preferences("xtreme_theme_prefs")
            var mismatches = 0
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                if (ThemePreferences.getThemeMode(context) != AppThemeMode.DARK ||
                    ThemePreferences.getCustomThemeState(context) != palette) mismatches++
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            ThemePreferences.saveThemeSelection(context, AppThemeMode.DARK, palette)
            assertEquals(0, mismatches)
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            repository.awaitPendingWrites()
        }
    }
}
