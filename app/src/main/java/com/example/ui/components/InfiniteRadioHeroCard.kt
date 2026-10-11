package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest

/** Artwork-led radio entry point. Playback controls remain in the player. */
@Composable
fun InfiniteRadioHeroCard(
    seedTrack: MusicTrack?,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    isLoading: Boolean = false,
    onPlayInfiniteRadio: (MusicTrack?) -> Unit,
    modifier: Modifier = Modifier,
    isRadioSession: Boolean = false
) {
    val radioPlaying = isRadioSession && isPlaying
    val radioLoading = isRadioSession && isLoading && !radioPlaying
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(), label = "radio_press")
    val shape = RoundedCornerShape(26.dp)
    val textShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 10f)

    Box(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
            .height(230.dp).shadow(12.dp, shape).clip(shape)
            .background(Color(0xFF172030))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.1f))), shape)
            .testTag("infinite_radio_hero_card")
    ) {
        if (seedTrack != null) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track = seedTrack, targetSize = ImageConfig.HERO_BANNER_SIZE),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(2.dp)
            )
        }
        // Retain 90% of the artwork beneath a light tint and a subtle glass reflection.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.04f), Color.Transparent, Color.White.copy(alpha = 0.015f))
        )))
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            Text("Infinite Radio", style = MaterialTheme.typography.headlineMedium.copy(
                color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp, shadow = textShadow
            ))
            Text("A fresh random mix · Endless discoveries", style = MaterialTheme.typography.bodyMedium.copy(
                color = Color.White, shadow = textShadow
            ))
            Spacer(Modifier.height(18.dp))
            val buttonShape = RoundedCornerShape(24.dp)
            Row(
                Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(8.dp, buttonShape, spotColor = Color.Black.copy(alpha = 0.25f))
                    .clip(buttonShape)
                    .background(Brush.linearGradient(listOf(
                        Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.22f)
                    )))
                    .border(1.dp, Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.48f)
                    )), buttonShape)
                    .clickable(
                        enabled = !radioPlaying && !radioLoading,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = { onPlayInfiniteRadio(null) }
                    )
                    .padding(horizontal = 24.dp, vertical = 13.dp)
                    .testTag("hero_play_infinite_radio_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (radioLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else if (!radioPlaying) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (radioPlaying) "Playing" else if (radioLoading) "Starting radio…" else "Play radio",
                    style = MaterialTheme.typography.labelLarge.copy(color = Color.White, fontWeight = FontWeight.Bold, shadow = textShadow)
                )
            }
        }
    }
}
