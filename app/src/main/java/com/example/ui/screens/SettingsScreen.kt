package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeBorder
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeLightBlue

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    playerUiState: PlayerUiState,
    effectsState: AudioEffectsState,
    onAudioQualitySelected: (AudioQuality) -> Unit,
    onCrystalClarityToggle: (Boolean) -> Unit,
    onToggleEqualizer: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var gaplessEnabled by remember { mutableStateOf(true) }

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
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
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
                                    color = XtremeLightBlue.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "STUDIO ENGINE",
                                        color = XtremeLightBlue,
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
                    icon = Icons.Default.GraphicEq
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Master Equalizer Card
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (effectsState.isEnabled) Color(0xFF0F2238) else Color(0xFF0C1726)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (effectsState.isEnabled) XtremeLightBlue.copy(alpha = 0.6f) else Color(0xFF1B3554)
                            ),
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
                                                    if (effectsState.isEnabled) XtremeLightBlue.copy(alpha = 0.2f)
                                                    else Color(0xFF16253B)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = "Equalizer",
                                                tint = if (effectsState.isEnabled) XtremeLightBlue else TextMuted,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "Audio Equalizer",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = TextPrimary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = if (effectsState.isEnabled) XtremeLightBlue.copy(alpha = 0.2f) else Color(0xFF1B2A3E),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = if (effectsState.isEnabled) "ACTIVE" else "OFF (DEFAULT)",
                                                        color = if (effectsState.isEnabled) XtremeLightBlue else TextMuted,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            Text(
                                                text = if (effectsState.isEnabled) "Preset: ${effectsState.selectedPreset}"
                                                else "Turn on to activate custom EQ curve",
                                                fontSize = 12.sp,
                                                color = if (effectsState.isEnabled) XtremeLightBlue else TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Switch(
                                        checked = effectsState.isEnabled,
                                        onCheckedChange = { onToggleEqualizer(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF031428),
                                            checkedTrackColor = XtremeLightBlue,
                                            uncheckedThumbColor = TextMuted,
                                            uncheckedTrackColor = Color(0xFF1A2B42)
                                        ),
                                        modifier = Modifier.testTag("settings_equalizer_switch")
                                    )
                                }

                                // Expanded Controls when Equalizer is ON
                                AnimatedVisibility(
                                    visible = effectsState.isEnabled,
                                    enter = fadeIn(tween(200)) + expandVertically(),
                                    exit = fadeOut(tween(150)) + shrinkVertically()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(top = 14.dp)
                                    ) {
                                        HorizontalDivider(color = Color(0xFF1B3554), thickness = 1.dp)

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Text(
                                            text = "QUICK PRESETS",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = TextMuted
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Presets FlowRow for responsive wrapping without cutoffs
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            effectsState.availablePresets.forEach { preset ->
                                                val isSelected = effectsState.selectedPreset.equals(preset, ignoreCase = true)
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isSelected) XtremeLightBlue else Color(0xFF14243B),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isSelected) XtremeLightBlue else Color(0xFF223C5E)
                                                    ),
                                                    modifier = Modifier.clickable { onSelectPreset(preset) }
                                                ) {
                                                    Text(
                                                        text = preset,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                                        color = if (isSelected) Color(0xFF031428) else TextPrimary,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // Sound FX Info Row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Dynamic Bass: ${effectsState.bassBoostStrength / 10}% • 3D Spatializer: ${effectsState.virtualizerStrength / 10}%",
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Button(
                                            onClick = onOpenEqualizer,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("open_equalizer_settings_button"),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF153356)
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Equalizer,
                                                contentDescription = null,
                                                tint = XtremeLightBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Tune 5-Band Equalizer & FX Sliders",
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                if (!effectsState.isEnabled) {
                                    Text(
                                        text = "Equalizer is turned off by default for pure raw playback. Switch on above to enable studio DSP, custom frequency curves, and bass enhancement.",
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.padding(top = 10.dp)
                                    )
                                }
                            }
                        }

                        // Crystal Clarity DSP Card
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (effectsState.crystalClarityEnabled) Color(0xFF0F253C) else Color(0xFF0C1726)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (effectsState.crystalClarityEnabled) XtremeCyan.copy(alpha = 0.5f) else Color(0xFF1B3554)
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
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (effectsState.crystalClarityEnabled) XtremeCyan.copy(alpha = 0.2f)
                                            else Color(0xFF16253B)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Waves,
                                        contentDescription = "Clarity",
                                        tint = if (effectsState.crystalClarityEnabled) XtremeCyan else TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Crystal Clarity Engine",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = XtremeCyan.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "DSP",
                                                color = XtremeCyan,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "High-frequency harmonic exciter & acoustic soundstage expansion",
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Switch(
                                    checked = effectsState.crystalClarityEnabled,
                                    onCheckedChange = { onCrystalClarityToggle(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF031428),
                                        checkedTrackColor = XtremeCyan,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = Color(0xFF1A2B42)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 2: Streaming Bitrate & Fidelity
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

            // SECTION 3: Playback Engine & Architecture
            item {
                SettingsSection(
                    title = "Playback Engine",
                    icon = Icons.Default.Speed
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1726)),
                        border = BorderStroke(1.dp, Color(0xFF1B3554)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            SettingToggleRow(
                                title = "Gapless & Crossfade Audio",
                                subtitle = "Zero-latency track transition buffering",
                                checked = gaplessEnabled,
                                onCheckedChange = { gaplessEnabled = it }
                            )

                            HorizontalDivider(color = Color(0xFF162A42), thickness = 1.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Audio Pipeline Precision",
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
                                    color = Color(0xFF112D4E)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = XtremeLightBlue,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color(0xFF162A42), thickness = 1.dp)

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
                                    color = Color(0xFF0F3245)
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

            // SECTION 4: About App
            item {
                SettingsSection(
                    title = "About App",
                    icon = Icons.Default.Info
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1726)),
                        border = BorderStroke(1.dp, Color(0xFF1B3554)),
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
                                            color = XtremeLightBlue.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                text = "v1.2.0",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = XtremeLightBlue,
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

                            // App Description Paragraph
                            Text(
                                text = "Xtreme Player is a high-performance, studio-grade music player engineered for audiophiles. Delivering 320 kbps ultra-high definition streaming, real-time 5-band parametric equalization, dynamic bass enhancement, and 3D spatial virtualizer audio, all wrapped in a sleek, responsive interface.",
                                fontSize = 12.sp,
                                color = Color(0xFFCBD2E1),
                                lineHeight = 18.sp
                            )

                            HorizontalDivider(color = Color(0xFF162A42), thickness = 1.dp)

                            // Developer Credit Card
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF07111D),
                                border = BorderStroke(1.dp, Color(0xFF182D46)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF14273E)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = "Developer",
                                            tint = XtremeLightBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Ravinder Singh",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = TextPrimary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.Verified,
                                                contentDescription = "Verified Developer",
                                                tint = XtremeLightBlue,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        Text(
                                            text = "Lead Developer & Audio Architect",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }

                            // Tech Specs in clean responsive vertical rows (avoids collision on any screen width!)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Architecture",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "Media3 1.5 • Android 14+",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Stream Fidelity",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "High-Res 320k Studio",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = XtremeLightBlue
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
                tint = XtremeLightBlue,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 14.sp,
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
        targetValue = if (isSelected) XtremeLightBlue else Color(0xFF1B3554),
        label = "quality_border"
    )
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF0F2238) else Color(0xFF0C1726),
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
                tint = if (isSelected) XtremeLightBlue else TextMuted,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = quality.title,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) XtremeLightBlue.copy(alpha = 0.2f) else Color(0xFF16253B)
                    ) {
                        Text(
                            text = quality.badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) XtremeLightBlue else TextMuted,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = quality.description,
                    fontSize = 11.sp,
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
                fontSize = 13.sp,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TextMuted
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF031428),
                checkedTrackColor = XtremeLightBlue,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Color(0xFF1A2B42)
            )
        )
    }
}
