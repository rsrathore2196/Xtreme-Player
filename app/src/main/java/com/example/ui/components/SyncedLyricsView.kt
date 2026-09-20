package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TrackLyrics
import com.example.ui.theme.LocalAppColors

@Composable
fun SyncedLyricsView(
    lyrics: TrackLyrics?,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    dominantColor: Color,
    accentColor: Color,
    isDark: Boolean,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val effectiveAccent = appColors.primaryAccent
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    val lines = lyrics?.lines ?: emptyList()
    val isSynced = lyrics?.isSynced == true

    // Fully theme-adaptive background and border colors
    val containerBg = if (isDark) {
        appColors.cardBackground.copy(alpha = 0.94f)
    } else {
        appColors.cardBackground.copy(alpha = 0.96f)
    }

    // Determine current active lyric line based on current playback timestamp (only if synced)
    val activeLineIndex by remember(lines, currentPositionMs, isSynced) {
        derivedStateOf {
            if (!isSynced || lines.isEmpty()) -1
            else {
                var found = -1
                for (i in lines.indices) {
                    if (currentPositionMs >= lines[i].timestampMs) {
                        found = i
                    } else {
                        break
                    }
                }
                found
            }
        }
    }

    // Smooth Apple Music auto-scroll: Keep active line centered and flowing upwards smoothly (only if synced)
    LaunchedEffect(activeLineIndex, isSynced) {
        if (isSynced && !listState.isScrollInProgress && activeLineIndex in lines.indices) {
            listState.animateScrollToItem(
                index = activeLineIndex,
                scrollOffset = -180 // Centers the active line nicely
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(containerBg)
            .border(
                BorderStroke(
                    1.2.dp,
                    if (isDark) appColors.cardBorder else appColors.cardBorder.copy(alpha = 0.7f)
                ),
                RoundedCornerShape(24.dp)
            )
            .testTag("synced_lyrics_view")
    ) {
        if (lyrics == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = effectiveAccent,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Searching live lyrics...",
                        color = appColors.textMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = appColors.textMuted,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Lyrics not available",
                        color = appColors.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No verified lyrics match for this song",
                        color = appColors.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = effectiveAccent
                        ),
                        border = BorderStroke(
                            1.dp,
                            effectiveAccent.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry Search",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Retry Search",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = if (isSynced) PaddingValues(top = 110.dp, bottom = 140.dp) else PaddingValues(top = 64.dp, bottom = 90.dp),
                verticalArrangement = if (isSynced) Arrangement.spacedBy(22.dp) else Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(lines, key = { index, item -> "${item.timestampMs}_$index" }) { index, item ->
                    val isActive = isSynced && (index == activeLineIndex)
                    val isPast = isSynced && (index < activeLineIndex)

                    val targetAlpha = if (!isSynced) {
                        1.0f
                    } else when {
                        isActive -> 1.0f
                        isPast -> 0.45f
                        else -> 0.35f
                    }
                    val targetScale = if (isActive) 1.04f else 1.0f

                    val activeTextColor = if (isDark) Color.White else appColors.textPrimary
                    val inactiveTextColor = appColors.textMuted

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = targetAlpha
                                scaleX = targetScale
                                scaleY = targetScale
                            }
                            .clickable(
                                enabled = isSynced,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSeekTo(item.timestampMs)
                                }
                            )
                            .padding(vertical = if (isSynced) 4.dp else 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(effectiveAccent)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = if (isActive) 23.sp else 18.sp,
                                fontWeight = if (isActive) FontWeight.ExtraBold else if (!isSynced) FontWeight.Medium else FontWeight.Medium,
                                lineHeight = if (isActive) 30.sp else if (!isSynced) 28.sp else 26.sp,
                                color = if (isActive || !isSynced) activeTextColor else inactiveTextColor
                            ),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Lyrics Mode Indicator Pill at top-end ("Synced" vs "Plain")
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 18.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(appColors.cardBackgroundElevated.copy(alpha = 0.92f))
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSynced) {
                                Color(0xFF10B981).copy(alpha = 0.5f)
                            } else {
                                effectiveAccent.copy(alpha = 0.4f)
                            }
                        ),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSynced) Color(0xFF10B981)
                                else effectiveAccent
                            )
                    )
                    Text(
                        text = if (isSynced) "Synced" else "Plain",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    )
                }
            }

            // Top gradient mask for smooth fading flow (Apple Music aesthetic)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                containerBg,
                                Color.Transparent
                            )
                        )
                    )
            )

            // Bottom gradient mask
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                containerBg
                            )
                        )
                    )
            )
        }
    }
}
