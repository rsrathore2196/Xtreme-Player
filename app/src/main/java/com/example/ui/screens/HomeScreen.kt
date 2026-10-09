package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.AppDynamicLogo
import com.example.ui.components.HomePullRefreshOverscrollLayout
import com.example.ui.components.InfiniteRadioHeroCard
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.SectionState
import java.util.Calendar

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
 * Primary Home Screen:
 * - Dynamic Content & Systematic Fresh Fetching on launch
 * - Smooth Material 3 Pull-to-Refresh with 120Hz high refresh rate target
 * - Maximum 6 Cards layout adhering strictly to requirements:
 *   1. 1x Small Pills-Based Card (2x3 Grid Layout - 6 items total)
 *   2. YouTube Music Home Screen-Based Card ("Quick Picks")
 *   3. Era-Specific Hits Card ("Era-Specific Hits", 90s, 2000s classics)
 *   4. User Selected Country & Language Card (Dynamically powered by user prefs)
 *   5. User Listening History Based Card (Room DB history DAO & frequency analysis)
 *   6. Fully Personalized Recommendation Card (PersonalizedHomeRecommendationEngine)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    isPlaying: Boolean = playerUiState?.isPlaying == true,
    isAutoplayEnabled: Boolean = playerUiState?.isAutoplayEnabled == true,
    currentTrack: MusicTrack? = playerUiState?.currentTrack,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onPlayInfiniteRadio: ((MusicTrack?) -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val heroSeedTrack by homeViewModel.heroSeedTrack.collectAsStateWithLifecycle()
    val pillsCardTracks by homeViewModel.pillsCardTracks.collectAsStateWithLifecycle()
    val ytmCard by homeViewModel.ytmCard.collectAsStateWithLifecycle()
    val eraCard by homeViewModel.eraCard.collectAsStateWithLifecycle()
    val countryLanguageCard by homeViewModel.countryLanguageCard.collectAsStateWithLifecycle()
    val listeningHistoryCard by homeViewModel.listeningHistoryCard.collectAsStateWithLifecycle()
    val personalizedCard by homeViewModel.personalizedCard.collectAsStateWithLifecycle()
    val userProfile by homeViewModel.userProfile.collectAsStateWithLifecycle()
    val isRefreshing by homeViewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshErrorMessage by homeViewModel.refreshErrorMessage.collectAsStateWithLifecycle()

    HomePullRefreshOverscrollLayout(
        isRefreshing = isRefreshing,
        onRefresh = { homeViewModel.refreshHome(isExplicitPull = true) },
        modifier = modifier.fillMaxSize()
    ) { contentOffset ->
        HomeScreenContent(
            heroSeedTrack = heroSeedTrack,
            pillsCardTracks = pillsCardTracks,
            ytmCard = ytmCard,
            eraCard = eraCard,
            countryLanguageCard = countryLanguageCard,
            listeningHistoryCard = listeningHistoryCard,
            personalizedCard = personalizedCard,
            userProfile = userProfile,
            currentTrack = currentTrack ?: playerUiState?.currentTrack,
            isPlaying = isPlaying,
            isAutoplayEnabled = isAutoplayEnabled,
            currentPlayingTrackId = currentPlayingTrackId ?: currentTrack?.id ?: playerUiState?.currentTrack?.id,
            onTrackClick = onTrackClick,
            onToggleFavorite = onToggleFavorite,
            onOpenSettings = onOpenSettings,
            onPlayInfiniteRadio = onPlayInfiniteRadio,
            onAddToQueue = onAddToQueue,
            boundaryOffset = contentOffset,
            refreshErrorMessage = refreshErrorMessage,
            onRetryRefresh = { homeViewModel.refreshHome(isExplicitPull = true) },
            onDismissRefreshError = { homeViewModel.clearRefreshError() }
        )
    }
}

/**
 * Backward-compatible overload for tests and preview components.
 */
@Composable
fun HomeScreen(
    catalogTracks: List<MusicTrack>,
    recentlyPlayed: List<MusicTrack>,
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    isPlaying: Boolean = playerUiState?.isPlaying == true,
    isAutoplayEnabled: Boolean = playerUiState?.isAutoplayEnabled == true,
    currentTrack: MusicTrack? = playerUiState?.currentTrack,
    shelves: List<com.example.ui.ai.HomeShelf> = emptyList(),
    userProfile: UserProfile? = null,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onPlayInfiniteRadio: ((MusicTrack?) -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val resolvedCountry = userProfile?.country?.trim()?.ifBlank { "India" } ?: "India"
    val trendingTitle = if (resolvedCountry.equals("India", ignoreCase = true)) "Trending in India" else "Trending in $resolvedCountry"

    HomeScreenContent(
        heroSeedTrack = recentlyPlayed.firstOrNull() ?: catalogTracks.firstOrNull(),
        pillsCardTracks = recentlyPlayed.take(6),
        ytmCard = SectionState(title = "Quick Picks", tracks = catalogTracks.take(12)),
        eraCard = SectionState(title = "Era-Specific Hits", tracks = catalogTracks.take(12)),
        countryLanguageCard = SectionState(title = trendingTitle, tracks = catalogTracks.take(12)),
        listeningHistoryCard = SectionState(title = "Jump Back In", tracks = recentlyPlayed),
        personalizedCard = SectionState(title = "Recommended for You", tracks = catalogTracks.take(12)),
        userProfile = userProfile,
        currentTrack = currentTrack ?: playerUiState?.currentTrack,
        isPlaying = isPlaying,
        isAutoplayEnabled = isAutoplayEnabled,
        currentPlayingTrackId = currentPlayingTrackId ?: currentTrack?.id ?: playerUiState?.currentTrack?.id,
        onTrackClick = onTrackClick,
        onToggleFavorite = onToggleFavorite,
        onOpenSettings = onOpenSettings,
        onPlayInfiniteRadio = onPlayInfiniteRadio,
        onAddToQueue = onAddToQueue,
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    heroSeedTrack: MusicTrack? = null,
    pillsCardTracks: List<MusicTrack>,
    ytmCard: SectionState,
    eraCard: SectionState,
    countryLanguageCard: SectionState,
    listeningHistoryCard: SectionState,
    personalizedCard: SectionState,
    userProfile: UserProfile?,
    currentTrack: MusicTrack? = null,
    isPlaying: Boolean = false,
    isAutoplayEnabled: Boolean = true,
    currentPlayingTrackId: String? = null,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onPlayInfiniteRadio: ((MusicTrack?) -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    boundaryOffset: Float = 0f,
    refreshErrorMessage: String? = null,
    onRetryRefresh: (() -> Unit)? = null,
    onDismissRefreshError: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val greeting = rememberGreeting()
    val resolvedCurrentTrack = currentTrack
    val currentPlayingId = currentPlayingTrackId ?: resolvedCurrentTrack?.id
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    val screenBg = appColors.scaffoldBackground
    val cardBg = appColors.cardBackground
    val cardBorderColor = appColors.cardBorder
    val textPrimaryColor = appColors.textPrimary
    val textMutedColor = appColors.textMuted

    val homeListState = rememberLazyListState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = homeListState,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .graphicsLayer {
                    translationY = boundaryOffset
                }
                .statusBarsPadding()
                .testTag("home_screen"),
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            // HEADER BAR: Dynamic App Logo, XTREME PLAYER Title, Greeting & Settings Icon (Preserved)
            item(key = "header_bar") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    color = textPrimaryColor
                                )
                            }
                        }

                        // Theme-adaptive Settings button on top right
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(cardBg)
                                .border(BorderStroke(1.dp, cardBorderColor), CircleShape)
                                .clickable { onOpenSettings?.invoke() }
                                .testTag("top_settings_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = textPrimaryColor,
                                modifier = Modifier.size(20.dp)
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
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp,
                                color = textMutedColor
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = userName.uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 21.sp,
                                letterSpacing = 1.sp,
                                color = textPrimaryColor
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Retryable error feedback for failed pull-to-refresh
            if (!refreshErrorMessage.isNullOrBlank()) {
                item(key = "refresh_error_banner") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isDark) Color(0x33EF4444) else Color(0x22DC2626),
                        border = BorderStroke(1.dp, if (isDark) Color(0x66EF4444) else Color(0x55DC2626)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = refreshErrorMessage,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.clickable { onRetryRefresh?.invoke() }
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 1. RESTORED INFINITE RADIO HERO CARD
            // Placement: First content card, below the app header and greeting, above every other card.
            // Visually rendered with edge-to-edge seed artwork, liquid glass pill, prominent title,
            // and tactile play/pause/resume glass button reflecting real session state.
            // Always visible even when user has no listening history.
            // =========================================================================
            item(key = "hero_infinite_radio_card") {
                InfiniteRadioHeroCard(
                    seedTrack = heroSeedTrack,
                    currentPlayingTrackId = currentPlayingId,
                    isPlaying = isPlaying,
                    isLoading = false,
                    onPlayInfiniteRadio = { track ->
                        onPlayInfiniteRadio?.invoke(track)
                    }
                )
            }

            // CARD 1: Recently Played (2 Columns Grid Layout, up to 6 items from real listening history)
            item(key = "card_1_recently_played_grid") {
                val pillsTracks = pillsCardTracks.take(6)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 10.dp)
                ) {
                    Text(
                        text = "Recently Played",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = textPrimaryColor
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (pillsTracks.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, cardBorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 22.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Your recently played songs will appear here.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = textMutedColor,
                                        fontSize = 14.sp
                                    ),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        val rowCount = (pillsTracks.size + 1) / 2
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (row in 0 until rowCount) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val item1 = pillsTracks.getOrNull(row * 2)
                                    val item2 = pillsTracks.getOrNull(row * 2 + 1)
                                    if (item1 != null) {
                                        HabitPillItem(
                                            track = item1,
                                            isPlaying = item1.id == currentPlayingId,
                                            cardBg = cardBg,
                                            cardBorderColor = cardBorderColor,
                                            textPrimaryColor = textPrimaryColor,
                                            textMutedColor = textMutedColor,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onTrackClick(item1, pillsTracks) }
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }

                                    if (item2 != null) {
                                        HabitPillItem(
                                            track = item2,
                                            isPlaying = item2.id == currentPlayingId,
                                            cardBg = cardBg,
                                            cardBorderColor = cardBorderColor,
                                            textPrimaryColor = textPrimaryColor,
                                            textMutedColor = textMutedColor,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onTrackClick(item2, pillsTracks) }
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // CARD 2: YouTube Music Home Screen-Based Card ("Quick Picks")
            item(key = "card_2_ytm_dynamic_feed") {
                SectionCardLayout(
                    state = ytmCard,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }

            // CARD 3: Era-Specific Hits Card (90s Hits, 2000s Nostalgia, Retro Classics)
            item(key = "card_3_era_specific_hits") {
                SectionCardLayout(
                    state = eraCard,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }

            // CARD 4: User Selected Country & Language Card (Dynamically powered by user prefs)
            item(key = "card_4_country_language_hits") {
                SectionCardLayout(
                    state = countryLanguageCard,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }

            // CARD 5: Fully Personalized Recommendation Card (PersonalizedHomeRecommendationEngine)
            item(key = "card_6_personalized_recommendation") {
                SectionCardLayout(
                    state = personalizedCard,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }
        }
    }
}

/**
 * Standard horizontal shelf layout for home cards with skeleton placeholder loader support.
 */
@Composable
fun SectionCardLayout(
    state: SectionState,
    currentPlayingId: String?,
    cardBg: Color,
    cardBorderColor: Color,
    textPrimaryColor: Color,
    textMutedColor: Color,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit
) {
    Column(modifier = Modifier.padding(top = 22.dp)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textPrimaryColor
                )
            )
            if (state.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = textMutedColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (state.isLoading && state.tracks.isEmpty()) {
            SectionShimmerRow()
        } else if (state.tracks.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val safeTracks = state.tracks.distinctBy { it.id }
                itemsIndexed(safeTracks, key = { index, track -> "${state.title}_${track.id}_$index" }) { _, track ->
                    ShelfTrackCard(
                        track = track,
                        isPlaying = track.id == currentPlayingId,
                        cardBg = cardBg,
                        cardBorderColor = cardBorderColor,
                        textPrimaryColor = textPrimaryColor,
                        textMutedColor = textMutedColor,
                        onClick = { onTrackClick(track, safeTracks) }
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cardBg,
                border = BorderStroke(1.dp, cardBorderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.errorMessage.isNotBlank()) state.errorMessage else "Trending songs are currently unavailable for this region.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = textMutedColor,
                            fontSize = 13.5.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Compact Habit Pill for the 2x3 Grid Layout.
 */
@Composable
fun HabitPillItem(
    track: MusicTrack,
    isPlaying: Boolean,
    cardBg: Color,
    cardBorderColor: Color,
    textPrimaryColor: Color,
    textMutedColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    val pillShape = remember { RoundedCornerShape(10.dp) }

    Row(
        modifier = modifier
            .height(52.dp)
            .clip(pillShape)
            .background(if (isPlaying) appColors.primaryAccent.copy(alpha = 0.14f) else cardBg)
            .border(
                BorderStroke(
                    1.dp,
                    if (isPlaying) appColors.primaryAccent.copy(alpha = 0.55f) else cardBorderColor
                ),
                pillShape
            )
            .clickable(onClick = onClick)
            .padding(end = 8.dp)
            .testTag("habit_pill_${track.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                .background(cardBorderColor.copy(alpha = 0.25f))
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track = track, targetSize = 100),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.40f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Playing",
                        tint = appColors.primaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = track.cleanTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isPlaying) appColors.primaryAccent else textPrimaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = track.artist,
                fontSize = 11.sp,
                color = textMutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SectionShimmerRow(shimmerBrush: Brush? = null) {
    val brush = shimmerBrush ?: rememberShimmerBrush()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false
    ) {
        items(4, key = { index -> "shimmer_col_$index" }) {
            Column(modifier = Modifier.width(136.dp)) {
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }
        }
    }
}

@Composable
fun ShelfTrackCard(
    track: MusicTrack,
    isPlaying: Boolean,
    cardBg: Color = if (LocalAppColors.current.isDark) Color(0xFF18181B) else Color(0xFFFFFFFF),
    cardBorderColor: Color = if (LocalAppColors.current.isDark) Color(0xFF27272A) else Color(0xFFE4E4E7),
    textPrimaryColor: Color = if (LocalAppColors.current.isDark) Color(0xFFFFFFFF) else Color(0xFF09090B),
    textMutedColor: Color = if (LocalAppColors.current.isDark) Color(0xFFA1A1AA) else Color(0xFF71717A),
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current

    Column(
        modifier = Modifier
            .width(136.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(136.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(cardBg)
                .border(
                    BorderStroke(
                        1.dp,
                        if (isPlaying) appColors.primaryAccent else cardBorderColor
                    ),
                    RoundedCornerShape(14.dp)
                )
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track = track, targetSize = 160),
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.40f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Playing",
                        tint = appColors.primaryAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.cleanTitle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) appColors.primaryAccent else textPrimaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = track.artist,
            fontSize = 11.sp,
            color = textMutedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

/**
 * Backward-compatible wrapper for test suites and shelf previews.
 */
@Composable
fun QuickPickCard(
    track: MusicTrack,
    isPlaying: Boolean = false,
    onClick: () -> Unit = {}
) {
    ShelfTrackCard(
        track = track,
        isPlaying = isPlaying,
        onClick = onClick
    )
}

