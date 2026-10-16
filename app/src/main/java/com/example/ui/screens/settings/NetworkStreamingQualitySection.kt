package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.playback.AudioQualityCeiling
import com.example.playback.NetworkConnectionType
import com.example.playback.NetworkQualityMonitor
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassSwitchColors

/** Keep live network emissions inside this section, away from the rest of Audio Settings. */
@Composable
internal fun NetworkStreamingQualitySection() {
    val context = LocalContext.current.applicationContext
    val monitor = remember(context) { NetworkQualityMonitor.getInstance(context) }
    val prefs by monitor.preferences.collectAsStateWithLifecycle()
    val active by monitor.activeQualityCeiling.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    var editingWifi by remember { mutableStateOf<Boolean?>(null) }
    val protectHotspots = remember(monitor) { monitor::setProtectHotspots }

    Column(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(22.dp),
        elevation = 6.dp, translucency = 0.82f, tintAccent = true).padding(16.dp)) {
        Text("Network & Streaming Quality", color = colors.textPrimary,
            fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Surface(color = colors.primaryAccent.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.cardBorder)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(when (active.connectionType) {
                    NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET, NetworkConnectionType.METERED_WIFI -> Icons.Default.Wifi
                    NetworkConnectionType.CELLULAR -> Icons.Default.SignalCellularAlt
                    NetworkConnectionType.OFFLINE -> Icons.Default.WifiOff
                }, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    val profile = when (active.connectionType) {
                        NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET -> "Wi-Fi / Ethernet (Unmetered)"
                        NetworkConnectionType.METERED_WIFI -> if (prefs.treatMeteredWifiAsCellular) "Mobile Data / Hotspot" else "Wi-Fi (Metered)"
                        NetworkConnectionType.CELLULAR -> "Mobile Data"
                        NetworkConnectionType.OFFLINE -> "Offline — Mobile Data limit"
                    }
                    Text("Active Profile: $profile", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Currently capping streams at: ${active.effectiveCeiling.label}",
                        color = colors.textSecondary, fontSize = 11.5.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        NetworkQualityRow("Wi-Fi Streaming Quality", prefs.wifiQualityCeiling.label) { editingWifi = true }
        HorizontalDivider(color = colors.dividerColor)
        NetworkQualityRow("Mobile Data Streaming Quality", prefs.cellularQualityCeiling.label) { editingWifi = false }
        HorizontalDivider(color = colors.dividerColor)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Protect Mobile Hotspots", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Apply Mobile Data quality limit when connected to metered Wi-Fi",
                    color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            Switch(checked = prefs.treatMeteredWifiAsCellular, onCheckedChange = protectHotspots,
                colors = liquidGlassSwitchColors(colors))
        }
        Text("The existing audio-quality setting also applies. Limits apply to the next track. Current progressive playback continues uninterrupted. " +
            "If no stream fits, the lowest available rate is used.", color = colors.textMuted, fontSize = 11.sp, lineHeight = 15.sp)
    }

    editingWifi?.let { wifi ->
        val selected = if (wifi) prefs.wifiQualityCeiling else prefs.cellularQualityCeiling
        AlertDialog(
            onDismissRequest = { editingWifi = null }, containerColor = colors.bottomSheetBackground,
            titleContentColor = colors.textPrimary, textContentColor = colors.textSecondary,
            title = { Text(if (wifi) "Wi-Fi Streaming Quality" else "Mobile Data Streaming Quality") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    AudioQualityCeiling.entries.forEach { ceiling ->
                        Row(Modifier.fillMaxWidth().selectable(selected = ceiling == selected, role = Role.RadioButton,
                            onClick = {
                                if (wifi) monitor.setWifiCeiling(ceiling) else monitor.setCellularCeiling(ceiling)
                                editingWifi = null
                            }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = ceiling == selected, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = colors.primaryAccent,
                                    unselectedColor = colors.textMuted))
                            Column(Modifier.weight(1f)) {
                                Text(ceiling.label, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(ceiling.description, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingWifi = null }) { Text("Close", color = colors.primaryAccent) } }
        )
    }
}

@Composable
private fun NetworkQualityRow(title: String, value: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
    }
}
