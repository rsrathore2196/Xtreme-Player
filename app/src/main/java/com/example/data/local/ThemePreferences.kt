package com.example.data.local

import android.content.Context
import android.content.SharedPreferences

enum class AppThemeMode(
    val storageKey: String,
    val title: String,
    val subtitle: String
) {
    SYSTEM("system", "System", "Follows device"),
    DARK("dark", "Dark", "Midnight blue"),
    LIGHT("light", "Light", "Daylight clean");

    companion object {
        fun fromKey(key: String?): AppThemeMode {
            return entries.find { it.storageKey.equals(key, ignoreCase = true) } ?: SYSTEM
        }
    }
}

object ThemePreferences {
    private const val PREFS_NAME = "xtreme_theme_prefs"
    private const val KEY_THEME_MODE = "selected_theme_mode"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getThemeMode(context: Context): AppThemeMode {
        val prefs = getPrefs(context)
        val key = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.storageKey)
        return AppThemeMode.fromKey(key)
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
    }
}
