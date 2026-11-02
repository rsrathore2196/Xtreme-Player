package com.example.ui.screens

import android.content.res.Configuration
import com.example.data.model.ServerHomeItem
import com.example.data.model.nativeKind
import com.example.ui.components.awaitContentDraw
import com.example.ui.viewmodel.HomeShelfPresentation
import com.example.ui.viewmodel.ShelfLoadStatus
import com.example.data.repository.CatalogDestination
import androidx.compose.ui.platform.LocalConfiguration
import com.example.ui.util.landscapeContentBounds

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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.compositeOver
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
import com.example.ui.viewmodel.HomeFeedWindow
import com.example.ui.viewmodel.HomeRevealGesture
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
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
    // Keep the existing phase and gradient geometry; only drawing observes each frame.
    val translation = rememberVisibleInfiniteFloat(
        initialValue = -800f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_float"
    )
    val palette = LocalAppColors.current
    val colors = remember(palette.cardBackground, palette.textMuted) {
        val base = palette.textMuted.copy(alpha = .10f).compositeOver(palette.cardBackground)
        val highlight = palette.textMuted.copy(alpha = .18f).compositeOver(base)
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

/** Cache-first native shelves, local listening cards and the existing radio hero.
 * Missing content uses the same card geometry while requests complete independently. */
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
    onOpenArtist: (com.example.data.repository.CatalogArtist) -> Unit = {},
    onOpenCatalog: (CatalogDestination) -> Unit = {}
) {
    val isRefreshing by homeViewModel.isRefreshing.collectAsStateWithLifecycle()
    val scrollMonitor: (Any, Boolean) -> Unit = remember(homeViewModel) {
        val activeLists = mutableSetOf<Any>()
        return@remember { list, active ->
            if (active) activeLists.add(list) else activeLists.remove(list)
            homeViewModel.setHomeScrolling(activeLists.isNotEmpty())
        }
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
                onOpenCatalog = onOpenCatalog,
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
            )
        }
    }
}

@Composable
fun HomeScreenContent(
    heroSeedTrack: MusicTrack? = null,
    pillsCardTracks: List<MusicTrack> = emptyList(),
    listeningHistoryCard: SectionState = SectionState(),
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
    isRadioSession: Boolean = false,
    isRadioLoading: Boolean = false,
    boundaryOffset: () -> Float = { 0f },
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOpenArtist: (com.example.data.repository.CatalogArtist) -> Unit = {},
    homeViewModel: HomeViewModel? = null,
    serverShelves: List<com.example.data.model.ServerHomeShelf> = emptyList(),
    profileShelf: com.example.data.model.ServerHomeShelf? = null,
    onOpenCatalog: (CatalogDestination) -> Unit = {}
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Only structural visibility is observed by the lazy-list owner. Section
    // contents are collected inside the corresponding lazy item below.
    val hasRecent = homeViewModel?.hasRecentlyPlayed?.collectAsStateWithLifecycle()?.value
        ?: pillsCardTracks.isNotEmpty()
    val optionalShelvesVisible = homeViewModel?.artistPortraitsReady?.collectAsStateWithLifecycle()?.value ?: true
    val hasArtists = (homeViewModel?.hasListenedArtists?.collectAsStateWithLifecycle()?.value
        ?: mostListenedArtists.isNotEmpty()) && optionalShelvesVisible
    val retainedWindow = rememberSaveable(stateSaver = Saver<HomeFeedWindow, Int>(
        save = { it.visibleCount }, restore = { HomeFeedWindow(it) }
    )) { mutableStateOf(HomeFeedWindow()) }
    val revealModifier = rememberHomeRevealModifier(listState) {
        if (homeViewModel != null) homeViewModel.revealNextHomeSection()
        else retainedWindow.value = retainedWindow.value.appendNext(serverShelves.size)
    }
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

    val nativeCards = homeViewModel?.serverCards?.collectAsStateWithLifecycle()?.value
        ?: remember(serverShelves) { serverShelves.map { HomeShelfPresentation("server_shelf_${it.id}", it, ShelfLoadStatus.SUCCESS) } }
    val artworkActive = com.example.ui.util.LocalArtworkRequestsActive.current
    LaunchedEffect(homeViewModel, listState, artworkActive) {
        if (homeViewModel == null) return@LaunchedEffect
        if (!artworkActive) { homeViewModel.setArtistSectionRelevant(false); return@LaunchedEffect }
        try {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { item ->
                val key = item.key.toString()
                key == "most_listened_artists"
            } }.distinctUntilChanged().collect(homeViewModel::setArtistSectionRelevant)
        } finally { homeViewModel.setArtistSectionRelevant(false) }
    }
    val homeView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(homeViewModel, artworkActive, homeView) {
        if (artworkActive && homeViewModel != null) {
            homeView.awaitContentDraw { homeViewModel.onHomeFrameDrawn() }
        }
    }
    val profileVisible = homeViewModel?.profileShelfVisible?.collectAsStateWithLifecycle()?.value ?: true
    val resolvedProfile = (homeViewModel?.profileShelf?.collectAsStateWithLifecycle()?.value ?: profileShelf)
        ?.takeIf { profileVisible }
    val revealedCount = homeViewModel?.revealedShelfCount?.collectAsStateWithLifecycle()?.value ?: retainedWindow.value.visibleCount

    ObserveHomeScroll(listState)

    androidx.compose.runtime.CompositionLocalProvider(com.example.ui.util.LocalHomeThumbnailLoading provides true) {
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
                .landscapeContentBounds(landscape, maxWidthDp = null)
                .then(revealModifier)
                .graphicsLayer {
                    translationY = boundaryOffset()
                }
                .statusBarsPadding()
                .testTag("home_screen"),
            contentPadding = PaddingValues(bottom = if (landscape) 192.dp else 180.dp)
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


            // =========================================================================
            // 1. RESTORED INFINITE RADIO HERO CARD
            // Placement: First content card, below the app header and greeting, above every other card.
            // Visually rendered with edge-to-edge lightly blurred artwork, a prominent title,
            // and a radio-only glass button reflecting the actual radio session.
            // Always visible even when user has no listening history.
            // =========================================================================
            artworkItem(state = listState, key = "hero_infinite_radio_card", contentType = "hero") {
                val seed = homeViewModel?.heroSeedTrack?.collectAsStateWithLifecycle()?.value ?: heroSeedTrack
                if (landscape) {
                    val history = if (hasRecent) homeViewModel?.listeningHistoryCard?.collectAsStateWithLifecycle()?.value else null
                    LandscapeHomeOverview(
                        seed = currentTrack ?: seed, currentPlayingId = currentPlayingId, isPlaying = isPlaying,
                        isLoading = isRadioLoading, isRadioSession = isRadioSession,
                        recentTracks = if (hasRecent) (history?.tracks ?: pillsCardTracks)
                            .take(com.example.ui.viewmodel.RECENTLY_PLAYED_CARD_LIMIT) else emptyList(),
                        onPlayRadio = { onPlayInfiniteRadio?.invoke(it) }, onTrackClick = onTrackClick)
                } else {
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
            }

            // CARD 1: Recently Played (2 Columns Grid Layout, four real history items)
            // Rendered only when pillsCardTracks contains genuine recently played tracks.
            // When history is empty, completely hide heading, card, placeholder message, and extra spacing.
            if (hasRecent && !landscape) {
                artworkItem(state = listState, key = "card_1_recently_played_grid", contentType = "recent_grid") {
                    val history = homeViewModel?.listeningHistoryCard?.collectAsStateWithLifecycle()?.value
                    val pillsTracks = (history?.tracks ?: pillsCardTracks)
                        .take(com.example.ui.viewmodel.RECENTLY_PLAYED_CARD_LIMIT)
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

            val cardsToShow = nativeCards.filterIndexed { index, card ->
                card.status == ShelfLoadStatus.LOADING || index < revealedCount
            }
            // Keep the initial native window first, then the two admitted local/profile stages.
            for (index in 0..cardsToShow.size) {
                if (index == minOf(3, cardsToShow.size)) {
                    if (hasArtists) {
                        artworkItem(state = listState, key = "most_listened_artists", contentType = "artist_shelf") {
                            val artists = homeViewModel?.mostListenedArtists?.collectAsStateWithLifecycle()?.value ?: mostListenedArtists
                            val enrichArtists = optionalShelvesVisible
                            if (artists.isEmpty()) return@artworkItem
                            val visibleArtists = remember(artists) {
                                artists.take(15)
                            }
                            val artistListState = rememberLazyListState()
                            ObserveHomeScroll(artistListState)
                            Column(Modifier.padding(top = 22.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Most Listened Artists",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = textPrimaryColor
                                    )
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
                                                    onOpenArtist(com.example.data.repository.ArtistPhotoRepository.cachedArtist(artist.name))
                                                }.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            VisibleItemArtwork(artistListState, artist.name) {
                                                com.example.ui.components.ArtistPortrait(
                                                    artist = com.example.data.repository.ArtistPhotoRepository.cachedArtist(artist.name),
                                                    deferEnrichment = true,
                                                    enrichmentAllowed = enrichArtists,
                                                    targetSize = with(androidx.compose.ui.platform.LocalDensity.current) { 124.8.dp.roundToPx() },
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

                    // Independent shelf slots keep successful content and scroll anchors stable.
                    if (resolvedProfile != null && resolvedProfile.tracks.isNotEmpty()) {
                        artworkItem(state = listState, key = "profile_shelf", contentType = "server_song_shelf") {
                            val section = remember(resolvedProfile) {
                                SectionState(title = resolvedProfile?.title.orEmpty(), subtitle = resolvedProfile?.subtitle.orEmpty(),
                                    tracks = resolvedProfile.tracks, isLoaded = true)
                            }
                            SectionCardLayout(state = section, tracksPrepared = true,
                                currentPlayingId = currentPlayingId, cardBg = cardBg, cardBorderColor = cardBorderColor,
                                textPrimaryColor = textPrimaryColor, textMutedColor = textMutedColor, onTrackClick = onTrackClick)
                        }
                    }
                }
                val card = cardsToShow.getOrNull(index) ?: continue
                artworkItem(state = listState, key = card.key, contentType = "server_song_shelf") {
                    when (card.status) {
                        ShelfLoadStatus.LOADING, ShelfLoadStatus.SUCCESS -> {
                            val shelf = card.shelf
                            val section = remember(card) { SectionState(title = shelf?.title.orEmpty(), subtitle = shelf?.subtitle.orEmpty(),
                                tracks = shelf?.tracks.orEmpty(), isLoaded = card.status == ShelfLoadStatus.SUCCESS,
                                isLoading = card.status == ShelfLoadStatus.LOADING) }
                            SectionCardLayout(state = section, tracksPrepared = true,
                                currentPlayingId = currentPlayingId, cardBg = cardBg, cardBorderColor = cardBorderColor,
                                textPrimaryColor = textPrimaryColor, textMutedColor = textMutedColor, onTrackClick = onTrackClick,
                                shelfItems = shelf?.items.orEmpty(), onOpenCatalog = onOpenCatalog,
                                nativeKind = remember(shelf) { shelf?.nativeKind() ?: com.example.data.model.NativeShelfKind.STANDARD })
                        }
                        ShelfLoadStatus.EMPTY, ShelfLoadStatus.ERROR -> Unit
                    }
                }
            }

        }
    }
    }
}


/** Observe user intent without consuming scroll or changing any gesture/animation. */
@Composable
private fun rememberHomeRevealModifier(state: LazyListState, reveal: () -> Unit): Modifier {
    val currentReveal by rememberUpdatedState(reveal)
    val gesture = remember(state) { HomeRevealGesture() }
    val intent = remember(state) { mutableLongStateOf(0L) }
    val threshold = with(LocalDensity.current) { 192.dp.toPx() }
    val connection = remember(state) { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && (consumed.y < 0f || available.y < 0f))
                intent.longValue = gesture.forwardInput()
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            gesture.settled()
            return Velocity.Zero
        }
    } }
    androidx.compose.runtime.LaunchedEffect(state, threshold) {
        androidx.compose.runtime.snapshotFlow {
            val layout = state.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            intent.longValue to (last != null && last.index == layout.totalItemsCount - 1 &&
                last.offset + last.size <= layout.viewportEndOffset + threshold)
        }.collect { (token, nearTail) ->
            if (gesture.consumeNearTail(token, nearTail)) currentReveal()
        }
    }
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) gesture.settled()
        }
    }
    return Modifier.nestedScroll(connection)
}

@Composable
private fun LandscapeHomeOverview(seed: MusicTrack?, currentPlayingId: String?, isPlaying: Boolean,
    isLoading: Boolean, isRadioSession: Boolean, recentTracks: List<MusicTrack>,
    onPlayRadio: (MusicTrack?) -> Unit, onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit) {
    val colors = LocalAppColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
        .testTag("landscape_home_overview")) {
        val heroWidth = ((maxWidth - 16.dp).coerceAtLeast(0.dp) / 3f)
        val recentColumns = if (maxWidth - heroWidth - 16.dp >= 412.dp) 3 else 2
        val visibleRecents = recentTracks.take(recentColumns * 3)
        val radio: @Composable () -> Unit = {
            InfiniteRadioHeroCard(seed, currentPlayingId, isPlaying, isLoading, onPlayRadio,
                modifier = Modifier.width(heroWidth), isRadioSession = isRadioSession,
                contentPadding = PaddingValues(0.dp), compactLayout = true)
        }
        if (recentTracks.isEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { radio() }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            radio()
            Column(Modifier.weight(1f).height(240.dp).testTag("landscape_recent_grid"),
                verticalArrangement = Arrangement.Center) {
                Text("Recently Played", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, color = colors.textPrimary))
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in 0 until (visibleRecents.size + recentColumns - 1) / recentColumns) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(recentColumns) { column ->
                                val track = visibleRecents.getOrNull(row * recentColumns + column)
                                if (track == null) Spacer(Modifier.weight(1f)) else HabitPillItem(
                                    track = track, isPlaying = track.id == currentPlayingId,
                                    cardBg = colors.cardBackground, cardBorderColor = colors.cardBorder,
                                    textPrimaryColor = colors.textPrimary, textMutedColor = colors.textMuted,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onTrackClick(track, recentTracks) })
                            }
                        }
                    }
                }
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
internal fun VisibleItemArtwork(state: LazyListState, key: String, content: @Composable () -> Unit) {
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
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    tracksPrepared: Boolean = false,
    shelfItems: List<ServerHomeItem> = emptyList(),
    onOpenCatalog: (CatalogDestination) -> Unit = {},
    nativeKind: com.example.data.model.NativeShelfKind = com.example.data.model.NativeShelfKind.STANDARD
) {
    // If no verified tracks and not loading, completely hide the entire shelf
    if (!state.isLoading && state.tracks.isEmpty() && shelfItems.isEmpty()) {
        return
    }

    val safeTracks = remember(state.tracks, tracksPrepared) {
        if (tracksPrepared) state.tracks else
            state.tracks.distinctBy { it.id }.take(com.example.recommendation.HomeRecommendationPolicy.MAX_SHELF_TRACKS)
    }
    val shelfListState = rememberLazyListState()
    ObserveHomeScroll(shelfListState)
    Column(modifier = Modifier.padding(top = 22.dp)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            if (state.isLoading && state.title.isBlank()) Box(Modifier.width(148.dp).height(22.dp)
                .clip(RoundedCornerShape(4.dp)).then(rememberShimmerBackground()).testTag("home_shelf_heading_loading"))
            else Text(
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

        if (state.isLoading && state.tracks.isEmpty() && shelfItems.isEmpty()) {
            SectionShimmerRow()
        } else if (nativeKind == com.example.data.model.NativeShelfKind.QUICK_PICKS) {
            QuickPicksGrid(safeTracks, shelfListState, currentPlayingId, cardBg, cardBorderColor, textPrimaryColor, textMutedColor, onTrackClick)
        } else if (nativeKind == com.example.data.model.NativeShelfKind.MUSIC_VIDEOS) {
            NativeMusicVideoRow(remember(shelfItems) { shelfItems.filterIsInstance<ServerHomeItem.Song>() }, safeTracks, shelfListState, currentPlayingId,
                cardBg, cardBorderColor, textPrimaryColor, textMutedColor, onTrackClick)
        } else if (state.tracks.isNotEmpty() || shelfItems.isNotEmpty()) {
            LazyRow(
                state = shelfListState,
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (shelfItems.isNotEmpty()) items(shelfItems, key = { it.key }, contentType = { "home_item" }) { item ->
                    VisibleItemArtwork(shelfListState, item.key) {
                        when (item) {
                            is ServerHomeItem.Song -> ShelfTrackCard(item.track, item.track.id == currentPlayingId,
                                cardBg, cardBorderColor, textPrimaryColor, textMutedColor) { onTrackClick(item.track, safeTracks) }
                            is ServerHomeItem.Album -> ShelfContentCard(item.album.title,
                                item.subtitle.ifBlank { item.album.artists.joinToString { it.name } },
                                rememberOptimizedImageRequest(item.album.image, com.example.ui.util.ImageConfig.RECOMMENDATION_CARD_SIZE,
                                    crossfadeEnabled = false, preferStableArtwork = true), false,
                                cardBg, cardBorderColor, textPrimaryColor, textMutedColor) { onOpenCatalog(CatalogDestination.Album(item.album)) }
                            is ServerHomeItem.Playlist -> ShelfContentCard(item.playlist.title, item.subtitle,
                                rememberOptimizedImageRequest(item.playlist.image, com.example.ui.util.ImageConfig.RECOMMENDATION_CARD_SIZE,
                                    crossfadeEnabled = false, preferStableArtwork = true), false,
                                cardBg, cardBorderColor, textPrimaryColor, textMutedColor) { onOpenCatalog(CatalogDestination.Playlist(item.playlist)) }
                        }
                    }
                }
                else
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
    ShelfContentCard(remember(track.title) { track.cleanTitle }, track.artist,
        rememberOptimizedImageRequest(track = track, targetSize = com.example.ui.util.ImageConfig.RECOMMENDATION_CARD_SIZE,
            crossfadeEnabled = false, preferStableArtwork = true), isPlaying,
        cardBg, cardBorderColor, textPrimaryColor, textMutedColor, artworkDescription = track.title, onClick = onClick)
}

@Composable
private fun ShelfContentCard(title: String, subtitle: String, artwork: coil.request.ImageRequest, isPlaying: Boolean,
    cardBg: Color, cardBorderColor: Color, textPrimaryColor: Color, textMutedColor: Color,
    artworkDescription: String = title, onClick: () -> Unit) {
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
                model = artwork,
                contentDescription = artworkDescription,
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
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) appColors.primaryAccent else textPrimaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
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

