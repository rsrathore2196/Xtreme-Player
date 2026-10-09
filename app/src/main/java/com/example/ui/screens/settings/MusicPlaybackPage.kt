package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.playback.AudioEffectsState
import com.example.playback.AudioQuality
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.liquidGlassSwitchColors
import com.example.ui.theme.bouncyClickable

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicPlaybackPage(
    selectedQuality: AudioQuality = AudioQuality.ULTRA_HD_320,
    effectsState: AudioEffectsState,
    isDarkMode: Boolean,
    onAudioQualitySelected: (AudioQuality) -> Unit,
    onCrystalClarityToggle: (Boolean) -> Unit,
    onToggleEqualizer: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent
    val dividerColor = appColors.dividerColor
    val textPrimary = appColors.textPrimary
    val textMuted = appColors.textMuted
    var gaplessEnabled by remember { mutableStateOf(true) }

    val presets = listOf("Flat", "Bass Boost", "Studio Master", "Vocal Enhance", "Electronic", "Rock", "Acoustic")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 1. Audio Quality & Streaming Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.HighQuality,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Audio Quality & Bitrate",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "Lossless streaming & Nyquist filter tuning",
                            fontSize = 11.5.sp,
                            color = textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                AudioQualityOption(
                    title = "Hi-Res Lossless • 24-bit/192 kHz (Studio Master)",
                    subtitle = "Highest Studio Grade Quality FLAC Audio",
                    isSelected = selectedQuality == AudioQuality.HI_RES_LOSSLESS,
                    isDarkMode = isDarkMode,
                    accentColor = accentColor,
                    onClick = { onAudioQualitySelected(AudioQuality.HI_RES_LOSSLESS) }
                )

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                AudioQualityOption(
                    title = "Ultra HD • 320 kbps (Limitless)",
                    subtitle = "Audiophile Grade High Quality Sound Reproduction",
                    isSelected = selectedQuality == AudioQuality.ULTRA_HD_320,
                    isDarkMode = isDarkMode,
                    accentColor = accentColor,
                    onClick = { onAudioQualitySelected(AudioQuality.ULTRA_HD_320) }
                )

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                AudioQualityOption(
                    title = "High • 160 kbps (Balanced)",
                    subtitle = "Optimized clear sound with fast data streaming",
                    isSelected = selectedQuality == AudioQuality.HIGH_160,
                    isDarkMode = isDarkMode,
                    accentColor = accentColor,
                    onClick = { onAudioQualitySelected(AudioQuality.HIGH_160) }
                )

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                AudioQualityOption(
                    title = "Medium • 96 kbps (Data Saver)",
                    subtitle = "Lowest network bandwidth usage",
                    isSelected = selectedQuality == AudioQuality.MEDIUM_96,
                    isDarkMode = isDarkMode,
                    accentColor = accentColor,
                    onClick = { onAudioQualitySelected(AudioQuality.MEDIUM_96) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Hardware Equalizer & Soundstage Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Equalizer,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Hardware Equalizer & DSP",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "5-Band parametric filter & soundstage",
                            fontSize = 11.5.sp,
                            color = textMuted,
                            lineHeight = 15.sp
                        )
                    }

                    Switch(
                        checked = effectsState.isEnabled,
                        onCheckedChange = { onToggleEqualizer(it) },
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_equalizer_master")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ACTIVE PRESET",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = textMuted
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    presets.forEach { preset ->
                        val isSelected = effectsState.selectedPreset.equals(preset, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .liquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(10.dp),
                                    isActive = isSelected,
                                    elevation = if (isSelected) 4.dp else 2.dp
                                )
                                .bouncyClickable { onSelectPreset(preset) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = preset,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) appColors.onPrimaryAccent else textPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Launch Full Equalizer Button with Liquid Glass styling
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .liquidGlassButton(appColors, shape = RoundedCornerShape(14.dp), elevation = 4.dp, isActive = true)
                        .bouncyClickable { onOpenEqualizer() }
                        .testTag("button_open_full_equalizer"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open 5-Band Hardware Equalizer",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 14.dp))

                // Crystal Clear Engine Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Waves,
                        contentDescription = null,
                        tint = if (effectsState.crystalClarityEnabled) accentColor else textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Crystal Clear Audio Engine",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Nyquist sharpening for crisp vocals & percussion",
                            fontSize = 11.sp,
                            color = textMuted,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = effectsState.crystalClarityEnabled,
                        onCheckedChange = { onCrystalClarityToggle(it) },
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_crystal_clarity")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Playback & Performance Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Playback & Performance",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "Audio engine buffer & pipeline configurations",
                            fontSize = 11.5.sp,
                            color = textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Gapless Playback Engine",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )
                        Text(
                            text = "Eliminates silence between consecutive tracks",
                            fontSize = 11.sp,
                            color = textMuted
                        )
                    }
                    Switch(
                        checked = gaplessEnabled,
                        onCheckedChange = { gaplessEnabled = it },
                        colors = liquidGlassSwitchColors(appColors),
                        modifier = Modifier.testTag("switch_gapless_playback")
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "16-bit PCM Hardware Output",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )
                        Text(
                            text = "Direct hardware rendering bypass for studio DAC",
                            fontSize = 11.sp,
                            color = textMuted
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "ACTIVE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Local Stutter-Free LRU Cache",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )
                        Text(
                            text = "Predictive audio pre-buffering to prevent network lag",
                            fontSize = 11.sp,
                            color = textMuted
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "ENABLED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AudioQualityOption(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isDarkMode: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable { onClick() }
            .then(
                if (isSelected) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = if (appColors.isDark) 0.14f else 0.09f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                } else {
                    Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                }
            )
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) accentColor else appColors.textPrimary
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = appColors.textMuted
            )
        }
        Icon(
            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) accentColor else appColors.textMuted.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}
