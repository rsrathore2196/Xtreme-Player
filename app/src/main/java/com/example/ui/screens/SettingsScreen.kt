package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.playback.AudioEffectsState
import com.example.playback.AudioQuality
import com.example.playback.PlayerUiState
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.XtremeBackground
import com.example.ui.theme.XtremeBorder
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue

@Composable
fun SettingsScreen(
    playerUiState: PlayerUiState,
    effectsState: AudioEffectsState,
    onAudioQualitySelected: (AudioQuality) -> Unit,
    onCrystalClarityToggle: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var gaplessEnabled by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(XtremeGradients.ScreenBackground)
            .padding(horizontal = 16.dp)
            .testTag("settings_screen"),
        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top Header with Logo
        item {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_xtreme_logo),
                        contentDescription = "Logo",
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Settings & Audio Engine",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Text(
                    text = "Configure playback fidelity, crystal soundstage & app preferences",
                    fontSize = 13.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // Section 1: Audio Quality & Streaming Bitrate
        item {
            SettingsSection(
                title = "Streaming & Audio Quality",
                icon = Icons.Default.HighQuality
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AudioQuality.entries.forEach { quality ->
                        val isSelected = playerUiState.selectedQuality == quality
                        QualityOptionCard(
                            quality = quality,
                            isSelected = isSelected,
                            onSelect = { onAudioQualitySelected(quality) }
                        )
                    }
                }
            }
        }

        // Section 2: Audio Clarity & DSP Engine
        item {
            SettingsSection(
                title = "Crystal Clarity & DSP Engine",
                icon = Icons.Default.GraphicEq
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Crystal Clarity Toggle Card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (effectsState.crystalClarityEnabled) Color(0xFF14241C) else Color(0xFF141722)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (effectsState.crystalClarityEnabled) XtremeGreen.copy(alpha = 0.5f) else XtremeBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCrystalClarityToggle(!effectsState.crystalClarityEnabled) }
                            .testTag("crystal_clarity_toggle_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (effectsState.crystalClarityEnabled) XtremeGreen.copy(alpha = 0.2f) else Color(0xFF1E2232)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Waves,
                                    contentDescription = "Clarity",
                                    tint = if (effectsState.crystalClarityEnabled) XtremeGreen else TextMuted,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Crystal Clarity Engine",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = XtremeGreen.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "STUDIO DSP",
                                            color = XtremeGreen,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "32-bit floating point high-frequency harmonic exciter & spatial soundstage",
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Switch(
                                checked = effectsState.crystalClarityEnabled,
                                onCheckedChange = { onCrystalClarityToggle(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = XtremeGreen,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = Color(0xFF222638)
                                )
                            )
                        }
                    }

                    // Equalizer Launcher Card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141722)),
                        border = BorderStroke(1.dp, XtremeBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Equalizer",
                                        tint = XtremeCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Hardware 5-Band Equalizer",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = TextPrimary
                                    )
                                }

                                Text(
                                    text = effectsState.selectedPreset,
                                    color = XtremeGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(
                                text = "Real-time frequency curve, dynamic BassBoost (${effectsState.bassBoostStrength / 10}%), and 3D Virtualizer (${effectsState.virtualizerStrength / 10}%)",
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            // Quick Presets horizontal chips
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 6.dp)
                            ) {
                                items(effectsState.availablePresets) { preset ->
                                    val isSelected = effectsState.selectedPreset.equals(preset, ignoreCase = true)
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) XtremeGreen else Color(0xFF1A1E2E),
                                        border = BorderStroke(1.dp, if (isSelected) XtremeGreen else Color(0xFF2A3045)),
                                        modifier = Modifier.clickable { onSelectPreset(preset) }
                                    ) {
                                        Text(
                                            text = preset,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.Black else TextPrimary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = onOpenEqualizer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("open_equalizer_settings_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2538)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Equalizer,
                                    contentDescription = null,
                                    tint = XtremeGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Tune Equalizer Bands & Sliders",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 3: Playback & Streaming Mechanics
        item {
            SettingsSection(
                title = "Playback Engine",
                icon = Icons.Default.Speed
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141722)),
                    border = BorderStroke(1.dp, XtremeBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingToggleRow(
                            title = "Gapless & Crossfade Audio",
                            subtitle = "Zero-latency track transition buffering",
                            checked = gaplessEnabled,
                            onCheckedChange = { gaplessEnabled = it }
                        )

                        Divider(color = Color(0xFF222638), thickness = 1.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Audio Pipeline Precision",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "32-bit Float AudioTrack output with anti-clipping headroom",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF1B2E24)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = XtremeGreen,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Divider(color = Color(0xFF222638), thickness = 1.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Local Stutter-Free Cache",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "100MB LRU disk cache for instantaneous track playback",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF1C2232)
                            ) {
                                Text(
                                    text = "OPTIMIZED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = XtremeCyan,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section 4: About App Section (User-requested)
        item {
            SettingsSection(
                title = "About App",
                icon = Icons.Default.Info
            ) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141724)),
                    border = BorderStroke(1.dp, XtremeBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("about_app_card")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // App Name and Version with Official Logo
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_xtreme_logo),
                                contentDescription = "Xtreme Player Logo",
                                modifier = Modifier.size(52.dp)
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Xtreme Player",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 18.sp,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = XtremeLightBlue.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "v1.2.0",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = XtremeLightBlue,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Studio Fidelity Online Music Player",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        // App Description Paragraph
                        Text(
                            text = "Xtreme Player is a high-performance, studio-grade music player engineered for audiophiles. Delivering 320 kbps ultra-high definition streaming, real-time 5-band parametric equalization, dynamic bass enhancement, and 3D spatial virtualizer audio, all wrapped in a sleek, responsive interface.",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD2E1),
                            lineHeight = 19.sp
                        )

                        Divider(color = Color(0xFF222838), thickness = 1.dp)

                        // Developer Credit Card
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF10131E),
                            border = BorderStroke(1.dp, Color(0xFF232A3E)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1E2436)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Developer",
                                        tint = XtremeGreen,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Ravinder Singh",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Verified,
                                            contentDescription = "Verified Developer",
                                            tint = XtremeCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = "Lead Developer & Audio Architect",
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // Tech Specs & Engine
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Build: 1.2.0 • Media3 1.5 • Android 14+",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "High-Res 320k Audio",
                                fontSize = 11.sp,
                                color = XtremeGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = XtremeGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
        content()
    }
}

@Composable
private fun QualityOptionCard(
    quality: AudioQuality,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isSelected) XtremeGreen else XtremeBorder,
        label = "quality_border"
    )
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF132219) else Color(0xFF141722),
        label = "quality_bg"
    )

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = animatedBgColor),
        border = BorderStroke(1.dp, animatedBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("quality_option_${quality.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) XtremeGreen else TextMuted,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = quality.title,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) XtremeGreen.copy(alpha = 0.2f) else Color(0xFF1E2232)
                    ) {
                        Text(
                            text = quality.badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) XtremeGreen else TextMuted,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = quality.description,
                    fontSize = 12.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextMuted
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = XtremeGreen,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Color(0xFF222638)
            )
        )
    }
}
