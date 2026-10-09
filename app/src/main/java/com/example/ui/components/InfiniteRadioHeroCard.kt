package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest

/**
 * Polished Liquid-Glass Infinite Radio Hero Card for Xtreme Player Home Screen.
 *
 * Visual Architecture:
 * - Full-width card with generous rounded corners (26dp) and consistent horizontal margins (20dp).
 * - Edge-to-edge seed-track album artwork cropped with high fidelity.
 * - Multi-layer liquid glass overlays: subtle dark gradient + specular sheen so artwork colors interact naturally.
 * - Translucent glass pill at the upper left with sparkle icon and "MADE FOR YOU".
 * - Prominent bold "Infinite Radio" headline.
 * - Dynamic truthful supporting text: "Based on: {seed title} · Endless station".
 * - Tactile rounded liquid-glass Play button reflecting real session playback states (Play, Pause, Resume, Loading).
 */
@Composable
fun InfiniteRadioHeroCard(
    seedTrack: MusicTrack?,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    isLoading: Boolean = false,
    onPlayInfiniteRadio: (MusicTrack?) -> Unit,
    modifier: Modifier = Modifier
) {
    val isThisTrackCurrent = seedTrack != null && seedTrack.id == currentPlayingTrackId
    val isTrackPlaying = isThisTrackCurrent && isPlaying
    val isTrackPaused = isThisTrackCurrent && !isPlaying
    val isTrackLoading = isThisTrackCurrent && isLoading

    val seedTitle = seedTrack?.title?.trim()?.ifBlank { "Top Picks" } ?: "Top Picks"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
            .height(230.dp)
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(26.dp),
                spotColor = Color.Black.copy(alpha = 0.50f),
                ambientColor = Color.Black.copy(alpha = 0.25f)
            )
            .clip(RoundedCornerShape(26.dp))
            .border(
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.38f),
                            Color.White.copy(alpha = 0.12f)
                        )
                    )
                ),
                shape = RoundedCornerShape(26.dp)
            )
            .testTag("infinite_radio_hero_card")
    ) {
        // 1. Edge-to-Edge Seed Artwork
        val coverUrl = seedTrack?.coverUrl
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = rememberOptimizedImageRequest(coverUrl, ImageConfig.HERO_BANNER_SIZE),
                contentDescription = "$seedTitle album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // High-quality fallback gradient if artwork URL is blank
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF1E2638),
                                Color(0xFF141926),
                                Color(0xFF0D0F17)
                            )
                        )
                    )
            )
        }

        // 2. Liquid Glass Backdrop Shading: Dark vignette gradient so white text remains 100% crisp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.48f),
                            Color.Black.copy(alpha = 0.86f),
                            Color.Black.copy(alpha = 0.94f)
                        )
                    )
                )
        )

        // 3. Ambient Specular Refraction Sheen: Top radial highlight interacting with artwork
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(240f, 90f),
                        radius = 480f
                    )
                )
        )

        // 4. Foreground Content Hierarchy
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            // UPPER LEFT: Translucent Liquid-Glass Badge "MADE FOR YOU"
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.10f)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            0.8.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.50f),
                                    Color.White.copy(alpha = 0.20f)
                                )
                            )
                        ),
                        CircleShape
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("hero_made_for_you_badge"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "MADE FOR YOU",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp,
                        fontSize = 10.5.sp,
                        color = Color.White
                    )
                )
            }

            // LOWER SECTION: Title, Supporting Text, and Liquid-Glass Play Button
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Infinite Radio",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        letterSpacing = (-0.4).sp,
                        color = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Based on: $seedTitle · Endless station",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        letterSpacing = 0.2.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Large rounded liquid-glass Play / Pause / Resume button
                val buttonInteraction = remember { MutableInteractionSource() }
                val isPressed by buttonInteraction.collectIsPressedAsState()
                val buttonScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.94f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "hero_play_press_spring"
                )

                Row(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = buttonScale
                            scaleY = buttonScale
                        }
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.30f),
                                    Color.White.copy(alpha = 0.16f)
                                )
                            )
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.70f),
                                        Color.White.copy(alpha = 0.30f)
                                    )
                                )
                            ),
                            RoundedCornerShape(22.dp)
                        )
                        .clickable(
                            interactionSource = buttonInteraction,
                            indication = null,
                            onClick = { onPlayInfiniteRadio(seedTrack) }
                        )
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                        .testTag("hero_play_infinite_radio_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    when {
                        isTrackLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Loading…",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        isTrackPlaying -> {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Pause",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        isTrackPaused -> {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Resume",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Resume",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Play",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
