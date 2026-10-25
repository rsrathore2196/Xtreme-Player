package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.LyricsOffsetManager
import com.example.data.model.MusicTrack
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest
import com.example.util.AppHaptics
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsOffsetBottomSheet(
    track: MusicTrack,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    val activeOffsetMs by LyricsOffsetManager.activeOffsetMs.collectAsStateWithLifecycle()
    var applyToAll by remember(track.id) { mutableStateOf(LyricsOffsetManager.usesGlobalOffset(context, track.id)) }

    // Internal slider state for smooth drag interactions
    var sliderFraction by remember(activeOffsetMs) {
        val fraction = (activeOffsetMs - LyricsOffsetManager.MIN_LYRICS_OFFSET_MS).toFloat() /
            (LyricsOffsetManager.MAX_LYRICS_OFFSET_MS - LyricsOffsetManager.MIN_LYRICS_OFFSET_MS)
        mutableFloatStateOf(fraction.coerceIn(0f, 1f))
    }

    val offsetDisplayColor by animateColorAsState(
        targetValue = when {
            activeOffsetMs == 0 -> if (!isDark && appColors.primaryAccent.luminance() > 0.70f) appColors.textPrimary else appColors.primaryAccent
            activeOffsetMs > 0 -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
            else -> if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
        },
        animationSpec = tween(durationMillis = 200),
        label = "offsetColor"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (appColors.isAmoled) Color(0xFF0A0A0A) else appColors.cardBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .width(42.dp)
                    .height(4.5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(appColors.cardBorder.copy(alpha = 0.8f))
            )
        },
        modifier = modifier.testTag("lyrics_offset_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = 0.90f),
                                        appColors.primaryAccent
                                    )
                                )
                            )
                            .border(1.dp, appColors.cardBorder.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = appColors.onPrimaryAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Lyrics Offset",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                        )
                        Text(
                            text = "Fine-tune sync timing with vocal audio",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = appColors.textSecondary
                            )
                        )
                    }
                }

                IconButton(
                    onClick = {
                        AppHaptics.performTap(context)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("lyrics_offset_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = appColors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Track Preview Snippet
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (appColors.isAmoled) Color(0xFF141414) else appColors.chipBackground,
                border = BorderStroke(1.dp, appColors.cardBorder.copy(alpha = if (isDark) 0.45f else 0.65f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(appColors.cardBorder)
                    ) {
                        AsyncImage(
                            model = rememberOptimizedImageRequest(
                                track = track,
                                targetSize = ImageConfig.LIST_ITEM_SIZE
                            ),
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.cleanTitle,
                            color = appColors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist.ifBlank { "Unknown Artist" },
                            color = appColors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Interactive Offset Hero Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = if (appColors.isAmoled) Color(0xFF121212) else if (isDark) Color(0xFF161F2E).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.95f),
                border = BorderStroke(1.2.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.4f)),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Big numerical readout
                    Text(
                        text = LyricsOffsetManager.formatOffset(activeOffsetMs),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.SansSerif,
                            color = offsetDisplayColor,
                            letterSpacing = (-0.5).sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("lyrics_offset_value_text")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Status Pill
                    Surface(
                        shape = CircleShape,
                        color = offsetDisplayColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, offsetDisplayColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = when {
                                activeOffsetMs == 0 -> "In Exact Sync (0.0s)"
                                activeOffsetMs > 0 -> "Delayed +${activeOffsetMs}ms • Lyrics appear later"
                                else -> "Advanced ${activeOffsetMs}ms • Lyrics appear earlier"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = offsetDisplayColor
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stepper & Slider Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Minus Button
                        Surface(
                            onClick = {
                                AppHaptics.performTap(context)
                                val newOffset = (activeOffsetMs - LyricsOffsetManager.STEP_MS)
                                    .coerceIn(LyricsOffsetManager.MIN_LYRICS_OFFSET_MS, LyricsOffsetManager.MAX_LYRICS_OFFSET_MS)
                                LyricsOffsetManager.updateCurrentOffset(context, track.id, newOffset, applyToAll)
                            },
                            enabled = activeOffsetMs > LyricsOffsetManager.MIN_LYRICS_OFFSET_MS,
                            shape = CircleShape,
                            color = if (appColors.isAmoled) Color(0xFF1E1E1E) else appColors.chipBackground,
                            border = BorderStroke(1.dp, appColors.cardBorder.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("lyrics_offset_decrease")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Decrease offset",
                                    tint = if (activeOffsetMs > LyricsOffsetManager.MIN_LYRICS_OFFSET_MS) appColors.textPrimary else appColors.textMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Slider
                        Slider(
                            value = sliderFraction,
                            onValueChange = { fraction ->
                                sliderFraction = fraction
                                val raw = LyricsOffsetManager.MIN_LYRICS_OFFSET_MS +
                                    fraction * (LyricsOffsetManager.MAX_LYRICS_OFFSET_MS - LyricsOffsetManager.MIN_LYRICS_OFFSET_MS)
                                val stepped = (raw / LyricsOffsetManager.STEP_MS).roundToInt() * LyricsOffsetManager.STEP_MS
                                LyricsOffsetManager.updateCurrentOffset(context, track.id, stepped, applyToAll)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = appColors.primaryAccent,
                                activeTrackColor = appColors.primaryAccent,
                                inactiveTrackColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.10f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lyrics_offset_slider")
                        )

                        // Plus Button
                        Surface(
                            onClick = {
                                AppHaptics.performTap(context)
                                val newOffset = (activeOffsetMs + LyricsOffsetManager.STEP_MS)
                                    .coerceIn(LyricsOffsetManager.MIN_LYRICS_OFFSET_MS, LyricsOffsetManager.MAX_LYRICS_OFFSET_MS)
                                LyricsOffsetManager.updateCurrentOffset(context, track.id, newOffset, applyToAll)
                            },
                            enabled = activeOffsetMs < LyricsOffsetManager.MAX_LYRICS_OFFSET_MS,
                            shape = CircleShape,
                            color = if (appColors.isAmoled) Color(0xFF1E1E1E) else appColors.chipBackground,
                            border = BorderStroke(1.dp, appColors.cardBorder.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("lyrics_offset_increase")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Increase offset",
                                    tint = if (activeOffsetMs < LyricsOffsetManager.MAX_LYRICS_OFFSET_MS) appColors.textPrimary else appColors.textMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Quick Jump Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            Triple("-0.5s", -500, "-0.5s"),
                            Triple("-0.1s", -100, "-0.1s"),
                            Triple("Reset", 0, "Reset"),
                            Triple("+0.1s", 100, "+0.1s"),
                            Triple("+0.5s", 500, "+0.5s")
                        ).forEach { (label, deltaOrTarget, tag) ->
                            val isReset = label == "Reset"
                            val isSelected = if (isReset) activeOffsetMs == 0 else false

                            Surface(
                                onClick = {
                                    AppHaptics.performTap(context)
                                    val newOffset = if (isReset) {
                                        0
                                    } else {
                                        (activeOffsetMs + deltaOrTarget).coerceIn(
                                            LyricsOffsetManager.MIN_LYRICS_OFFSET_MS,
                                            LyricsOffsetManager.MAX_LYRICS_OFFSET_MS
                                        )
                                    }
                                    LyricsOffsetManager.updateCurrentOffset(context, track.id, newOffset, applyToAll)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isReset && activeOffsetMs == 0) {
                                    appColors.primaryAccent.copy(alpha = 0.20f)
                                } else if (appColors.isAmoled) {
                                    Color(0xFF1C1C1C)
                                } else {
                                    appColors.chipBackground
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isReset && activeOffsetMs == 0) {
                                        appColors.primaryAccent.copy(alpha = 0.60f)
                                    } else {
                                        appColors.cardBorder.copy(alpha = 0.45f)
                                    }
                                ),
                                modifier = Modifier.testTag("lyrics_offset_chip_$tag")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val resetAccentColor = if (!isDark && appColors.primaryAccent.luminance() > 0.70f) appColors.textPrimary else appColors.primaryAccent
                                    if (isReset) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = null,
                                            tint = if (activeOffsetMs == 0) resetAccentColor else appColors.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isReset || isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isReset && activeOffsetMs == 0) resetAccentColor else appColors.textPrimary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Option: Apply globally vs this track
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (appColors.isAmoled) Color(0xFF141414) else appColors.chipBackground.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, appColors.cardBorder.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            AppHaptics.performTap(context)
                            applyToAll = !applyToAll
                            LyricsOffsetManager.updateCurrentOffset(context, track.id, activeOffsetMs, applyToAll)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Apply to all songs",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textPrimary
                            )
                        )
                        Text(
                            text = if (applyToAll) "Offset affects all playback" else "Offset saved for \"${track.cleanTitle}\"",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = appColors.textSecondary
                            )
                        )
                    }

                    Switch(
                        checked = applyToAll,
                        onCheckedChange = { checked ->
                            AppHaptics.performTap(context)
                            applyToAll = checked
                            LyricsOffsetManager.updateCurrentOffset(context, track.id, activeOffsetMs, checked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = appColors.primaryAccent,
                            uncheckedThumbColor = appColors.textSecondary,
                            uncheckedTrackColor = appColors.cardBorder
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Done / Dismiss Button
            Surface(
                onClick = {
                    AppHaptics.performTap(context)
                    onDismiss()
                },
                shape = RoundedCornerShape(16.dp),
                color = appColors.primaryAccent,
                contentColor = appColors.onPrimaryAccent,
                border = BorderStroke(1.dp, appColors.cardBorder.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("lyrics_offset_done_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = appColors.onPrimaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Done",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = appColors.onPrimaryAccent
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
