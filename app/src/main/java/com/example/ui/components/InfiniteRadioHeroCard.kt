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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.size.Precision
import com.example.data.model.MusicTrack
import com.example.ui.theme.LocalAppColors
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
    isRadioSession: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
    compactLayout: Boolean = false
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val glassEnabled = LocalDynamicGlassEnabled.current
    // Artwork is not a light-theme surface: a localized dark scrim keeps white
    // foreground readable regardless of the cover or surrounding app mode.
    val glassTint = Color(0xFF080D18)
    val foreground = Color.White
    val buttonForeground = if (glassEnabled) foreground else com.example.ui.theme.contrastingContentColor(appColors.cardBackground)
    val playbackActive = isPlaying
    val radioLoading = isRadioSession && isLoading && !playbackActive
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(), label = "radio_press")
    val shape = RoundedCornerShape(26.dp)
    var artworkSize by remember { mutableStateOf(IntSize.Zero) }
    val textShadow = if (!glassEnabled) Shadow.None else Shadow(
        Color.Black.copy(alpha = 0.85f),
        Offset(0f, 1f), 6f
    )

    Box(
        modifier.fillMaxWidth().padding(contentPadding)
            .height(240.dp).shadow(if (glassEnabled) 14.dp else 6.dp, shape).clip(shape)
            .background(glassTint)
            .border(1.5.dp, if (!glassEnabled) SolidColor(appColors.cardBorder) else Brush.linearGradient(listOf(
                Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.12f),
                Color.Black.copy(alpha = if (isDark) 0.35f else 0.18f), Color.White.copy(alpha = 0.6f)
            )), shape)
            .onSizeChanged { artworkSize = it }
            .testTag("infinite_radio_hero_card")
    ) {
        if (seedTrack != null && artworkSize.width > 0 && artworkSize.height > 0) {
            // Resolve a high-quality source, but decode only the measured pixel rectangle.
            // Coil's FILL scaling keeps enough pixels for the existing center crop.
            val artworkRequest = rememberOptimizedImageRequest(
                track = seedTrack, targetSize = ImageConfig.HERO_BANNER_SIZE, decodeSize = artworkSize
            )
            val fullQualityArtwork = remember(artworkRequest, artworkSize) {
                artworkRequest.newBuilder().size(artworkSize.width, artworkSize.height)
                    .transformations(com.example.ui.util.HeroArtworkCropTransformation)
                    .allowHardware(false)
                    .precision(Precision.INEXACT).allowRgb565(false).build()
            }
            AsyncImage(
                model = fullQualityArtwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // A continuous, theme-aware tint supports the lower content without a separate text panel.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent,
            0.22f to glassTint.copy(alpha = 0.15f),
            0.48f to glassTint.copy(alpha = 0.72f),
            1f to glassTint.copy(alpha = 0.92f)
        )))
        // A clear lens reflection keeps the artwork sharp beneath the glass.
        if (glassEnabled) Box(Modifier.fillMaxSize().background(Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.20f),
            0.28f to Color.White.copy(alpha = 0.035f),
            0.48f to Color.Transparent,
            0.86f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.08f)
        )))
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compactLayout) 16.dp else 20.dp,
                vertical = if (compactLayout) 12.dp else 16.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            Text("Infinite Radio", maxLines = if (compactLayout) 2 else Int.MAX_VALUE,
                overflow = if (compactLayout) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
                style = MaterialTheme.typography.headlineMedium.copy(
                color = foreground, fontWeight = FontWeight.Black, fontSize = 26.sp, shadow = textShadow
            ).let { if (compactLayout) it.copy(lineHeight = 30.sp) else it })
            Spacer(Modifier.height(3.dp))
            Text("A fresh random mix · Endless discoveries", maxLines = if (compactLayout) 2 else Int.MAX_VALUE,
                overflow = if (compactLayout) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
                style = MaterialTheme.typography.bodyMedium.copy(
                color = foreground, fontWeight = FontWeight.Medium,
                fontSize = 13.sp, lineHeight = 18.sp, shadow = textShadow
            ))
            Spacer(Modifier.height(if (compactLayout) 10.dp else 12.dp))
            val buttonShape = RoundedCornerShape(24.dp)
            Row(
                Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(if (glassEnabled) 8.dp else 2.dp, buttonShape, spotColor = Color.Black.copy(alpha = 0.25f))
                    .clip(buttonShape)
                    .background(if (!glassEnabled) SolidColor(appColors.cardBackground)
                    else if (!isDark) Brush.linearGradient(listOf(
                        glassTint.copy(alpha = 0.55f), glassTint.copy(alpha = 0.45f), glassTint.copy(alpha = 0.50f)))
                    else Brush.linearGradient(listOf(
                        Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.22f)
                    )))
                    .border(1.dp, if (!glassEnabled) SolidColor(appColors.cardBorder) else Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.48f)
                    )), buttonShape)
                    .clickable(
                        enabled = !playbackActive && !radioLoading,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = { onPlayInfiniteRadio(null) }
                    )
                    .padding(horizontal = if (compactLayout) 16.dp else 24.dp, vertical = 13.dp)
                    .testTag("hero_play_infinite_radio_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (radioLoading) {
                    VisibleCircularProgressIndicator(Modifier.size(18.dp), color = buttonForeground, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else if (!playbackActive) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = buttonForeground, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (playbackActive) "Playing" else if (radioLoading) "Starting radio…" else "Play",
                    style = MaterialTheme.typography.labelLarge.copy(color = buttonForeground, fontWeight = FontWeight.Bold, shadow = textShadow)
                )
            }
        }
        // Inset reflection plus the outer rim form a beveled glass edge around the cover.
        Box(Modifier.matchParentSize().padding(3.dp).border(
            0.75.dp,
            Brush.linearGradient(listOf(
                Color.White.copy(alpha = 0.42f), Color.Transparent,
                Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.32f)
            )),
            RoundedCornerShape(23.dp)
        ))
    }
}
