package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.example.performance.AppDispatchers
import com.example.playback.OutputPrecision
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Immutable
data class LyricsSettings(
    val provider: String = "Auto",
    val textSizeSp: Int = 32,
    val ready: Boolean = false
)

@Immutable
data class PlaybackIntegrationSettings(
    val keepLyricsAwake: Boolean = false,
    val addonEnabled: Boolean = false,
    val addonUrl: String = "",
    val addonName: String = "",
    val sources: List<PlaybackSource> = emptyList()
) {
    fun sourceList(ceiling: com.example.playback.AudioQualityCeiling): List<PlaybackSource> = sources.ifEmpty {
        val legacy = if (addonUrl.isNotBlank()) listOf(PlaybackSource(PlaybackSource.addonId(addonUrl),
            PlaybackSourceKind.ADDON, addonName.ifBlank { "Custom server" }, addonEnabled, addonUrl)) else emptyList()
        legacy + PlaybackSource.defaults(ceiling)
    }
}

@Immutable
data class PlayerExperienceSettings(
    val dynamicAlbumGlow: Boolean = true,
    val highContrastTypography: Boolean = false,
    val crossfade: Boolean = false,
    val spatialAudio: Boolean = false,
    val skipSilence: Boolean = false,
    val outputPrecision: OutputPrecision = OutputPrecision.PCM_16
)

object OtherSettingsPreferences {
    private const val PREFS_NAME = "xtreme_other_settings"
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_HOST = "proxy_host"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_PROXY_TYPE = "proxy_type"
    private const val KEY_TEXT_SIZE_INDEX = "text_size_index"
    private const val KEY_UI_SIZE_INDEX = "ui_size_index"
    private const val KEY_LYRICS_PROVIDER = "lyrics_provider"
    private const val KEY_LYRICS_TEXT_SIZE = "lyrics_text_size_sp"

    val LYRICS_PROVIDERS: List<String> = listOf(
        "Auto",
        "PaxSenix",
        "BetterLyrics",
        "BetterLyrics Portato",
        "BiniLyrics",
        "LyricsPlus",
        "PaxSenix Spotify",
        "PaxSenix Musixmatch",
        "Lrc.Red",
        "Musixmatch",
        "KuGou",
        "LrcLib",
        "Unison",
        "Genius"
    )

    val LYRICS_PROVIDER_DESCRIPTIONS: Map<String, String> = mapOf(
        "Auto" to "Prioritizes PaxSenix, BetterLyrics & fast verified sources",
        "PaxSenix" to "Apple Music Style Syllable Lyrics",
        "BetterLyrics" to "Word by Word Syllable Lyrics",
        "BetterLyrics Portato" to "syllable-synchronized Alternative",
        "BiniLyrics" to "High-quality, word-by-word karaoke-style TTML",
        "LyricsPlus" to "Granular syllable-level tracking and timed",
        "PaxSenix Spotify" to "Authenticated Spotify search and lyrics",
        "PaxSenix: Spotify" to "Authenticated Spotify search and lyrics",
        "PaxSenix Musixmatch" to "Authenticated Musixmatch lyrics",
        "PaxSenix: Musixmatch" to "Authenticated Musixmatch lyrics",
        "Lrc.Red" to "Clean, standard line-by-line synchronized Lyrics",
        "Musixmatch" to "Comprehensive coverage for Global and New Releases",
        "KuGou" to "Eastern Asian Pop Music and Regional Lyrics",
        "LrcLib" to "Crowd-indexed line-synced lyrics.",
        "Unison" to "Host Manual Text or Time Corrections",
        "Genius" to "un-synced plain text and contextual fallback"
    )

    fun getProviderDescription(provider: String): String {
        return LYRICS_PROVIDER_DESCRIPTIONS[provider]
            ?: LYRICS_PROVIDER_DESCRIPTIONS.entries.firstOrNull {
                it.key.equals(provider, ignoreCase = true) ||
                it.key.replace(":", "").equals(provider.replace(":", ""), ignoreCase = true)
            }?.value
            ?: ""
    }

    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lyricsStarted = AtomicBoolean(false)
    private val lyricsState = MutableStateFlow(LyricsSettings())
    @Volatile
    private var lyricsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private const val KEY_TOTAL_PLAYED_COUNT = "total_played_count"
    private const val KEY_TOTAL_LISTENING_MINUTES = "total_listening_minutes"
    private const val KEY_CACHE_CLEARED_TIMESTAMP = "cache_cleared_timestamp"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_DYNAMIC_GLASS_ENABLED = "dynamic_glass_enabled"
    private const val KEY_ALBUM_GLOW = "player_dynamic_album_glow"
    private const val KEY_HIGH_CONTRAST = "player_high_contrast_typography"
    private const val KEY_CROSSFADE = "playback_crossfade_enabled"
    private const val KEY_SPATIAL_AUDIO = "playback_spatial_audio"
    private const val KEY_SKIP_SILENCE = "playback_skip_silence"
    private const val KEY_OUTPUT_PRECISION = "playback_output_precision"
    private const val KEY_KEEP_LYRICS_AWAKE = "keep_lyrics_screen_awake"
    private const val KEY_ADDON_ENABLED = "streaming_addon_enabled"
    private const val KEY_ADDON_URL = "streaming_addon_url"
    private const val KEY_ADDON_NAME = "streaming_addon_name"

    data class ScaleOption(
        val index: Int,
        val label: String,
        val scale: Float,
        val badge: String
    )

    val TEXT_SIZE_OPTIONS: List<ScaleOption> = listOf(
        ScaleOption(0, "Smallest", 0.72f, "-24%"),
        ScaleOption(1, "Extra Small", 0.8f, "-16%"),
        ScaleOption(2, "Small", 0.88f, "-8%"),
        ScaleOption(3, "Default", 0.95f, "Standard"),
        ScaleOption(4, "Large", 1.05f, "+10%"),
        ScaleOption(5, "Extra Large", 1.15f, "+20%"),
        ScaleOption(6, "Largest", 1.25f, "+30%")
    )
    const val DEFAULT_TEXT_SIZE_INDEX = 3

    val UI_SIZE_OPTIONS: List<ScaleOption> = listOf(
        ScaleOption(0, "Smallest", 0.76f, "-18%"),
        ScaleOption(1, "Extra Small", 0.82f, "-12%"),
        ScaleOption(2, "Small", 0.88f, "-6%"),
        ScaleOption(3, "Default", 0.94f, "Standard"),
        ScaleOption(4, "Large", 1.0f, "+6%"),
        ScaleOption(5, "Extra Large", 1.06f, "+12%"),
        ScaleOption(6, "Largest", 1.14f, "+20%")
    )
    const val DEFAULT_UI_SIZE_INDEX = 3

    private fun getPrefs(context: Context): SharedPreferences {
        return ReactiveSettingsRepository.preferences(context, PREFS_NAME)
    }

    fun observeLyricsSettings(context: Context): StateFlow<LyricsSettings> {
        if (lyricsStarted.compareAndSet(false, true)) {
            lyricsScope.launch {
                val app = context.applicationContext
                val prefs = getPrefs(app)
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == KEY_LYRICS_PROVIDER || key == KEY_LYRICS_TEXT_SIZE) {
                        publishLyricsState(prefs)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                lyricsListener = listener
                publishLyricsState(prefs)
            }
        }
        return lyricsState.asStateFlow()
    }

    private fun publishLyricsState(prefs: SharedPreferences) {
        val raw = prefs.getString(KEY_LYRICS_PROVIDER, "Auto") ?: "Auto"
        val provider = LYRICS_PROVIDERS.firstOrNull { it.equals(raw, ignoreCase = true) } ?: "Auto"
        val textSize = prefs.getInt(KEY_LYRICS_TEXT_SIZE, 32).coerceIn(18, 44)
        lyricsState.value = LyricsSettings(provider = provider, textSizeSp = textSize, ready = true)
    }

    fun getLyricsProvider(context: Context): String {
        val raw = getPrefs(context).getString(KEY_LYRICS_PROVIDER, "Auto") ?: "Auto"
        return LYRICS_PROVIDERS.firstOrNull { it.equals(raw, ignoreCase = true) } ?: "Auto"
    }

    fun setLyricsProvider(context: Context, provider: String) {
        val canonical = LYRICS_PROVIDERS.firstOrNull { it.equals(provider, ignoreCase = true) } ?: return
        val app = context.applicationContext
        observeLyricsSettings(app)
        lyricsScope.launch {
            getPrefs(app).edit().putString(KEY_LYRICS_PROVIDER, canonical).apply()
        }
    }

    fun setLyricsTextSize(context: Context, sizeSp: Int) {
        val app = context.applicationContext
        observeLyricsSettings(app)
        lyricsScope.launch {
            getPrefs(app).edit().putInt(KEY_LYRICS_TEXT_SIZE, sizeSp.coerceIn(18, 44)).apply()
        }
    }

    fun playbackIntegrations(context: Context): PlaybackIntegrationSettings {
        val prefs = getPrefs(context)
        return PlaybackIntegrationSettings(
            keepLyricsAwake = prefs.getBoolean(KEY_KEEP_LYRICS_AWAKE, false),
            addonEnabled = prefs.getBoolean(KEY_ADDON_ENABLED, false),
            addonUrl = prefs.getString(KEY_ADDON_URL, "") ?: "",
            addonName = prefs.getString(KEY_ADDON_NAME, "") ?: "",
            sources = PlaybackSourcesPreferences.snapshot(context)
        )
    }

    suspend fun loadPlaybackSources(context: Context) = PlaybackSourcesPreferences.load(context)
    fun savePlaybackSources(context: Context, sources: List<PlaybackSource>) = PlaybackSourcesPreferences.save(context, sources)
    fun observePlaybackIntegrations(context: Context): Flow<PlaybackIntegrationSettings> = merge(
        observeLegacyPlaybackIntegrations(context).map { Unit }, PlaybackSourcesPreferences.changes(context)
    ).conflate().map {
        val sources = loadPlaybackSources(context)
        playbackIntegrations(context).copy(sources = sources)
    }.distinctUntilChanged().flowOn(AppDispatchers.Io)

    private fun observeLegacyPlaybackIntegrations(context: Context): Flow<PlaybackIntegrationSettings> {
        val keys = setOf(KEY_KEEP_LYRICS_AWAKE, KEY_ADDON_ENABLED, KEY_ADDON_URL, KEY_ADDON_NAME)
        return callbackFlow {
            val app = context.applicationContext
            val prefs = getPrefs(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || keys.contains(key)) {
                    trySend(Unit)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }.conflate()
            .map { playbackIntegrations(context.applicationContext) }
            .distinctUntilChanged()
            .flowOn(AppDispatchers.Io)
    }

    fun setKeepLyricsAwake(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_KEEP_LYRICS_AWAKE, enabled).apply()
    }

    fun setStreamingAddonEnabled(context: Context, enabled: Boolean) {
        val hasUrl = playbackIntegrations(context).addonUrl.isNotBlank()
        getPrefs(context).edit().putBoolean(KEY_ADDON_ENABLED, enabled && hasUrl).apply()
    }

    fun saveStreamingAddon(context: Context, url: String, name: String) {
        val editor = getPrefs(context).edit()
            .putString(KEY_ADDON_URL, url)
            .putString(KEY_ADDON_NAME, name)
        if (url.isBlank()) {
            editor.putBoolean(KEY_ADDON_ENABLED, false)
        }
        editor.apply()
    }

    fun playerExperienceSettings(context: Context): PlayerExperienceSettings {
        val prefs = getPrefs(context)
        return PlayerExperienceSettings(
            dynamicAlbumGlow = prefs.getBoolean(KEY_ALBUM_GLOW, true),
            highContrastTypography = prefs.getBoolean(KEY_HIGH_CONTRAST, false),
            crossfade = prefs.getBoolean(KEY_CROSSFADE, false),
            spatialAudio = prefs.getBoolean(KEY_SPATIAL_AUDIO, false),
            skipSilence = prefs.getBoolean(KEY_SKIP_SILENCE, false),
            outputPrecision = OutputPrecision.fromStored(prefs.getString(KEY_OUTPUT_PRECISION, null))
        )
    }

    fun observePlayerExperience(context: Context): Flow<PlayerExperienceSettings> {
        val keys = setOf(
            KEY_ALBUM_GLOW,
            KEY_HIGH_CONTRAST,
            KEY_CROSSFADE,
            KEY_SPATIAL_AUDIO,
            KEY_SKIP_SILENCE,
            KEY_OUTPUT_PRECISION
        )
        return callbackFlow {
            val app = context.applicationContext
            val prefs = getPrefs(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || keys.contains(key)) {
                    trySend(Unit)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }.conflate()
            .map { playerExperienceSettings(context.applicationContext) }
            .distinctUntilChanged()
            .flowOn(AppDispatchers.Io)
    }

    fun setDynamicAlbumGlow(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALBUM_GLOW, enabled).apply()
    }

    fun setHighContrastTypography(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }

    fun setCrossfade(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CROSSFADE, enabled).apply()
    }

    fun setSpatialAudio(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SPATIAL_AUDIO, enabled).apply()
    }

    fun setSkipSilence(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SKIP_SILENCE, enabled).apply()
    }

    fun setOutputPrecision(context: Context, precision: OutputPrecision) {
        getPrefs(context).edit().putString(KEY_OUTPUT_PRECISION, precision.name).apply()
    }

    fun isDynamicGlassEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DYNAMIC_GLASS_ENABLED, true)
    }

    fun setDynamicGlassEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DYNAMIC_GLASS_ENABLED, enabled).apply()
    }

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

    // Default haptic feedback is OFF for new users (false)
    fun isHapticsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTICS_ENABLED, false)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
    }

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

    fun calculateCacheSizeBytes(context: Context): Long {
        var size = 0L
        try {
            size += getFolderSize(context.cacheDir)
            val ext = context.externalCacheDir
            if (ext != null) {
                size += getFolderSize(ext)
            }
        } catch (_: Exception) {}
        return size
    }

    private fun getFolderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        var length = 0L
        val list = file.listFiles() ?: return file.length()
        for (f in list) {
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
            getPrefs(context).edit().putLong(KEY_CACHE_CLEARED_TIMESTAMP, System.currentTimeMillis()).apply()
        } catch (_: Exception) {
            success = false
        }
        return success
    }

    private fun clearCacheDir(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { file ->
            try {
                if (file.isDirectory) deleteDir(file) else file.delete()
            } catch (_: Exception) {}
        }
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list() ?: return false
            for (child in children) {
                val ok = deleteDir(File(dir, child))
                if (!ok) return false
            }
            return dir.delete()
        }
        return dir?.delete() ?: false
    }
}
