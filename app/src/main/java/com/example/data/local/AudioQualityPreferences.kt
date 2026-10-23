package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.playback.AudioQuality
import com.example.playback.AudioQualityCeiling
import com.example.playback.NetworkAudioPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

object AudioQualityPreferences {
    private const val PREFS_NAME = "xtreme_audio_quality_prefs"
    private const val KEY_WIFI_CEILING = "wifi_quality_ceiling"
    private const val KEY_CELLULAR_CEILING = "cellular_quality_ceiling"
    private const val KEY_METERED_WIFI = "treat_metered_wifi_as_cellular"
    private const val KEY_AUDIO_ENHANCER = "prefer_youtube_opus"
    private const val KEY_RETURN_TO_YOUTUBE = "enhancer_return_to_youtube"
    private fun getPrefs(context: Context): SharedPreferences {
        return ReactiveSettingsRepository.preferences(context, PREFS_NAME)
    }

    /** The active network profile is the only quality preference; old saved IDs cannot cap it. */
    fun getSelectedQuality(context: Context): AudioQuality {
        val monitor = com.example.playback.NetworkQualityMonitor.getInstance(context)
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) monitor.currentAudioQuality
            else monitor.snapshot().effectiveCeiling.audioQuality
    }

    fun setSelectedQuality(context: Context, quality: AudioQuality) {
        val ceiling = when (quality) {
            AudioQuality.HI_RES_LOSSLESS -> AudioQualityCeiling.MAX
            AudioQuality.ULTRA_HD_320 -> AudioQualityCeiling.HIGH
            AudioQuality.HIGH_160 -> AudioQualityCeiling.NORMAL
            AudioQuality.MEDIUM_96 -> AudioQualityCeiling.DATA_SAVER
        }
        com.example.playback.NetworkQualityMonitor.getInstance(context).setActiveCeiling(ceiling)
    }

    fun getNetworkSettings(context: Context): NetworkAudioPreferences = getPrefs(context).let { prefs ->
        NetworkAudioPreferences(
            AudioQualityCeiling.fromName(prefs.getString(KEY_WIFI_CEILING, null), AudioQualityCeiling.MAX),
            AudioQualityCeiling.fromName(prefs.getString(KEY_CELLULAR_CEILING, null), AudioQualityCeiling.HIGH),
            prefs.getBoolean(KEY_METERED_WIFI, true)
        )
    }

    /** Listener callbacks only enqueue a signal; preference reads stay on IO. */
    fun observeNetworkSettings(context: Context) = callbackFlow {
        val prefs = getPrefs(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf(KEY_WIFI_CEILING, KEY_CELLULAR_CEILING, KEY_METERED_WIFI)) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { getNetworkSettings(context.applicationContext) }.flowOn(Dispatchers.IO)

    fun setWifiQualityCeiling(context: Context, ceiling: AudioQualityCeiling) {
        getPrefs(context).edit().putString(KEY_WIFI_CEILING, ceiling.name).apply()
    }
    fun setCellularQualityCeiling(context: Context, ceiling: AudioQualityCeiling) {
        getPrefs(context).edit().putString(KEY_CELLULAR_CEILING, ceiling.name).apply()
    }
    fun setTreatMeteredWifiAsCellular(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_METERED_WIFI, enabled).apply()
    }

    fun isAudioEnhancerEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_AUDIO_ENHANCER, false)
    fun returnToYouTube(context: Context): Boolean = getPrefs(context).getBoolean(KEY_RETURN_TO_YOUTUBE, false)
    private const val KEY_SOURCE_REQUEST_REVISION = "source_request_revision"
    private const val KEY_SOURCE_REQUEST_TRACK = "source_request_track"
    data class EnhancerSelection(val enabled: Boolean = false, val returnToYouTube: Boolean = false,
        val revision: Long = 0L, val trackId: String = "")
    fun enhancerSelection(context: Context): EnhancerSelection = getPrefs(context).let { prefs ->
        EnhancerSelection(prefs.getBoolean(KEY_AUDIO_ENHANCER, false), prefs.getBoolean(KEY_RETURN_TO_YOUTUBE, false),
            prefs.getLong(KEY_SOURCE_REQUEST_REVISION, 0L), prefs.getString(KEY_SOURCE_REQUEST_TRACK, "").orEmpty())
    }
    fun setAudioEnhancerEnabled(context: Context, enabled: Boolean, returnToYouTube: Boolean = false, trackId: String = "") {
        getPrefs(context).edit().putBoolean(KEY_AUDIO_ENHANCER, enabled)
            .putBoolean(KEY_RETURN_TO_YOUTUBE, !enabled && returnToYouTube)
            .putString(KEY_SOURCE_REQUEST_TRACK, trackId)
            .apply {
                // An explicit retry must emit even when the previous lookup left the preference on.
                if (trackId.isNotBlank()) putLong(KEY_SOURCE_REQUEST_REVISION,
                    getPrefs(context).getLong(KEY_SOURCE_REQUEST_REVISION, 0L) + 1L)
            }.apply()
    }
    /** Read both values together so an automatic On → Off request is one policy change. */
    fun observeEnhancerSelection(context: Context) = callbackFlow {
        val prefs = getPrefs(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_AUDIO_ENHANCER || key == KEY_RETURN_TO_YOUTUBE ||
                key == KEY_SOURCE_REQUEST_REVISION || key == KEY_SOURCE_REQUEST_TRACK) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener); trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { enhancerSelection(context) }.flowOn(Dispatchers.IO)
    fun observeAudioEnhancer(context: Context) = callbackFlow {
        val prefs = getPrefs(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_AUDIO_ENHANCER) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { isAudioEnhancerEnabled(context.applicationContext) }.flowOn(Dispatchers.IO)
}
