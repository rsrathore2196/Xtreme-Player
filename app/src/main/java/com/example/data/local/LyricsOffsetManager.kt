package com.example.data.local

import android.content.Context
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

    private var currentActiveTrackId: String? = null

    fun loadOffsetForTrack(context: Context, trackId: String?): Int {
        currentActiveTrackId = trackId
        val prefs = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        val offset = if (!trackId.isNullOrBlank() && prefs.contains(KEY_TRACK_OFFSET_PREFIX + trackId)) {
            prefs.getInt(KEY_TRACK_OFFSET_PREFIX + trackId, 0)
        } else {
            prefs.getInt(KEY_GLOBAL_OFFSET, 0)
        }
        val clamped = offset.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        _activeOffsetMs.value = clamped
        return clamped
    }

    fun isTrackSpecificOffset(context: Context, trackId: String?): Boolean {
        if (trackId.isNullOrBlank()) return false
        val prefs = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        return prefs.contains(KEY_TRACK_OFFSET_PREFIX + trackId)
    }

    fun getEffectiveOffset(context: Context, trackId: String?): Int {
        val prefs = ReactiveSettingsRepository.preferences(context, PREFS_NAME)
        val offset = if (!trackId.isNullOrBlank() && prefs.contains(KEY_TRACK_OFFSET_PREFIX + trackId)) {
            prefs.getInt(KEY_TRACK_OFFSET_PREFIX + trackId, 0)
        } else {
            prefs.getInt(KEY_GLOBAL_OFFSET, 0)
        }
        return offset.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
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
