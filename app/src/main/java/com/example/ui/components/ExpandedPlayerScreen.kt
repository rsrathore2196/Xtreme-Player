package com.example.ui.components

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.playback.RepeatMode
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremeRose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ExpandedPlayerScreen(
    uiState: PlayerUiState,
    onMinimize: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onAddToPlaylist: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack ?: return
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var isMenuOpen by remember { mutableStateOf(false) }
    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    var isFlipped by remember(track.id) { mutableStateOf(false) }

    // Dynamic color extracted from album art
    var dominantColor by remember(track.id) { mutableStateOf(Color(0xFF0F2B48)) }
    var accentColor by remember(track.id) { mutableStateOf(XtremeLightBlue) }

    LaunchedEffect(track.coverUrl) {
        if (track.coverUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val loader = ImageLoader(context)
                    val request = ImageRequest.Builder(context)
                        .data(track.coverUrl)
                        .allowHardware(false)
                        .build()
                    val result = (loader.execute(request) as? SuccessResult)?.drawable
                    val bitmap = (result as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val palette = Palette.from(bitmap).generate()
                        val dom = palette.getDarkVibrantColor(
                            palette.getDominantColor(android.graphics.Color.parseColor("#0F2B48"))
                        )
                        val acc = palette.getLightVibrantColor(
                            palette.getVibrantColor(android.graphics.Color.parseColor("#38BDF8"))
                        )
                        dominantColor = Color(dom)
                        accentColor = Color(acc)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val animatedDominantColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(600),
        label = "dominant_color"
    )
    val animatedAccentColor by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(600),
        label = "accent_color"
    )

    val trackDuration = if (uiState.durationMs > 0) uiState.durationMs else track.durationMs
    val currentPosition = if (isUserScrubbing) {
        (scrubPosition * trackDuration).toLong()
    } else {
        uiState.currentPositionMs
    }

    val sliderValue = if (trackDuration > 0) {
        if (isUserScrubbing) scrubPosition else (currentPosition.toFloat() / trackDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val albumArtScale by animateFloatAsState(
        targetValue = if (uiState.isPlaying) 1.0f else 0.88f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "album_art_scale"
    )

    val flipRotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "flip_card_rotation"
    )

    Surface(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Intercept and consume all touch and gesture events so underlying screens never move
                detectTapGestures { }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { /* consume background clicks to prevent touch pass-through */ }
            )
            .testTag("expanded_player_screen"),
        color = Color(0xFF020610) // 100% OPAQUE base - ZERO reflection, ZERO transparency
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            // Animated Bokeh Mode Background Layer
            PlayerBokehBackground(
                dominantColor = animatedDominantColor,
                accentColor = animatedAccentColor
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 500.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
            // TOP BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onMinimize,
                    modifier = Modifier.testTag("player_minimize_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize Player",
                        tint = TextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PLAYING FROM XTREME • ${uiState.selectedQuality.kbps}K",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            letterSpacing = 1.2.sp,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = track.genre,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = animatedAccentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Box {
                    IconButton(onClick = { isMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = TextPrimary
                        )
                    }

                    DropdownMenu(
                        expanded = isMenuOpen,
                        onDismissRequest = { isMenuOpen = false },
                        modifier = Modifier.background(Color(0xFF0F2238))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add to Playlist", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = XtremeCyan) },
                            onClick = {
                                isMenuOpen = false
                                onAddToPlaylist(track)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share Track", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = TextSecondary) },
                            onClick = {
                                isMenuOpen = false
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Listening to \"${track.title}\" by ${track.artist} in 320kbps on Xtreme Player!")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Track"))
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // CENTER 3D FLIPPABLE ALBUM ART / CREDITS CARD WITH DYNAMIC GLOW
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Adaptive Ambient Glow Layer matching album art
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    animatedAccentColor.copy(alpha = 0.45f),
                                    animatedDominantColor.copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // 3D Flippable Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth(albumArtScale)
                        .sizeIn(maxWidth = 330.dp, maxHeight = 330.dp)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            rotationY = flipRotation
                            cameraDistance = 14f * density
                        }
                        .shadow(
                            elevation = 28.dp,
                            shape = RoundedCornerShape(24.dp),
                            spotColor = animatedAccentColor.copy(alpha = 0.5f),
                            ambientColor = Color.Black
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isFlipped = !isFlipped
                        }
                        .testTag("flip_album_art_card")
                ) {
                    if (flipRotation <= 90f) {
                        // FRONT SIDE: Album Artwork
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = track.coverUrl,
                                contentDescription = "Cover Art - Tap to flip",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF14243B))
                            )

                            // Subtle Hint pill at bottom
                            Surface(
                                color = Color.Black.copy(alpha = 0.58f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = null,
                                        tint = animatedAccentColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Tap to view song credits",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    } else {
                        // BACK SIDE: Song credits and information
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationY = 180f }
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            animatedDominantColor.copy(alpha = 0.95f),
                                            Color(0xFF0D1C2E),
                                            Color(0xFF071220)
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(1.5.dp, animatedAccentColor.copy(alpha = 0.65f)),
                                    RoundedCornerShape(24.dp)
                                )
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    color = animatedAccentColor.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = animatedAccentColor,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "TRACK CREDITS & DETAILS",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = animatedAccentColor,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = track.title,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    color = TextPrimary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                CreditDetailRow(
                                    label = "Album / Movie",
                                    value = track.album.ifBlank { "Original Single" }
                                )
                                CreditDetailRow(
                                    label = "Singers / Artists",
                                    value = if (track.singers.isNotBlank()) track.singers else track.artist
                                )
                                CreditDetailRow(
                                    label = "Composer / Writer",
                                    value = if (track.writer.isNotBlank()) track.writer else "Original Composer"
                                )
                                CreditDetailRow(
                                    label = "Genre & Language",
                                    value = "${track.genre} • ${if (track.language.isNotBlank()) track.language else "Hindi"}${if (track.year.isNotBlank()) " (${track.year})" else ""}"
                                )
                                CreditDetailRow(
                                    label = "Audio Fidelity",
                                    value = "${track.bitrateKbps} kbps Studio Master • 44.1 kHz"
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "🔄 Tap anywhere to flip back",
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // TRACK INFO & FAVORITE TOGGLE
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${track.artist} • ${track.album}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Favorite Heart Toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavorite(track)
                    },
                    modifier = Modifier.testTag("player_favorite_toggle")
                ) {
                    Icon(
                        imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (uiState.isFavorite) XtremeRose else TextMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // TIMELINE SCRUB BAR WITH 320 KBPS BADGE
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isUserScrubbing = true
                        scrubPosition = it
                    },
                    onValueChangeFinished = {
                        val seekPos = (scrubPosition * trackDuration).toLong()
                        onSeekTo(seekPos)
                        isUserScrubbing = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = XtremeLightBlue,
                        activeTrackColor = XtremeLightBlue,
                        inactiveTrackColor = Color(0xFF1C3454)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .testTag("player_scrub_slider")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    )

                    // High Quality Audio Badge
                    Surface(
                        color = Color(0xFF10233B),
                        border = BorderStroke(1.dp, Color(0xFF1F416A)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(XtremeLightBlue)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiState.qualityBadge.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = XtremeLightBlue,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }

                    Text(
                        text = formatTime(trackDuration),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // MAIN CONTROL CLUSTER (Shuffle, Prev, Play/Pause, Next, Repeat)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle Button
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (uiState.isShuffle) XtremeLightBlue else TextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Previous Button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSkipPrevious()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Play / Pause Raised Button with Gradient
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(72.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 3.5.dp,
                            color = XtremeLightBlue
                        )
                    } else {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayPause()
                            },
                            modifier = Modifier
                                .size(68.dp)
                                .shadow(
                                    elevation = 16.dp,
                                    shape = CircleShape,
                                    spotColor = XtremeLightBlue.copy(alpha = 0.8f),
                                    ambientColor = Color.Black
                                )
                                .clip(CircleShape)
                                .background(XtremeGradients.ButtonGradient)
                                .testTag("expanded_player_play_pause")
                        ) {
                            Icon(
                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                tint = Color(0xFF031428),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                // Next Button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSkipNext()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Repeat Mode Button (Off -> All -> One)
                IconButton(onClick = onToggleRepeat) {
                    val icon = when (uiState.repeatMode) {
                        RepeatMode.ONE -> Icons.Default.RepeatOne
                        else -> Icons.Default.Repeat
                    }
                    val tint = when (uiState.repeatMode) {
                        RepeatMode.OFF -> TextMuted
                        else -> XtremeLightBlue
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Repeat Mode",
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // BOTTOM BAR (Audio Output Switcher & Up-Next Queue sheet trigger)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Audio Device / Speaker Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181B22))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speaker,
                        contentDescription = null,
                        tint = XtremeCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Lossless Output • 24-bit/48kHz",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    )
                }

                // Action Button: Up Next Queue
                IconButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.testTag("player_queue_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Up-Next Queue",
                        tint = XtremeLightBlue,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@Composable
private fun CreditDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextMuted,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 12.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PlayerBokehBackground(
    dominantColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bokeh_transition")

    val floatAnim1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "bokeh_float_1"
    )

    val floatAnim2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(13000, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "bokeh_float_2"
    )

    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(5500, easing = FastOutSlowInEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "bokeh_pulse"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Solid opaque base fill: guarantees no bleed-through from background screen
        drawRect(color = Color(0xFF020610))

        // 2. Large deep bokeh orb (Dominant album color) floating upper-left
        val orb1Center = Offset(
            x = w * (0.28f + 0.12f * (floatAnim1 - 0.5f)),
            y = h * (0.26f + 0.10f * (floatAnim2 - 0.5f))
        )
        val orb1Radius = w * 0.78f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    dominantColor.copy(alpha = 0.85f * pulseAnim),
                    dominantColor.copy(alpha = 0.40f * pulseAnim),
                    Color.Transparent
                ),
                center = orb1Center,
                radius = orb1Radius
            ),
            center = orb1Center,
            radius = orb1Radius
        )

        // 3. Medium vibrant bokeh orb (Accent album color) floating mid-right
        val orb2Center = Offset(
            x = w * (0.76f - 0.14f * (floatAnim2 - 0.5f)),
            y = h * (0.44f + 0.12f * (floatAnim1 - 0.5f))
        )
        val orb2Radius = w * 0.68f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.75f * pulseAnim),
                    accentColor.copy(alpha = 0.30f),
                    Color.Transparent
                ),
                center = orb2Center,
                radius = orb2Radius
            ),
            center = orb2Center,
            radius = orb2Radius
        )

        // 4. Secondary bokeh orb (Electric cyan/deep blue) floating bottom-left
        val orb3Center = Offset(
            x = w * (0.32f + 0.16f * (floatAnim2 - 0.5f)),
            y = h * (0.76f - 0.10f * (floatAnim1 - 0.5f))
        )
        val orb3Radius = w * 0.72f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF0284C7).copy(alpha = 0.60f * pulseAnim),
                    dominantColor.copy(alpha = 0.25f),
                    Color.Transparent
                ),
                center = orb3Center,
                radius = orb3Radius
            ),
            center = orb3Center,
            radius = orb3Radius
        )

        // 5. Distinct soft bokeh discs (camera blur circles of varying sizes)
        // Disc A: Upper right glowing disc
        val discARadius = w * 0.26f
        val discACenter = Offset(w * 0.82f, h * 0.18f + 25f * (floatAnim1 - 0.5f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.48f * pulseAnim), Color.Transparent),
                center = discACenter,
                radius = discARadius
            ),
            center = discACenter,
            radius = discARadius
        )

        // Disc B: Mid left soft disc
        val discBRadius = w * 0.20f
        val discBCenter = Offset(w * 0.12f, h * 0.50f - 30f * (floatAnim2 - 0.5f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(dominantColor.copy(alpha = 0.52f), Color.Transparent),
                center = discBCenter,
                radius = discBRadius
            ),
            center = discBCenter,
            radius = discBRadius
        )

        // Disc C: Bottom right disc
        val discCRadius = w * 0.24f
        val discCCenter = Offset(w * 0.84f, h * 0.80f + 20f * (floatAnim1 - 0.5f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.40f * pulseAnim), Color.Transparent),
                center = discCCenter,
                radius = discCRadius
            ),
            center = discCCenter,
            radius = discCRadius
        )

        // Disc D: Subtle center luminous micro-disc
        val discDRadius = w * 0.14f
        val discDCenter = Offset(w * 0.50f, h * 0.36f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                center = discDCenter,
                radius = discDRadius
            ),
            center = discDCenter,
            radius = discDRadius
        )

        // 6. Deep cinematographic vignette overlay: ensures text and controls have pristine contrast
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF020610).copy(alpha = 0.40f),
                    Color.Transparent,
                    Color(0xFF020610).copy(alpha = 0.65f),
                    Color(0xFF020610).copy(alpha = 0.95f)
                )
            )
        )
    }
}
