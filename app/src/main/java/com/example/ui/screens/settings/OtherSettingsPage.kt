package com.example.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.contrastingContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.example.data.cache.SmartCacheManager
import com.example.data.local.OtherSettingsPreferences
import kotlinx.coroutines.launch
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.liquidGlassSwitchColors
import com.example.ui.theme.bouncyClickable

@Composable
fun OtherSettingsPage(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    onNestedPageChanged: (String?, String?, (() -> Boolean)?) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var showSourcesScreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val currentNavigationCallback by androidx.compose.runtime.rememberUpdatedState(onNestedPageChanged)

    androidx.compose.runtime.LaunchedEffect(showSourcesScreen) {
        if (showSourcesScreen) {
            currentNavigationCallback("Sources", "SOURCES_FULLSCREEN") {
                showSourcesScreen = false
                true
            }
        } else {
            currentNavigationCallback(null, null, null)
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { currentNavigationCallback(null, null, null) }
    }

    if (showSourcesScreen) {
        SourcesScreen(onNavigateBack = { showSourcesScreen = false })
        return
    }

    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val inputBg = appColors.cardBackgroundElevated
    val accentColor = appColors.primaryAccent
    val dividerColor = appColors.dividerColor
    val isAccentWhite = accentColor == Color.White || (appColors.isAmoled && accentColor == Color.White) || ((0.299 * accentColor.red + 0.587 * accentColor.green + 0.114 * accentColor.blue) > 0.70f)
    val pillActiveTextColor = contrastingContentColor(if (isAccentWhite) Color.White else accentColor)

    // Proxy state
    var isProxyEnabled by remember { mutableStateOf(OtherSettingsPreferences.isProxyEnabled(context)) }
    var proxyHost by remember { mutableStateOf(OtherSettingsPreferences.getProxyHost(context)) }
    var proxyPort by remember { mutableStateOf(OtherSettingsPreferences.getProxyPort(context).toString()) }
    var proxyType by remember { mutableStateOf(OtherSettingsPreferences.getProxyType(context)) }
    var proxySavedMessage by remember { mutableStateOf(false) }

    // Cache state backed by SmartCacheManager
    val coroutineScope = rememberCoroutineScope()
    val cacheStats by SmartCacheManager.stats.collectAsStateWithLifecycle()
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheClearedMessage by remember { mutableStateOf(false) }
    var cacheFeedbackText by remember { mutableStateOf("") }
    var isOptimizingCache by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        SmartCacheManager.configure(context)
        SmartCacheManager.refreshStats()
    }

    val lyricsPreferences = remember(context) { OtherSettingsPreferences.observeLyricsSettings(context) }
    val lyricsSettings by lyricsPreferences.collectAsStateWithLifecycle()
    var showLyricsProviders by remember { mutableStateOf(false) }
    var isHapticsEnabled by remember {
        mutableStateOf(OtherSettingsPreferences.isHapticsEnabled(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        PlaybackIntegrationsSection(onOpenSourcesScreen = { showSourcesScreen = true })
        Spacer(Modifier.height(16.dp))
        // 1. Smart Storage & Cache Management Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Smart Storage & Cache",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Smart auto-trim, quotas & background cleanup",
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Cache Usage Stat Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = inputBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "Total Cache Usage",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.textPrimary
                                )
                                Text(
                                    text = "Audio (${cacheStats.formattedAudio}) • Images (${cacheStats.formattedImage})",
                                    fontSize = 11.sp,
                                    color = appColors.textMuted
                                )
                            }

                            Text(
                                text = cacheStats.formattedTotal,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = accentColor
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Usage quota bar
                        val usagePercent = cacheStats.usagePercentage
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { usagePercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (usagePercent > 0.85f) Color(0xFFEF4444) else accentColor,
                            trackColor = appColors.cardBorder
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(usagePercent * 100).toInt()}% of ${cacheStats.formattedQuota} limit",
                                fontSize = 10.5.sp,
                                color = appColors.textMuted
                            )
                            Text(
                                text = "Free space: ${cacheStats.formattedFreeDisk}",
                                fontSize = 10.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Smart Auto-Trim Toggle
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = inputBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Smart Auto-Trim",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Removes least recently used audio when the cache reaches its limit",
                                fontSize = 10.5.sp,
                                color = appColors.textMuted
                            )
                        }

                        Switch(
                            checked = cacheStats.autoTrimEnabled,
                            onCheckedChange = { isEnabled ->
                                SmartCacheManager.setSmartAutoTrim(isEnabled)
                            },
                            colors = liquidGlassSwitchColors(appColors),
                            modifier = Modifier.testTag("switch_auto_trim")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Max Cache Limit",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textPrimary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )
                CacheQuotaSlider(cacheStats.maxQuotaBytes, SmartCacheManager::setQuota)

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons: Smart Optimize & Clear Cache
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Smart Optimize Button
                    OutlinedButton(
                        onClick = {
                            com.example.util.AppHaptics.performTap(context)
                            isOptimizingCache = true
                            cacheClearedMessage = false
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(500)
                                val result = SmartCacheManager.smartOptimize(context)
                                SmartCacheManager.refreshStats()
                                isOptimizingCache = false
                                cacheFeedbackText = result.second
                                cacheClearedMessage = true
                                com.example.util.AppHaptics.performTap(context)
                                android.widget.Toast.makeText(context, result.second, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = accentColor
                        ),
                        enabled = !isOptimizingCache,
                        modifier = Modifier.weight(1f).height(44.dp).testTag("button_smart_optimize")
                    ) {
                        if (isOptimizingCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = accentColor
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOptimizingCache) "Optimizing..." else "Smart Optimize",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Clear All Cache Button
                    OutlinedButton(
                        onClick = { showClearCacheDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFEF4444)
                        ),
                        modifier = Modifier.weight(1f).height(44.dp).testTag("button_clear_cache")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clear Cache",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (cacheClearedMessage) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (cacheFeedbackText.isNotEmpty()) "✓ $cacheFeedbackText" else "✓ Cache successfully cleared!",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxWidth().liquidGlassCard(appColors,
            shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth().clickable { showLyricsProviders = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Lyrics Provider", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = appColors.textPrimary)
                        Text(lyricsSettings.provider, fontSize = 12.sp, color = appColors.textSecondary)
                    }
                    Text("›", fontSize = 24.sp, color = appColors.textMuted)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = dividerColor)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lyrics Text Size", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = appColors.textPrimary)
                        Text("Adjust lyrics only", fontSize = 11.5.sp, color = appColors.textMuted)
                    }
                    IconButton(onClick = { OtherSettingsPreferences.setLyricsTextSize(context, lyricsSettings.textSizeSp - 2) },
                        enabled = lyricsSettings.textSizeSp > 18) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease lyrics text size", tint = appColors.textPrimary)
                    }
                    Text("${lyricsSettings.textSizeSp} sp", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
                    IconButton(onClick = { OtherSettingsPreferences.setLyricsTextSize(context, lyricsSettings.textSizeSp + 2) },
                        enabled = lyricsSettings.textSizeSp < 44) {
                        Icon(Icons.Default.Add, contentDescription = "Increase lyrics text size", tint = appColors.textPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 3.5. Haptic Feedback Setting Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
                .testTag("card_haptic_feedback")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Vibration,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                text = "Haptic Feedback",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Tactile vibration response on taps & controls",
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }

                    Switch(
                        checked = isHapticsEnabled,
                        onCheckedChange = {
                            isHapticsEnabled = it
                            OtherSettingsPreferences.setHapticsEnabled(context, it)
                            if (it) {
                                com.example.util.AppHaptics.performTap(context)
                            }
                        },
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_haptics_enabled")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Proxy Settings Card (Privacy & Custom Network - moved to last & bottom)
        ProxySettingsCard(
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = accentColor,
            dividerColor = dividerColor,
            inputBg = inputBg,
            isProxyEnabled = isProxyEnabled,
            onProxyEnabledChange = {
                isProxyEnabled = it
                OtherSettingsPreferences.setProxyEnabled(context, it)
            },
            proxyType = proxyType,
            onProxyTypeChange = {
                proxyType = it
                OtherSettingsPreferences.setProxyType(context, it)
            },
            proxyHost = proxyHost,
            onProxyHostChange = { proxyHost = it },
            proxyPort = proxyPort,
            onProxyPortChange = { input ->
                if (input.all { it.isDigit() } && input.length <= 5) {
                    proxyPort = input
                }
            },
            onSaveProxy = {
                val portInt = proxyPort.toIntOrNull() ?: 8080
                OtherSettingsPreferences.setProxyHost(context, proxyHost)
                OtherSettingsPreferences.setProxyPort(context, portInt)
                OtherSettingsPreferences.setProxyType(context, proxyType)
                proxySavedMessage = true
            },
            proxySavedMessage = proxySavedMessage
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showLyricsProviders) {
        com.example.ui.components.LyricsProviderSelectionPopup(
            currentProvider = lyricsSettings.provider,
            onSelectProvider = { provider ->
                OtherSettingsPreferences.setLyricsProvider(context, provider)
            },
            onDismissRequest = { showLyricsProviders = false },
            appColors = appColors
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            containerColor = appColors.cardBackground,
            title = {
                Text(
                    text = "Clear Application Cache?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "This will clear temporary album art images and stream buffers (${cacheStats.formattedTotal}). Your playlists and favorite tracks will not be affected.",
                    fontSize = 13.sp,
                    color = appColors.textMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val freed = SmartCacheManager.clearAllCache()
                            cacheFeedbackText = "Cleared ${SmartCacheManager.formatBytes(freed)}"
                            cacheClearedMessage = true
                            showClearCacheDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    )
                ) {
                    Text("Clear", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel", color = appColors.textSecondary)
                }
            }
        )
    }
}

/**
 * 4. Proxy Settings Card (Privacy & Custom Network)
 * Moved to the bottom of the Other Settings menu
 */
@Composable
private fun ProxySettingsCard(
    cardBg: Color,
    cardBorder: Color,
    accentColor: Color,
    dividerColor: Color,
    inputBg: Color,
    isProxyEnabled: Boolean,
    onProxyEnabledChange: (Boolean) -> Unit,
    proxyType: String,
    onProxyTypeChange: (String) -> Unit,
    proxyHost: String,
    onProxyHostChange: (String) -> Unit,
    proxyPort: String,
    onProxyPortChange: (String) -> Unit,
    onSaveProxy: () -> Unit,
    proxySavedMessage: Boolean,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = "Proxy Settings",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Route network traffic through private proxy",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Switch(
                    checked = isProxyEnabled,
                    onCheckedChange = onProxyEnabledChange,
                    colors = liquidGlassSwitchColors(appColors),
                    modifier = Modifier.testTag("switch_proxy_enabled")
                )
            }

            if (isProxyEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = dividerColor, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Proxy Protocol Selection (HTTP vs SOCKS5)
                Text(
                    text = "PROXY PROTOCOL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = appColors.textMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("HTTP", "SOCKS5").forEach { type ->
                        val isSelected = proxyType.equals(type, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.18f) else inputBg,
                            border = BorderStroke(1.dp, if (isSelected) accentColor else cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onProxyTypeChange(type) }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = type,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) accentColor else appColors.textPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Proxy Host
                Text(
                    text = "Proxy Server Host",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = proxyHost,
                    onValueChange = onProxyHostChange,
                    placeholder = { Text("127.0.0.1 or proxy.example.com", fontSize = 12.sp, color = appColors.textMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputBg,
                        unfocusedContainerColor = inputBg,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = appColors.textPrimary,
                        unfocusedTextColor = appColors.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_proxy_host")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Proxy Port
                Text(
                    text = "Proxy Server Port",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = onProxyPortChange,
                    placeholder = { Text("8080", fontSize = 12.sp, color = appColors.textMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputBg,
                        unfocusedContainerColor = inputBg,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = appColors.textPrimary,
                        unfocusedTextColor = appColors.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_proxy_port")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Save Proxy Button
                val isProxyBtnLight = accentColor == Color.White || (appColors.isAmoled && accentColor == Color.White) || ((0.299 * accentColor.red + 0.587 * accentColor.green + 0.114 * accentColor.blue) > 0.65f)
                val proxyBtnBg = if (isProxyBtnLight) Color.White else accentColor
                val proxyBtnTextColor = contrastingContentColor(proxyBtnBg)
                Button(
                    onClick = onSaveProxy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = proxyBtnBg,
                        contentColor = proxyBtnTextColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("btn_save_proxy")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = proxyBtnTextColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Save Proxy Configuration",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = proxyBtnTextColor
                    )
                }

                if (proxySavedMessage) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "✓ Proxy configuration applied successfully",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }
        }
    }
}

/** Drag locally; persistence and cache resizing happen once when the gesture finishes. */
@Composable
internal fun CacheQuotaSlider(quotaBytes: Long, onQuotaSelected: (Long) -> Unit) {
    val colors = LocalAppColors.current
    val megabyte = 1024L * 1024L
    var selection by remember(quotaBytes) {
        androidx.compose.runtime.mutableFloatStateOf((SmartCacheManager.normalizeQuota(quotaBytes) / megabyte).toFloat())
    }
    Surface(shape = RoundedCornerShape(16.dp), color = colors.cardBackgroundElevated,
        border = BorderStroke(1.dp, colors.cardBorder)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recently played audio & artwork", fontSize = 11.sp, color = colors.textMuted,
                    modifier = Modifier.weight(1f).padding(end = 8.dp))
                Text(SmartCacheManager.formatBytes(selection.toLong() * megabyte),
                    color = colors.primaryAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.testTag("cache_limit_value"))
            }
            androidx.compose.material3.Slider(
                value = selection,
                onValueChange = { selection = it },
                onValueChangeFinished = { onQuotaSelected(SmartCacheManager.normalizeQuota(selection.toLong() * megabyte)) },
                valueRange = 512f..8192f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = colors.primaryAccent, activeTrackColor = colors.primaryAccent,
                    inactiveTrackColor = colors.cardBorder),
                modifier = Modifier.fillMaxWidth().testTag("slider_cache_quota"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("512 MB", color = colors.textMuted, fontSize = 10.sp)
                Text("8 GB", color = colors.textMuted, fontSize = 10.sp)
            }
        }
    }
}
