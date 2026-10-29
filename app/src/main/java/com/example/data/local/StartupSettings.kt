package com.example.data.local

import android.content.Context
import com.example.playback.AudioQuality
import android.content.SharedPreferences
import com.example.performance.AppDispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong

/** A single IO-loaded startup snapshot; constructors never open preference files. */
data class StartupSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val customTheme: CustomThemeState = CustomThemeState(),
    val textSizeIndex: Int = OtherSettingsPreferences.DEFAULT_TEXT_SIZE_INDEX,
    val uiSizeIndex: Int = OtherSettingsPreferences.DEFAULT_UI_SIZE_INDEX,
    val dynamicGlass: Boolean = true,
    val profile: UserProfile = UserProfile(),
    val quality: AudioQuality = AudioQuality.EXTREME_320
) {
    val textScale get() = OtherSettingsPreferences.TEXT_SIZE_OPTIONS[textSizeIndex].scale
    val uiScale get() = OtherSettingsPreferences.UI_SIZE_OPTIONS[uiSizeIndex].scale
    companion object {
        fun read(context: Context): StartupSettings {
            com.example.playback.AudioEffectsManager.initializePreferences(context)
            return StartupSettings(
                ThemePreferences.getThemeMode(context), ThemePreferences.getCustomThemeState(context),
                OtherSettingsPreferences.getTextSizeIndex(context), OtherSettingsPreferences.getUiSizeIndex(context),
                OtherSettingsPreferences.isDynamicGlassEnabled(context), UserProfilePreferences.getUserProfile(context),
                AudioQualityPreferences.getSelectedQuality(context)
            )
        }
    }
}

/** Process-local reuse, invalidated by the existing stores (including backup restore).
 * No preferences are opened by the constructor or the UI-safe peek operation. */
internal class StartupSettingsCache(private val context: Context) : AutoCloseable {
    private data class Snapshot(val revision: Long, val settings: StartupSettings)
    private val revision = AtomicLong()
    private val loading = Mutex()
    @Volatile private var snapshot: Snapshot? = null
    private var preferences: List<SharedPreferences> = emptyList()
    private var memoryPreferences: List<SharedPreferences> = emptyList()
    private val memoryListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        val index = memoryPreferences.indexOf(prefs)
        if (index != 2 && index != 3 || key == null ||
            index == 2 && key in appearanceKeys || index == 3 && key in networkKeys) revision.incrementAndGet()
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        // Listening statistics and lyrics settings do not invalidate appearance/profile.
        val relevant = when (prefs) {
            preferences.getOrNull(2) -> key == null || key in appearanceKeys
            preferences.getOrNull(3) -> key == null || key in networkKeys
            else -> true
        }
        if (relevant)
            revision.incrementAndGet()
    }

    fun peek(): StartupSettings? = snapshot?.takeIf { it.revision == revision.get() }?.settings

    suspend fun load(): StartupSettings = withContext(AppDispatchers.Io) {
        loading.withLock {
            if (preferences.isEmpty()) {
                preferences = listOf("xtreme_theme_prefs", "xtreme_user_profile_prefs",
                    "xtreme_other_settings", "xtreme_audio_quality_prefs").map {
                    context.applicationContext.getSharedPreferences(it, Context.MODE_PRIVATE)
                }
                preferences.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
                memoryPreferences = listOf("xtreme_theme_prefs", "xtreme_user_profile_prefs",
                    "xtreme_other_settings", "xtreme_audio_quality_prefs").map {
                    ReactiveSettingsRepository.preferences(context, it)
                }
                memoryPreferences.forEach { it.registerOnSharedPreferenceChangeListener(memoryListener) }
            }
            peek()?.let { return@withContext it }
            (context.applicationContext as? com.example.XtremeMusicApp)?.settingsRepository?.let {
                it.awaitPendingWrites()
                // Listener order is unspecified: a raw restore listener may run
                // before the adapter's notification. Read its loaded memory on IO.
                it.refreshExternalEdits()
            }
            while (true) {
                peek()?.let { return@withContext it }
                val started = revision.get()
                val loaded = com.example.performance.BackgroundWorkCoordinator.traceWork("Settings restore") {
                    StartupSettings.read(context)
                }
                if (started == revision.get()) {
                    snapshot = Snapshot(started, loaded)
                    return@withContext loaded
                }
                // A concurrent preference/restore edit supersedes the old read.
                yield()
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
    }

    override fun close() {
        preferences.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        memoryPreferences.forEach { it.unregisterOnSharedPreferenceChangeListener(memoryListener) }
    }

    private companion object {
        val appearanceKeys = setOf("text_size_index", "ui_size_index", "dynamic_glass_enabled")
        val networkKeys = setOf("wifi_quality_ceiling", "cellular_quality_ceiling", "treat_metered_wifi_as_cellular")
    }
}
