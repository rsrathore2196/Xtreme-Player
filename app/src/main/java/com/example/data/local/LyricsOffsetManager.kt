package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import com.example.performance.AppDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object LyricsOffsetManager {
    const val MIN_LYRICS_OFFSET_MS = -5_000
    const val MAX_LYRICS_OFFSET_MS = 5_000
    const val STEP_MS = 100

    private const val PREFS_NAME = "lyrics_offset_preferences"
    private const val KEY_GLOBAL_OFFSET = "global_lyrics_offset_ms"
    private const val KEY_TRACK_OFFSET_PREFIX = "track_lyrics_offset_"

    private val _activeOffsetMs = MutableStateFlow(0)
    val activeOffsetMs: StateFlow<Int> = _activeOffsetMs.asStateFlow()

    @Volatile private var currentActiveTrackId: String? = null

    /** Join the IO preload and keep the active value in sync with restore/external edits. */
    fun observeOffsetForTrack(context: Context, trackId: String?) = callbackFlow {
        val prefs = withContext(AppDispatchers.Io) {
            ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        }
        currentActiveTrackId = trackId
        fun publish() {
            val offset = effectiveOffset(prefs, trackId)
            if (currentActiveTrackId == trackId) _activeOffsetMs.value = offset
            trySend(offset)
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_GLOBAL_OFFSET || key == KEY_TRACK_OFFSET_PREFIX + trackId) publish()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        publish()
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun loadOffsetForTrack(context: Context, trackId: String?): Int {
        currentActiveTrackId = trackId
        return getEffectiveOffset(context, trackId).also { _activeOffsetMs.value = it }
    }

    private fun effectiveOffset(prefs: SharedPreferences, trackId: String?): Int =
        (if (!trackId.isNullOrBlank() && prefs.contains(KEY_TRACK_OFFSET_PREFIX + trackId))
            prefs.getInt(KEY_TRACK_OFFSET_PREFIX + trackId, 0)
        else prefs.getInt(KEY_GLOBAL_OFFSET, 0)).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)

    fun usesGlobalOffset(context: Context, trackId: String?): Boolean {
        val prefs = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        return prefs.contains(KEY_GLOBAL_OFFSET) &&
            (trackId.isNullOrBlank() || !prefs.contains(KEY_TRACK_OFFSET_PREFIX + trackId))
    }

    fun isTrackSpecificOffset(context: Context, trackId: String?): Boolean {
        if (trackId.isNullOrBlank()) return false
        val prefs = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        return prefs.contains(KEY_TRACK_OFFSET_PREFIX + trackId)
    }

    fun getEffectiveOffset(context: Context, trackId: String?): Int {
        val prefs = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        return effectiveOffset(prefs, trackId)
    }

    fun updateCurrentOffset(
        context: Context,
        trackId: String?,
        offsetMs: Int,
        applyToAll: Boolean = false
    ) {
        val clamped = offsetMs.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        _activeOffsetMs.value = clamped
        val prefs = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        val editor = prefs.edit()
        if (applyToAll || trackId.isNullOrBlank()) {
            editor.putInt(KEY_GLOBAL_OFFSET, clamped)
            if (!trackId.isNullOrBlank()) {
                editor.remove(KEY_TRACK_OFFSET_PREFIX + trackId)
            }
        } else {
            editor.putInt(KEY_TRACK_OFFSET_PREFIX + trackId, clamped)
        }
        editor.apply()
    }

    fun resetOffset(context: Context, trackId: String?, applyToAll: Boolean = false) {
        updateCurrentOffset(context, trackId, 0, applyToAll)
    }

    fun formatOffset(offsetMs: Int): String {
        val seconds = offsetMs / 1000f
        return when {
            offsetMs == 0 -> "0.0 s"
            offsetMs > 0 -> String.format(Locale.US, "+%.1f s", seconds)
            else -> String.format(Locale.US, "%.1f s", seconds)
        }
    }

    fun adjustedLyricsPosition(positionMs: Long, offsetMs: Int): Long =
        (positionMs - offsetMs.toLong()).coerceAtLeast(0L)

    fun adjustedLyricsSeekTarget(lineTimeMs: Long, offsetMs: Int): Long =
        (lineTimeMs + offsetMs.toLong()).coerceAtLeast(0L)
}
