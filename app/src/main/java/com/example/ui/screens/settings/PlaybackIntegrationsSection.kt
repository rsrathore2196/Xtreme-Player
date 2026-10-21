package com.example.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.OtherSettingsPreferences
import com.example.data.remote.StreamingAddons
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassSwitchColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackIntegrationsSection() {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val preferences = remember(context) { OtherSettingsPreferences.observePlaybackIntegrations(context) }
    val settings by preferences.collectAsStateWithLifecycle(initialValue = OtherSettingsPreferences.playbackIntegrations(context))
    val scope = rememberCoroutineScope()
    var url by remember(settings.addonUrl) { mutableStateOf(settings.addonUrl) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(22.dp),
        elevation = 6.dp, translucency = .82f, tintAccent = true).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Keep Screen Awake on Lyrics", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Keep the display on while lyrics are open", color = colors.textMuted, fontSize = 11.sp)
            }
            Switch(settings.keepLyricsAwake, { OtherSettingsPreferences.setKeepLyricsAwake(context, it) },
                colors = liquidGlassSwitchColors(colors), modifier = Modifier.testTag("switch_keep_lyrics_awake"))
        }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = colors.dividerColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Streaming Addons", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(settings.addonName.ifBlank { "Connect your personal or community music server" },
                    color = colors.textMuted, fontSize = 11.sp)
            }
            Switch(settings.addonEnabled, { OtherSettingsPreferences.setStreamingAddonEnabled(context, it) },
                enabled = settings.addonUrl.isNotBlank(), colors = liquidGlassSwitchColors(colors),
                modifier = Modifier.testTag("switch_streaming_addon"))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = url, onValueChange = { url = it; message = null },
            label = { Text("Add-on server URL") }, placeholder = { Text("https://example.com/a/your-token") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("streaming_addon_url"))
        Spacer(Modifier.height(6.dp))
        Text("Compatible manifest, search and stream endpoints. Enabled add-ons are tried for new tracks; normal providers remain available.",
            color = colors.textMuted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            if (settings.addonUrl.isNotBlank()) TextButton(enabled = !busy, onClick = {
                OtherSettingsPreferences.saveStreamingAddon(context, "", ""); message = null
            }) { Text("Remove") }
            TextButton(enabled = url.isNotBlank() && !busy, modifier = Modifier.testTag("connect_streaming_addon"), onClick = {
                val requested = url
                busy = true; message = null
                scope.launch {
                    try {
                        val result = StreamingAddons.connect(requested)
                        if (url == requested) {
                            OtherSettingsPreferences.saveStreamingAddon(context, result.baseUrl, result.name)
                            error = false; message = "Connected to ${result.name}"
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = true; message = e.message ?: "Could not connect to this add-on" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Connecting…" else "Connect & Save") }
        }
        message?.let { Text(it, color = if (error) MaterialTheme.colorScheme.error else colors.primaryAccent, fontSize = 12.sp) }
    }
}
