package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.MusicTrack
import com.example.data.remote.MixItem
import com.example.data.remote.MusicDataSource
import com.example.playback.PlayerUiState
import com.example.ui.ai.HomeShelf
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremeRose
import java.util.Calendar

@Composable
fun HomeScreen(
    catalogTracks: List<MusicTrack>,
    recentlyPlayed: List<MusicTrack>,
    playerUiState: PlayerUiState,
    shelves: List<HomeShelf> = emptyList(),
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val greeting = rememberGreeting()
    val currentPlayingId = playerUiState.currentTrack?.id
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(XtremeGradients.ScreenBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .testTag("home_screen"),
            contentPadding = PaddingValues(bottom = 130.dp)
        ) {
            // HEADER BAR WITH APP LOGO
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_xtreme_logo),
                            contentDescription = "Xtreme Player Logo",
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "XTREME",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 2.sp,
                                        color = if (isDark) XtremeLightBlue else Color(0xFF0284C7)
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "PLAYER",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 2.sp,
                                        color = TextPrimary
                                    )
                                )
                            }
                            Text(
                                text = greeting,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            }

            // HERO FEATURED TRACK
            if (catalogTracks.isNotEmpty()) {
                val heroTrack = catalogTracks.first()
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = appColors.cardBackground),
                        border = BorderStroke(1.dp, appColors.cardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .clickable { onTrackClick(heroTrack, catalogTracks) }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        ) {
                            AsyncImage(
                                model = heroTrack.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Gradient Shade for contrast
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = if (isDark) {
                                                listOf(
                                                    Color.Transparent,
                                                    Color(0xCC0E0E12),
                                                    Color(0xF00A0B0E)
                                                )
                                            } else {
                                                listOf(
                                                    Color.Transparent,
                                                    Color(0x990F172A),
                                                    Color(0xEE0A1828)
                                                )
                                            }
                                        )
                                    )
                            )

                            // Content Overlay
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = (if (isDark) XtremeCyan else Color(0xFF38BDF8)).copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "FEATURED 320K STREAM",
                                            color = if (isDark) XtremeCyan else Color(0xFFE0F2FE),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = heroTrack.title,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${heroTrack.artist} • ${heroTrack.album}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = Color(0xFFBAE6FD)
                                            ),
                                            maxLines = 1
                                        )
                                    }

                                    // Play Circle Button
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .shadow(
                                                elevation = 10.dp,
                                                shape = CircleShape,
                                                spotColor = (if (isDark) XtremeLightBlue else Color(0xFF0284C7)).copy(alpha = 0.6f)
                                            )
                                            .clip(CircleShape)
                                            .background(
                                                if (isDark) XtremeGradients.ButtonGradient
                                                else Brush.linearGradient(listOf(Color(0xFF0284C7), Color(0xFF2563EB)))
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = if (isDark) Color(0xFF031428) else Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SMART AI BACKGROUND SHELVES (Personalized like Spotify / Apple Music)
            shelves.forEach { shelf ->
                item(key = "shelf_${shelf.id}") {
                    Column(modifier = Modifier.padding(top = 22.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = shelf.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            if (shelf.subtitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = shelf.subtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextMuted
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(shelf.tracks, key = { it.id }) { track ->
                                ShelfTrackCard(
                                    track = track,
                                    isPlaying = track.id == currentPlayingId,
                                    onClick = { onTrackClick(track, shelf.tracks) }
                                )
                            }
                        }
                    }
                }
            }

            // TOP MIXES CAROUSEL
            item {
                Column(modifier = Modifier.padding(top = 24.dp)) {
                    Text(
                        text = "Top Mixes for You",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(MusicDataSource.topMixes) { mix ->
                            MixCard(mix = mix) {
                                val mixTracks = catalogTracks.filter {
                                    it.genre.equals(mix.targetGenre, ignoreCase = true)
                                }.ifEmpty { catalogTracks }
                                onTrackClick(mixTracks.first(), mixTracks)
                            }
                        }
                    }
                }
            }

            // QUICK PICKS (Recently Played or Quick Grid)
            val quickTracks = if (recentlyPlayed.isNotEmpty()) recentlyPlayed.take(4) else catalogTracks.take(4)
            if (quickTracks.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 24.dp, start = 20.dp, end = 20.dp)) {
                        Text(
                            text = if (recentlyPlayed.isNotEmpty()) "Jump Back In" else "Quick Picks",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 2x2 Grid of cards
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (i in 0 until (quickTracks.size + 1) / 2) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val first = quickTracks.getOrNull(i * 2)
                                    val second = quickTracks.getOrNull(i * 2 + 1)

                                    if (first != null) {
                                        QuickPickCard(
                                            track = first,
                                            isPlaying = first.id == currentPlayingId,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onTrackClick(first, catalogTracks) }
                                        )
                                    }
                                    if (second != null) {
                                        QuickPickCard(
                                            track = second,
                                            isPlaying = second.id == currentPlayingId,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onTrackClick(second, catalogTracks) }
                                        )
                                    } else if (first != null) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TRENDING 320KBPS TRACKS LIST
            item {
                Column(modifier = Modifier.padding(top = 26.dp, start = 20.dp, end = 20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "All Curated Streams",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "${catalogTracks.size} Tracks",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            items(catalogTracks) { track ->
                val isPlaying = track.id == currentPlayingId
                TrackListItem(
                    track = track,
                    isPlaying = isPlaying,
                    onClick = { onTrackClick(track, catalogTracks) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ShelfTrackCard(
    track: MusicTrack,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    Column(
        modifier = Modifier
            .width(136.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(136.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isDark) Color(0xFF14243B) else Color(0xFFE2EDFB))
                .border(
                    BorderStroke(
                        1.dp,
                        if (isPlaying) (if (isDark) XtremeLightBlue else Color(0xFF0284C7))
                        else appColors.cardBorder
                    ),
                    RoundedCornerShape(14.dp)
                )
        ) {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Bitrate badge
            Surface(
                color = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.88f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            ) {
                Text(
                    text = "${track.bitrateKbps}K",
                    color = if (isDark) XtremeCyan else Color(0xFF0284C7),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            // Playing indicator
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Playing",
                        tint = XtremeCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) (if (isDark) XtremeLightBlue else Color(0xFF0284C7)) else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            fontSize = 11.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MixCard(mix: MixItem, onClick: () -> Unit) {
    val appColors = LocalAppColors.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = appColors.cardBackground),
        border = BorderStroke(1.dp, appColors.cardBorder),
        modifier = Modifier
            .width(145.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            AsyncImage(
                model = mix.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(125.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = mix.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mix.description,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun QuickPickCard(
    track: MusicTrack,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isPlaying) {
            if (isDark) XtremeLightBlue.copy(alpha = 0.18f) else Color(0xFF0284C7).copy(alpha = 0.12f)
        } else appColors.cardBackground,
        border = BorderStroke(
            1.dp,
            if (isPlaying) (if (isDark) XtremeLightBlue else Color(0xFF0284C7)) else appColors.cardBorder
        ),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(6.dp)
        ) {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = if (isPlaying) (if (isDark) XtremeLightBlue else Color(0xFF0284C7)) else TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TrackListItem(
    track: MusicTrack,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isPlaying) {
                    if (isDark) XtremeLightBlue.copy(alpha = 0.12f) else Color(0xFF0284C7).copy(alpha = 0.08f)
                } else Color.Transparent
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        AsyncImage(
            model = track.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(appColors.cardBorder)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isPlaying) (if (isDark) XtremeLightBlue else Color(0xFF0284C7)) else TextPrimary,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Source and quality badge
                val isYt = track.source == "YouTube Music"
                val badgeText = if (isYt) "YT Music" else "JioSaavn • 320k"
                val badgeColor = if (isYt) Color(0xFFFF0033) else (if (isDark) XtremeLightBlue else Color(0xFF0284C7))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${track.artist} • ${track.formatDuration()}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Equalizer visual or favorite button
        if (isPlaying) {
            Icon(
                imageVector = Icons.Default.Equalizer,
                contentDescription = "Playing",
                tint = if (isDark) XtremeLightBlue else Color(0xFF0284C7),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (track.isLiked) XtremeRose else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun rememberGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..22 -> "Good evening"
        else -> "Late Night Sessions"
    }
}
