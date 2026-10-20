package com.example.ui.screens

import com.example.ui.components.rememberVisibleInfiniteFloat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Equalizer
import com.example.ui.components.xtremeLiquidGlassBackground
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import com.example.ui.viewmodel.MostListenedArtist
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.SectionState
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private val NoHomeScrollListener: (Any, Boolean) -> Unit = { _, _ -> }
private val LocalHomeScrollListener = androidx.compose.runtime.staticCompositionLocalOf { NoHomeScrollListener }

@Composable
private fun ObserveHomeScroll(state: androidx.compose.foundation.lazy.LazyListState) {
    val listener = LocalHomeScrollListener.current
    if (listener === NoHomeScrollListener) return
    androidx.compose.runtime.LaunchedEffect(state, listener) {
        androidx.compose.runtime.snapshotFlow { state.isScrollInProgress }
            .collect { listener(state, it) }
    }
    androidx.compose.runtime.DisposableEffect(state, listener) {
        onDispose { listener(state, false) }
    }
}

@Composable
private fun ArtistPageButton(forward: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape)
            .xtremeLiquidGlassBackground(shape = CircleShape, elevation = 3.dp, backdrop = null)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (forward) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = if (forward) "See more artists" else "Previous artists",
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun rememberShimmerBackground(): Modifier {
    // Keep the same phase, colors and gradient geometry; only drawing observes each frame.
    val translation = rememberVisibleInfiniteFloat(
        initialValue = -800f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_float"
    )
    val isDark = LocalAppColors.current.isDark
    val colors = remember(isDark) {
        val base = if (isDark) Color(0xFF142033) else Color(0xFFE2E8F0)
        val highlight = if (isDark) Color(0xFF20324E) else Color(0xFFF1F5F9)
        listOf(base, highlight, base)
    }
    return remember(translation, colors) {
        Modifier.drawWithCache {
            val shader = LinearGradientShader(Offset.Zero, Offset(400f, 400f), colors)
            val matrix = android.graphics.Matrix()
            val paint = Paint().apply { this.shader = shader }
            val bounds = Rect(Offset.Zero, size)
            onDrawBehind {
                matrix.setTranslate(translation.value, 0f)
                shader.setLocalMatrix(matrix)
                drawIntoCanvas { it.drawRect(bounds, paint) }
            }
        }
    }
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
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOpenArtist: (com.example.data.repository.CatalogArtist) -> Unit = {}
) {
    val isRefreshing by homeViewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshErrorMessage by homeViewModel.refreshErrorMessage.collectAsStateWithLifecycle()
    val scrollMonitor: (Any, Boolean) -> Unit = remember(homeViewModel) {
        val activeLists = mutableSetOf<Any>()
        return@remember { list, active ->
            if (active) activeLists.add(list) else activeLists.remove(list)
            homeViewModel.setHomeScrolling(activeLists.isNotEmpty())
        }
    }
    val viewportOwner = remember(homeViewModel) { Any() }
    androidx.compose.runtime.DisposableEffect(homeViewModel, viewportOwner) {
        onDispose {
            homeViewModel.setHomeScrolling(false)
            homeViewModel.setRecommendationViewport(viewportOwner, emptySet())
        }
    }
    androidx.compose.runtime.LaunchedEffect(homeViewModel, listState, viewportOwner) {
        androidx.compose.runtime.snapshotFlow {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.filter { item ->
                item.offset + item.size >= layout.viewportStartOffset && item.offset < layout.viewportEndOffset
            }.mapNotNull { it.key as? String }.toSet()
        }.distinctUntilChanged().collect { homeViewModel.setRecommendationViewport(viewportOwner, it) }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalHomeScrollListener provides scrollMonitor) {
        HomePullRefreshOverscrollLayout(
            isRefreshing = isRefreshing,
            onRefresh = { homeViewModel.refreshHome(isExplicitPull = true) },
            modifier = modifier.fillMaxSize()
        ) { contentOffset ->
            HomeScreenContent(
                homeViewModel = homeViewModel,
                listState = listState,
                onOpenArtist = onOpenArtist,
                isRadioSession = playerUiState?.isInfiniteRadio == true,
                isRadioLoading = playerUiState?.isLoading == true,
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
    modifier: Modifier = Modifier,
    meaningfulUniqueTracks: Int = 0
) {
    val resolvedCountry = userProfile?.country?.trim()?.ifBlank { "India" } ?: "India"
    val trendingTitle = if (resolvedCountry.equals("India", ignoreCase = true)) "Trending in India" else "Trending in $resolvedCountry"

    HomeScreenContent(
        heroSeedTrack = recentlyPlayed.firstOrNull() ?: catalogTracks.firstOrNull(),
        pillsCardTracks = recentlyPlayed.take(6),
        ytmCard = SectionState(title = "Quick Picks", tracks = com.example.recommendation.HomeRecommendationPolicy.diversify(catalogTracks, com.example.recommendation.HomeRecommendationPolicy.MAX_SHELF_TRACKS)),
        eraCard = SectionState(title = "Era-Specific Hits", tracks = shelves.firstOrNull { it.id == "shelf_era_classics" }?.tracks.orEmpty()),
        countryLanguageCard = SectionState(title = trendingTitle, tracks = shelves.firstOrNull { it.id == "shelf_country_trending" }?.tracks.orEmpty()),
        listeningHistoryCard = SectionState(title = "Jump Back In", tracks = recentlyPlayed),
        personalizedCard = SectionState(title = "Recommended for You", tracks =
            shelves.filter { it.id in setOf("shelf_quick_picks", "shelf_top_artist", "shelf_mood_vibe") }
                .takeIf { recentlyPlayed.isNotEmpty() || meaningfulUniqueTracks >= 1 }?.let { com.example.recommendation.HomeRecommendationPolicy.interleave(it.map { shelf -> shelf.tracks }) }.orEmpty()),
        isRadioSession = playerUiState?.isInfiniteRadio == true,
        isRadioLoading = playerUiState?.isLoading == true,
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
    pillsCardTracks: List<MusicTrack> = emptyList(),
    ytmCard: SectionState = SectionState(),
    eraCard: SectionState = SectionState(),
    countryLanguageCard: SectionState = SectionState(),
    listeningHistoryCard: SectionState = SectionState(),
    personalizedCard: SectionState = SectionState(),
    userProfile: UserProfile? = null,
    currentTrack: MusicTrack? = null,
    isPlaying: Boolean = false,
    isAutoplayEnabled: Boolean = true,
    currentPlayingTrackId: String? = null,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onPlayInfiniteRadio: ((MusicTrack?) -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    mostListenedArtists: List<MostListenedArtist> = emptyList(),
    similarArtistsCard: SectionState = SectionState(),
    inspiredTrackCard: SectionState = SectionState(),
    isRadioSession: Boolean = false,
    isRadioLoading: Boolean = false,
    boundaryOffset: () -> Float = { 0f },
    refreshErrorMessage: String? = null,
    onRetryRefresh: (() -> Unit)? = null,
    onDismissRefreshError: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOpenArtist: (com.example.data.repository.CatalogArtist) -> Unit = {},
    homeViewModel: HomeViewModel? = null
) {
    // Only structural visibility is observed by the lazy-list owner. Section
    // contents are collected inside the corresponding lazy item below.
    val hasRecent = homeViewModel?.hasRecentlyPlayed?.collectAsStateWithLifecycle()?.value
        ?: pillsCardTracks.isNotEmpty()
    val hasArtists = homeViewModel?.hasListenedArtists?.collectAsStateWithLifecycle()?.value
        ?: mostListenedArtists.isNotEmpty()
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

    ObserveHomeScroll(listState)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .graphicsLayer {
                    translationY = boundaryOffset()
                }
                .statusBarsPadding()
                .testTag("home_screen"),
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            // HEADER BAR: Dynamic App Logo, XTREME PLAYER Title, Greeting & Settings Icon (Preserved)
            item(key = "header_bar", contentType = "header") {
                val profile = homeViewModel?.userProfile?.collectAsStateWithLifecycle()?.value ?: userProfile
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
                                .xtremeLiquidGlassBackground(shape = CircleShape, elevation = 3.dp, backdrop = null)
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

                    val userName = profile?.name?.trim()?.takeIf { it.isNotBlank() } ?: "Listener"

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
                item(key = "refresh_error_banner", contentType = "error_banner") {
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
            // Visually rendered with edge-to-edge lightly blurred artwork, a prominent title,
            // and a radio-only glass button reflecting the actual radio session.
            // Always visible even when user has no listening history.
            // =========================================================================
            artworkItem(state = listState, key = "hero_infinite_radio_card", contentType = "hero") {
                val seed = homeViewModel?.heroSeedTrack?.collectAsStateWithLifecycle()?.value ?: heroSeedTrack
                InfiniteRadioHeroCard(
                    seedTrack = currentTrack ?: seed,
                    currentPlayingTrackId = currentPlayingId,
                    isPlaying = isPlaying,
                    isLoading = isRadioLoading,
                    isRadioSession = isRadioSession,
                    onPlayInfiniteRadio = { track ->
                        onPlayInfiniteRadio?.invoke(track)
                    }
                )
            }

            // CARD 1: Recently Played (2 Columns Grid Layout, up to 6 items from real listening history)
            // Rendered only when pillsCardTracks contains genuine recently played tracks.
            // When history is empty, completely hide heading, card, placeholder message, and extra spacing.
            if (hasRecent) {
                artworkItem(state = listState, key = "card_1_recently_played_grid", contentType = "recent_grid") {
                    val history = homeViewModel?.listeningHistoryCard?.collectAsStateWithLifecycle()?.value
                    val pillsTracks = history?.tracks?.take(6) ?: pillsCardTracks.take(6)
                    if (pillsTracks.isEmpty()) return@artworkItem
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
            artworkItem(state = listState, key = "card_2_ytm_dynamic_feed", contentType = "song_shelf") {
                val section = homeViewModel?.sectionState(com.example.recommendation.UnifiedRecommendationEngine.Shelf.QUICK)
                    ?.collectAsStateWithLifecycle()?.value ?: ytmCard
                SectionCardLayout(
                    state = section,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }

            if (hasArtists) {
                artworkItem(state = listState, key = "most_listened_artists", contentType = "artist_shelf") {
                    val artists = homeViewModel?.mostListenedArtists?.collectAsStateWithLifecycle()?.value ?: mostListenedArtists
                    if (artists.isEmpty()) return@artworkItem
                    var artistPage by rememberSaveable(artists.firstOrNull()?.name) { mutableIntStateOf(0) }
                    val pageCount = (artists.size + 14) / 15
                    val page = artistPage.coerceIn(0, pageCount - 1)
                    val visibleArtists = remember(artists, page) {
                        artists.drop(page * 15).take(15)
                    }
                    val artistListState = rememberLazyListState()
                    ObserveHomeScroll(artistListState)
                    val artistScope = rememberCoroutineScope()
                    Column(Modifier.padding(top = 22.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Most Listened Artists",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = textPrimaryColor
                            )
                            if (page > 0) ArtistPageButton(forward = false, tint = textPrimaryColor) {
                                artistPage = page - 1
                                artistScope.launch { artistListState.scrollToItem(0) }
                            }
                            if (page + 1 < pageCount) ArtistPageButton(forward = true, tint = textPrimaryColor) {
                                artistPage = page + 1
                                artistScope.launch { artistListState.scrollToItem(0) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        LazyRow(
                            state = artistListState,
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(visibleArtists, key = { it.name }, contentType = { "artist_card" }) { artist ->
                                Column(
                                    Modifier.width(168.dp).clip(RoundedCornerShape(18.dp))
                                        .background(cardBg)
                                        .clickable {
                                            onOpenArtist(com.example.data.repository.CatalogArtist("", artist.name))
                                        }.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    VisibleItemArtwork(artistListState, artist.name) {
                                        com.example.ui.components.ArtistPortrait(
                                            artist = com.example.data.repository.CatalogArtist("", artist.name),
                                            deferEnrichment = true,
                                            modifier = Modifier.size(124.8.dp).clip(CircleShape)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(artist.name, color = textPrimaryColor, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                                        maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }

            // CARD 3: Era-Specific Hits Card (90s Hits, 2000s Nostalgia, Retro Classics)
            artworkItem(state = listState, key = "card_3_era_specific_hits", contentType = "song_shelf") {
                val section = homeViewModel?.sectionState(com.example.recommendation.UnifiedRecommendationEngine.Shelf.ERA)
                    ?.collectAsStateWithLifecycle()?.value ?: eraCard
                SectionCardLayout(
                    state = section,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }

            // CARD 4: User Selected Country & Language Card (Dynamically powered by user prefs)
            artworkItem(state = listState, key = "card_4_country_language_hits", contentType = "song_shelf") {
                val section = homeViewModel?.sectionState(com.example.recommendation.UnifiedRecommendationEngine.Shelf.CHART)
                    ?.collectAsStateWithLifecycle()?.value ?: countryLanguageCard
                SectionCardLayout(
                    state = section,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }

            // CARD 5: Fully Personalized Recommendation Card (PersonalizedHomeRecommendationEngine)
            artworkItem(state = listState, key = "card_6_personalized_recommendation", contentType = "song_shelf") {
                val section = homeViewModel?.sectionState(com.example.recommendation.UnifiedRecommendationEngine.Shelf.PERSONALIZED)
                    ?.collectAsStateWithLifecycle()?.value ?: personalizedCard
                SectionCardLayout(
                    state = section,
                    currentPlayingId = currentPlayingId,
                    cardBg = cardBg,
                    cardBorderColor = cardBorderColor,
                    textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor,
                    onTrackClick = onTrackClick
                )
            }
            artworkItem(state = listState, key = "card_7_similar_artists", contentType = "song_shelf") {
                val section = homeViewModel?.sectionState(com.example.recommendation.UnifiedRecommendationEngine.Shelf.SIMILAR)
                    ?.collectAsStateWithLifecycle()?.value ?: similarArtistsCard
                SectionCardLayout(state = section, currentPlayingId = currentPlayingId,
                    cardBg = cardBg, cardBorderColor = cardBorderColor, textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor, onTrackClick = onTrackClick)
            }
            artworkItem(state = listState, key = "card_8_inspired_by_track", contentType = "song_shelf") {
                val section = homeViewModel?.sectionState(com.example.recommendation.UnifiedRecommendationEngine.Shelf.INSPIRED)
                    ?.collectAsStateWithLifecycle()?.value ?: inspiredTrackCard
                SectionCardLayout(state = section, currentPlayingId = currentPlayingId,
                    cardBg = cardBg, cardBorderColor = cardBorderColor, textPrimaryColor = textPrimaryColor,
                    textMutedColor = textMutedColor, onTrackClick = onTrackClick)
            }
        }
    }
}

/** Preserve item keys/geometry while admitting image work only for the visible viewport. */
private fun androidx.compose.foundation.lazy.LazyListScope.artworkItem(
    state: LazyListState, key: String, contentType: String,
    itemContent: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit
) {
    item(key = key, contentType = contentType) {
        VisibleItemArtwork(state, key) { itemContent() }
    }
}

/** No layout node or data transformation; only this item's image consumers observe visibility. */
@Composable
private fun VisibleItemArtwork(state: LazyListState, key: String, content: @Composable () -> Unit) {
    val parentActive = com.example.ui.util.LocalArtworkRequestsActive.current
    val active by remember(state, key, parentActive) {
        androidx.compose.runtime.derivedStateOf {
            if (!parentActive) false else {
                val layout = state.layoutInfo
                layout.visibleItemsInfo.any { item -> item.key == key &&
                    item.offset + item.size > layout.viewportStartOffset && item.offset < layout.viewportEndOffset }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.example.ui.util.LocalArtworkRequestsActive provides active, content = content)
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
    // If no verified tracks and not loading, completely hide the entire shelf
    if (!state.isLoading && state.tracks.isEmpty()) {
        return
    }

    val safeTracks = remember(state.tracks) {
        state.tracks.distinctBy { it.id }.take(com.example.recommendation.HomeRecommendationPolicy.MAX_SHELF_TRACKS)
    }
    val shelfListState = rememberLazyListState()
    ObserveHomeScroll(shelfListState)
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
                state = shelfListState,
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(safeTracks, key = { _, track -> track.id }, contentType = { _, _ -> "home_track" }) { _, track ->
                    VisibleItemArtwork(shelfListState, track.id) {
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
            .height(62.4.dp)
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
                .width(52.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                .background(cardBorderColor.copy(alpha = 0.25f))
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track = track, targetSize = 100,
                    crossfadeEnabled = false, preferStableArtwork = true),
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
                text = remember(track.title) { track.cleanTitle },
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
    val background = if (shimmerBrush != null) Modifier.background(shimmerBrush) else rememberShimmerBackground()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false
    ) {
        items(4, key = { index -> "shimmer_col_$index" }) {
            Column(modifier = Modifier.width(163.2.dp)) {
                Box(
                    modifier = Modifier
                        .size(163.2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .then(background)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .then(background)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .then(background)
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
            .width(163.2.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(163.2.dp)
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
                model = rememberOptimizedImageRequest(track = track, targetSize = com.example.ui.util.ImageConfig.RECOMMENDATION_CARD_SIZE,
                    crossfadeEnabled = false, preferStableArtwork = true),
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
            text = remember(track.title) { track.cleanTitle },
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

