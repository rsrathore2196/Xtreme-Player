package com.example.ui.components

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
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
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.playback.RepeatMode
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeRose

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
    onOpenEqualizer: () -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack ?: return
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var isMenuOpen by remember { mutableStateOf(false) }
    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF16231C), // Ambient dark emerald/cyan top glow
                        Color(0xFF0F1116),
                        Color(0xFF090A0D)
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .testTag("expanded_player_screen")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
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
                        text = "PLAYING FROM XTREME 320K",
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
                            color = XtremeGreen,
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
                        modifier = Modifier.background(Color(0xFF1E222B))
                    ) {
                        DropdownMenuItem(
                            text = { Text("320kbps Equalizer", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = XtremeGreen) },
                            onClick = {
                                isMenuOpen = false
                                onOpenEqualizer()
                            }
                        )
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

            // CENTER ALBUM ART WITH AMBIENT GLOW
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Ambient Glow Layer
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    XtremeGreen.copy(alpha = 0.35f),
                                    XtremeCyan.copy(alpha = 0.2f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // High-Res Rounded Squircle Artwork
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = "Cover Art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(albumArtScale)
                        .aspectRatio(1f)
                        .shadow(
                            elevation = 28.dp,
                            shape = RoundedCornerShape(24.dp),
                            spotColor = XtremeGreen.copy(alpha = 0.5f),
                            ambientColor = Color.Black
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF222631))
                )
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
                        thumbColor = XtremeGreen,
                        activeTrackColor = XtremeGreen,
                        inactiveTrackColor = Color(0xFF2B313F)
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

                    // 320 kbps High Quality Audio Badge
                    Surface(
                        color = XtremeGreen.copy(alpha = 0.16f),
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
                                    .background(XtremeGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "HQ • 320 KBPS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = XtremeGreen,
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
                        tint = if (uiState.isShuffle) XtremeGreen else TextMuted,
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

                // Play / Pause Raised Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(72.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 3.5.dp,
                            color = XtremeGreen
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
                                    elevation = 14.dp,
                                    shape = CircleShape,
                                    spotColor = XtremeGreen.copy(alpha = 0.7f),
                                    ambientColor = Color.Black
                                )
                                .clip(CircleShape)
                                .background(XtremeGreen)
                                .testTag("expanded_player_play_pause")
                        ) {
                            Icon(
                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                tint = Color.Black,
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
                        else -> XtremeGreen
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

                // Action Buttons: Equalizer & Up Next Queue
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenEqualizer,
                        modifier = Modifier.testTag("player_equalizer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Equalizer & Audio Effects",
                            tint = XtremeGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.testTag("player_queue_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Up-Next Queue",
                            tint = TextPrimary,
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
