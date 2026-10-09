package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.playback.AudioQuality

object AudioQualityPreferences {
    private const val PREFS_NAME = "xtreme_audio_quality_prefs"
    private const val KEY_AUDIO_QUALITY_ID = "selected_audio_quality_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedQuality(context: Context): AudioQuality {
        val savedId = getPrefs(context).getString(KEY_AUDIO_QUALITY_ID, null)
        return if (savedId != null) {
            AudioQuality.fromId(savedId)
        } else {
            AudioQuality.ULTRA_HD_320
        }
    }

    fun setSelectedQuality(context: Context, quality: AudioQuality) {
        getPrefs(context).edit().putString(KEY_AUDIO_QUALITY_ID, quality.id).apply()
    }
}
