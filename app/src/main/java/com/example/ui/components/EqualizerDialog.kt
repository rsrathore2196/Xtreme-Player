package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.playback.AudioEffectsState
import com.example.playback.BandState
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerDialog(
    effectsState: AudioEffectsState,
    onEnableChanged: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onBandLevelChanged: (Short, Short) -> Unit,
    onBassBoostChanged: (Int) -> Unit,
    onVirtualizerChanged: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF13151D)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("equalizer_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(20.dp)
            ) {
                // HEADER WITH MASTER SWITCH
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (effectsState.isEnabled) XtremeGreen.copy(alpha = 0.2f)
                                    else Color(0xFF232733)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = if (effectsState.isEnabled) XtremeGreen else TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Audio Equalizer & FX",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = if (effectsState.isEnabled) "Real-time DSP Active (320k)" else "Effects Bypassed",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (effectsState.isEnabled) XtremeGreen else TextMuted,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    Switch(
                        checked = effectsState.isEnabled,
                        onCheckedChange = onEnableChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = XtremeGreen,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = Color(0xFF282C3A)
                        ),
                        modifier = Modifier.testTag("equalizer_master_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // PRESETS FLOW ROW
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PRESETS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    IconButton(
                        onClick = onReset,
                        enabled = effectsState.isEnabled,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset to Flat",
                            tint = if (effectsState.isEnabled) TextSecondary else Color(0xFF333845),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    effectsState.availablePresets.forEach { preset ->
                        val isSelected = effectsState.selectedPreset.equals(preset, ignoreCase = true)
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = effectsState.isEnabled) {
                                    onPresetSelected(preset)
                                },
                            color = if (isSelected && effectsState.isEnabled) XtremeGreen
                                    else if (isSelected) Color(0xFF323644)
                                    else XtremeCard,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = preset,
                                color = if (isSelected && effectsState.isEnabled) Color.Black else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // BASS BOOST & 3D VIRTUALIZER CONTROLS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Bass Boost Card
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D27)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Bass Boost",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(effectsState.bassBoostStrength / 10)}%",
                                    color = XtremeGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = (effectsState.bassBoostStrength / 1000f),
                                onValueChange = { onBassBoostChanged((it * 1000).toInt()) },
                                enabled = effectsState.isEnabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = XtremeGreen,
                                    activeTrackColor = XtremeGreen,
                                    inactiveTrackColor = Color(0xFF282C3A)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Virtualizer Card
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D27)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "3D Virtualizer",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(effectsState.virtualizerStrength / 10)}%",
                                    color = XtremeCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = (effectsState.virtualizerStrength / 1000f),
                                onValueChange = { onVirtualizerChanged((it * 1000).toInt()) },
                                enabled = effectsState.isEnabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = XtremeCyan,
                                    activeTrackColor = XtremeCyan,
                                    inactiveTrackColor = Color(0xFF282C3A)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ADJUSTABLE FREQUENCY BANDS
                Text(
                    text = "FREQUENCY BANDS (REAL-TIME)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    effectsState.bands.forEach { band ->
                        BandSliderRow(
                            band = band,
                            isEnabled = effectsState.isEnabled,
                            onLevelChange = { newLevel ->
                                onBandLevelChanged(band.index, newLevel)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "APPLY & CLOSE",
                            color = XtremeGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BandSliderRow(
    band: BandState,
    isEnabled: Boolean,
    onLevelChange: (Short) -> Unit
) {
    val rangeSpan = (band.maxLevelMb - band.minLevelMb).toFloat().coerceAtLeast(1f)
    val sliderValue = ((band.levelMb - band.minLevelMb) / rangeSpan).coerceIn(0f, 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF171A24))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Frequency Label
        Text(
            text = band.displayFrequency,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isEnabled) TextPrimary else TextMuted,
            modifier = Modifier.width(48.dp)
        )

        // Frequency Slider
        Slider(
            value = sliderValue,
            onValueChange = { fraction ->
                val newMb = (band.minLevelMb + (fraction * rangeSpan)).toInt().toShort()
                onLevelChange(newMb)
            },
            enabled = isEnabled,
            colors = SliderDefaults.colors(
                thumbColor = XtremeGreen,
                activeTrackColor = XtremeGreen,
                inactiveTrackColor = Color(0xFF282C3A)
            ),
            modifier = Modifier.weight(1f)
        )

        // Level dB Label
        Text(
            text = band.displayLevelDb,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isEnabled) XtremeGreen else TextMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(54.dp)
        )
    }
}
