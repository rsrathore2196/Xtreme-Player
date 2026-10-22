    // --- Haptic Feedback Settings ---
    fun isHapticsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTICS_ENABLED, false)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
    }
