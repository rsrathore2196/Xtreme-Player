package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.example.performance.AppDispatchers

@Immutable
data class LyricsSettings(val provider: String = "Auto", val textSizeSp: Int = 34, val ready: Boolean = false)

@Immutable
data class PlayerExperienceSettings(
    val dynamicAlbumGlow: Boolean = true,
    val highContrastTypography: Boolean = false,
    val crossfade: Boolean = false
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object OtherSettingsPreferences {
    private const val PREFS_NAME = "xtreme_other_settings"

    // Proxy keys
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_HOST = "proxy_host"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_PROXY_TYPE = "proxy_type"

    // Text Size & App UI Size keys
    private const val KEY_TEXT_SIZE_INDEX = "text_size_index"
    private const val KEY_UI_SIZE_INDEX = "ui_size_index"
    private const val KEY_LYRICS_PROVIDER = "lyrics_provider"
    private const val KEY_LYRICS_TEXT_SIZE = "lyrics_text_size_sp"
    val LYRICS_PROVIDERS = listOf("Auto", "lrc.red", "BiniLyrics", "BetterLyrics", "PaxSenix", "LyricsPlus", "Unison",
        "BetterLyrics Portato", "PaxSenix: Spotify", "PaxSenix: Musixmatch", "Musixmatch", "KuGou",
        "YouTube captions", "LRCLIB", "YouTube Music", "Genius")
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lyricsStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lyricsState = MutableStateFlow(LyricsSettings())
    private var lyricsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun observeLyricsSettings(context: Context): StateFlow<LyricsSettings> {
        if (lyricsStarted.compareAndSet(false, true)) lyricsScope.launch {
            val prefs = getPrefs(context.applicationContext)
            fun publish() {
                lyricsState.value = LyricsSettings(prefs.getString(KEY_LYRICS_PROVIDER, "Auto")
                    ?.takeIf { it in LYRICS_PROVIDERS } ?: "Auto", prefs.getInt(KEY_LYRICS_TEXT_SIZE, 34).coerceIn(18, 44), true)
            }
            lyricsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_LYRICS_PROVIDER || key == KEY_LYRICS_TEXT_SIZE) publish()
            }.also(prefs::registerOnSharedPreferenceChangeListener)
            publish()
        }
        return lyricsState.asStateFlow()
    }
    fun getLyricsProvider(context: Context): String = getPrefs(context).getString(KEY_LYRICS_PROVIDER, "Auto")
        ?.takeIf { it in LYRICS_PROVIDERS } ?: "Auto"
    fun setLyricsProvider(context: Context, provider: String) {
        if (provider !in LYRICS_PROVIDERS) return
        val app = context.applicationContext
        observeLyricsSettings(app)
        lyricsScope.launch { getPrefs(app).edit().putString(KEY_LYRICS_PROVIDER, provider).apply() }
    }
    fun setLyricsTextSize(context: Context, sizeSp: Int) {
        val app = context.applicationContext
        observeLyricsSettings(app)
        lyricsScope.launch { getPrefs(app).edit().putInt(KEY_LYRICS_TEXT_SIZE, sizeSp.coerceIn(18, 44)).apply() }
    }

    // Usage Statistics keys
    private const val KEY_TOTAL_PLAYED_COUNT = "total_played_count"
    private const val KEY_TOTAL_LISTENING_MINUTES = "total_listening_minutes"
    private const val KEY_CACHE_CLEARED_TIMESTAMP = "cache_cleared_timestamp"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_DYNAMIC_GLASS_ENABLED = "dynamic_glass_enabled"
    private const val KEY_ALBUM_GLOW = "player_dynamic_album_glow"
    private const val KEY_HIGH_CONTRAST = "player_high_contrast_typography"
    private const val KEY_CROSSFADE = "playback_crossfade_enabled"

    fun playerExperienceSettings(context: Context): PlayerExperienceSettings = getPrefs(context).let {
        PlayerExperienceSettings(it.getBoolean(KEY_ALBUM_GLOW, true),
            it.getBoolean(KEY_HIGH_CONTRAST, false), it.getBoolean(KEY_CROSSFADE, false))
    }

    /** Reuse the preloaded memory preferences and ordered IO persistence, including restores. */
    fun observePlayerExperience(context: Context) = callbackFlow {
        val prefs = getPrefs(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_ALBUM_GLOW || key == KEY_HIGH_CONTRAST || key == KEY_CROSSFADE) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { playerExperienceSettings(context.applicationContext) }
        .distinctUntilChanged().flowOn(AppDispatchers.Io)

    fun setDynamicAlbumGlow(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALBUM_GLOW, enabled).apply()
    }
    fun setHighContrastTypography(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }
    fun setCrossfade(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CROSSFADE, enabled).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return ReactiveSettingsRepository.preferences(context, PREFS_NAME)
    }

    // --- Dynamic Blur & Liquid Glass Settings ---
    fun isDynamicGlassEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DYNAMIC_GLASS_ENABLED, true)
    }

    fun setDynamicGlassEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DYNAMIC_GLASS_ENABLED, enabled).apply()
    }

    // --- Proxy Settings ---
    fun isProxyEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROXY_ENABLED, false)
    }

    fun setProxyEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply()
    }

    fun getProxyHost(context: Context): String {
        return getPrefs(context).getString(KEY_PROXY_HOST, "127.0.0.1") ?: "127.0.0.1"
    }

    fun setProxyHost(context: Context, host: String) {
        getPrefs(context).edit().putString(KEY_PROXY_HOST, host.trim()).apply()
    }

    fun getProxyPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_PROXY_PORT, 8080)
    }

    fun setProxyPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_PROXY_PORT, port).apply()
    }

    fun getProxyType(context: Context): String {
        return getPrefs(context).getString(KEY_PROXY_TYPE, "HTTP") ?: "HTTP"
    }

    fun setProxyType(context: Context, type: String) {
        getPrefs(context).edit().putString(KEY_PROXY_TYPE, type).apply()
    }

    // --- Haptic Feedback Settings ---
    fun isHapticsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTICS_ENABLED, true)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
    }

    // --- Scale Option Data Class ---
    data class ScaleOption(
        val index: Int,
        val label: String,
        val scale: Float,
        val badge: String
    )

    // Text Size: 3 decrease options, 1 default (5% smaller), 3 increase options
    val TEXT_SIZE_OPTIONS = listOf(
        ScaleOption(0, "Smallest", 0.72f, "-24%"),
        ScaleOption(1, "Extra Small", 0.80f, "-16%"),
        ScaleOption(2, "Small", 0.88f, "-8%"),
        ScaleOption(3, "Default", 0.95f, "Standard"),
        ScaleOption(4, "Large", 1.05f, "+10%"),
        ScaleOption(5, "Extra Large", 1.15f, "+20%"),
        ScaleOption(6, "Largest", 1.25f, "+30%")
    )

    const val DEFAULT_TEXT_SIZE_INDEX = 3

    fun getTextSizeIndex(context: Context): Int {
        val idx = getPrefs(context).getInt(KEY_TEXT_SIZE_INDEX, DEFAULT_TEXT_SIZE_INDEX)
        return idx.coerceIn(0, TEXT_SIZE_OPTIONS.lastIndex)
    }

    fun setTextSizeIndex(context: Context, index: Int) {
        val safeIndex = index.coerceIn(0, TEXT_SIZE_OPTIONS.lastIndex)
        getPrefs(context).edit().putInt(KEY_TEXT_SIZE_INDEX, safeIndex).apply()
    }

    fun getTextScale(context: Context): Float {
        val index = getTextSizeIndex(context)
        return TEXT_SIZE_OPTIONS.getOrNull(index)?.scale ?: 0.95f
    }

    // App UI Size: 3 decrease options, 1 default (6% smaller), 3 increase options
    val UI_SIZE_OPTIONS = listOf(
        ScaleOption(0, "Smallest", 0.76f, "-18%"),
        ScaleOption(1, "Extra Small", 0.82f, "-12%"),
        ScaleOption(2, "Small", 0.88f, "-6%"),
        ScaleOption(3, "Default", 0.94f, "Standard"),
        ScaleOption(4, "Large", 1.00f, "+6%"),
        ScaleOption(5, "Extra Large", 1.06f, "+12%"),
        ScaleOption(6, "Largest", 1.14f, "+20%")
    )

    const val DEFAULT_UI_SIZE_INDEX = 3

    fun getUiSizeIndex(context: Context): Int {
        val idx = getPrefs(context).getInt(KEY_UI_SIZE_INDEX, DEFAULT_UI_SIZE_INDEX)
        return idx.coerceIn(0, UI_SIZE_OPTIONS.lastIndex)
    }

    fun setUiSizeIndex(context: Context, index: Int) {
        val safeIndex = index.coerceIn(0, UI_SIZE_OPTIONS.lastIndex)
        getPrefs(context).edit().putInt(KEY_UI_SIZE_INDEX, safeIndex).apply()
    }

    fun getUiScale(context: Context): Float {
        val index = getUiSizeIndex(context)
        return UI_SIZE_OPTIONS.getOrNull(index)?.scale ?: 0.94f
    }

    // --- Usage Statistics ---
    fun getTotalPlayedCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_PLAYED_COUNT, 24)
    }

    fun incrementPlayedCount(context: Context) {
        val current = getTotalPlayedCount(context)
        getPrefs(context).edit().putInt(KEY_TOTAL_PLAYED_COUNT, current + 1).apply()
    }

    fun getTotalListeningMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_LISTENING_MINUTES, 86)
    }

    fun addListeningMinutes(context: Context, minutes: Int) {
        val current = getTotalListeningMinutes(context)
        getPrefs(context).edit().putInt(KEY_TOTAL_LISTENING_MINUTES, current + minutes).apply()
    }

    // --- Cache Management ---
    fun calculateCacheSizeBytes(context: Context): Long {
        var size = 0L
        try {
            size += getFolderSize(context.cacheDir)
            context.externalCacheDir?.let {
                size += getFolderSize(it)
            }
        } catch (_: Exception) {}
        return size
    }

    private fun getFolderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        var length = 0L
        val files = file.listFiles() ?: return file.length()
        for (f in files) {
            length += if (f.isDirectory) getFolderSize(f) else f.length()
        }
        return length
    }

    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun clearCache(context: Context): Boolean {
        var success = true
        try {
            clearCacheDir(context.cacheDir)
            context.externalCacheDir?.let { clearCacheDir(it) }
            getPrefs(context).edit()
                .putLong(KEY_CACHE_CLEARED_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
            success = false
        }
        return success
    }

    private fun clearCacheDir(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { file ->
            try {
                if (file.isDirectory) {
                    deleteDir(file)
                } else {
                    file.delete()
                }
            } catch (_: Exception) {}
        }
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list() ?: return false
            for (child in children) {
                val success = deleteDir(File(dir, child))
                if (!success) return false
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        }
        return false
    }
}
