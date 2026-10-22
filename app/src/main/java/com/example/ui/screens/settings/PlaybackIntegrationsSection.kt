package com.example.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.PlaybackSourcesPreferences
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassSwitchColors

/**
 * Playback integrations card in Other Settings.
 * Displays "Keep Screen Awake on Lyrics" and "Streaming Addons" as a single openable row
 * displaying layout name, currently selected source name, and an on/off switch.
 */
@Composable
internal fun PlaybackIntegrationsSection(
    onOpenSourcesScreen: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current

    val preferences = remember(context) { OtherSettingsPreferences.observePlaybackIntegrations(context) }
    val settings by preferences.collectAsStateWithLifecycle(initialValue = OtherSettingsPreferences.playbackIntegrations(context))

    val sourcesFlow = remember(context) { PlaybackSourcesPreferences.observeSources(context) }
    val sources by sourcesFlow.collectAsStateWithLifecycle(initialValue = PlaybackSourcesPreferences.getSources(context))

    val activeSourceName = remember(sources) {
        sources.firstOrNull { it.isEffectiveActive }?.name ?: "YouTube Music"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlassCard(
                colors,
                shape = RoundedCornerShape(22.dp),
                elevation = 6.dp,
                translucency = .82f,
                tintAccent = true
            )
            .padding(16.dp)
    ) {
        // Keep Screen Awake on Lyrics
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Keep Screen Awake on Lyrics",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Keep the display on while lyrics are open",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = settings.keepLyricsAwake,
                onCheckedChange = { OtherSettingsPreferences.setKeepLyricsAwake(context, it) },
                colors = liquidGlassSwitchColors(colors),
                modifier = Modifier.testTag("switch_keep_lyrics_awake")
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = colors.dividerColor)

        // Streaming Addons as a single openable row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSourcesScreen() }
                .padding(vertical = 4.dp)
                .testTag("row_streaming_addons")
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = "Streaming Addons",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = activeSourceName,
                    color = colors.textMuted,
                    fontSize = 12.sp
                )
            }

            Text(
                text = "›",
                fontSize = 22.sp,
                color = colors.textMuted,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}
