package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.PlaylistEntity
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeBorder
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremePurple
import com.example.ui.theme.XtremeRose

@Composable
fun LibraryScreen(
    favoriteTracks: List<MusicTrack>,
    playlists: List<PlaylistEntity>,
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    isDarkMode: Boolean = false,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onImportPlaylistClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Playlists", "Liked Songs")
    val isDark = isDarkMode
    val appColors = LocalAppColors.current

    val currentPlayingId = currentPlayingTrackId ?: playerUiState?.currentTrack?.id

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(appColors.screenBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .testTag("library_screen"),
            contentPadding = PaddingValues(bottom = 150.dp)
        ) {
        // TOP BAR
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Your Library",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                letterSpacing = (-0.6).sp,
                                color = appColors.textPrimary
                            )
                        )
                        Text(
                            text = "Saved music, custom playlists & liked songs",
                            style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted)
                        )
                    }

                    // Add playlist button (liquid glass styled with theme-specific color)
                    IconButton(
                        onClick = {
                            onCreatePlaylistClick()
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                spotColor = appColors.primaryAccent.copy(alpha = 0.40f)
                            )
                            .clip(CircleShape)
                            .background(
                                LiquidGlass.glassBrush(
                                    colors = appColors,
                                    translucency = 0.85f,
                                    tintAccent = true
                                )
                            )
                            .border(
                                BorderStroke(
                                    1.2.dp,
                                    LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.65f)
                                ),
                                CircleShape
                            )
                            .testTag("create_playlist_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create Playlist",
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3D Liquid Glass Segmented Tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassCard(appColors, shape = RoundedCornerShape(16.dp), elevation = 4.dp, translucency = 0.95f)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        val selectedTabBg = if (appColors.isDark) {
                            if (appColors.isAmoled) {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF282828),
                                        Color(0xFF181818),
                                        Color(0xFF0E0E0E)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = 0.40f),
                                        Color(0xFF1E2838).copy(alpha = 0.85f),
                                        appColors.primaryAccent.copy(alpha = 0.22f)
                                    )
                                )
                            }
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    appColors.primaryAccent.copy(alpha = 0.92f),
                                    appColors.secondaryAccent.copy(alpha = 0.85f)
                                )
                            )
                        }
                        val selectedTabBorder = BorderStroke(
                            1.3.dp,
                            if (appColors.isDark) {
                                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.75f)
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.85f),
                                        appColors.primaryAccent.copy(alpha = 0.50f)
                                    )
                                )
                            }
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .then(
                                    if (isSelected) {
                                        Modifier
                                            .background(selectedTabBg)
                                            .border(selectedTabBorder, RoundedCornerShape(12.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                                .bouncyClickable { selectedTab = index }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (appColors.isAmoled) Color.White else if (isSelected) Color.White else (if (appColors.isDark) Color.White.copy(alpha = 0.70f) else appColors.textMuted),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // PLAYLISTS TAB
                // 1. Liked Songs Pin Card
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .liquidGlassCard(appColors, shape = RoundedCornerShape(18.dp), elevation = 2.dp, translucency = 0.90f, tintAccent = true)
                            .clickable {
                                if (favoriteTracks.isNotEmpty()) {
                                    onTrackClick(favoriteTracks.first(), favoriteTracks)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val likedSongsBoxBg = if (appColors.isAmoled) {
                                Brush.linearGradient(listOf(Color(0xFF242424), Color(0xFF141414)))
                            } else {
                                Brush.linearGradient(listOf(appColors.primaryAccent, appColors.secondaryAccent))
                            }
                            val likedSongsHeartTint = if (appColors.isAmoled) {
                                Color(0xFFFF3B69)
                            } else {
                                appColors.onPrimaryAccent
                            }

                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(likedSongsBoxBg)
                                    .then(
                                        if (appColors.isAmoled) Modifier.border(1.dp, Color(0xFF383838), RoundedCornerShape(12.dp))
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = likedSongsHeartTint,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Liked Songs",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = if (appColors.isDark) Color.White else appColors.textPrimary
                                    )
                                )
                                Text(
                                    text = "${favoriteTracks.size} tracks saved in 320kbps",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (appColors.isDark) Color(0xFFCBD5E1) else appColors.textSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }

                            if (favoriteTracks.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(appColors.primaryAccent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Liked",
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Import Third-Party Playlist Card
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .liquidGlassCard(appColors, shape = RoundedCornerShape(18.dp), elevation = 4.dp, translucency = 0.78f, tintAccent = true)
                            .clickable { onImportPlaylistClick() }
                            .testTag("import_playlist_banner_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        appColors.primaryAccent.copy(alpha = if (isDark) 0.20f else 0.14f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = appColors.primaryAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Import Playlists",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = if (appColors.isDark) Color.White else appColors.textPrimary
                                )

                                Text(
                                    text = "Spotify, Apple Music, YouTube",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.primaryAccent
                                )
                                Text(
                                    text = "Auto-matches tracks to 320kbps HD audio",
                                    fontSize = 11.sp,
                                    color = if (appColors.isDark) Color(0xFFCBD5E1) else appColors.textMuted
                                )
                            }
                        }
                    }
                }

                // Custom Playlists List
                if (playlists.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp, start = 20.dp, end = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = appColors.textMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No custom playlists yet",
                                color = appColors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Create custom collections or import from Spotify, YouTube & Apple Music.",
                                color = appColors.textMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = onCreatePlaylistClick,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = appColors.primaryAccent,
                                        contentColor = if (isDark) Color(0xFF101014) else Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "Create Playlist",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp,
                                        maxLines = 1
                                    )
                                }

                                androidx.compose.material3.OutlinedButton(
                                    onClick = onImportPlaylistClick,
                                    border = BorderStroke(
                                        1.dp,
                                        appColors.primaryAccent.copy(alpha = 0.6f)
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = appColors.primaryAccent
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Import External",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = "Custom Playlists (${playlists.size})",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = appColors.textPrimary
                            ),
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)
                        )
                    }

                    items(playlists, key = { "pl_${it.playlistId}" }) { playlist ->
                        PlaylistRowItem(
                            playlist = playlist,
                            onClick = { onOpenPlaylist(playlist.playlistId) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            1 -> {
                // LIKED SONGS TAB
                if (favoriteTracks.isEmpty()) {
                    item(key = "empty_liked_songs") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp, start = 20.dp, end = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = appColors.textMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Songs you like will appear here",
                                color = appColors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Tap the heart icon on any song to save it to your library.",
                                color = appColors.textMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, start = 16.dp, end = 16.dp)
                            )
                        }
                    }
                } else {
                    items(favoriteTracks, key = { "fav_${it.id}" }) { track ->
                        TrackListItem(
                            track = track,
                            isPlaying = track.id == currentPlayingId,
                            onClick = { onTrackClick(track, favoriteTracks) },
                            onToggleFavorite = { onToggleFavorite(track) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
fun PlaylistRowItem(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassCard(appColors, shape = RoundedCornerShape(14.dp), elevation = 1.dp, translucency = 0.90f)
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = rememberOptimizedImageRequest(playlist.coverUrl, ImageConfig.LIST_ITEM_SIZE),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (appColors.isDark) appColors.cardBackgroundElevated else Color(0xFFE2E8F0))
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                color = appColors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (playlist.description.isNotBlank()) playlist.description else "Custom Playlist • Xtreme 320k",
                color = appColors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
