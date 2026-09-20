package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.R
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.data.remote.MixItem
import com.example.data.remote.MusicDataSource
import com.example.playback.PlayerUiState
import com.example.ui.ai.HomeShelf
import com.example.ui.components.AppDynamicLogo
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremeRose
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.SectionState
import java.util.Calendar

@Composable
fun rememberOptimizedImageRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(200)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}

@Composable
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = -800f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_float"
    )
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val baseColor = if (isDark) Color(0xFF142033) else Color(0xFFE2E8F0)
    val highlightColor = if (isDark) Color(0xFF20324E) else Color(0xFFF1F5F9)

    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 400f, 400f)
    )
}

/**
 * Primary Home Screen using HomeViewModel for instant local-first render,
 * parallel data orchestration, and deferred section loading.
 */
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    playerUiState: PlayerUiState,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val catalogTracks by homeViewModel.catalogTracks.collectAsState()
    val recentlyPlayed by homeViewModel.recentlyPlayed.collectAsState()
    val shelves by homeViewModel.homeShelves.collectAsState()
    val userProfile by homeViewModel.userProfile.collectAsState()
    val punjabiSection by homeViewModel.punjabiSection.collectAsState()
    val eraSection by homeViewModel.eraSection.collectAsState()

    HomeScreenContent(
        catalogTracks = catalogTracks,
        recentlyPlayed = recentlyPlayed,
        playerUiState = playerUiState,
        shelves = shelves,
        userProfile = userProfile,
        punjabiSection = punjabiSection,
        eraSection = eraSection,
        onLoadPunjabiSection = { homeViewModel.loadPunjabiSectionIfNeeded() },
        onLoadEraSection = { homeViewModel.loadEraSectionIfNeeded() },
        onTrackClick = onTrackClick,
        onToggleFavorite = onToggleFavorite,
        modifier = modifier
    )
}

/**
 * Backward-compatible overload for components or tests calling HomeScreen directly.
 */
@Composable
fun HomeScreen(
    catalogTracks: List<MusicTrack>,
    recentlyPlayed: List<MusicTrack>,
    playerUiState: PlayerUiState,
    shelves: List<HomeShelf> = emptyList(),
    userProfile: UserProfile? = null,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    HomeScreenContent(
        catalogTracks = catalogTracks,
        recentlyPlayed = recentlyPlayed,
        playerUiState = playerUiState,
        shelves = shelves,
        userProfile = userProfile,
        punjabiSection = SectionState(),
        eraSection = SectionState(),
        onLoadPunjabiSection = null,
        onLoadEraSection = null,
        onTrackClick = onTrackClick,
        onToggleFavorite = onToggleFavorite,
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    catalogTracks: List<MusicTrack>,
    recentlyPlayed: List<MusicTrack>,
    playerUiState: PlayerUiState,
    shelves: List<HomeShelf>,
    userProfile: UserProfile?,
    punjabiSection: SectionState,
    eraSection: SectionState,
    onLoadPunjabiSection: (() -> Unit)?,
    onLoadEraSection: (() -> Unit)?,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val greeting = rememberGreeting()
    val currentPlayingId = playerUiState.currentTrack?.id
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val shimmerBrush = rememberShimmerBrush()

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
            item(key = "header_bar") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppDynamicLogo(modifier = Modifier.size(38.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "XTREME",
                                fontWeight = FontWeight.Black,
                                fontSize = 21.sp,
                                letterSpacing = 1.sp,
                                color = appColors.primaryAccent
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "PLAYER",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 21.sp,
                                letterSpacing = 1.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val userName = userProfile?.name?.trim()?.takeIf { it.isNotBlank() } ?: "Listener"

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 50.dp)
                    ) {
                        // Greeting text aligned with the app name text, size increased > 15%
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp,
                                color = TextMuted
                            )
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        // User name under the greeting: all capital characters, size equal to app name text (21.sp)
                        Text(
                            text = userName.uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 21.sp,
                                letterSpacing = 1.sp,
                                color = TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // HERO FEATURED TRACK
            if (catalogTracks.isNotEmpty()) {
                val heroTrack = catalogTracks.first()
                item(key = "hero_track_${heroTrack.id}") {
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
                                .background(appColors.cardBorder)
                        ) {
                            AsyncImage(
                                model = rememberOptimizedImageRequest(heroTrack.coverUrl),
                                contentDescription = heroTrack.title,
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
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${heroTrack.artist} • ${heroTrack.genre}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = Color.White.copy(alpha = 0.8f)
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Play Button FAB
                                    Surface(
                                        shape = CircleShape,
                                        color = appColors.primaryAccent,
                                        shadowElevation = 8.dp,
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clickable { onTrackClick(heroTrack, catalogTracks) }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (heroTrack.id == currentPlayingId) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = Color.Black,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SMART AI BACKGROUND SHELVES
            if (shelves.isNotEmpty()) {
                items(shelves, key = { "shelf_${it.id}" }) { shelf ->
                    Column(modifier = Modifier.padding(top = 24.dp)) {
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
                            items(shelf.tracks, key = { "track_${shelf.id}_${it.id}" }) { track ->
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
            item(key = "top_mixes_carousel") {
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
                        items(MusicDataSource.topMixes, key = { "mix_${it.title}" }) { mix ->
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
                item(key = "quick_picks_grid") {
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

            // DEFERRED LAZY SECTION: TOP PUNJABI ARTISTS & HITS
            item(key = "section_punjabi_hits") {
                LaunchedEffect(Unit) {
                    onLoadPunjabiSection?.invoke()
                }

                Column(modifier = Modifier.padding(top = 24.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = punjabiSection.title.ifBlank { "Top Punjabi Artists & Hits" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        if (punjabiSection.subtitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = punjabiSection.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextMuted
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (punjabiSection.isLoading && punjabiSection.tracks.isEmpty()) {
                        SectionShimmerRow(shimmerBrush = shimmerBrush)
                    } else if (punjabiSection.tracks.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(punjabiSection.tracks, key = { "punjabi_${it.id}" }) { track ->
                                ShelfTrackCard(
                                    track = track,
                                    isPlaying = track.id == currentPlayingId,
                                    onClick = { onTrackClick(track, punjabiSection.tracks) }
                                )
                            }
                        }
                    }
                }
            }

            // DEFERRED LAZY SECTION: ERA-SPECIFIC HITS
            item(key = "section_era_hits") {
                LaunchedEffect(Unit) {
                    onLoadEraSection?.invoke()
                }

                Column(modifier = Modifier.padding(top = 24.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = eraSection.title.ifBlank { "Era-Specific Hits" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        if (eraSection.subtitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = eraSection.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextMuted
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (eraSection.isLoading && eraSection.tracks.isEmpty()) {
                        SectionShimmerRow(shimmerBrush = shimmerBrush)
                    } else if (eraSection.tracks.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(eraSection.tracks, key = { "era_${it.id}" }) { track ->
                                ShelfTrackCard(
                                    track = track,
                                    isPlaying = track.id == currentPlayingId,
                                    onClick = { onTrackClick(track, eraSection.tracks) }
                                )
                            }
                        }
                    }
                }
            }

            // TRENDING 320KBPS TRACKS LIST
            item(key = "all_curated_streams_header") {
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

            items(catalogTracks, key = { "stream_${it.id}" }) { track ->
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
fun SectionShimmerRow(shimmerBrush: Brush) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false
    ) {
        items(4) {
            Column(modifier = Modifier.width(136.dp)) {
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
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
                        if (isPlaying) appColors.primaryAccent
                        else appColors.cardBorder
                    ),
                    RoundedCornerShape(14.dp)
                )
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track.coverUrl),
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
                    color = appColors.primaryAccent,
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
            color = if (isPlaying) appColors.primaryAccent else TextPrimary,
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
            Box(
                modifier = Modifier
                    .size(125.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(appColors.cardBorder)
            ) {
                AsyncImage(
                    model = rememberOptimizedImageRequest(mix.coverUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
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
            appColors.primaryAccent.copy(alpha = if (isDark) 0.18f else 0.12f)
        } else appColors.cardBackground,
        border = BorderStroke(
            1.dp,
            if (isPlaying) appColors.primaryAccent else appColors.cardBorder
        ),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(appColors.cardBorder)
            ) {
                AsyncImage(
                    model = rememberOptimizedImageRequest(track.coverUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = if (isPlaying) appColors.primaryAccent else TextPrimary,
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
                    appColors.primaryAccent.copy(alpha = if (isDark) 0.12f else 0.08f)
                } else Color.Transparent
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with fixed size and background to eliminate jank
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(appColors.cardBorder)
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track.coverUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isPlaying) appColors.primaryAccent else TextPrimary,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Source and quality badge
                val isExtended = track.source == "Extended Stream" || track.id.startsWith("yt_")
                val badgeText = if (isExtended) "HQ • 256k" else "HD • 320k"
                val badgeColor = if (isExtended) Color(0xFFFF5252) else appColors.primaryAccent
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
                tint = appColors.primaryAccent,
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
