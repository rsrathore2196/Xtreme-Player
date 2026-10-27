package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.example.ui.util.landscapeContentBounds
import com.example.ui.util.landscapePlaylistColumns
import com.example.ui.util.landscapePlaylistTileWidth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import com.example.ui.components.bouncyOverscroll
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import com.example.ui.components.TrackListItem
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberPlaylistCoverRequest
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
    onOpenArtist: (com.example.data.repository.CatalogArtist) -> Unit = {},
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Playlists", "Listening Stats")
    var likedOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var period by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(com.example.data.local.ListeningPeriod.WEEKLY) }
    val statsScroll = rememberLazyListState()
    val likedScroll = rememberLazyListState()
    if (likedOpen) {
        androidx.activity.compose.BackHandler { likedOpen = false }
        val liked by androidx.compose.runtime.produceState<com.example.data.local.PlaylistWithTracks?>(null, favoriteTracks) {
            value = kotlinx.coroutines.withContext(com.example.performance.AppDispatchers.CpuBounded) {
                com.example.data.local.PlaylistWithTracks(com.example.data.local.PlaylistEntity(-1L, "Liked Songs", "Your favorite songs",
                    favoriteTracks.firstOrNull()?.coverUrl.orEmpty()), favoriteTracks.map(com.example.data.local.TrackEntity::fromMusicTrack)).also { it.musicTracks }
            }
        }
        liked?.let { PlaylistDetailScreen(it, currentPlayingTrackId = currentPlayingTrackId,
            onBackClick = { likedOpen = false }, onPlayTrack = onTrackClick, onToggleFavorite = onToggleFavorite,
            onDeletePlaylist = {}, onRemoveTrack = {}, readOnly = true, pinPlaybackActions = true, listState = likedScroll) }
        return
    }
    val isDark = isDarkMode
    val appColors = LocalAppColors.current
    // Retain the selection motion when the two tab bodies replace their headers.
    val tabPosition = animateFloatAsState(selectedTab.toFloat(),
        animationSpec = spring(dampingRatio = 1f, stiffness = 220f), label = "library_tab_position")
    val tabLabelColors = tabs.indices.map { index ->
        animateColorAsState(if (selectedTab == index) com.example.ui.theme.contrastingContentColor(appColors.primaryAccent)
            else appColors.textMuted, animationSpec = tween(220), label = "library_tab_label_$index")
    }
    val layoutDirection = LocalLayoutDirection.current

    val currentPlayingId = currentPlayingTrackId ?: playerUiState?.currentTrack?.id
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(appColors.scaffoldBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        val libraryHeader: @Composable () -> Unit = {
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
                    if (selectedTab == 0) {
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
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(Modifier.fillMaxWidth().testTag("library_tabs")) {
                    Box(Modifier.matchParentSize().liquidGlassCard(appColors,
                        shape = RoundedCornerShape(16.dp), elevation = 4.dp, translucency = 0.95f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                            .drawBehind {
                                val gap = 4.dp.toPx()
                                val tabWidth = (size.width - gap) / 2f
                                val progress = tabPosition.value.coerceIn(0f, 1f)
                                val position = if (layoutDirection == LayoutDirection.Rtl) 1f - progress else progress
                                drawRoundRect(appColors.primaryAccent,
                                    topLeft = Offset((tabWidth + gap) * position, 0f),
                                    size = Size(tabWidth, size.height), cornerRadius = CornerRadius(12.dp.toPx()))
                            },
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val isSelected = selectedTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .bouncyClickable { selectedTab = index }
                                    .semantics { selected = isSelected; role = androidx.compose.ui.semantics.Role.Tab }
                                    .testTag("library_tab_$index")
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = title,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = tabLabelColors[index].value, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

        }
        val columns = if (landscape) landscapePlaylistColumns(maxWidth.value) else 2
        val tileWidth = if (landscape) landscapePlaylistTileWidth(maxWidth.value) else 0f
        val playlistRows = remember(playlists, columns) { playlists.chunked(columns) }
        val coverSize = if (landscape) with(LocalDensity.current) {
            tileWidth.dp.roundToPx().coerceIn(120, 1000)
        } else ImageConfig.PLAYLIST_HEADER_SIZE
        if (selectedTab == 1) {
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp)
                .landscapeContentBounds(landscape, maxWidthDp = null).fillMaxSize()
                .statusBarsPadding().testTag("library_screen")) {
                libraryHeader()
                ListeningStatsScreen(period, { period = it }, { selectedTab = 0 }, onTrackClick, onOpenArtist,
                    statsScroll, embedded = true, modifier = Modifier.weight(1f))
            }
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .landscapeContentBounds(landscape, maxWidthDp = null)
                .bouncyOverscroll()
                .statusBarsPadding()
                .testTag("library_screen"),
            contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 150.dp)
        ) {
        item { libraryHeader() }

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
                            .clickable { likedOpen = true }
                            .testTag("open_liked_songs")
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
                                    text = "${favoriteTracks.size} tracks liked",
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
                                        .clickable { favoriteTracks.firstOrNull()?.let { onTrackClick(it, favoriteTracks) } }
                                        .testTag("play_liked_songs")
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

                    items(playlistRows, key = { row -> "pl_row_${row.joinToString("_") { it.playlistId.toString() }}" }) { rowItems ->
                        val rowKey = remember(rowItems) { "pl_row_${rowItems.joinToString("_") { it.playlistId.toString() }}" }
                        val rowVisible by remember(listState, rowKey) {
                            derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == rowKey } }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 7.dp),
                            horizontalArrangement = if (landscape) Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
                                else Arrangement.spacedBy(14.dp)
                        ) {
                            rowItems.forEach { playlist ->
                                key(playlist.playlistId) {
                                    PlaylistGridCard(
                                        playlist = playlist,
                                        onClick = { onOpenPlaylist(playlist.playlistId) },
                                        coverRequestsActive = rowVisible,
                                        coverSize = coverSize,
                                        modifier = if (landscape) Modifier.width(tileWidth.dp) else Modifier.weight(1f)
                                    )
                                }
                            }
                            repeat(columns - rowItems.size) {
                                Spacer(modifier = if (landscape) Modifier.width(tileWidth.dp) else Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}

/**
 * Big square playlist card showing high-resolution album art with title and details positioned below.
 * Displayed 2 side-by-side in a responsive grid.
 */
@Composable
fun PlaylistGridCard(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverRequestsActive: Boolean = true,
    coverSize: Int = ImageConfig.PLAYLIST_HEADER_SIZE
) {
    val appColors = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .bouncyClickable(onClick = onClick)
            .testTag("playlist_card_${playlist.playlistId}")
    ) {
        // Big square album art
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(18.dp),
                    spotColor = if (appColors.isDark) Color.Black.copy(alpha = 0.45f) else appColors.primaryAccent.copy(alpha = 0.20f)
                )
                .clip(RoundedCornerShape(18.dp))
                .background(if (appColors.isDark) appColors.cardBackgroundElevated else Color(0xFFE2E8F0))
                .border(
                    BorderStroke(
                        1.dp,
                        if (appColors.dynamicGlassEnabled) LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.45f)
                        else Brush.verticalGradient(listOf(appColors.cardBorder, appColors.cardBorder))
                    ),
                    RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (playlist.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = rememberPlaylistCoverRequest(playlist.coverUrl, coverSize,
                        loadAllowed = coverRequestsActive),
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val fallbackBrush = if (appColors.isAmoled) {
                    Brush.linearGradient(listOf(Color(0xFF282828), Color(0xFF141414)))
                } else {
                    Brush.linearGradient(
                        listOf(
                            appColors.primaryAccent.copy(alpha = 0.35f),
                            appColors.secondaryAccent.copy(alpha = 0.20f)
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(fallbackBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = appColors.primaryAccent,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Playlist name below the big square album art
        Text(
            text = playlist.title,
            color = appColors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        // Description / subtitle below name
        Text(
            text = if (playlist.description.isNotBlank()) playlist.description else "Custom Playlist",
            color = appColors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
        )
    }
}

@Composable
fun PlaylistRowItem(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlaylistGridCard(playlist = playlist, onClick = onClick, modifier = modifier)
}
