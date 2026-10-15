package com.example.data.local

import android.content.Context
import com.example.playback.AudioQuality

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
        fun read(context: Context) = StartupSettings(
            ThemePreferences.getThemeMode(context), ThemePreferences.getCustomThemeState(context),
            OtherSettingsPreferences.getTextSizeIndex(context), OtherSettingsPreferences.getUiSizeIndex(context),
            OtherSettingsPreferences.isDynamicGlassEnabled(context), UserProfilePreferences.getUserProfile(context),
            AudioQualityPreferences.getSelectedQuality(context)
        )
    }
}
