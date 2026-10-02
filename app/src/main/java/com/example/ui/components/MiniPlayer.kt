package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.example.ui.theme.contrastingContentColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.util.AppHaptics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun MiniPlayer(
    uiState: PlayerUiState,
    onPlayPauseClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onClick: () -> Unit,
    currentPositionProvider: () -> Long = { uiState.currentPositionMs },
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack ?: return

    var totalDrag by remember { mutableFloatStateOf(0f) }

    val playPauseScale by animateFloatAsState(
        targetValue = if (uiState.isPlaying) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "play_pause_scale"
    )

    val appColors = LocalAppColors.current
    val context = LocalContext.current
    val imageRequest = rememberOptimizedImageRequest(track.coverUrl, targetSize = ImageConfig.MINI_PLAYER_SIZE)

    val cachedColors = remember(track.id) {
        com.example.ui.util.TrackPaletteCache.get(track.id)
    }
    val ambientGlowColor = cachedColors?.accentColor ?: appColors.primaryAccent

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer()
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                spotColor = Color.Black.copy(alpha = if (appColors.isDark) 0.60f else 0.12f),
                ambientColor = Color.Black.copy(alpha = if (appColors.isDark) 0.30f else 0.06f)
            )
            .clip(CircleShape)
            .clickable {
                AppHaptics.performTap(context)
                onClick()
            }
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                    },
                    onDragEnd = {
                        if (totalDrag < -60f) {
                            AppHaptics.performTap(context)
                            onSkipNext()
                        } else if (totalDrag > 60f) {
                            AppHaptics.performTap(context)
                            onSkipPrevious()
                        }
                        totalDrag = 0f
                    }
                )
            }
            .testTag("mini_player_container"),
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(
            1.2.dp,
            com.example.ui.theme.LiquidGlass.specularBorderBrush(
                appColors,
                highlightAlpha = if (appColors.isDark) 0.40f else 0.50f
            )
        )
    ) {
        val miniPlayerBaseBrush = if (appColors.isAmoled) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF141414),
                    Color(0xFF0A0A0A),
                    Color(0xFF000000)
                )
            )
        } else if (appColors.isDark) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF222B3D).copy(alpha = 0.94f),
                    Color(0xFF161E2C).copy(alpha = 0.96f),
                    Color(0xFF0F141E).copy(alpha = 0.98f)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.96f),
                    Color(0xFFF8FAFC).copy(alpha = 0.97f),
                    Color(0xFFF1F5F9).copy(alpha = 0.98f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(miniPlayerBaseBrush)
        ) {
            // Enhanced frosted Gaussian blur diffusion layer (36dp blur with multi-stop highlights)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        if (appColors.isDark) {
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.16f),
                                    appColors.primaryAccent.copy(alpha = 0.08f),
                                    Color.White.copy(alpha = 0.03f)
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.60f),
                                    appColors.primaryAccent.copy(alpha = 0.10f),
                                    Color.White.copy(alpha = 0.30f)
                                )
                            )
                        }
                    )
                    .blur(36.dp)
            )

            // Ambient warm glow from album art on the right half
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                ambientGlowColor.copy(alpha = if (appColors.isDark) 0.32f else 0.16f)
                            )
                        )
                    )
                    .blur(24.dp)
            )

            // Top specular highlight sheen
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (appColors.isDark) 0.20f else 0.32f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = 40f
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art - rounded rectangle matching Screenshot 2
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Track Artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF262A34))
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Title & Artist (clean, bold, high contrast, non-overlapping)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.cleanTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = appColors.textPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (uiState.isPlaying) {
                            Spacer(modifier = Modifier.width(6.dp))
                            MiniAnimatedEqualizerBars()
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = appColors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play / Pause Button - Theme tint, blurry liquid glass UI style & matte finish to the accent colour
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = appColors.primaryAccent
                        )
                    } else {
                        val isPlaying = uiState.isPlaying
                        val playPauseMatteBorder = BorderStroke(
                            1.2.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (appColors.isDark) 0.50f else 0.60f),
                                    appColors.primaryAccent.copy(alpha = if (isPlaying) 0.65f else 0.35f)
                                )
                            )
                        )
                        val playPauseMatteBg = if (isPlaying) {
                            // Active playing state: rich dynamic matte accent gradient
                            if (appColors.isAmoled && appColors.primaryAccent == Color.White) {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFFFFFFF),
                                        Color(0xFFE2E8F0)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.95f else 0.92f),
                                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.80f else 0.82f)
                                    )
                                )
                            }
                        } else {
                            // Paused state: subtle liquid glass container
                            if (appColors.isDark) {
                                if (appColors.isAmoled) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF242424),
                                            Color(0xFF161616),
                                            Color(0xFF0C0C0C)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            appColors.primaryAccent.copy(alpha = 0.28f),
                                            appColors.cardBackgroundElevated.copy(alpha = 0.88f),
                                            appColors.primaryAccent.copy(alpha = 0.16f)
                                        )
                                    )
                                }
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = 0.20f),
                                        Color.White.copy(alpha = 0.92f),
                                        appColors.primaryAccent.copy(alpha = 0.12f)
                                    )
                                )
                            }
                        }

                        // Icon tint strictly calculated for high dynamic contrast against playPauseMatteBg
                        val playPauseIconTint = if (isPlaying) {
                            val accentLum = (0.299 * appColors.primaryAccent.red + 0.587 * appColors.primaryAccent.green + 0.114 * appColors.primaryAccent.blue)
                            if (appColors.isAmoled && (appColors.primaryAccent == Color.White || accentLum > 0.70f)) {
                                Color(0xFF0A0A0A)
                            } else {
                                contrastingContentColor(appColors.primaryAccent)
                            }
                        } else {
                            // When paused on dark/frosted surface:
                            if (appColors.isDark) {
                                if (appColors.isAmoled) {
                                    val lum = (0.299 * appColors.primaryAccent.red + 0.587 * appColors.primaryAccent.green + 0.114 * appColors.primaryAccent.blue)
                                    if (appColors.primaryAccent != Color.White && lum > 0.35f) {
                                        appColors.primaryAccent
                                    } else {
                                        Color.White
                                    }
                                } else {
                                    appColors.primaryAccent
                                }
                            } else {
                                appColors.primaryAccent
                            }
                        }

                        Surface(
                            onClick = {
                                AppHaptics.performTap(context)
                                onPlayPauseClick()
                            },
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = playPauseMatteBorder,
                            modifier = Modifier
                                .size(44.dp)
                                .scale(playPauseScale)
                                .shadow(
                                    elevation = if (isPlaying) 8.dp else 4.dp,
                                    shape = CircleShape,
                                    spotColor = appColors.primaryAccent.copy(alpha = if (isPlaying) 0.55f else 0.25f),
                                    ambientColor = Color.Transparent
                                )
                                .clip(CircleShape)
                                .background(playPauseMatteBg)
                                .testTag("mini_player_play_pause")
                        ) {
                            // Inner Gaussian blur layer for liquid glass depth (skip when AMOLED to keep icon razor sharp)
                            val skipSheen = appColors.isAmoled
                            if (!skipSheen) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            if (appColors.isDark) {
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.12f),
                                                        Color.White.copy(alpha = 0.02f)
                                                    )
                                                )
                                            } else {
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.35f),
                                                        Color.White.copy(alpha = 0.10f)
                                                    )
                                                )
                                            }
                                        )
                                        .blur(14.dp)
                                )

                                // Top specular sheen
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.White.copy(alpha = if (isPlaying) 0.22f else 0.14f),
                                                    Color.Transparent
                                                ),
                                                startY = 0f,
                                                endY = 22f
                                            )
                                        )
                                )
                            }

                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = playPauseIconTint,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Skip Next Button - Theme tint with blurry liquid glass UI style & matte finish
                val nextMatteBorder = BorderStroke(
                    1.2.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (appColors.isDark) 0.45f else 0.55f),
                            appColors.primaryAccent.copy(alpha = 0.35f)
                        )
                    )
                )
                val nextMatteBg = if (appColors.isDark) {
                    if (appColors.isAmoled) {
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF242424),
                                Color(0xFF161616),
                                Color(0xFF0E0E0E)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                appColors.primaryAccent.copy(alpha = 0.28f),
                                Color(0xFF222B3D).copy(alpha = 0.85f),
                                appColors.primaryAccent.copy(alpha = 0.16f)
                            )
                        )
                    }
                } else {
                    Brush.verticalGradient(
                        listOf(
                            appColors.primaryAccent.copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.90f),
                            appColors.primaryAccent.copy(alpha = 0.12f)
                        )
                    )
                }

                Surface(
                    onClick = {
                        AppHaptics.performTap(context)
                        onSkipNext()
                    },
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = nextMatteBorder,
                    modifier = Modifier
                        .size(38.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = CircleShape,
                            spotColor = Color.Black.copy(alpha = if (appColors.isDark) 0.35f else 0.15f),
                            ambientColor = Color.Transparent
                        )
                        .clip(CircleShape)
                        .background(nextMatteBg)
                        .testTag("mini_player_next")
                ) {
                    // Inner Gaussian blur layer
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (appColors.isDark) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.14f),
                                            Color.White.copy(alpha = 0.03f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.35f),
                                            Color.White.copy(alpha = 0.12f)
                                        )
                                    )
                                }
                            )
                            .blur(12.dp)
                    )

                    // Top specular sheen
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = if (appColors.isDark) 0.18f else 0.25f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = 18f
                                )
                            )
                    )

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Skip Next",
                            tint = if (appColors.isDark) appColors.textPrimary else appColors.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MiniAnimatedEqualizerBars() {
    val transition = rememberInfiniteTransition(label = "mini_eq_transition")

    val fraction1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_bar_1"
    )

    val fraction2 by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_bar_2"
    )

    val fraction3 by transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_bar_3"
    )

    val eqColor = LocalAppColors.current.primaryAccent

    Canvas(
        modifier = Modifier
            .size(width = 14.dp, height = 14.dp)
            .graphicsLayer()
    ) {
        val totalH = size.height
        val barW = 2.5.dp.toPx()
        val space = 2.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())

        val h1 = (totalH * fraction1).coerceIn(3f, totalH)
        drawRoundRect(
            color = eqColor,
            topLeft = androidx.compose.ui.geometry.Offset(0f, totalH - h1),
            size = androidx.compose.ui.geometry.Size(barW, h1),
            cornerRadius = corner
        )

        val h2 = (totalH * fraction2).coerceIn(3f, totalH)
        drawRoundRect(
            color = eqColor,
            topLeft = androidx.compose.ui.geometry.Offset(barW + space, totalH - h2),
            size = androidx.compose.ui.geometry.Size(barW, h2),
            cornerRadius = corner
        )

        val h3 = (totalH * fraction3).coerceIn(3f, totalH)
        drawRoundRect(
            color = eqColor,
            topLeft = androidx.compose.ui.geometry.Offset((barW + space) * 2f, totalH - h3),
            size = androidx.compose.ui.geometry.Size(barW, h3),
            cornerRadius = corner
        )
    }
}
