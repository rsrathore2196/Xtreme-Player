package com.example.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.BuildConfig

// Global Single Source of Truth for Application Version
val APP_VERSION: String = BuildConfig.VERSION_NAME
val APP_VERSION_DISPLAY: String = "v$APP_VERSION"

enum class SettingsCategory(
    val title: String,
    private val rawSubtitle: String,
    val icon: ImageVector,
    val tag: String
) {
    THEMES_APP_UI(
        title = "Themes and App UI",
        rawSubtitle = "Dark Mode · Light Mode · System Theme · Player Layout",
        icon = Icons.Default.Palette,
        tag = "settings_cat_themes"
    ),
    MUSIC_PLAYBACK(
        title = "Music & Playback",
        rawSubtitle = "Audio Quality · Equalizer & DSP · Gapless Playback",
        icon = Icons.Default.MusicNote,
        tag = "settings_cat_music"
    ),
    OTHER_SETTINGS(
        title = "Other Settings",
        rawSubtitle = "Clear Cache · Text & UI Sizing · Proxy Privacy",
        icon = Icons.Default.Settings,
        tag = "settings_cat_others"
    ),
    BACKUP_RESTORE(
        title = "Backup & Restore",
        rawSubtitle = "Create Backup · Restore · Auto Backup · Backup Location",
        icon = Icons.Default.Restore,
        tag = "settings_cat_backup"
    ),
    ABOUT(
        title = "About",
        rawSubtitle = "Version $APP_VERSION_DISPLAY · Developer · Audio Engine",
        icon = Icons.Default.Info,
        tag = "settings_cat_about"
    );

    val subtitle: String
        get() = if (this == ABOUT) "Version $APP_VERSION_DISPLAY · Developer · Audio Engine" else rawSubtitle
}

