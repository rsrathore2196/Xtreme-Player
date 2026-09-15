package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.playback.AudioEffectsState
import com.example.playback.AudioQuality
import com.example.playback.PlayerUiState
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeLightBlue

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    playerUiState: PlayerUiState,
    effectsState: AudioEffectsState,
    isDarkMode: Boolean = true,
    onToggleDarkMode: () -> Unit = {},
    onAudioQualitySelected: (AudioQuality) -> Unit,
    onCrystalClarityToggle: (Boolean) -> Unit,
    onToggleEqualizer: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var gaplessEnabled by remember { mutableStateOf(true) }

    val defaultCardBorder = if (isDarkMode) Color(0xFF1E3554) else Color(0xFFCBD5E1)
    val defaultDivider = if (isDarkMode) Color(0xFF162A42) else Color(0xFFE2E8F0)
    val cardElevation = CardDefaults.cardElevation(defaultElevation = if (isDarkMode) 0.dp else 2.5.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(XtremeGradients.ScreenBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .testTag("settings_screen"),
            contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Header with Logo
            item {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_xtreme_logo),
                            contentDescription = "Logo",
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Settings",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = if (isDarkMode) XtremeLightBlue.copy(alpha = 0.15f) else Color(0xFFDBEAFE),
                                    border = BorderStroke(1.dp, if (isDarkMode) XtremeLightBlue.copy(alpha = 0.3f) else Color(0xFF93C5FD)),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "STUDIO ENGINE",
                                        color = if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.8.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Equalizer, streaming fidelity, DSP & app preferences",
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            // SECTION 1: Hardware Equalizer & Soundstage
            item {
                SettingsSection(
                    title = "Hardware Equalizer & Soundstage",
                    icon = Icons.Default.GraphicEq,
                    isDarkMode = isDarkMode
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Master Equalizer Card
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (effectsState.isEnabled) {
                                    if (isDarkMode) XtremeLightBlue.copy(alpha = 0.12f) else Color(0xFFEFF6FF)
                                } else {
                                    XtremeCard
                                }
                            ),
                            border = BorderStroke(
                                1.2.dp,
                                if (effectsState.isEnabled) {
                                    if (isDarkMode) XtremeLightBlue.copy(alpha = 0.6f) else Color(0xFF0284C7)
                                } else {
                                    defaultCardBorder
                                }
                            ),
                            elevation = cardElevation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("equalizer_master_card")
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Master Toggle Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (effectsState.isEnabled) {
                                                        if (isDarkMode) XtremeLightBlue.copy(alpha = 0.2f) else Color(0xFFDBEAFE)
                                                    } else {
                                                        if (isDarkMode) Color(0xFF16253B) else Color(0xFFF1F5F9)
                                                    }
                                                )
                                                .border(
                                                    BorderStroke(
                                                        1.dp,
                                                        if (effectsState.isEnabled) {
                                                            if (isDarkMode) XtremeLightBlue.copy(alpha = 0.4f) else Color(0xFF93C5FD)
                                                        } else {
                                                            if (isDarkMode) Color(0xFF1E3554) else Color(0xFFCBD5E1)
                                                        }
                                                    ),
                                                    CircleShape
                                                )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Waves,
                                                contentDescription = "Equalizer Master",
                                                tint = if (effectsState.isEnabled) {
                                                    if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7)
                                                } else {
                                                    if (isDarkMode) Color(0xFF4A7BA7) else Color(0xFF64748B)
                                                },
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .align(Alignment.Center)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Hardware Equalizer Master",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = if (effectsState.isEnabled) "ON: Audio DSP pipeline active" else "OFF: Hardware effects disabled",
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = effectsState.isEnabled,
                                        onCheckedChange = onToggleEqualizer,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = if (isDarkMode) XtremeLightBlue else Color.White,
                                            checkedTrackColor = if (isDarkMode) XtremeLightBlue.copy(alpha = 0.35f) else Color(0xFF0284C7),
                                            uncheckedThumbColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                                            uncheckedTrackColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0)
                                        ),
                                        modifier = Modifier.testTag("equalizer_master_switch")
                                    )
                                }

                                AnimatedVisibility(
                                    visible = effectsState.isEnabled,
                                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                                ) {
                                    Column(modifier = Modifier.padding(top = 14.dp)) {
                                        HorizontalDivider(color = defaultDivider, thickness = 1.dp)

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Preset Buttons
                                        Text(
                                            text = "Equalizer Preset",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            color = TextPrimary,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        FlowRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            effectsState.availablePresets.forEach { preset ->
                                                val isPresetSelected = effectsState.selectedPreset == preset
                                                Button(
                                                    onClick = { onSelectPreset(preset) },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isPresetSelected) {
                                                            if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7)
                                                        } else {
                                                            if (isDarkMode) Color(0xFF16253B) else Color(0xFFF8FAFC)
                                                        },
                                                        contentColor = if (isPresetSelected) {
                                                            if (isDarkMode) Color.Black else Color.White
                                                        } else {
                                                            if (isDarkMode) TextPrimary else Color(0xFF1E293B)
                                                        }
                                                    ),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isPresetSelected) {
                                                            if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7)
                                                        } else {
                                                            if (isDarkMode) Color(0xFF263D5C) else Color(0xFFCBD5E1)
                                                        }
                                                    ),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .testTag("preset_$preset")
                                                ) {
                                                    Text(
                                                        text = preset,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }

                                        Button(
                                            onClick = onOpenEqualizer,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7),
                                                contentColor = if (isDarkMode) Color.Black else Color.White
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                                .testTag("open_equalizer_button")
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Equalizer,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Open 5-Band Parametric EQ",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Audio Effect Options Card
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = XtremeCard),
                            border = BorderStroke(1.2.dp, defaultCardBorder),
                            elevation = cardElevation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("audio_effects_card")
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Crystal Clear Engine
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Crystal Clear Engine",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "8kHz Nyquist sharpening filter",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Switch(
                                        checked = effectsState.crystalClarityEnabled,
                                        onCheckedChange = onCrystalClarityToggle,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = if (isDarkMode) XtremeLightBlue else Color.White,
                                            checkedTrackColor = if (isDarkMode) XtremeLightBlue.copy(alpha = 0.35f) else Color(0xFF0284C7),
                                            uncheckedThumbColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                                            uncheckedTrackColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0)
                                        ),
                                        modifier = Modifier.testTag("crystal_clarity_switch")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 2: Audio Quality
            item {
                SettingsSection(
                    title = "Audio Quality & Streaming",
                    icon = Icons.Default.HighQuality,
                    isDarkMode = isDarkMode
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = XtremeCard),
                        border = BorderStroke(1.2.dp, defaultCardBorder),
                        elevation = cardElevation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("audio_quality_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Quality Options
                            AudioQuality.entries.forEach { quality ->
                                val isSelected = playerUiState.selectedQuality == quality
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        if (isDarkMode) XtremeLightBlue.copy(alpha = 0.12f) else Color(0xFFEFF6FF)
                                    } else {
                                        if (isDarkMode) Color.Transparent else Color(0xFFF8FAFC)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) {
                                            if (isDarkMode) XtremeLightBlue.copy(alpha = 0.6f) else Color(0xFF0284C7)
                                        } else {
                                            if (isDarkMode) Color(0xFF1A2E47) else Color(0xFFE2E8F0)
                                        }
                                    ),
                                    onClick = { onAudioQualitySelected(quality) },
                                    modifier = Modifier.fillMaxWidth().testTag("quality_option_${quality.name}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = quality.title,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = if (isSelected) {
                                                    if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7)
                                                } else {
                                                    TextPrimary
                                                }
                                            )
                                            Text(
                                                text = quality.description,
                                                fontSize = 11.sp,
                                                color = TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) {
                                                if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7)
                                            } else {
                                                if (isDarkMode) Color(0xFF4A7BA7) else Color(0xFF94A3B8)
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 3: Playback & Performance
            item {
                SettingsSection(
                    title = "Playback & Performance",
                    icon = Icons.Default.Speed,
                    isDarkMode = isDarkMode
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = XtremeCard),
                        border = BorderStroke(1.2.dp, defaultCardBorder),
                        elevation = cardElevation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playback_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Gapless Playback",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Seamless queue transitions for continuous audio",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = gaplessEnabled,
                                    onCheckedChange = { gaplessEnabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = if (isDarkMode) XtremeLightBlue else Color.White,
                                        checkedTrackColor = if (isDarkMode) XtremeLightBlue.copy(alpha = 0.35f) else Color(0xFF0284C7),
                                        uncheckedThumbColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                                        uncheckedTrackColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0)
                                    ),
                                    modifier = Modifier.testTag("gapless_switch")
                                )
                            }

                            HorizontalDivider(color = defaultDivider, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "16-bit PCM Hardware Audio",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "16-bit PCM AudioTrack hardware-compatible stream",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isDarkMode) Color(0xFF112D4E) else Color(0xFFEFF6FF),
                                    border = BorderStroke(1.dp, if (isDarkMode) Color(0xFF1E3A5F) else Color(0xFF93C5FD))
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = defaultDivider, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Local Stutter-Free Cache",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "100MB LRU disk cache for instantaneous track playback",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isDarkMode) Color(0xFF0F3245) else Color(0xFFF0FDF4),
                                    border = BorderStroke(1.dp, if (isDarkMode) Color(0xFF144D5A) else Color(0xFF86EFAC))
                                ) {
                                    Text(
                                        text = "OPTIMIZED",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDarkMode) XtremeCyan else Color(0xFF16A34A),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 4: App Appearance & Theme
            item {
                SettingsSection(
                    title = "App Theme & Appearance",
                    icon = Icons.Default.Palette,
                    isDarkMode = isDarkMode
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = XtremeCard),
                        border = BorderStroke(1.2.dp, defaultCardBorder),
                        elevation = cardElevation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("theme_settings_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Select App Color Scheme",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Choose between the midnight blue dark theme or daylight blue theme.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Night / Dark Mode Row
                                Surface(
                                    onClick = { if (!isDarkMode) onToggleDarkMode() },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isDarkMode) {
                                        XtremeLightBlue.copy(alpha = 0.15f)
                                    } else {
                                        Color(0xFFF8FAFC)
                                    },
                                    border = BorderStroke(
                                        width = if (isDarkMode) 2.dp else 1.2.dp,
                                        color = if (isDarkMode) {
                                            XtremeLightBlue
                                        } else {
                                            Color(0xFFCBD5E1)
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("theme_button_dark")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        Brush.linearGradient(
                                                            listOf(Color(0xFF000206), Color(0xFF0A1828))
                                                        )
                                                    )
                                                    .border(BorderStroke(1.dp, if (isDarkMode) Color(0xFF1E3A5F) else Color(0xFFCBD5E1)), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DarkMode,
                                                    contentDescription = null,
                                                    tint = if (isDarkMode) XtremeLightBlue else Color(0xFF64748B),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(14.dp))

                                            Column {
                                                Text(
                                                    text = "Night Mode",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (isDarkMode) XtremeLightBlue else TextPrimary
                                                )
                                                Text(
                                                    text = "Black & Night Blue gradient canvas",
                                                    fontSize = 12.sp,
                                                    color = TextMuted
                                                )
                                            }
                                        }

                                        Icon(
                                            imageVector = if (isDarkMode) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isDarkMode) XtremeLightBlue else Color(0xFF94A3B8),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                // Light Mode Row
                                Surface(
                                    onClick = { if (isDarkMode) onToggleDarkMode() },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (!isDarkMode) {
                                        Color(0xFF0284C7).copy(alpha = 0.12f)
                                    } else {
                                        Color.Transparent
                                    },
                                    border = BorderStroke(
                                        width = if (!isDarkMode) 2.dp else 1.2.dp,
                                        color = if (!isDarkMode) {
                                            Color(0xFF0284C7)
                                        } else {
                                            Color(0xFF1E3554)
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("theme_button_light")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        Brush.linearGradient(
                                                            listOf(Color(0xFFFFFFFF), Color(0xFFE2EDFB))
                                                        )
                                                    )
                                                    .border(BorderStroke(1.dp, if (!isDarkMode) Color(0xFF93C5FD) else Color(0xFF64748B)), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LightMode,
                                                    contentDescription = null,
                                                    tint = if (!isDarkMode) Color(0xFF0284C7) else Color(0xFF90A4AE),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(14.dp))

                                            Column {
                                                Text(
                                                    text = "Light Mode",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (!isDarkMode) Color(0xFF0284C7) else TextPrimary
                                                )
                                                Text(
                                                    text = "Crisp white & royal blue daylight palette",
                                                    fontSize = 12.sp,
                                                    color = TextMuted
                                                )
                                            }
                                        }

                                        Icon(
                                            imageVector = if (!isDarkMode) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (!isDarkMode) Color(0xFF0284C7) else Color(0xFF4A6572),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 5: About App
            item {
                SettingsSection(
                    title = "About App",
                    icon = Icons.Default.Info,
                    isDarkMode = isDarkMode
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = XtremeCard),
                        border = BorderStroke(1.2.dp, defaultCardBorder),
                        elevation = cardElevation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("about_app_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // App Name and Version with Logo
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_xtreme_logo),
                                    contentDescription = "Xtreme Player Logo",
                                    modifier = Modifier.size(48.dp)
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Xtreme Player",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 17.sp,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isDarkMode) XtremeLightBlue.copy(alpha = 0.2f) else Color(0xFFDBEAFE),
                                            border = BorderStroke(1.dp, if (isDarkMode) XtremeLightBlue.copy(alpha = 0.4f) else Color(0xFF93C5FD))
                                        ) {
                                            Text(
                                                text = "v1.4.0",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Studio Fidelity Online Music Player",
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }

                            // App Description Paragraph: Dark slate in light mode so it never disappears!
                            Text(
                                text = "Xtreme Player is a high-performance, studio-grade music player engineered for audiophiles. Delivering 320 kbps ultra-high definition streaming, real-time 5-band parametric equalization, dynamic bass enhancement, and 3D spatial virtualizer audio, all wrapped in a sleek, responsive interface.",
                                fontSize = 12.sp,
                                color = if (isDarkMode) Color(0xFFCBD2E1) else Color(0xFF334155),
                                lineHeight = 18.sp
                            )

                            HorizontalDivider(color = defaultDivider, thickness = 1.dp)

                            // Developer Info
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Developer",
                                    tint = if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Developer",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "RS Rathore",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }
                            }

                            // Verified Badge
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = "Verified",
                                    tint = if (isDarkMode) Color(0xFF10B981) else Color(0xFF059669),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Build Status",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "✓ Production Ready (v1.4.0)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isDarkMode) Color(0xFF10B981) else Color(0xFF059669)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    isDarkMode: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isDarkMode) XtremeLightBlue else Color(0xFF0284C7),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = TextPrimary
            )
        }
        content()
    }
}
