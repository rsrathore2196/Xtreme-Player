package com.example.ui.components

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatQuote
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
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.collectAsState
import com.example.playback.AudioDeviceManager
import com.example.playback.AudioQuality
import com.example.playback.SoundOutputDevice
import com.example.ui.util.ImageConfig
import com.example.ui.util.TrackPaletteCache
import com.example.ui.util.ExtractedTrackColors
import com.example.util.AppHaptics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import coil.imageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.model.MusicTrack
import com.example.data.repository.ArtworkDimensions
import com.example.data.repository.iTunesArtworkRepository
import com.example.data.repository.toMediaItem
import com.example.playback.PlayerUiState
import com.example.playback.RepeatMode
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.primaryLiquidGlassButton
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
    onAddToQueue: (MusicTrack) -> Unit = {},
    lyrics: com.example.data.model.TrackLyrics? = null,
    onRetryLyrics: () -> Unit = {},
    availableAudioDevices: List<SoundOutputDevice> = emptyList(),
    onSelectAudioDevice: (Int) -> Unit = {},
    isDark: Boolean = LocalAppColors.current.isDark,
    currentPositionProvider: () -> Long = { uiState.currentPositionMs },
    trackDurationProvider: () -> Long = { if (uiState.durationMs > 0) uiState.durationMs else (uiState.currentTrack?.durationMs ?: 0L) },
    bufferedPositionProvider: () -> Long = { uiState.bufferedPositionMs },
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack ?: return
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val appColors = LocalAppColors.current

    val itunesRepo = remember { iTunesArtworkRepository.getInstance(context) }
    val initialRawCoverUrl = remember(track.coverUrl) {
        val raw = track.coverUrl
        when {
            raw.contains("150x150.jpg") -> raw.replace("150x150.jpg", "500x500.jpg")
            raw.contains("50x50.jpg") -> raw.replace("50x50.jpg", "500x500.jpg")
            raw.contains("hqdefault.jpg") -> raw.replace("hqdefault.jpg", "maxresdefault.jpg")
            raw.contains("mqdefault.jpg") -> raw.replace("mqdefault.jpg", "maxresdefault.jpg")
            raw.contains("default.jpg") && !raw.contains("maxresdefault.jpg") -> raw.replace("default.jpg", "maxresdefault.jpg")
            raw.contains("100x100bb") -> itunesRepo.formatUrl(raw, ArtworkDimensions.EXPANDED_PLAYER)
            raw.contains("100x100") -> itunesRepo.formatUrl(raw, ArtworkDimensions.EXPANDED_PLAYER)
            else -> raw
        }
    }

    val memoryItunesUrl = remember(track.id) {
        itunesRepo.getMemoryCachedUrl(track.toMediaItem(), ArtworkDimensions.EXPANDED_PLAYER)
    }

    var highResCoverUrl by remember(track.id) {
        mutableStateOf(memoryItunesUrl ?: initialRawCoverUrl)
    }

    LaunchedEffect(track.id) {
        if (memoryItunesUrl == null) {
            val resolved = withContext(Dispatchers.IO) {
                itunesRepo.resolveArtwork(track.toMediaItem(), ArtworkDimensions.EXPANDED_PLAYER)
            }
            if (resolved.isNotBlank() && resolved != highResCoverUrl) {
                highResCoverUrl = resolved
            }
        }
    }

    val highResImageRequest = remember(highResCoverUrl) {
        ImageRequest.Builder(context)
            .data(highResCoverUrl)
            .size(coil.size.Size.ORIGINAL) // Full High Quality album art without downscaling
            .scale(coil.size.Scale.FILL)
            .crossfade(true)
            .crossfade(200) // smooth 200ms crossfade transition once resolved
            .allowHardware(true)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .networkCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
    }

    var isMenuOpen by remember { mutableStateOf(false) }
    var isFlipped by remember(track.id) { mutableStateOf(false) }
    var showLyrics by remember(track.id) { mutableStateOf(false) }
    var showSoundOutputDialog by remember { mutableStateOf(false) }

    val fallbackDevices by AudioDeviceManager.availableDevices.collectAsState()
    val effectiveDevices = if (availableAudioDevices.isNotEmpty()) availableAudioDevices else fallbackDevices
    val activeOutputDevice = effectiveDevices.find { it.isSelected }

    // Dynamic color extracted from album art with active theme fallback and in-memory song ID cache
    val cachedColors = remember(track.id) { TrackPaletteCache.get(track.id) }
    var dominantColor by remember(track.id) { mutableStateOf(cachedColors?.dominantColor ?: appColors.cardBackgroundElevated) }
    var accentColor by remember(track.id, appColors.primaryAccent) { mutableStateOf(cachedColors?.accentColor ?: appColors.primaryAccent) }
    var vibrantColor by remember(track.id, appColors.primaryAccent) { mutableStateOf(cachedColors?.vibrantColor ?: appColors.primaryAccent) }

    LaunchedEffect(track.id, track.coverUrl, appColors.primaryAccent) {
        // Fast-path: If already in memory cache, avoid all processing
        val inMem = TrackPaletteCache.get(track.id)
        if (inMem != null) {
            dominantColor = inMem.dominantColor
            accentColor = inMem.accentColor
            vibrantColor = inMem.vibrantColor
            return@LaunchedEffect
        }

        if (track.coverUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val loader = context.imageLoader
                    val request = ImageRequest.Builder(context)
                        .data(track.coverUrl)
                        .size(ImageConfig.PALETTE_THUMBNAIL_SIZE, ImageConfig.PALETTE_THUMBNAIL_SIZE) // Explicit 100x100 for fast, lightweight palette extraction
                        .allowHardware(false)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build()
                    val result = (loader.execute(request) as? SuccessResult)?.drawable
                    val bitmap = (result as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val palette = withContext(Dispatchers.Default) {
                            Palette.from(bitmap).generate()
                        }
                        val defaultAccentInt = android.graphics.Color.rgb(
                            (appColors.primaryAccent.red * 255).toInt(),
                            (appColors.primaryAccent.green * 255).toInt(),
                            (appColors.primaryAccent.blue * 255).toInt()
                        )
                        val defaultDomInt = android.graphics.Color.rgb(
                            (appColors.cardBackgroundElevated.red * 255).toInt(),
                            (appColors.cardBackgroundElevated.green * 255).toInt(),
                            (appColors.cardBackgroundElevated.blue * 255).toInt()
                        )
                        val dom = if (isDark) {
                            palette.getDarkVibrantColor(
                                palette.getDominantColor(palette.getDarkMutedColor(defaultDomInt))
                            )
                        } else {
                            palette.getLightMutedColor(
                                palette.getLightVibrantColor(palette.getDominantColor(defaultDomInt))
                            )
                        }
                        val acc = if (isDark) {
                            palette.getLightVibrantColor(
                                palette.getVibrantColor(defaultAccentInt)
                            )
                        } else {
                            palette.getVibrantColor(
                                palette.getDarkVibrantColor(defaultAccentInt)
                            )
                        }
                        val vib = palette.getVibrantColor(
                            palette.getLightVibrantColor(
                                palette.getDominantColor(defaultAccentInt)
                            )
                        )
                        val extracted = ExtractedTrackColors(
                            dominantColor = Color(dom),
                            accentColor = Color(acc),
                            vibrantColor = Color(vib)
                        )
                        TrackPaletteCache.put(track.id, extracted)
                        withContext(Dispatchers.Main) {
                            dominantColor = extracted.dominantColor
                            accentColor = extracted.accentColor
                            vibrantColor = extracted.vibrantColor
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val animatedDominantColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "dominant_color"
    )
    val animatedAccentColor by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "accent_color"
    )
    val animatedVibrantColor by animateColorAsState(
        targetValue = vibrantColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "vibrant_color"
    )

    val albumArtScale by animateFloatAsState(
        targetValue = if (uiState.isPlaying) 1.0f else 0.88f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 550f),
        label = "album_art_scale"
    )

    val flipRotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "flip_card_rotation"
    )

    val lyricsInteractionSource = remember { MutableInteractionSource() }
    val isLyricsPressed by lyricsInteractionSource.collectIsPressedAsState()

    val lyricsButtonScale by animateFloatAsState(
        targetValue = when {
            isLyricsPressed -> 0.92f
            showLyrics -> 1.06f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "lyrics_button_scale"
    )

    val outputInteractionSource = remember { MutableInteractionSource() }
    val isOutputPressed by outputInteractionSource.collectIsPressedAsState()
    val outputButtonScale by animateFloatAsState(
        targetValue = if (isOutputPressed) 0.94f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "output_button_scale"
    )

    val queueInteractionSource = remember { MutableInteractionSource() }
    val isQueuePressed by queueInteractionSource.collectIsPressedAsState()
    val queueButtonScale by animateFloatAsState(
        targetValue = if (isQueuePressed) 0.90f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "queue_button_scale"
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
            ),
        color = if (appColors.isAmoled) Color(0xFF000000) else appColors.scaffoldBackground // 100% OPAQUE base inheriting Canvas Color
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            // Animated Bokeh Mode Background Layer with High-Depth Frosted Glass
            PlayerBokehBackground(
                dominantColor = animatedDominantColor,
                accentColor = animatedAccentColor,
                vibrantColor = animatedVibrantColor,
                isDark = isDark
            )

            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            if (isLandscape) {
                // LANDSCAPE DEDICATED TWO-PANE LAYOUT
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT PANE: Album Art / Lyrics (Proper Square Shape Guarantee)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = showLyrics,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(170, easing = LinearOutSlowInEasing)) + scaleIn(initialScale = 0.96f, animationSpec = tween(170, easing = FastOutSlowInEasing))) togetherWith
                                (fadeOut(animationSpec = tween(130, easing = FastOutLinearInEasing)) + scaleOut(targetScale = 0.98f, animationSpec = tween(130, easing = FastOutLinearInEasing)))
                            },
                            label = "lyrics_album_art_transition_landscape",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { clip = false }
                        ) { isLyricsActive ->
                            if (isLyricsActive) {
                                SyncedLyricsView(
                                    lyrics = lyrics,
                                    isPlaying = uiState.isPlaying,
                                    currentPositionProvider = currentPositionProvider,
                                    onSeekTo = onSeekTo,
                                    dominantColor = animatedDominantColor,
                                    accentColor = animatedAccentColor,
                                    isDark = isDark,
                                    onRetry = onRetryLyrics,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Adaptive Ambient Glow
                                    Box(
                                        modifier = Modifier
                                            .size(240.dp)
                                            .graphicsLayer()
                                            .clip(RoundedCornerShape(32.dp))
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

                                    // 3D Flippable Album Art Card - Strictly Guaranteed Square
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight(0.92f)
                                            .aspectRatio(1f)
                                            .graphicsLayer {
                                                rotationY = flipRotation
                                                cameraDistance = 14f * density
                                            }
                                            .shadow(
                                                elevation = 28.dp,
                                                shape = RoundedCornerShape(22.dp),
                                                spotColor = animatedAccentColor.copy(alpha = 0.5f),
                                                ambientColor = Color.Black
                                            )
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                isFlipped = !isFlipped
                                            }
                                            .testTag("flip_album_art_card")
                                    ) {
                                        if (flipRotation <= 90f) {
                                            // FRONT SIDE: Album Artwork with Motion Video Support
                                            MotionAlbumArtPlayer(
                                                videoUrl = track.motionArtworkUrl,
                                                staticCoverUrl = highResCoverUrl,
                                                contentDescription = "Cover Art - Tap to flip",
                                                shape = RoundedCornerShape(22.dp),
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            // BACK SIDE: Song credits
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer { rotationY = 180f }
                                                    .background(if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground)
                                                    .border(
                                                        BorderStroke(
                                                            1.5.dp,
                                                            if (isDark) animatedAccentColor.copy(alpha = 0.65f) else appColors.cardBorder
                                                        ),
                                                        RoundedCornerShape(22.dp)
                                                    )
                                                    .padding(14.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Text(
                                                        text = track.cleanTitle,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 14.sp,
                                                        color = appColors.textPrimary,
                                                        textAlign = TextAlign.Center,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000)
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    CreditDetailRow(
                                                        label = "Album",
                                                        value = track.album.ifBlank { "Original Single" },
                                                        isDark = isDark
                                                    )
                                                    CreditDetailRow(
                                                        label = "Artist",
                                                        value = if (track.singers.isNotBlank()) track.singers else track.artist,
                                                        isDark = isDark
                                                    )
                                                    CreditDetailRow(
                                                        label = "Quality",
                                                        value = "${track.bitrateKbps} kbps Studio Master",
                                                        isDark = isDark
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "🔄 Tap to flip",
                                                        fontSize = 9.sp,
                                                        color = appColors.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // RIGHT PANE: Controls & Track Info
                    Column(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(
                                        LiquidGlass.glassBrush(appColors, translucency = 0.82f, tintAccent = false)
                                    )
                                    .border(
                                        BorderStroke(1.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.22f)),
                                        CircleShape
                                    )
                                    .bouncyClickable { onMinimize() }
                                    .testTag("player_minimize_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Minimize Player",
                                    tint = appColors.textPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "NOW PLAYING",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 2.0.sp,
                                        color = appColors.primaryAccent
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = track.cleanTitle,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = appColors.textPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            LiquidGlass.glassBrush(appColors, translucency = 0.82f, tintAccent = false)
                                        )
                                        .border(
                                            BorderStroke(1.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.22f)),
                                            CircleShape
                                        )
                                        .bouncyClickable { isMenuOpen = true }
                                        .testTag("player_options_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = appColors.textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                PlayerOptionsMenu(
                                    expanded = isMenuOpen,
                                    onDismissRequest = { isMenuOpen = false },
                                    track = track,
                                    onAddToQueue = onAddToQueue,
                                    onAddToPlaylist = onAddToPlaylist,
                                    isDark = isDark,
                                    appColors = appColors
                                )
                            }
                        }

                        // Track Info & Favorite
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.cleanTitle,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = appColors.textPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${track.artist} • ${track.album}",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = appColors.textSecondary,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Favorite Heart Toggle - Liquid Glass Button
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .liquidGlassButton(
                                        colors = appColors,
                                        shape = CircleShape,
                                        elevation = if (uiState.isFavorite) 6.dp else 3.dp,
                                        isActive = uiState.isFavorite,
                                        translucency = 0.84f
                                    )
                                    .bouncyClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleFavorite(track)
                                    }
                                    .testTag("player_favorite_toggle"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (uiState.isFavorite) Color(0xFFF43F5E) else appColors.textMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Scrubber Timeline
                        val qualityChipText = when (uiState.selectedQuality) {
                            AudioQuality.HI_RES_LOSSLESS -> {
                                val isArchiveLossless = track.isLossless && (track.audioUrl.contains("archive.org") || track.source.equals("INTERNET_ARCHIVE", ignoreCase = true) || track.id.startsWith("ia_"))
                                if (isArchiveLossless) "Hi-Res Lossless" else "Lossless"
                            }
                            AudioQuality.ULTRA_HD_320 -> "Ultra HD"
                            AudioQuality.HIGH_160 -> "HD Audio"
                            AudioQuality.MEDIUM_96 -> "Data Saver"
                        }
                        PlayerTimelineSection(
                            currentPositionProvider = currentPositionProvider,
                            trackDurationProvider = trackDurationProvider,
                            qualityChipText = qualityChipText,
                            onSeekTo = onSeekTo,
                            isDark = isDark,
                            isPlaying = uiState.isPlaying
                        )

                        // Main Controls (Shuffle, Previous, Play/Pause, Next, Repeat/Replay) in Full Liquid Glass UI
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Shuffle Button
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .liquidGlassButton(
                                        colors = appColors,
                                        shape = CircleShape,
                                        elevation = if (uiState.isShuffle) 8.dp else 4.dp,
                                        isActive = uiState.isShuffle,
                                        translucency = if (uiState.isShuffle) 0.88f else 0.80f
                                    )
                                    .bouncyClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleShuffle()
                                    }
                                    .testTag("expanded_player_shuffle"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (uiState.isShuffle) appColors.primaryAccent else appColors.textMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Previous Button
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .liquidGlassButton(
                                        colors = appColors,
                                        shape = CircleShape,
                                        elevation = 6.dp,
                                        isActive = false,
                                        translucency = 0.82f
                                    )
                                    .bouncyClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSkipPrevious()
                                    }
                                    .testTag("expanded_player_previous"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous Track",
                                    tint = appColors.textPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            // Centerpiece Play / Pause Button with Primary Liquid Glass
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(62.dp)
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(46.dp),
                                        strokeWidth = 3.5.dp,
                                        color = appColors.primaryAccent
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .primaryLiquidGlassButton(
                                                colors = appColors,
                                                shape = CircleShape,
                                                elevation = 14.dp,
                                                translucency = 0.82f
                                            )
                                            .bouncyClickable(targetScaleOnPress = 0.90f) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onPlayPause()
                                            }
                                            .testTag("expanded_player_play_pause"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                            tint = if (appColors.isDark) Color.White else appColors.primaryAccent,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                }
                            }

                            // Next Button
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .liquidGlassButton(
                                        colors = appColors,
                                        shape = CircleShape,
                                        elevation = 6.dp,
                                        isActive = false,
                                        translucency = 0.82f
                                    )
                                    .bouncyClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSkipNext()
                                    }
                                    .testTag("expanded_player_next"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next Track",
                                    tint = appColors.textPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            // Repeat / Replay Mode Button (Off -> All -> One)
                            val isRepeatActiveLandscape = uiState.repeatMode != RepeatMode.OFF
                            val repeatIconLandscape = when (uiState.repeatMode) {
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            }
                            val repeatTintLandscape = when (uiState.repeatMode) {
                                RepeatMode.OFF -> appColors.textMuted
                                else -> appColors.primaryAccent
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .liquidGlassButton(
                                        colors = appColors,
                                        shape = CircleShape,
                                        elevation = if (isRepeatActiveLandscape) 8.dp else 4.dp,
                                        isActive = isRepeatActiveLandscape,
                                        translucency = if (isRepeatActiveLandscape) 0.88f else 0.80f
                                    )
                                    .bouncyClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleRepeat()
                                    }
                                    .testTag("expanded_player_repeat"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = repeatIconLandscape,
                                    contentDescription = "Repeat Mode",
                                    tint = repeatTintLandscape,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Bottom Actions Row (Lyrics on bottom-left, Output in bottom-center, Queue on bottom-right)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val landscapeBottomPillInnerBlur = if (appColors.isDark) {
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f)))
                            } else {
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.15f)))
                            }

                            // 1. Synced Lyrics Toggle Button (Pill shape on bottom-left, Frosted Liquid Glass UI)
                            val landscapeLyricsBorder = BorderStroke(
                                1.2.dp,
                                if (showLyrics) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), appColors.primaryAccent.copy(alpha = 0.45f)))
                                else LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.45f)
                            )
                            val landscapeLyricsBg = if (showLyrics) {
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.88f else 0.92f),
                                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.70f else 0.78f)
                                    )
                                )
                            } else {
                                LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                            }
                            val landscapeLyricsContentColor = if (showLyrics) Color.White else if (appColors.isDark) Color.White else appColors.textPrimary

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showLyrics = !showLyrics
                                },
                                interactionSource = lyricsInteractionSource,
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                border = landscapeLyricsBorder,
                                modifier = Modifier
                                    .size(44.dp)
                                    .shadow(
                                        elevation = if (showLyrics) 8.dp else 4.dp,
                                        shape = RoundedCornerShape(22.dp),
                                        spotColor = if (showLyrics) appColors.primaryAccent.copy(alpha = 0.45f) else Color.Black.copy(alpha = if (appColors.isDark) 0.45f else 0.10f),
                                        ambientColor = Color.Transparent
                                    )
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(landscapeLyricsBg)
                                    .graphicsLayer {
                                        scaleX = lyricsButtonScale
                                        scaleY = lyricsButtonScale
                                    }
                                    .testTag("player_lyrics_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(landscapeBottomPillInnerBlur)
                                            .blur(16.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = if (showLyrics) 0.28f else if (appColors.isDark) 0.18f else 0.30f),
                                                        Color.Transparent
                                                    ),
                                                    startY = 0f,
                                                    endY = 20f
                                                )
                                            )
                                    )
                                    Icon(
                                        imageVector = Icons.Default.FormatQuote,
                                        contentDescription = "Toggle Lyrics",
                                        tint = landscapeLyricsContentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // 2. Output Devices Button (Pill shape in bottom-centre, Frosted Liquid Glass UI)
                            val isLandscapeOutputActive = activeOutputDevice?.isBluetooth == true
                            val landscapeOutputBorder = BorderStroke(
                                1.2.dp,
                                if (isLandscapeOutputActive) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), appColors.primaryAccent.copy(alpha = 0.45f)))
                                else LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.45f)
                            )
                            val landscapeOutputBg = if (isLandscapeOutputActive) {
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.88f else 0.92f),
                                        appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.70f else 0.78f)
                                    )
                                )
                            } else {
                                LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                            }
                            val landscapeOutputContentColor = if (isLandscapeOutputActive) Color.White else if (appColors.isDark) Color.White else appColors.textPrimary

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showSoundOutputDialog = true
                                },
                                interactionSource = outputInteractionSource,
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                border = landscapeOutputBorder,
                                modifier = Modifier
                                    .height(44.dp)
                                    .padding(horizontal = 8.dp)
                                    .shadow(
                                        elevation = if (isLandscapeOutputActive) 8.dp else 4.dp,
                                        shape = RoundedCornerShape(22.dp),
                                        spotColor = if (isLandscapeOutputActive) appColors.primaryAccent.copy(alpha = 0.45f) else Color.Black.copy(alpha = if (appColors.isDark) 0.45f else 0.10f),
                                        ambientColor = Color.Transparent
                                    )
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(landscapeOutputBg)
                                    .graphicsLayer {
                                        scaleX = outputButtonScale
                                        scaleY = outputButtonScale
                                    }
                                    .testTag("sound_output_device_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(landscapeBottomPillInnerBlur)
                                            .blur(16.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = if (isLandscapeOutputActive) 0.28f else if (appColors.isDark) 0.18f else 0.30f),
                                                        Color.Transparent
                                                    ),
                                                    startY = 0f,
                                                    endY = 20f
                                                )
                                            )
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                activeOutputDevice?.isBluetooth == true -> Icons.Default.BluetoothAudio
                                                activeOutputDevice?.isWired == true -> Icons.Default.Headphones
                                                activeOutputDevice?.isUsb == true -> Icons.Default.Usb
                                                else -> Icons.Default.Speaker
                                            },
                                            contentDescription = "Output Devices",
                                            tint = landscapeOutputContentColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(7.dp))
                                        Text(
                                            text = "Output Devices",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = landscapeOutputContentColor,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            // 3. Queue / Library Button (Pill shape on bottom-right, Frosted Liquid Glass UI)
                            val landscapeQueueBorder = BorderStroke(
                                1.3.dp,
                                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.55f)
                            )
                            val landscapeQueueBg = LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                            val landscapeQueueContentColor = if (appColors.isDark) Color.White else appColors.textPrimary

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onOpenQueue()
                                },
                                interactionSource = queueInteractionSource,
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                border = landscapeQueueBorder,
                                modifier = Modifier
                                    .size(44.dp)
                                    .shadow(
                                        elevation = 4.dp,
                                        shape = RoundedCornerShape(22.dp),
                                        spotColor = if (appColors.isDark) Color.Black.copy(alpha = 0.45f) else appColors.primaryAccent.copy(alpha = 0.12f),
                                        ambientColor = if (appColors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.05f)
                                    )
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(landscapeQueueBg)
                                    .graphicsLayer {
                                        scaleX = queueButtonScale
                                        scaleY = queueButtonScale
                                    }
                                    .testTag("player_queue_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(landscapeBottomPillInnerBlur)
                                            .blur(16.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = if (appColors.isDark) 0.18f else 0.30f),
                                                        Color.Transparent
                                                    ),
                                                    startY = 0f,
                                                    endY = 20f
                                                )
                                            )
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = "Up-Next Queue",
                                        tint = landscapeQueueContentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
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
                    .padding(vertical = if (isLandscape) 6.dp else 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .liquidGlassButton(
                            colors = appColors,
                            shape = CircleShape,
                            elevation = 4.dp,
                            isActive = false,
                            translucency = 0.82f
                        )
                        .bouncyClickable { onMinimize() }
                        .testTag("player_minimize_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize Player",
                        tint = appColors.textPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 2.0.sp,
                            color = appColors.primaryAccent
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.cleanTitle,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = appColors.textPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .liquidGlassButton(
                                colors = appColors,
                                shape = CircleShape,
                                elevation = 4.dp,
                                isActive = isMenuOpen,
                                translucency = 0.82f
                            )
                            .bouncyClickable { isMenuOpen = true }
                            .testTag("player_options_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = appColors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    PlayerOptionsMenu(
                        expanded = isMenuOpen,
                        onDismissRequest = { isMenuOpen = false },
                        track = track,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        isDark = isDark,
                        appColors = appColors
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // CENTER: SMOOTH ANIMATED REPLACEMENT OF ALBUM ART WITH SYNCED LYRICS
            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(170, easing = LinearOutSlowInEasing)) + scaleIn(initialScale = 0.96f, animationSpec = tween(170, easing = FastOutSlowInEasing))) togetherWith
                    (fadeOut(animationSpec = tween(130, easing = FastOutLinearInEasing)) + scaleOut(targetScale = 0.98f, animationSpec = tween(130, easing = FastOutLinearInEasing)))
                },
                label = "lyrics_album_art_transition",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer { clip = false }
            ) { isLyricsActive ->
                if (isLyricsActive) {
                    SyncedLyricsView(
                        lyrics = lyrics,
                        isPlaying = uiState.isPlaying,
                        currentPositionProvider = currentPositionProvider,
                        onSeekTo = onSeekTo,
                        dominantColor = animatedDominantColor,
                        accentColor = animatedAccentColor,
                        isDark = isDark,
                        onRetry = onRetryLyrics,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                // Adaptive Ambient Glow Layer matching album art with hardware acceleration
                Box(
                    modifier = Modifier
                        .size(if (isLandscape) 190.dp else 280.dp)
                        .graphicsLayer()
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
                        .fillMaxHeight(if (isLandscape) 0.88f else 1f)
                        .fillMaxWidth(albumArtScale)
                        .sizeIn(
                            maxWidth = if (isLandscape) 210.dp else 330.dp,
                            maxHeight = if (isLandscape) 210.dp else 330.dp
                        )
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
                        // FRONT SIDE: Album Artwork with Motion Video Support
                        MotionAlbumArtPlayer(
                            videoUrl = track.motionArtworkUrl,
                            staticCoverUrl = highResCoverUrl,
                            contentDescription = "Cover Art - Tap to flip",
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // BACK SIDE: Song credits and information (Fully theme adaptive)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationY = 180f }
                                .background(if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground)
                                .border(
                                    BorderStroke(
                                        1.5.dp,
                                        if (isDark) animatedAccentColor.copy(alpha = 0.65f) else appColors.cardBorder
                                    ),
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
                                    color = animatedAccentColor.copy(alpha = 0.16f),
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
                                    text = track.cleanTitle,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    color = appColors.textPrimary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            initialDelayMillis = 1000
                                        )
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                CreditDetailRow(
                                    label = "Album / Movie",
                                    value = track.album.ifBlank { "Original Single" },
                                    isDark = isDark
                                )
                                CreditDetailRow(
                                    label = "Singers / Artists",
                                    value = if (track.singers.isNotBlank()) track.singers else track.artist,
                                    isDark = isDark
                                )
                                CreditDetailRow(
                                    label = "Composer / Writer",
                                    value = if (track.writer.isNotBlank()) track.writer else "Original Composer",
                                    isDark = isDark
                                )
                                CreditDetailRow(
                                    label = "Genre & Language",
                                    value = "${track.genre} • ${if (track.language.isNotBlank()) track.language else "Hindi"}${if (track.year.isNotBlank()) " (${track.year})" else ""}",
                                    isDark = isDark
                                )
                                CreditDetailRow(
                                    label = "Audio Fidelity",
                                    value = "${track.bitrateKbps} kbps Studio Master • 44.1 kHz • 24-Bit Lossless",
                                    isDark = isDark
                                )
                                if (track.isrc.isNotBlank()) {
                                    CreditDetailRow(
                                        label = "ISRC Code",
                                        value = track.isrc,
                                        isDark = isDark
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "🔄 Tap anywhere to flip back",
                                    fontSize = 10.sp,
                                    color = appColors.textMuted,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 16.dp))

            // TRACK INFO & FAVORITE TOGGLE
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.cleanTitle,
                        style = (if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall).copy(
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${track.artist} • ${track.album}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = appColors.textSecondary,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Favorite Heart Toggle - Liquid Glass Button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .liquidGlassButton(
                            colors = appColors,
                            shape = CircleShape,
                            elevation = if (uiState.isFavorite) 8.dp else 4.dp,
                            isActive = uiState.isFavorite,
                            translucency = 0.84f
                        )
                        .bouncyClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleFavorite(track)
                        }
                        .testTag("player_favorite_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (uiState.isFavorite) Color(0xFFF43F5E) else appColors.textMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 14.dp))

            // TIMELINE SCRUB BAR (Isolated recomposition)
            val landscapeQualityChipText = when (uiState.selectedQuality) {
                AudioQuality.HI_RES_LOSSLESS -> {
                    val isArchiveLossless = track.isLossless && (track.audioUrl.contains("archive.org") || track.source.equals("INTERNET_ARCHIVE", ignoreCase = true) || track.id.startsWith("ia_"))
                    if (isArchiveLossless) "Hi-Res Lossless" else "Lossless"
                }
                AudioQuality.ULTRA_HD_320 -> "Ultra HD"
                AudioQuality.HIGH_160 -> "HD Audio"
                AudioQuality.MEDIUM_96 -> "Data Saver"
            }
            PlayerTimelineSection(
                currentPositionProvider = currentPositionProvider,
                trackDurationProvider = trackDurationProvider,
                qualityChipText = landscapeQualityChipText,
                onSeekTo = onSeekTo,
                isDark = isDark,
                isPlaying = uiState.isPlaying
            )

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 14.dp))

            // MAIN CONTROL CLUSTER (Shuffle, Prev, Play/Pause, Next, Repeat/Replay) in Full Liquid Glass UI
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .liquidGlassButton(
                            colors = appColors,
                            shape = CircleShape,
                            elevation = if (uiState.isShuffle) 8.dp else 4.dp,
                            isActive = uiState.isShuffle,
                            translucency = if (uiState.isShuffle) 0.88f else 0.80f
                        )
                        .bouncyClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleShuffle()
                        }
                        .testTag("expanded_player_shuffle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (uiState.isShuffle) appColors.primaryAccent else appColors.textMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Previous Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .liquidGlassButton(
                            colors = appColors,
                            shape = CircleShape,
                            elevation = 6.dp,
                            isActive = false,
                            translucency = 0.82f
                        )
                        .bouncyClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSkipPrevious()
                        }
                        .testTag("expanded_player_previous"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = appColors.textPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Centerpiece Play / Pause Raised Button with Primary Liquid Glass UI
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(76.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 3.5.dp,
                            color = appColors.primaryAccent
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .primaryLiquidGlassButton(
                                    colors = appColors,
                                    shape = CircleShape,
                                    elevation = 16.dp,
                                    translucency = 0.82f
                                )
                                .bouncyClickable(targetScaleOnPress = 0.90f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPlayPause()
                                }
                                .testTag("expanded_player_play_pause"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                tint = if (appColors.isDark) Color.White else appColors.primaryAccent,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                // Next Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .liquidGlassButton(
                            colors = appColors,
                            shape = CircleShape,
                            elevation = 6.dp,
                            isActive = false,
                            translucency = 0.82f
                        )
                        .bouncyClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSkipNext()
                        }
                        .testTag("expanded_player_next"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = appColors.textPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Repeat / Replay Mode Button (Off -> All -> One)
                val isRepeatActive = uiState.repeatMode != RepeatMode.OFF
                val repeatIcon = when (uiState.repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                }
                val repeatTint = when (uiState.repeatMode) {
                    RepeatMode.OFF -> appColors.textMuted
                    else -> appColors.primaryAccent
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .liquidGlassButton(
                            colors = appColors,
                            shape = CircleShape,
                            elevation = if (isRepeatActive) 8.dp else 4.dp,
                            isActive = isRepeatActive,
                            translucency = if (isRepeatActive) 0.88f else 0.80f
                        )
                        .bouncyClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleRepeat()
                        }
                        .testTag("expanded_player_repeat"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repeat Mode",
                        tint = repeatTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 14.dp))

            // BOTTOM BAR (Lyrics Button on bottom-left, Output Devices in bottom-centre, Queue on bottom-right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = if (isLandscape) 4.dp else 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val bottomPillInnerBlur = if (appColors.isDark) {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f)))
                } else {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.15f)))
                }

                // 1. Synced Lyrics Toggle Button (Pill shape on bottom-left, Frosted Liquid Glass UI)
                val lyricsBorder = BorderStroke(
                    1.2.dp,
                    if (showLyrics) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), appColors.primaryAccent.copy(alpha = 0.45f)))
                    else LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.45f)
                )
                val lyricsBg = if (showLyrics) {
                    Brush.verticalGradient(
                        listOf(
                            appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.88f else 0.92f),
                            appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.70f else 0.78f)
                        )
                    )
                } else {
                    LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                }
                val lyricsContentColor = if (showLyrics) Color.White else if (appColors.isDark) Color.White else appColors.textPrimary

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showLyrics = !showLyrics
                    },
                    interactionSource = lyricsInteractionSource,
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    border = lyricsBorder,
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(
                            elevation = if (showLyrics) 8.dp else 4.dp,
                            shape = RoundedCornerShape(22.dp),
                            spotColor = if (showLyrics) appColors.primaryAccent.copy(alpha = 0.45f) else (if (appColors.isDark) Color.Black.copy(alpha = 0.45f) else appColors.primaryAccent.copy(alpha = 0.12f)),
                            ambientColor = if (appColors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.05f)
                        )
                        .clip(RoundedCornerShape(22.dp))
                        .background(lyricsBg)
                        .graphicsLayer {
                            scaleX = lyricsButtonScale
                            scaleY = lyricsButtonScale
                        }
                        .testTag("player_lyrics_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Inner Gaussian blur layer inside the button pill
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(22.dp))
                                .background(bottomPillInnerBlur)
                                .blur(16.dp)
                        )
                        // Top specular sheen
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = if (showLyrics) 0.28f else if (appColors.isDark) 0.18f else 0.30f),
                                            Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = 20f
                                    )
                                )
                        )
                        Icon(
                            imageVector = Icons.Default.FormatQuote,
                            contentDescription = "Toggle Lyrics",
                            tint = lyricsContentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 2. Output Devices Button (Pill shape in bottom-centre, Frosted Liquid Glass UI)
                val isOutputActive = activeOutputDevice?.isBluetooth == true
                val outputBorder = BorderStroke(
                    1.3.dp,
                    if (isOutputActive) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), appColors.primaryAccent.copy(alpha = 0.45f)))
                    else LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.55f)
                )
                val outputBg = if (isOutputActive) {
                    Brush.verticalGradient(
                        listOf(
                            appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.88f else 0.92f),
                            appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.70f else 0.78f)
                        )
                    )
                } else {
                    LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                }
                val outputContentColor = if (isOutputActive) Color.White else if (appColors.isDark) Color.White else appColors.textPrimary

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showSoundOutputDialog = true
                    },
                    interactionSource = outputInteractionSource,
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    border = outputBorder,
                    modifier = Modifier
                        .height(44.dp)
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .shadow(
                            elevation = if (isOutputActive) 8.dp else 4.dp,
                            shape = RoundedCornerShape(22.dp),
                            spotColor = if (isOutputActive) appColors.primaryAccent.copy(alpha = 0.45f) else (if (appColors.isDark) Color.Black.copy(alpha = 0.45f) else appColors.primaryAccent.copy(alpha = 0.12f)),
                            ambientColor = if (appColors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.05f)
                        )
                        .clip(RoundedCornerShape(22.dp))
                        .background(outputBg)
                        .graphicsLayer {
                            scaleX = outputButtonScale
                            scaleY = outputButtonScale
                        }
                        .testTag("sound_output_device_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Inner Gaussian blur layer inside the button pill
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(22.dp))
                                .background(bottomPillInnerBlur)
                                .blur(16.dp)
                        )
                        // Top specular sheen
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = if (isOutputActive) 0.28f else if (appColors.isDark) 0.18f else 0.30f),
                                            Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = 20f
                                    )
                                )
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    activeOutputDevice?.isBluetooth == true -> Icons.Default.BluetoothAudio
                                    activeOutputDevice?.isWired == true -> Icons.Default.Headphones
                                    activeOutputDevice?.isUsb == true -> Icons.Default.Usb
                                    else -> Icons.Default.Speaker
                                },
                                contentDescription = "Output Devices",
                                tint = outputContentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "Output Devices",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = outputContentColor,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // 3. Queue / Library Button (Pill shape on bottom-right, Frosted Liquid Glass UI)
                val queueBorder = BorderStroke(
                    1.3.dp,
                    LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.55f)
                )
                val queueBg = LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                val queueContentColor = if (appColors.isDark) Color.White else appColors.textPrimary

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenQueue()
                    },
                    interactionSource = queueInteractionSource,
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    border = queueBorder,
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(22.dp),
                            spotColor = if (appColors.isDark) Color.Black.copy(alpha = 0.45f) else appColors.primaryAccent.copy(alpha = 0.12f),
                            ambientColor = if (appColors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.05f)
                        )
                        .clip(RoundedCornerShape(22.dp))
                        .background(queueBg)
                        .graphicsLayer {
                            scaleX = queueButtonScale
                            scaleY = queueButtonScale
                        }
                        .testTag("player_queue_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Inner Gaussian blur layer inside the button pill
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(22.dp))
                                .background(bottomPillInnerBlur)
                                .blur(16.dp)
                        )
                        // Top specular sheen
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = if (appColors.isDark) 0.18f else 0.30f),
                                            Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = 20f
                                    )
                                )
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Up-Next Queue",
                            tint = queueContentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // Sound Output Devices Popup Dialog
    if (showSoundOutputDialog) {
        SoundOutputDeviceDialog(
            devices = effectiveDevices,
            isDark = isDark,
            onSelectDevice = { deviceId ->
                onSelectAudioDevice(deviceId)
                AudioDeviceManager.selectDevice(context, deviceId)
            },
            onDismiss = { showSoundOutputDialog = false }
        )
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
private fun CreditDetailRow(label: String, value: String, isDark: Boolean = true) {
    val appColors = LocalAppColors.current
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
            color = appColors.textMuted,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 8.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 8.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = value,
                fontSize = 12.sp,
                color = appColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1000
                ),
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun PlayerBokehBackground(
    dominantColor: Color,
    accentColor: Color,
    vibrantColor: Color = accentColor,
    isDark: Boolean = true,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
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
        initialValue = 0.75f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(5500, easing = FastOutSlowInEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "bokeh_pulse"
    )

    val isAmoled = appColors.isAmoled

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Root theme canvas base fill:
        // - AMOLED: Solid black #000000 at the foundation
        // - Dark: theme scaffoldBackground
        // - Light: theme scaffoldBackground or pristine #F8FAFC
        val baseColor = if (isAmoled) {
            Color(0xFF000000)
        } else if (isDark) {
            appColors.scaffoldBackground
        } else {
            appColors.scaffoldBackground
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(baseColor)
        )

        // 2. Multi-layered Gaussian blur diffusion (32dp - 48dp) blending dynamic album art palette colors
        // Layer A: Deeper Ambient Diffusion (blur 48.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(48.dp)
                .graphicsLayer { alpha = 0.88f }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                val bgCenter = Offset(
                    x = w * (0.50f + 0.05f * (floatAnim1 - 0.5f)),
                    y = h * (0.38f + 0.05f * (floatAnim2 - 0.5f))
                )
                val bgRadius = w * 0.95f

                if (isAmoled) {
                    // AMOLED Dark Mode Adaptation:
                    // Subtle, dark-tinted dynamic radial glow derived directly from album art palette
                    // fading into deep pure AMOLED black at the edges to preserve battery savings while looking rich.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                dominantColor.copy(alpha = 0.42f * pulseAnim),
                                vibrantColor.copy(alpha = 0.25f * pulseAnim),
                                Color(0xFF000000).copy(alpha = 0.85f),
                                Color(0xFF000000)
                            ),
                            center = bgCenter,
                            radius = bgRadius
                        ),
                        center = bgCenter,
                        radius = bgRadius
                    )
                } else if (!isDark) {
                    // Light Mode Adaptation:
                    // Soft, pastel/translucent ambient foundation derived from album art palette
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                dominantColor.copy(alpha = 0.28f * pulseAnim),
                                vibrantColor.copy(alpha = 0.18f * pulseAnim),
                                Color.Transparent
                            ),
                            center = bgCenter,
                            radius = bgRadius
                        ),
                        center = bgCenter,
                        radius = bgRadius
                    )
                } else {
                    // Standard Dark Mode
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                dominantColor.copy(alpha = 0.70f * pulseAnim),
                                vibrantColor.copy(alpha = 0.50f * pulseAnim),
                                Color.Transparent
                            ),
                            center = bgCenter,
                            radius = bgRadius
                        ),
                        center = bgCenter,
                        radius = bgRadius
                    )
                }
            }
        }

        // Layer B: Vibrant Liquid Foreground Orbs (blur 32.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(32.dp)
                .graphicsLayer { alpha = 0.95f }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                val effectiveAccent = accentColor
                val effectiveVibrant = vibrantColor
                val effectiveDominant = dominantColor

                if (isAmoled) {
                    // AMOLED mode: Dark-tinted dynamic glowing aura behind artwork
                    // Radial gradient concentrates glow centrally and gracefully fades to true black (#000000)
                    val auraCenter = Offset(
                        x = w * (0.50f + 0.06f * (floatAnim1 - 0.5f)),
                        y = h * (0.35f + 0.05f * (floatAnim2 - 0.5f))
                    )
                    val auraRadius = w * 0.82f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveVibrant.copy(alpha = 0.38f * pulseAnim),
                                effectiveAccent.copy(alpha = 0.26f * pulseAnim),
                                effectiveDominant.copy(alpha = 0.14f * pulseAnim),
                                Color.Transparent
                            ),
                            center = auraCenter,
                            radius = auraRadius
                        ),
                        center = auraCenter,
                        radius = auraRadius
                    )

                    // Secondary subtle ambient highlight
                    val orb1Center = Offset(
                        x = w * (0.28f + 0.08f * (floatAnim2 - 0.5f)),
                        y = h * (0.24f + 0.06f * (floatAnim1 - 0.5f))
                    )
                    val orb1Radius = w * 0.60f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveDominant.copy(alpha = 0.24f * pulseAnim),
                                effectiveAccent.copy(alpha = 0.12f * pulseAnim),
                                Color.Transparent
                            ),
                            center = orb1Center,
                            radius = orb1Radius
                        ),
                        center = orb1Center,
                        radius = orb1Radius
                    )
                } else if (!isDark) {
                    // Light Mode Adaptation:
                    // Blend soft, pastel/translucent gradient derived from album art palette
                    // with high text/icon legibility
                    val dominantAlpha = 0.26f
                    val accentAlpha = 0.30f
                    val vibrantAlpha = 0.24f

                    // Primary ambient radial pastel aura behind artwork
                    val auraCenter = Offset(
                        x = w * (0.50f + 0.08f * (floatAnim1 - 0.5f)),
                        y = h * (0.36f + 0.06f * (floatAnim2 - 0.5f))
                    )
                    val auraRadius = w * 0.88f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveAccent.copy(alpha = accentAlpha * pulseAnim),
                                effectiveVibrant.copy(alpha = vibrantAlpha * pulseAnim),
                                effectiveDominant.copy(alpha = dominantAlpha * pulseAnim),
                                Color.Transparent
                            ),
                            center = auraCenter,
                            radius = auraRadius
                        ),
                        center = auraCenter,
                        radius = auraRadius
                    )

                    // Secondary pastel orb floating upper-left
                    val orb1Center = Offset(
                        x = w * (0.24f + 0.10f * (floatAnim1 - 0.5f)),
                        y = h * (0.22f + 0.08f * (floatAnim2 - 0.5f))
                    )
                    val orb1Radius = w * 0.72f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveDominant.copy(alpha = (dominantAlpha * 0.8f) * pulseAnim),
                                effectiveAccent.copy(alpha = (accentAlpha * 0.5f) * pulseAnim),
                                Color.Transparent
                            ),
                            center = orb1Center,
                            radius = orb1Radius
                        ),
                        center = orb1Center,
                        radius = orb1Radius
                    )

                    // Floating pastel orb mid-right
                    val orb2Center = Offset(
                        x = w * (0.78f - 0.12f * (floatAnim2 - 0.5f)),
                        y = h * (0.48f + 0.10f * (floatAnim1 - 0.5f))
                    )
                    val orb2Radius = w * 0.70f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveVibrant.copy(alpha = (vibrantAlpha * 0.8f) * pulseAnim),
                                effectiveAccent.copy(alpha = (accentAlpha * 0.4f) * pulseAnim),
                                Color.Transparent
                            ),
                            center = orb2Center,
                            radius = orb2Radius
                        ),
                        center = orb2Center,
                        radius = orb2Radius
                    )
                } else {
                    // Standard Dark Mode
                    val dominantAlpha = 0.85f
                    val accentAlpha = 0.90f
                    val vibrantAlpha = 0.75f

                    // Dynamic primary ambient radial glass aura centered behind artwork
                    val auraCenter = Offset(
                        x = w * (0.50f + 0.08f * (floatAnim1 - 0.5f)),
                        y = h * (0.36f + 0.06f * (floatAnim2 - 0.5f))
                    )
                    val auraRadius = w * 0.88f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveAccent.copy(alpha = accentAlpha * pulseAnim),
                                effectiveVibrant.copy(alpha = (vibrantAlpha * 0.75f) * pulseAnim),
                                effectiveDominant.copy(alpha = (dominantAlpha * 0.50f) * pulseAnim),
                                Color.Transparent
                            ),
                            center = auraCenter,
                            radius = auraRadius
                        ),
                        center = auraCenter,
                        radius = auraRadius
                    )

                    // Secondary ambient glow floating upper-left
                    val orb1Center = Offset(
                        x = w * (0.24f + 0.10f * (floatAnim1 - 0.5f)),
                        y = h * (0.22f + 0.08f * (floatAnim2 - 0.5f))
                    )
                    val orb1Radius = w * 0.72f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveDominant.copy(alpha = dominantAlpha * pulseAnim),
                                effectiveAccent.copy(alpha = (dominantAlpha * 0.40f) * pulseAnim),
                                Color.Transparent
                            ),
                            center = orb1Center,
                            radius = orb1Radius
                        ),
                        center = orb1Center,
                        radius = orb1Radius
                    )

                    // Vibrant bokeh orb floating mid-right
                    val orb2Center = Offset(
                        x = w * (0.78f - 0.12f * (floatAnim2 - 0.5f)),
                        y = h * (0.48f + 0.10f * (floatAnim1 - 0.5f))
                    )
                    val orb2Radius = w * 0.70f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveVibrant.copy(alpha = vibrantAlpha * pulseAnim),
                                effectiveAccent.copy(alpha = (accentAlpha * 0.50f) * pulseAnim),
                                Color.Transparent
                            ),
                            center = orb2Center,
                            radius = orb2Radius
                        ),
                        center = orb2Center,
                        radius = orb2Radius
                    )

                    // Lower aura floating bottom-left
                    val orb3Center = Offset(
                        x = w * (0.30f + 0.14f * (floatAnim2 - 0.5f)),
                        y = h * (0.78f - 0.08f * (floatAnim1 - 0.5f))
                    )
                    val orb3Radius = w * 0.74f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                effectiveAccent.copy(alpha = 0.55f * pulseAnim),
                                effectiveDominant.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            center = orb3Center,
                            radius = orb3Radius
                        ),
                        center = orb3Center,
                        radius = orb3Radius
                    )
                }
            }
        }

        // 3. Subtle Frosted Glass Micro-Texture Noise Overlay
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.040f }) {
            val step = 4f
            var y = 0f
            while (y < size.height) {
                var x = (y.toInt() % 3) * 1.5f
                while (x < size.width) {
                    drawRect(
                        color = if (isDark) Color.White else Color(0xFF1E293B),
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(1.5f, 1.5f)
                    )
                    x += step * 3f
                }
                y += step * 2f
            }
        }

        // 4. Crystal-clear 3D liquid glass specular overlays & caustic curvature sheen
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Top specular caustic sheen across upper glass curvature
            val glassCausticBrush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDark) 0.14f else 0.25f),
                    Color.White.copy(alpha = if (isDark) 0.03f else 0.06f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h * 0.45f
            )
            drawRect(glassCausticBrush)

            // Dynamic vignette overlay to guarantee controls and text legibility across all themes
            val vignetteColors = if (isAmoled) {
                listOf(
                    Color(0xFF000000).copy(alpha = 0.40f),
                    Color.Transparent,
                    Color(0xFF000000).copy(alpha = 0.65f),
                    Color(0xFF000000).copy(alpha = 0.95f)
                )
            } else if (isDark) {
                val darkBase = appColors.scaffoldBackground
                listOf(
                    darkBase.copy(alpha = 0.30f),
                    Color.Transparent,
                    darkBase.copy(alpha = 0.55f),
                    darkBase.copy(alpha = 0.90f)
                )
            } else {
                val lightBase = appColors.scaffoldBackground
                listOf(
                    lightBase.copy(alpha = 0.20f),
                    Color.Transparent,
                    lightBase.copy(alpha = 0.35f),
                    lightBase.copy(alpha = 0.88f)
                )
            }
            drawRect(brush = Brush.verticalGradient(colors = vignetteColors))
        }
    }
}

@Composable
private fun PlayerOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    track: MusicTrack,
    onAddToQueue: (MusicTrack) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    isDark: Boolean,
    appColors: com.example.ui.theme.AppThemeColors = LocalAppColors.current,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val opaqueMenuBaseColor = if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground
    val opaqueMenuBrush = Brush.verticalGradient(
        if (appColors.isAmoled) {
            listOf(Color(0xFF000000), Color(0xFF000000))
        } else {
            listOf(appColors.cardBackground, appColors.cardBackground)
        }
    )

    val menuBorderStroke = if (appColors.isAmoled) {
        BorderStroke(1.2.dp, Color(0xFF222222))
    } else {
        BorderStroke(1.2.dp, appColors.cardBorder.copy(alpha = if (isDark) 0.60f else 0.75f))
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            shape = RoundedCornerShape(22.dp),
            containerColor = opaqueMenuBaseColor,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
            border = menuBorderStroke,
            modifier = modifier
                .widthIn(min = 220.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(opaqueMenuBrush)
                .drawWithContent {
                    drawContent()
                    if (!appColors.isAmoled) {
                        // Top specular reflection sheen
                        val sheenBrush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isDark) 0.12f else 0.25f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = 40f
                        )
                        drawRect(sheenBrush)
                    }
                }
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            val itemIconBg = if (appColors.isAmoled) Color(0xFF141414) else if (isDark) appColors.cardBorder.copy(alpha = 0.35f) else appColors.chipBackground

            // Action 1: Add to Queue
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Add to Queue",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    )
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(itemIconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = {
                    AppHaptics.performTap(context)
                    onAddToQueue(track)
                    onDismissRequest()
                    android.widget.Toast.makeText(
                        context,
                        "Added \"${track.title}\" to Queue",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )

            // Action 2: Add to Playlist
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Add to Playlist",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    )
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(itemIconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = {
                    AppHaptics.performTap(context)
                    onDismissRequest()
                    onAddToPlaylist(track)
                },
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )

            // Action 3: Share Track
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Share Track",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    )
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(itemIconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = {
                    AppHaptics.performTap(context)
                    onDismissRequest()
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, "Listening to \"${track.title}\" by ${track.artist} on Xtreme Player!")
                        type = "text/plain"
                    }
                    context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Track"))
                },
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PlayerTimelineSection(
    currentPositionProvider: () -> Long,
    trackDurationProvider: () -> Long,
    qualityChipText: String = "",
    onSeekTo: (Long) -> Unit,
    isDark: Boolean,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    // Dynamic real-time playback position state
    var livePosition by remember { mutableLongStateOf(currentPositionProvider()) }

    // Synchronize smoothly in real-time while playing (every 150ms)
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            livePosition = currentPositionProvider()
            return@LaunchedEffect
        }
        while (isActive) {
            if (!isUserScrubbing) {
                livePosition = currentPositionProvider()
            }
            delay(150)
        }
    }

    // Keep updated on external position shifts (seeks, track changes, etc.)
    val externalPos = currentPositionProvider()
    LaunchedEffect(externalPos) {
        if (!isUserScrubbing) {
            livePosition = externalPos
        }
    }

    val trackDuration = trackDurationProvider().coerceAtLeast(1L)
    val currentPosition = if (isUserScrubbing) {
        (scrubPosition * trackDuration).toLong()
    } else {
        livePosition
    }
    val sliderValue = if (isUserScrubbing) scrubPosition else (currentPosition.toFloat() / trackDuration.toFloat()).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth().graphicsLayer()) {
        WavyScrubberBar(
            progress = sliderValue,
            isPlaying = isPlaying,
            onSeekStarted = {
                isUserScrubbing = true
            },
            onSeekProgress = { fraction ->
                scrubPosition = fraction
            },
            onSeekFinished = { fraction ->
                val seekPos = (fraction * trackDuration).toLong()
                livePosition = seekPos
                onSeekTo(seekPos)
                isUserScrubbing = false
            },
            isDark = isDark,
            modifier = Modifier
                .fillMaxWidth()
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
                    color = appColors.textMuted,
                    fontSize = 12.sp
                )
            )

            // Dynamic Quality Chip (Hi Res Lossless, Lossless, Ultra HD, HD Audio, Data Saver)
            if (qualityChipText.isNotBlank()) {
                Surface(
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.22f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(LiquidGlass.glassBrush(appColors, translucency = 0.85f, tintAccent = false))
                        .padding(horizontal = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(appColors.primaryAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = qualityChipText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = appColors.primaryAccent,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }
            }

            Text(
                text = formatTime(trackDuration),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = appColors.textMuted,
                    fontSize = 12.sp
                )
            )
        }
    }
}
