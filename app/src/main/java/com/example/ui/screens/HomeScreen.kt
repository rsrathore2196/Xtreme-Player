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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.SuccessResult
import com.example.ui.util.ExtractedTrackColors
import com.example.ui.util.TrackPaletteCache
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.data.remote.MixItem
import com.example.data.remote.MusicDataSource
import com.example.playback.PlayerUiState
import com.example.ui.ai.HomeShelf
import com.example.ui.components.AppDynamicLogo
import com.example.ui.components.TrackActionSheet
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.liquidGlassButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremeRose
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
 * Primary Home Screen using HomeViewModel for instant local-first render,
 * parallel data orchestration, and deferred section loading.
 */
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
    onPlayInfiniteRadio: (() -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val catalogTracks by homeViewModel.catalogTracks.collectAsState()
    val recentlyPlayed by homeViewModel.recentlyPlayed.collectAsState()
    val shelves by homeViewModel.homeShelves.collectAsState()
    val userProfile by homeViewModel.userProfile.collectAsState()
    val punjabiSection by homeViewModel.punjabiSection.collectAsState()
    val eraSection by homeViewModel.eraSection.collectAsState()
    val newReleases by homeViewModel.newReleases.collectAsState()

    HomeScreenContent(
        catalogTracks = catalogTracks,
        recentlyPlayed = recentlyPlayed,
        currentTrack = currentTrack ?: playerUiState?.currentTrack,
        isPlaying = isPlaying,
        isAutoplayEnabled = isAutoplayEnabled,
        currentPlayingTrackId = currentPlayingTrackId ?: currentTrack?.id ?: playerUiState?.currentTrack?.id,
        shelves = shelves,
        userProfile = userProfile,
        punjabiSection = punjabiSection,
        eraSection = eraSection,
        newReleases = newReleases,
        onLoadPunjabiSection = { homeViewModel.loadPunjabiSectionIfNeeded() },
        onLoadEraSection = { homeViewModel.loadEraSectionIfNeeded() },
        onTrackClick = onTrackClick,
        onToggleFavorite = onToggleFavorite,
        onOpenSettings = onOpenSettings,
        onPlayInfiniteRadio = onPlayInfiniteRadio,
        onAddToQueue = onAddToQueue,
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
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    isPlaying: Boolean = playerUiState?.isPlaying == true,
    isAutoplayEnabled: Boolean = playerUiState?.isAutoplayEnabled == true,
    currentTrack: MusicTrack? = playerUiState?.currentTrack,
    shelves: List<HomeShelf> = emptyList(),
    userProfile: UserProfile? = null,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onPlayInfiniteRadio: (() -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    HomeScreenContent(
        catalogTracks = catalogTracks,
        recentlyPlayed = recentlyPlayed,
        currentTrack = currentTrack ?: playerUiState?.currentTrack,
        isPlaying = isPlaying,
        isAutoplayEnabled = isAutoplayEnabled,
        currentPlayingTrackId = currentPlayingTrackId ?: currentTrack?.id ?: playerUiState?.currentTrack?.id,
        shelves = shelves,
        userProfile = userProfile,
        punjabiSection = SectionState(),
        eraSection = SectionState(),
        newReleases = emptyList(),
        onLoadPunjabiSection = null,
        onLoadEraSection = null,
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
    catalogTracks: List<MusicTrack>,
    recentlyPlayed: List<MusicTrack>,
    currentTrack: MusicTrack? = null,
    isPlaying: Boolean = false,
    isAutoplayEnabled: Boolean = true,
    currentPlayingTrackId: String? = null,
    shelves: List<HomeShelf>,
    userProfile: UserProfile?,
    punjabiSection: SectionState,
    eraSection: SectionState,
    newReleases: List<MusicTrack> = emptyList(),
    onLoadPunjabiSection: (() -> Unit)?,
    onLoadEraSection: (() -> Unit)?,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onPlayInfiniteRadio: (() -> Unit)? = null,
    onAddToQueue: ((MusicTrack) -> Unit)? = null,
    playerUiState: PlayerUiState? = null,
    modifier: Modifier = Modifier
) {
    val greeting = rememberGreeting()
    val resolvedCurrentTrack = currentTrack ?: playerUiState?.currentTrack
    val resolvedIsPlaying = isPlaying || (playerUiState?.isPlaying == true)
    val resolvedIsAutoplay = isAutoplayEnabled && (playerUiState?.isAutoplayEnabled ?: true)
    val currentPlayingId = currentPlayingTrackId ?: resolvedCurrentTrack?.id
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    var actionSheetTrack by remember { mutableStateOf<MusicTrack?>(null) }
    val releasesToDisplay = remember(newReleases, catalogTracks) {
        if (newReleases.isNotEmpty()) newReleases else catalogTracks.take(12)
    }

    // Hoist LazyListState and fling behavior for 120Hz smooth scrolling
    val newReleasesListState = rememberLazyListState()
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = newReleasesListState)

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
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            // HEADER BAR WITH APP LOGO & TOP-RIGHT SETTINGS ICON
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
                                    color = appColors.textPrimary
                                )
                            }
                        }

                        // Neutral Frosted Liquid Glass Pill Settings button on top right (icon only, pill shape)
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .liquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(18.dp),
                                    elevation = 2.dp,
                                    isActive = false,
                                    translucency = 0.85f,
                                    borderWidth = 1.dp
                                )
                                .bouncyClickable { onOpenSettings?.invoke() }
                                .testTag("top_settings_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Settings",
                                    tint = if (appColors.isDark) Color.White.copy(alpha = 0.85f) else appColors.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
                                color = appColors.textMuted
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
                                color = appColors.textPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 1. HERO RECOMMENDATION CARD (Infinite Radio - Dynamic Playing Album Art & 3D Liquid Glass)
            item(key = "hero_infinite_radio_card") {
                val isPlayingInfiniteRadio = resolvedIsPlaying && resolvedIsAutoplay

                // Choose track for album art: current track, or recently played, or first catalog track
                val heroTrack = resolvedCurrentTrack
                    ?: recentlyPlayed.firstOrNull { !it.coverUrl.isNullOrBlank() }
                    ?: catalogTracks.firstOrNull { !it.coverUrl.isNullOrBlank() }
                val heroCoverUrl = heroTrack?.coverUrl

                // Extract playing song album art color when playing
                val context = LocalContext.current
                val cachedHeroColors = remember(heroTrack?.id) {
                    heroTrack?.id?.let { TrackPaletteCache.get(it) }
                }
                var heroTrackColor by remember(heroTrack?.id) {
                    mutableStateOf(cachedHeroColors?.dominantColor)
                }

                LaunchedEffect(heroTrack?.id, heroCoverUrl) {
                    val trackId = heroTrack?.id ?: return@LaunchedEffect
                    if (heroCoverUrl.isNullOrBlank()) return@LaunchedEffect
                    val inMem = TrackPaletteCache.get(trackId)
                    if (inMem != null) {
                        heroTrackColor = inMem.dominantColor
                        return@LaunchedEffect
                    }
                    withContext(Dispatchers.IO) {
                        try {
                            val loader = context.imageLoader
                            val request = ImageRequest.Builder(context)
                                .data(heroCoverUrl)
                                .size(ImageConfig.PALETTE_THUMBNAIL_SIZE, ImageConfig.PALETTE_THUMBNAIL_SIZE)
                                .allowHardware(false)
                                .build()
                            val result = (loader.execute(request) as? SuccessResult)?.drawable
                            val bitmap = (result as? BitmapDrawable)?.bitmap
                            if (bitmap != null) {
                                val palette = withContext(Dispatchers.Default) {
                                    Palette.from(bitmap).generate()
                                }
                                val dom = palette.getDarkVibrantColor(
                                    palette.getDominantColor(android.graphics.Color.parseColor("#0F2B48"))
                                )
                                val acc = palette.getLightVibrantColor(
                                    palette.getVibrantColor(dom)
                                )
                                val extracted = ExtractedTrackColors(
                                    dominantColor = Color(dom),
                                    accentColor = Color(acc),
                                    vibrantColor = Color(acc)
                                )
                                TrackPaletteCache.put(trackId, extracted)
                                withContext(Dispatchers.Main) {
                                    heroTrackColor = extracted.dominantColor
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                val accentGlowColor = heroTrackColor ?: appColors.primaryAccent
                val cardShape = RoundedCornerShape(28.dp)
                val cardBorderBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (appColors.isDark) 0.60f else 0.85f),
                        accentGlowColor.copy(alpha = if (appColors.isDark) 0.35f else 0.40f),
                        Color.White.copy(alpha = if (appColors.isDark) 0.12f else 0.22f)
                    )
                )

                Surface(
                    shape = cardShape,
                    color = if (appColors.isDark) Color(0xFF101726) else Color(0xFFF1F5F9),
                    border = BorderStroke(1.2.dp, cardBorderBrush),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = cardShape,
                            spotColor = accentGlowColor.copy(alpha = if (appColors.isDark) 0.40f else 0.20f),
                            ambientColor = Color.Black.copy(alpha = if (appColors.isDark) 0.30f else 0.08f)
                        )
                        .clip(cardShape)
                        .bouncyClickable { onPlayInfiniteRadio?.invoke() }
                        .testTag("hero_recommendation_card")
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 1. ALBUM ART - HIGH VISIBILITY & HARDWARE-ACCELERATED ZERO-STUTTER
                        if (!heroCoverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = heroCoverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer {
                                        alpha = 0.90f
                                    }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                appColors.primaryAccent.copy(alpha = 0.40f),
                                                Color(0xFF1E293B)
                                            )
                                        )
                                    )
                            )
                        }

                        // 2. Translucent Readability Scrim (Protects text contrast while leaving album art clear)
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.20f),
                                            Color.Black.copy(alpha = 0.35f),
                                            Color.Black.copy(alpha = 0.75f)
                                        )
                                    )
                                )
                        )

                        // 3. 3D Liquid Glass Chromatic Accent Light Refraction
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            accentGlowColor.copy(alpha = 0.22f),
                                            Color.Transparent
                                        ),
                                        center = Offset(240f, 180f),
                                        radius = 650f
                                    )
                                )
                        )

                        // 4. 3D Liquid Glass Top Specular Curvature Light Sheen
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = if (appColors.isDark) 0.30f else 0.45f),
                                            Color.White.copy(alpha = 0.06f),
                                            Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = 160f
                                    )
                                )
                        )

                        // 5. Card Foreground Content
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            // "✨ MADE FOR YOU" 3D Liquid Glass Pill Badge
                            Surface(
                                shape = CircleShape,
                                color = Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.65f),
                                            Color.White.copy(alpha = 0.20f)
                                        )
                                    )
                                ),
                                modifier = Modifier
                                    .shadow(4.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.24f),
                                                Color.Black.copy(alpha = 0.25f)
                                            )
                                        )
                                    )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.White.copy(alpha = 0.25f), Color.Transparent),
                                                    startY = 0f,
                                                    endY = 14f
                                                )
                                            )
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "MADE FOR YOU",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp,
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // "Infinite Radio" Title
                            Text(
                                text = "Infinite Radio",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp,
                                    color = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Subtitle
                            val subtitleText = if (isPlaying && currentTrack != null) {
                                "Playing: ${currentTrack.cleanTitle}"
                            } else if (heroTrack != null) {
                                "Based on: ${heroTrack.cleanTitle} · Endless station"
                            } else {
                                "An endless station shaped by your listening"
                            }

                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.5.sp,
                                    color = Color.White.copy(alpha = 0.90f)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // 3D Liquid Glass Play Button (White text & icon only, high contrast in AMOLED)
                            val playBtnShape = CircleShape
                            val playBtnBorder = BorderStroke(
                                1.3.dp,
                                if (appColors.isAmoled) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.75f),
                                            Color.White.copy(alpha = 0.25f),
                                            Color.White.copy(alpha = 0.10f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.80f),
                                            accentGlowColor.copy(alpha = 0.50f),
                                            Color.White.copy(alpha = 0.25f)
                                        )
                                    )
                                }
                            )
                            val heroBtnBg = if (appColors.isAmoled) {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF2A2A2A),
                                        Color(0xFF1A1A1A),
                                        Color(0xFF0F0F0F)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        accentGlowColor.copy(alpha = 0.90f),
                                        accentGlowColor.copy(alpha = 0.70f)
                                    )
                                )
                            }

                            Surface(
                                shape = playBtnShape,
                                color = Color.Transparent,
                                border = playBtnBorder,
                                modifier = Modifier
                                    .shadow(
                                        elevation = 8.dp,
                                        shape = playBtnShape,
                                        spotColor = if (appColors.isAmoled) Color.Black.copy(alpha = 0.50f) else accentGlowColor.copy(alpha = 0.60f)
                                    )
                                    .clip(playBtnShape)
                                    .background(heroBtnBg)
                                    .bouncyClickable { onPlayInfiniteRadio?.invoke() }
                                    .testTag("infinite_radio_play_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.35f),
                                                        Color.Transparent
                                                    ),
                                                    startY = 0f,
                                                    endY = 22f
                                                )
                                            )
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlayingInfiniteRadio) {
                                                Icons.Default.Equalizer
                                            } else {
                                                Icons.Default.PlayArrow
                                            },
                                            contentDescription = if (isPlayingInfiniteRadio) "Playing" else "Play",
                                            tint = Color.White,
                                            modifier = Modifier.size(19.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isPlayingInfiniteRadio) "Playing" else "Play",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. JUMP BACK IN / QUICK PICKS (Immediately below Hero Recommendation)
            val quickTracks = if (recentlyPlayed.isNotEmpty()) recentlyPlayed.take(4) else catalogTracks.take(4)
            if (quickTracks.isNotEmpty()) {
                item(key = "quick_picks_grid") {
                    Column(modifier = Modifier.padding(top = 14.dp, start = 20.dp, end = 20.dp)) {
                        Text(
                            text = if (recentlyPlayed.isNotEmpty()) "Jump Back In" else "Quick Picks",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = appColors.textPrimary
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

            // SMART AI BACKGROUND SHELVES
            if (shelves.isNotEmpty()) {
                items(shelves, key = { "shelf_${it.id}" }) { shelf ->
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = shelf.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = appColors.textPrimary
                                )
                            )
                            if (shelf.subtitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = shelf.subtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = appColors.textMuted
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
                                fontSize = 18.sp,
                                color = appColors.textPrimary
                            )
                        )
                        if (eraSection.subtitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = eraSection.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = appColors.textMuted
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (eraSection.isLoading && eraSection.tracks.isEmpty()) {
                        SectionShimmerRow()
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

            // 5. SPOTIFY-STYLE 3-COLUMN "QUICK PICKS" GRID: NEW RELEASES
            if (releasesToDisplay.isNotEmpty()) {
                item(key = "new_releases_section") {
                    val countryName = userProfile?.country?.trim()?.takeIf { it.isNotBlank() } ?: "India"
                    val langs = userProfile?.languages?.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(" & ") ?: "Trending"
                    val configuration = LocalConfiguration.current
                    val screenWidth = configuration.screenWidthDp.dp
                    val isWide = configuration.screenWidthDp >= 600

                    val horizontalPadding = if (isWide) 24.dp else 16.dp

                    // Dedicated Outer 3D Liquid Glass Card Container adapting strictly to selected theme card color
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding, vertical = 10.dp)
                            .testTag("section_new_releases")
                    ) {
                        // Theme card color tokens selected by user in theme settings
                        val cardBaseColor = appColors.cardBackground
                        val cardElevatedColor = appColors.cardBackgroundElevated

                        // Soft ambient backdrop glow adapting to theme card color rather than accent color
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(6.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            cardElevatedColor.copy(alpha = if (appColors.isDark) 0.25f else 0.12f),
                                            cardBaseColor.copy(alpha = if (appColors.isDark) 0.10f else 0.04f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        // 3D Liquid Glass Outer Card Frame applying same gradient card color as Top Mixes for You
                        val cardShape = RoundedCornerShape(24.dp)
                        val outerCardBorder = BorderStroke(
                            1.3.dp,
                            LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.50f)
                        )
                        val outerCardBg = LiquidGlass.glassBrush(appColors, translucency = 0.85f, tintAccent = false)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(
                                    elevation = if (appColors.isDark) 6.dp else 8.dp,
                                    shape = cardShape,
                                    spotColor = Color.Black.copy(alpha = if (appColors.isDark) 0.40f else 0.08f),
                                    ambientColor = if (appColors.isDark) Color.Black.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.04f)
                                )
                                .clip(cardShape),
                            shape = cardShape,
                            color = Color.Transparent,
                            border = outerCardBorder
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(outerCardBg)
                            ) {
                                // Internal Frosted diffusion layer (GPU-efficient, 120 FPS fluid render)
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            if (appColors.isDark) {
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.08f),
                                                        cardBaseColor.copy(alpha = 0.04f),
                                                        Color.White.copy(alpha = 0.02f)
                                                    )
                                                )
                                            } else {
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.45f),
                                                        Color.White.copy(alpha = 0.12f),
                                                        Color.Transparent
                                                    )
                                                )
                                            }
                                        )
                                )

                                // Top Specular Sheen (curvature light catch)
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.White.copy(alpha = if (appColors.isDark) 0.20f else 0.32f),
                                                    Color.Transparent
                                                ),
                                                startY = 0f,
                                                endY = 48f
                                            )
                                        )
                                )

                                // Inner Content Layout
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 18.dp, bottom = 18.dp)
                                ) {
                                    // Section Header inside card: Clean title without dot, no HD 320k badge
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp)
                                    ) {
                                        Text(
                                            text = "New Releases",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 18.sp,
                                                color = appColors.textPrimary
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Fresh drops in $countryName • $langs",
                                            style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Retain the current layout structure and columns for the individual new release items
                                    val columnWidth = remember(screenWidth, isWide) {
                                        if (isWide) 260.dp else (screenWidth * 0.43f).coerceIn(145.dp, 210.dp)
                                    }
                                    val pageWidth = remember(columnWidth) { (columnWidth * 2) + 8.dp }
                                    val releasePages = remember(releasesToDisplay) { releasesToDisplay.chunked(6) }

                                    LazyRow(
                                        state = newReleasesListState,
                                        flingBehavior = snapFlingBehavior,
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        itemsIndexed(
                                            releasePages,
                                            key = { index, page -> "release_page_${index}_${page.firstOrNull()?.id ?: index}" }
                                        ) { _, pageTracks ->
                                            val pageColumns = remember(pageTracks) { pageTracks.chunked(3) }
                                            Row(
                                                modifier = Modifier.width(pageWidth),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                pageColumns.forEach { columnTracks ->
                                                    Column(
                                                        modifier = Modifier.width(columnWidth),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        columnTracks.forEach { releaseTrack ->
                                                            key(releaseTrack.id) {
                                                                NewReleaseGridCard(
                                                                    track = releaseTrack,
                                                                    isPlaying = releaseTrack.id == currentPlayingId,
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    onClick = { onTrackClick(releaseTrack, releasesToDisplay) },
                                                                    onLongClick = { actionSheetTrack = releaseTrack },
                                                                    onMoreOptionsClick = { actionSheetTrack = releaseTrack }
                                                                )
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
                    }
                }
            }

            // TOP MIXES CAROUSEL (Moved below New Releases at bottom of feed)
            item(key = "top_mixes_carousel") {
                Column(modifier = Modifier.padding(top = 16.dp, bottom = 28.dp)) {
                    Text(
                        text = "Top Mixes for You",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = appColors.textPrimary
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
        }

        // Context Menu Action Sheet for New Releases and tracks
        actionSheetTrack?.let { track ->
            TrackActionSheet(
                track = track,
                onDismiss = { actionSheetTrack = null },
                onPlayNow = { t ->
                    onTrackClick(t, catalogTracks)
                },
                onAddToQueue = { t ->
                    onAddToQueue?.invoke(t)
                },
                onPlayInfiniteRadio = { t ->
                    onPlayInfiniteRadio?.invoke()
                },
                onToggleFavorite = { t ->
                    onToggleFavorite(t)
                }
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
        items(4) {
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
                .clip(RoundedCornerShape(18.dp))
                .background(if (isDark) Color(0xFF14243B) else Color(0xFFE2EDFB))
                .border(
                    if (isPlaying) BorderStroke(1.2.dp, appColors.primaryAccent)
                    else LiquidGlass.border(appColors, 1.2.dp, 0.40f),
                    RoundedCornerShape(18.dp)
                )
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track.coverUrl, ImageConfig.RECOMMENDATION_CARD_SIZE),
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
            text = track.cleanTitle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) appColors.primaryAccent else appColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            fontSize = 11.sp,
            color = appColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MixCard(mix: MixItem, onClick: () -> Unit) {
    val appColors = LocalAppColors.current

    Box(
        modifier = Modifier
            .width(145.dp)
            .liquidGlassCard(appColors, shape = RoundedCornerShape(20.dp), elevation = 4.dp, translucency = 0.85f, tintAccent = false)
            .bouncyClickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(125.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(appColors.cardBorder.copy(alpha = 0.35f))
            ) {
                AsyncImage(
                    model = rememberOptimizedImageRequest(mix.coverUrl, ImageConfig.RECOMMENDATION_CARD_SIZE),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = mix.title,
                color = appColors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mix.description,
                color = appColors.textMuted,
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
    val cardShape = CircleShape

    val cardBrush = if (isPlaying) {
        Brush.verticalGradient(
            listOf(
                appColors.primaryAccent.copy(alpha = 0.35f),
                appColors.primaryAccent.copy(alpha = 0.18f)
            )
        )
    } else {
        LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
    }

    val cardBorder = BorderStroke(
        1.2.dp,
        if (isPlaying) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.45f),
                    appColors.primaryAccent.copy(alpha = 0.50f)
                )
            )
        } else {
            LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.38f else 0.45f)
        }
    )

    // Pill-shaped container with liquid glass styling
    Box(
        modifier = modifier
            .shadow(
                elevation = if (isPlaying) 6.dp else 3.dp,
                shape = cardShape,
                spotColor = Color.Black.copy(alpha = if (isDark) 0.40f else 0.08f),
                ambientColor = Color.Transparent
            )
            .clip(cardShape)
            .background(cardBrush)
            .border(cardBorder, cardShape)
            .bouncyClickable { onClick() }
            .testTag("quick_pick_card_${track.id}")
    ) {
        // Frosted diffusion sheen layer (hardware-accelerated, zero-stutter)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    if (isDark) {
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.10f))
                        )
                    }
                )
        )

        // Top specular highlight sheen
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (isDark) 0.12f else 0.22f), Color.Transparent),
                        startY = 0f,
                        endY = 24f
                    )
                )
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(appColors.cardBorder.copy(alpha = 0.35f))
            ) {
                AsyncImage(
                    model = rememberOptimizedImageRequest(track.coverUrl, ImageConfig.LIST_ITEM_SIZE),
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
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.cleanTitle,
                    color = if (isPlaying) (if (isDark) Color.White else appColors.primaryAccent) else (if (isDark) Color.White else appColors.textPrimary),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = if (isDark) Color(0xFFCBD5E1) else appColors.textSecondary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewReleaseGridCard(
    track: MusicTrack,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMoreOptionsClick: () -> Unit = {}
) {
    val appColors = LocalAppColors.current

    val isDark = appColors.isDark
    val cardShape = remember { RoundedCornerShape(12.dp) }
    val cardBaseColor = appColors.cardBackground
    val cardElevatedColor = appColors.cardBackgroundElevated
    val cardBorderColor = appColors.cardBorder

    val cardBrush = remember(isPlaying, isDark, cardBaseColor, cardElevatedColor) {
        if (isPlaying) {
            Brush.verticalGradient(
                listOf(
                    cardElevatedColor,
                    cardBaseColor
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    cardBaseColor,
                    if (isDark) cardBaseColor else cardElevatedColor
                )
            )
        }
    }

    val cardBorder = remember(isPlaying, isDark, cardBorderColor) {
        BorderStroke(
            1.2.dp,
            if (isPlaying) {
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.50f else 0.85f),
                        cardBorderColor,
                        cardBorderColor.copy(alpha = 0.50f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDark) 0.20f else 0.75f),
                        cardBorderColor.copy(alpha = if (isDark) 0.60f else 0.85f),
                        cardBorderColor.copy(alpha = if (isDark) 0.30f else 0.50f)
                    )
                )
            }
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer { }
            .shadow(
                elevation = if (isPlaying) 6.dp else 2.dp,
                shape = cardShape,
                spotColor = Color.Black.copy(alpha = if (isDark) 0.35f else 0.06f),
                ambientColor = Color.Transparent
            )
            .clip(cardShape)
            .background(cardBrush)
            .border(cardBorder, cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("new_release_card_${track.id}")
    ) {
        // Frosted diffusion sheen layer (hardware-accelerated, zero-stutter)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    if (isDark) {
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.08f))
                        )
                    }
                )
        )

        // Top specular highlight sheen
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (isDark) 0.14f else 0.22f), Color.Transparent),
                        startY = 0f,
                        endY = 30f
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 5.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album / Track Artwork: Compact square thumbnail (40dp x 40dp) with 8dp rounded corners
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(appColors.cardBorder.copy(alpha = 0.35f))
            ) {
                AsyncImage(
                    model = rememberOptimizedImageRequest(track.coverUrl, 80),
                    contentDescription = track.cleanTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

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
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(7.dp))

            // Text Content Container: Positioned to the right of artwork, occupying remaining width
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Song Title: Bold, single-line text truncated with ellipsis, using cleanTitle
                Text(
                    text = track.cleanTitle,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) (if (isDark) Color.White else appColors.primaryAccent) else (if (isDark) Color.White else appColors.textPrimary),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                // Artist / Subtitle: Dimmed/secondary text tone below title, single-line truncated with ellipsis
                Text(
                    text = track.artist,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDark) Color(0xFFCBD5E1) else appColors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Compact 3-dot Action Menu Trigger
            IconButton(
                onClick = onMoreOptionsClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options for ${track.cleanTitle}",
                    tint = if (isDark) Color.White.copy(alpha = 0.70f) else appColors.textMuted,
                    modifier = Modifier.size(14.dp)
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
    onRemoveClick: (() -> Unit)? = null,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { }
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isPlaying) {
                    appColors.primaryAccent.copy(alpha = if (isDark) 0.14f else 0.08f)
                } else if (!isDark) {
                    Color.White.copy(alpha = 0.55f)
                } else Color.Transparent
            )
            .then(
                if (!isDark) {
                    Modifier.border(
                        BorderStroke(1.2.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.45f)),
                        RoundedCornerShape(14.dp)
                    )
                } else if (isPlaying) {
                    Modifier.border(
                        BorderStroke(1.dp, appColors.primaryAccent.copy(alpha = 0.35f)),
                        RoundedCornerShape(14.dp)
                    )
                } else Modifier
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 10.dp),
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
                model = rememberOptimizedImageRequest(track.coverUrl, ImageConfig.LIST_ITEM_SIZE),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.50f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.cleanTitle,
                color = if (isPlaying) appColors.primaryAccent else appColors.textPrimary,
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
                    color = appColors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Loading spinner, Equalizer visual, or favorite button
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .padding(2.dp),
                strokeWidth = 2.dp,
                color = appColors.primaryAccent
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (isPlaying) {
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
                tint = if (track.isLiked) XtremeRose else appColors.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        if (onRemoveClick != null) {
            IconButton(
                onClick = onRemoveClick,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("track_remove_button_${track.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove song from playlist",
                    tint = appColors.primaryAccent.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
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
