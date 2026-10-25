package com.example.ui.components

import com.example.performance.AppDispatchers

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.Toast
import android.os.Build
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Usb
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
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
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
import com.example.ui.theme.bouncyClickable
import com.example.ui.components.playerLiquidGlassButton as liquidGlassButton
import com.example.ui.components.playerPrimaryLiquidGlassButton as primaryLiquidGlassButton
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremeRose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.ceil

private val playerSingerSeparator = Regex("[,;]|\\s+(?:feat\\.?|ft\\.?|featuring|&)\\s+", RegexOption.IGNORE_CASE)

/** Compact player subtitle only; retain full recording credits everywhere else. */
internal fun playerSingerLabel(track: MusicTrack): String {
    fun firstTwo(value: String) = playerSingerSeparator.splitToSequence(value)
        .map(String::trim).filter(String::isNotBlank)
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        .take(2).joinToString(", ")
    return firstTwo(track.singers).ifBlank { firstTwo(track.artist) }
}

@OptIn(ExperimentalSharedTransitionApi::class)
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
    playbackPositionFlow: StateFlow<Long>? = null,
    currentPositionProvider: () -> Long = { uiState.currentPositionMs },
    trackDurationProvider: () -> Long = { if (uiState.durationMs > 0) uiState.durationMs else (uiState.currentTrack?.durationMs ?: 0L) },
    bufferedPositionProvider: () -> Long = { uiState.bufferedPositionMs },
    onLyricsVisibilityChanged: (Boolean) -> Unit = {},
    isScreenVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack ?: return
    val singerLabel = remember(track.artist, track.singers) { playerSingerLabel(track) }
    // A preference is a ceiling, never evidence of the format actually playing.
    val displayedQuality = uiState.qualityBadge.ifBlank { track.qualityBadge }.ifBlank { "Resolving audio" }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val appColors = LocalAppColors.current

    val itunesRepo = iTunesArtworkRepository.peekInstance()
    val artworkLoader = remember(context) { context.imageLoader }
    val initialRawCoverUrl = remember(track.coverUrl) {
        val raw = track.coverUrl
        when {
            raw.contains("150x150.jpg") -> raw.replace("150x150.jpg", "500x500.jpg")
            raw.contains("50x50.jpg") -> raw.replace("50x50.jpg", "500x500.jpg")
            raw.contains("hqdefault.jpg") -> raw.replace("hqdefault.jpg", "maxresdefault.jpg")
            raw.contains("mqdefault.jpg") -> raw.replace("mqdefault.jpg", "maxresdefault.jpg")
            raw.contains("default.jpg") && !raw.contains("maxresdefault.jpg") -> raw.replace("default.jpg", "maxresdefault.jpg")
            raw.contains("100x100bb") -> iTunesArtworkRepository.formatArtworkUrl(raw, ArtworkDimensions.EXPANDED_PLAYER)
            raw.contains("100x100") -> iTunesArtworkRepository.formatArtworkUrl(raw, ArtworkDimensions.EXPANDED_PLAYER)
            else -> raw
        }
    }

    val resolvedArtworkRequest = com.example.ui.util.rememberOptimizedImageRequest(
        track = track, targetSize = if (isScreenVisible) com.example.ui.util.ImageConfig.PLAYER_SCREEN_SIZE else 192)
    val highResCoverUrl = resolvedArtworkRequest.data as? String ?: ""
    val memoryItunesUrl = remember(track.id, track.title, track.artist, track.source, track.coverUrl, itunesRepo) {
        itunesRepo?.peekMemoryCachedUrl(track.toMediaItem(), ArtworkDimensions.EXPANDED_PLAYER)
    }
    val cachedArtworkKey by key(track.id, track.title, track.artist, track.coverUrl,
        highResCoverUrl, memoryItunesUrl, resolvedArtworkRequest.placeholderMemoryCacheKey) {
        produceState(resolvedArtworkRequest.placeholderMemoryCacheKey) {
            // Scanning Coil's decoded cache and comparing artwork variants is CPU
            // work. A keyed producer cannot publish a previous song's placeholder.
            value = withContext(AppDispatchers.CpuBounded) {
                val urls = mutableSetOf(track.coverUrl, initialRawCoverUrl, highResCoverUrl)
                memoryItunesUrl?.let { url ->
                    val dimensions = Regex("\\d+x\\d+bb")
                    listOf(200, 600, 1200).forEach { size ->
                        urls.add(url.replace(dimensions, "${size}x${size}bb"))
                    }
                }
                val cache = artworkLoader.memoryCache
                cache?.keys?.asSequence()?.filter { it.key in urls }
                    ?.mapNotNull { cacheKey -> cache[cacheKey]?.bitmap?.let { cacheKey to it.width.toLong() * it.height } }
                    ?.maxByOrNull { it.second }?.first ?: resolvedArtworkRequest.placeholderMemoryCacheKey
            }
        }
    }
    val highResImageRequest = remember(resolvedArtworkRequest, cachedArtworkKey) {
        resolvedArtworkRequest.newBuilder().placeholderMemoryCacheKey(cachedArtworkKey).build()
    }

    var isMenuOpen by remember { mutableStateOf(false) }
    var isSleepTimerOpen by remember { mutableStateOf(false) }
    var isLyricsOffsetOpen by remember { mutableStateOf(false) }
    val lyricsOffsetMs by com.example.data.local.LyricsOffsetManager.activeOffsetMs.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) {
        com.example.data.local.LyricsOffsetManager.loadOffsetForTrack(context, track.id)
    }
    var isFlipped by remember(track.id) { mutableStateOf(false) }
    var showLyrics by remember(track.id) { mutableStateOf(false) }
    val lyricsTextController = rememberLyricsTextController(lyrics, showLyrics && isScreenVisible)
    val lyricsTextState by lyricsTextController.state.collectAsStateWithLifecycle()
    LaunchedEffect(lyricsTextController, lyricsTextState.message) {
        lyricsTextState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            lyricsTextController.clearMessage()
        }
    }

    val integrationsFlow = remember(context) { com.example.data.local.OtherSettingsPreferences.observePlaybackIntegrations(context) }
    val integrations by integrationsFlow.collectAsStateWithLifecycle(
        initialValue = com.example.data.local.OtherSettingsPreferences.playbackIntegrations(context))
    KeepLyricsScreenAwake(showLyrics && isScreenVisible, integrations.keepLyricsAwake)
    var maximizeLyrics by remember(track.id) { mutableStateOf(false) }
    val lyricsExpanded = showLyrics && maximizeLyrics
    val lyricsExpansion = animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = tween(LYRICS_EXPANSION_MS, easing = FastOutSlowInEasing),
        label = "lyrics_expansion"
    )
    val expandedLyricsVisible by remember { derivedStateOf { lyricsExpansion.value > 0f } }
    val lyricsViewportAnimating by remember(lyricsExpanded) {
        derivedStateOf { kotlin.math.abs(lyricsExpansion.value - if (lyricsExpanded) 1f else 0f) > 0.001f }
    }
    DisposableEffect(lyricsViewportAnimating, isScreenVisible) {
        val lease = if (lyricsViewportAnimating && isScreenVisible)
            com.example.performance.BackgroundWorkCoordinator.navigationBusy() else null
        onDispose { lease?.close() }
    }
    LaunchedEffect(showLyrics) { if (!showLyrics) maximizeLyrics = false }
    BackHandler(enabled = lyricsExpanded && isScreenVisible) { maximizeLyrics = false }
    val lyricsLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lyricsVisibilityCallback by rememberUpdatedState(onLyricsVisibilityChanged)
    LaunchedEffect(showLyrics, isScreenVisible, lyricsLifecycle) {
        if (showLyrics && isScreenVisible) lyricsLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            lyricsVisibilityCallback(true)
            try { kotlinx.coroutines.awaitCancellation() } finally { lyricsVisibilityCallback(false) }
        }
    }
    var showSoundOutputDialog by remember { mutableStateOf(false) }
    var showLyricsTextSizePopup by remember { mutableStateOf(false) }
    var showLyricsProviderPopup by remember { mutableStateOf(false) }
    val lyricsPreferences = remember(context) { com.example.data.local.OtherSettingsPreferences.observeLyricsSettings(context) }
    val lyricsSettings by lyricsPreferences.collectAsStateWithLifecycle()
    val displayedLyricsProvider = lyrics?.provider?.takeIf { it.isNotBlank() } ?: lyricsSettings.provider

    val coroutineScope = rememberCoroutineScope()
    var lyricsImportTarget by rememberSaveable(stateSaver = LyricsImportTargetSaver) { mutableStateOf<MusicTrack?>(null) }
    val latestTrack by rememberUpdatedState(track)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val importTrack = lyricsImportTarget
        lyricsImportTarget = null
        if (uri != null) {
            showLyricsProviderPopup = false
            if (importTrack == null) {
                Toast.makeText(context, "Please select the lyrics file again", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch(AppDispatchers.Io) {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                        com.example.data.local.CustomLyricsManager.readImport(stream)
                    }
                    if (!content.isNullOrBlank()) {
                        val saved = com.example.data.local.CustomLyricsManager.saveCustomLyrics(context, importTrack, content)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (saved) {
                                if (latestTrack.id == importTrack.id) onRetryLyrics()
                                Toast.makeText(context, "Custom lyrics imported successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to save custom lyrics", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Selected .lrc file is empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Error importing file: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // SAF grants access to the chosen document; no broad storage permission is needed.
    val launchImportFlow = {
        lyricsImportTarget = track
        filePickerLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
    }

    val fallbackDevices by AudioDeviceManager.availableDevices.collectAsStateWithLifecycle()
    val effectiveDevices = if (availableAudioDevices.isNotEmpty()) availableAudioDevices else fallbackDevices
    val activeOutputDevice = effectiveDevices.find { it.isSelected }

    // Dynamic color extracted from album art with active theme fallback and in-memory song ID cache
    val paletteKey = remember(track.id, track.coverUrl, isDark, appColors.primaryAccent, appColors.cardBackgroundElevated) {
        com.example.ui.util.playerPaletteKey(track, isDark, appColors.primaryAccent, appColors.cardBackgroundElevated)
    }
    val cachedColors = remember(paletteKey) { TrackPaletteCache.get(paletteKey) }
    var dominantColor by remember(track.id) { mutableStateOf(cachedColors?.dominantColor ?: appColors.cardBackgroundElevated) }
    var accentColor by remember(track.id, appColors.primaryAccent) { mutableStateOf(cachedColors?.accentColor ?: appColors.primaryAccent) }
    var vibrantColor by remember(track.id, appColors.primaryAccent) { mutableStateOf(cachedColors?.vibrantColor ?: appColors.primaryAccent) }

    LaunchedEffect(paletteKey, isScreenVisible, appColors.dynamicAlbumGlow) {
        if (!isScreenVisible || !appColors.dynamicAlbumGlow) return@LaunchedEffect
        // Fast-path: If already in memory cache, avoid all processing
        val inMem = TrackPaletteCache.get(paletteKey)
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
                        val palette = withContext(AppDispatchers.CpuBounded) {
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
                        TrackPaletteCache.put(paletteKey, extracted)
                        withContext(Dispatchers.Main) {
                            dominantColor = extracted.dominantColor
                            accentColor = extracted.accentColor
                            vibrantColor = extracted.vibrantColor
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
        }
    }

    val animatedDominantColor = animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "dominant_color"
    )
    val animatedAccentColor = animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "accent_color"
    )
    val animatedVibrantColor = animateColorAsState(
        targetValue = vibrantColor,
        animationSpec = tween(650, easing = LinearOutSlowInEasing),
        label = "vibrant_color"
    )

    val isEffectivelyDark by remember(isDark, appColors.isDark, appColors.isAmoled, appColors.dynamicAlbumGlow, animatedDominantColor, appColors.scaffoldBackground) { derivedStateOf {
        val color = if (appColors.dynamicAlbumGlow) animatedDominantColor.value else appColors.scaffoldBackground
        if (isDark || appColors.isDark || appColors.isAmoled) {
            true
        } else if (color != Color.Unspecified) {
            val base = appColors.scaffoldBackground
            val blendedRed = color.red * 0.25f + base.red * 0.75f
            val blendedGreen = color.green * 0.25f + base.green * 0.75f
            val blendedBlue = color.blue * 0.25f + base.blue * 0.75f
            val lum = 0.299f * blendedRed + 0.587f * blendedGreen + 0.114f * blendedBlue
            lum < 0.50f
        } else {
            false
        }
    } }

    val albumArtScale = animateFloatAsState(
        targetValue = if (uiState.isPlaying) 1.0f else 0.88f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 550f),
        label = "album_art_scale"
    )

    val flipRotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(if (isFlipped) 300 else 260, easing = FastOutSlowInEasing),
        label = "flip_card_rotation"
    )
    // Only switch faces at the midpoint; rotation itself is read by the graphics layer.
    val showArtworkFront by remember { derivedStateOf { flipRotation <= 90f } }

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

    val playerBackdrop = rememberLiquidGlassBackdrop()
    CompositionLocalProvider(LocalLiquidGlassBackdrop provides playerBackdrop) {
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
            // Album-adaptive glossy backdrop; separate from sharp artwork and controls.
            PlayerGlassBackground(
                artworkUrl = highResCoverUrl,
                artworkRequest = resolvedArtworkRequest,
                dominantColor = animatedDominantColor,
                accentColor = animatedAccentColor,
                vibrantColor = animatedVibrantColor,
                isDark = isDark
            )

            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            if (isLandscape) {
                // LANDSCAPE DEDICATED TWO-PANE LAYOUT
                LandscapeLyricsExpansion(
                    expansion = lyricsExpansion,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                ) {
                    // LEFT PANE: Album Art / Lyrics (Proper Square Shape Guarantee)
                    SharedTransitionLayout(
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        AnimatedContent(
                            targetState = showLyrics,
                            transitionSpec = { lyricsArtworkTransition() },
                            contentAlignment = Alignment.Center,
                            label = "lyrics_album_art_transition_landscape",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { clip = false }
                        ) { isLyricsActive ->
                            if (isLyricsActive) {
                                Column(Modifier.fillMaxSize()) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AsyncImage(model = highResImageRequest, contentDescription = "Cover Art",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(44.dp)
                                                .sharedElement(rememberSharedContentState("lyrics_artwork_${track.id}"),
                                                    animatedVisibilityScope = this@AnimatedContent,
                                                    boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) })
                                                .clip(RoundedCornerShape(10.dp)))
                                        Column(Modifier.weight(1f)) {
                                            Text(track.cleanTitle, color = appColors.textPrimary, fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                maxLines = 1, modifier = Modifier.basicMarquee(), softWrap = false)
                                            Text(singerLabel, color = appColors.textSecondary, fontSize = 14.sp,
                                                maxLines = 1, modifier = Modifier.basicMarquee(), softWrap = false)
                                        }
                                        IconButton(onClick = { onToggleFavorite(track) }) {
                                            Icon(if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = "Favorite",
                                                tint = if (uiState.isFavorite) Color(0xFFF43F5E) else appColors.textMuted)
                                        }
                                    }
                                PlayerSyncedLyrics(
                                    lyrics = lyricsTextState.lyrics,
                                    currentPositionMs = uiState.currentPositionMs,
                                    playbackPositionFlow = playbackPositionFlow,
                                    isPlaying = uiState.isPlaying,
                                    currentPositionProvider = currentPositionProvider,
                                    onSeekTo = onSeekTo,
                                    dominantColor = animatedDominantColor,
                                    accentColor = animatedAccentColor,
                                    isDark = isDark,
                                    onRetry = onRetryLyrics,
                                    showRomanization = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                                    showTranslation = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                                    viewportAnimating = lyricsViewportAnimating,
                                    lyricsOffsetMs = lyricsOffsetMs,
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Adaptive Ambient Glow follows the saved player preference.
                                    if (appColors.dynamicAlbumGlow) Box(
                                        modifier = Modifier
                                            .size(240.dp)
                                            .graphicsLayer()
                                            .clip(RoundedCornerShape(32.dp))
                                            .drawWithCache {
                                                val brush = Brush.radialGradient(
                                                    colors = listOf(
                                                        animatedAccentColor.value.copy(alpha = 0.45f),
                                                        animatedDominantColor.value.copy(alpha = 0.25f),
                                                        Color.Transparent
                                                    )
                                                )
                                                onDrawBehind { drawRect(brush) }
                                            }
                                    )

                                    // 3D Flippable Album Art Card - Strictly Guaranteed Square
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight(0.92f)
                                            .aspectRatio(1f)
                                            .sharedElement(rememberSharedContentState("lyrics_artwork_${track.id}"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) })
                                            .graphicsLayer {
                                                rotationY = flipRotation
                                                cameraDistance = 14f * density
                                            }
                                            .animatedArtworkShadow(animatedAccentColor, RoundedCornerShape(22.dp), appColors.dynamicAlbumGlow)
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                isFlipped = !isFlipped
                                            }
                                            .testTag("flip_album_art_card")
                                    ) {
                                        if (showArtworkFront) {
                                            // FRONT SIDE: Album Artwork with Motion Video Support
                                            MotionAlbumArtPlayer(
                                                videoUrl = track.motionArtworkUrl,
                                                staticCoverUrl = highResCoverUrl,
                                                staticImageRequest = resolvedArtworkRequest,
                                                contentDescription = "Cover Art - Tap to flip",
                                                shape = RoundedCornerShape(22.dp),
                                                modifier = Modifier.fillMaxSize(),
                                                placeholderMemoryCacheKey = cachedArtworkKey
                                            )
                                        } else {
                                            // BACK SIDE: Song credits
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer { rotationY = 180f }
                                                    .background(if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground)
                                                    .animatedArtworkBorder(animatedAccentColor, isDark,
                                                        appColors.cardBorder, RoundedCornerShape(22.dp))
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
                                                        fontSize = 16.sp,
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
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "🔄 Tap to flip",
                                                        fontSize = 11.sp,
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
                            .fillMaxHeight()
                            .graphicsLayer {
                                alpha = 1f - lyricsExpansion.value
                                translationY = size.height * lyricsExpansion.value
                            }
                            .then(if (lyricsExpanded) Modifier.clearAndSetSemantics { } else Modifier),
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
                                    .liquidGlassButton(appColors, shape = CircleShape, elevation = 4.dp)
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .bouncyClickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onOpenQueue()
                                    }
                                    .testTag("player_now_playing_title_header")
                            ) {
                                Text(
                                    text = "NOW PLAYING",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        letterSpacing = 2.0.sp,
                                        color = appColors.primaryAccent
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = track.cleanTitle,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
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
                                        .liquidGlassButton(appColors, shape = CircleShape, elevation = 4.dp)
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
                                    onOpenQueue = onOpenQueue,
                                    onAddToQueue = onAddToQueue,
                                    onAddToPlaylist = onAddToPlaylist,
                                    onOpenEqualizer = onOpenEqualizer,
                                    onOpenSleepTimer = { isSleepTimerOpen = true },
                                    onOpenLyricsOffset = { isLyricsOffsetOpen = true },
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
                                        fontSize = 22.sp,
                                        color = appColors.textPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = singerLabel,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = appColors.textSecondary,
                                        fontSize = 16.sp,
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

                        // Keep the normal timeline in place; the expanded footer takes over only in lyrics focus mode.
                        Column {
                            if (showLyrics) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    LyricsTextSizeButton(
                                        onClick = { showLyricsTextSizePopup = true }
                                    )
                                    LyricsTextTransformButton(
                                        romanize = true, active = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                                        loading = lyricsTextState.loading == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                                        enabled = !lyrics?.lines.isNullOrEmpty(),
                                        onClick = { lyricsTextController.toggle(com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE) }
                                    )
                                    LyricsProviderPill(
                                        providerName = displayedLyricsProvider,
                                        modifier = Modifier.weight(1f),
                                        onClick = { showLyricsProviderPopup = true },
                                        isEffectivelyDark = isEffectivelyDark
                                    )
                                    LyricsTextTransformButton(
                                        romanize = false, active = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                                        loading = lyricsTextState.loading == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                                        enabled = !lyrics?.lines.isNullOrEmpty(),
                                        onClick = { lyricsTextController.toggle(com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE) }
                                    )
                                    LyricsExpansionButton(
                                        expanded = lyricsExpanded,
                                        onClick = { maximizeLyrics = !maximizeLyrics }
                                    )
                                }
                            }
                            PlayerTimelineSection(
                                trackId = track.id,
                                currentPositionProvider = currentPositionProvider,
                                playbackPositionFlow = playbackPositionFlow,
                                trackDurationProvider = trackDurationProvider,
                                qualityChipText = displayedQuality,
                                onSeekTo = onSeekTo,
                                isDark = isDark,
                                isPlaying = uiState.isPlaying && !lyricsExpanded,
                                active = !lyricsExpanded,
                                dominantColor = animatedDominantColor
                            )

                        }

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
                            // 1. Synced Lyrics Toggle Button (Pill shape on bottom-left, Frosted Liquid Glass UI)
                            val landscapeLyricsContentColor = appColors.textPrimary

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!showLyrics) isFlipped = false
                                    showLyrics = !showLyrics
                                },
                                interactionSource = lyricsInteractionSource,
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .liquidGlassButton(appColors, shape = RoundedCornerShape(22.dp), elevation = 0.dp, isActive = showLyrics)
                                    .graphicsLayer {
                                        scaleX = lyricsButtonScale
                                        scaleY = lyricsButtonScale
                                    }
                                    .testTag("player_lyrics_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
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
                            val landscapeOutputContentColor = appColors.textPrimary

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showSoundOutputDialog = true
                                },
                                interactionSource = outputInteractionSource,
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                modifier = Modifier
                                    .height(44.dp)
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .liquidGlassButton(appColors, shape = RoundedCornerShape(22.dp), elevation = 0.dp, isActive = isLandscapeOutputActive)
                                    .graphicsLayer {
                                        scaleX = outputButtonScale
                                        scaleY = outputButtonScale
                                    }
                                    .testTag("sound_output_device_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
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
                            val landscapeQueueContentColor = if (appColors.isDark) Color.White else appColors.textPrimary

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onOpenQueue()
                                },
                                interactionSource = queueInteractionSource,
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .liquidGlassButton(appColors, shape = RoundedCornerShape(22.dp), elevation = 0.dp, isActive = false)
                                    .graphicsLayer {
                                        scaleX = queueButtonScale
                                        scaleY = queueButtonScale
                                    }
                                    .testTag("player_queue_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
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
                    if (expandedLyricsVisible) Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            .graphicsLayer { alpha = lyricsExpansion.value }
                            .then(if (!lyricsExpanded) Modifier.clearAndSetSemantics { } else Modifier)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LyricsTextSizeButton(
                                onClick = { showLyricsTextSizePopup = true }
                            )
                            LyricsTextTransformButton(
                                romanize = true, active = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                                loading = lyricsTextState.loading == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                                enabled = !lyrics?.lines.isNullOrEmpty(),
                                onClick = { lyricsTextController.toggle(com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE) }
                            )
                            LyricsProviderPill(
                                providerName = displayedLyricsProvider,
                                modifier = Modifier.weight(1f),
                                onClick = { showLyricsProviderPopup = true },
                                isEffectivelyDark = isEffectivelyDark
                            )
                            LyricsTextTransformButton(
                                romanize = false, active = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                                loading = lyricsTextState.loading == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                                enabled = !lyrics?.lines.isNullOrEmpty(),
                                onClick = { lyricsTextController.toggle(com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE) }
                            )
                            LyricsExpansionButton(
                                expanded = true,
                                onClick = { maximizeLyrics = false }
                            )
                        }
                        PlayerTimelineSection(
                            trackId = track.id,
                            currentPositionProvider = currentPositionProvider,
                            playbackPositionFlow = playbackPositionFlow,
                            trackDurationProvider = trackDurationProvider,
                            qualityChipText = "",
                            onSeekTo = onSeekTo,
                            isDark = isDark,
                            isPlaying = uiState.isPlaying && lyricsExpanded,
                            active = lyricsExpanded,
                            dominantColor = animatedDominantColor
                        )
                    } else Box(Modifier)
                }
            } else {
                PortraitPlayerExpansion(
                    expansion = lyricsExpansion,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 500.dp)
                ) {
            Column {
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
                        .clip(RoundedCornerShape(8.dp))
                        .bouncyClickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenQueue()
                        }
                        .testTag("player_landscape_now_playing_title_header")
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
                        onOpenQueue = onOpenQueue,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onOpenEqualizer = onOpenEqualizer,
                        onOpenSleepTimer = { isSleepTimerOpen = true },
                        onOpenLyricsOffset = { isLyricsOffsetOpen = true },
                        isDark = isDark,
                        appColors = appColors
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            }

            // Shared artwork and metadata travel between their original bounds and the lyrics header.
            SharedTransitionLayout(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = { lyricsArtworkTransition() },
                    modifier = Modifier.fillMaxSize(),
                    label = "lyrics_header_transition"
                ) { isLyricsActive ->
                    Column(Modifier.fillMaxSize()) {
                        if (isLyricsActive) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AsyncImage(
                                    model = highResImageRequest,
                                    contentDescription = "Cover Art",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(52.dp)
                                    .sharedElement(
                                        rememberSharedContentState("lyrics_artwork_${track.id}"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) }
                                    ).clip(RoundedCornerShape(12.dp))
                                )
                                Column(Modifier.weight(1f).sharedBounds(
                                        rememberSharedContentState("lyrics_metadata_${track.id}"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) }
                                )) {
                                    Text(track.cleanTitle, color = appColors.textPrimary,
                                        fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                                        modifier = Modifier.fillMaxWidth().basicMarquee(), softWrap = false)
                                    Spacer(Modifier.height(2.dp))
                                    Text(singerLabel, color = appColors.textSecondary,
                                        fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1,
                                        modifier = Modifier.fillMaxWidth().basicMarquee(), softWrap = false)
                                }
                                // Favorite Heart Toggle - Liquid Glass Button
                                Box(
                                    modifier = Modifier
                                    .size(44.dp)
                                    .sharedElement(
                                        rememberSharedContentState("lyrics_favorite_${track.id}"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) }
                                    )
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
                            Spacer(Modifier.height(8.dp))
                            PlayerSyncedLyrics(
                                lyrics = lyricsTextState.lyrics,
                                currentPositionMs = uiState.currentPositionMs,
                                playbackPositionFlow = playbackPositionFlow,
                                isPlaying = uiState.isPlaying,
                                currentPositionProvider = currentPositionProvider,
                                onSeekTo = onSeekTo,
                                dominantColor = animatedDominantColor,
                                accentColor = animatedAccentColor,
                                isDark = isDark,
                                onRetry = onRetryLyrics,
                                showRomanization = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                                showTranslation = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                                viewportAnimating = lyricsViewportAnimating,
                                lyricsOffsetMs = lyricsOffsetMs,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )

                        } else {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                // Adaptive Ambient Glow Layer matching album art with hardware acceleration
                                if (appColors.dynamicAlbumGlow) Box(
                                    modifier = Modifier
                                    .size(if (isLandscape) 190.dp else 280.dp)
                                    .graphicsLayer()
                                    .clip(RoundedCornerShape(36.dp))
                                    .drawWithCache {
                                        val brush = Brush.radialGradient(
                                            colors = listOf(
                                                animatedAccentColor.value.copy(alpha = 0.45f),
                                                animatedDominantColor.value.copy(alpha = 0.25f),
                                                Color.Transparent
                                            )
                                        )
                                        onDrawBehind { drawRect(brush) }
                                    }
                                )

                                // 3D Flippable Card
                                Box(
                                    modifier = Modifier
                                    .fillMaxHeight(if (isLandscape) 0.88f else 1f)
                                    .animatedFillMaxWidth(albumArtScale)
                                    .sizeIn(
                                        maxWidth = if (isLandscape) 210.dp else 330.dp,
                                        maxHeight = if (isLandscape) 210.dp else 330.dp
                                    )
                                    .aspectRatio(1f)
                                    .sharedElement(
                                        rememberSharedContentState("lyrics_artwork_${track.id}"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) }
                                    )
                                    .graphicsLayer {
                                        rotationY = flipRotation
                                        cameraDistance = 14f * density
                                    }
                                    .animatedArtworkShadow(animatedAccentColor, RoundedCornerShape(24.dp), appColors.dynamicAlbumGlow)
                                    .clip(RoundedCornerShape(24.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isFlipped = !isFlipped
                                    }
                                    .testTag("flip_album_art_card")
                                ) {
                                    if (showArtworkFront) {
                                        // FRONT SIDE: Album Artwork with Motion Video Support
                                        MotionAlbumArtPlayer(
                                            videoUrl = track.motionArtworkUrl,
                                            staticCoverUrl = highResCoverUrl,
                                            staticImageRequest = resolvedArtworkRequest,
                                            contentDescription = "Cover Art - Tap to flip",
                                            shape = RoundedCornerShape(24.dp),
                                            modifier = Modifier.fillMaxSize(),
                                            placeholderMemoryCacheKey = cachedArtworkKey
                                        )
                                    } else {
                                        // BACK SIDE: Song credits and information (Fully theme adaptive)
                                        Box(
                                            modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { rotationY = 180f }
                                            .background(if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground)
                                            .animatedArtworkBorder(animatedAccentColor, isDark,
                                                appColors.cardBorder, RoundedCornerShape(24.dp))
                                            .padding(20.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Surface(
                                                    color = animatedAccentColor.value.copy(alpha = 0.16f),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Info,
                                                            contentDescription = null,
                                                            tint = animatedAccentColor.value,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "TRACK CREDITS & DETAILS",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = animatedAccentColor.value,
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
                            Spacer(Modifier.height(16.dp))
                            // TRACK INFO & FAVORITE TOGGLE
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).sharedBounds(
                                        rememberSharedContentState("lyrics_metadata_${track.id}"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) }
                                )) {
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
                                        text = singerLabel,
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
                                    .sharedElement(
                                        rememberSharedContentState("lyrics_favorite_${track.id}"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                        boundsTransform = { _, _ -> tween(LYRICS_ARTWORK_TRANSITION_MS, easing = FastOutSlowInEasing) }
                                    )
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

                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 14.dp))

            AnimatedVisibility(
                visible = showLyrics,
                enter = fadeIn(tween(220)) + expandVertically(tween(LYRICS_EXPANSION_MS)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(LYRICS_EXPANSION_MS))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LyricsTextSizeButton(
                        onClick = { showLyricsTextSizePopup = true }
                    )
                    LyricsTextTransformButton(
                        romanize = true, active = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                        loading = lyricsTextState.loading == com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE,
                        enabled = !lyrics?.lines.isNullOrEmpty(),
                        onClick = { lyricsTextController.toggle(com.example.data.remote.LyricsTextTransformer.Mode.ROMANIZE) }
                    )
                    LyricsProviderPill(
                        providerName = displayedLyricsProvider,
                        modifier = Modifier.weight(1f),
                        onClick = { showLyricsProviderPopup = true },
                        isEffectivelyDark = isEffectivelyDark
                    )
                    LyricsTextTransformButton(
                        romanize = false, active = lyricsTextState.active == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                        loading = lyricsTextState.loading == com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE,
                        enabled = !lyrics?.lines.isNullOrEmpty(),
                        onClick = { lyricsTextController.toggle(com.example.data.remote.LyricsTextTransformer.Mode.TRANSLATE) }
                    )
                    LyricsExpansionButton(
                        expanded = lyricsExpanded,
                        onClick = { maximizeLyrics = !maximizeLyrics }
                    )
                }
            }

            // TIMELINE SCRUB BAR (Isolated recomposition)
            PlayerTimelineSection(
                trackId = track.id,
                currentPositionProvider = currentPositionProvider,
                playbackPositionFlow = playbackPositionFlow,
                trackDurationProvider = trackDurationProvider,
                qualityChipText = displayedQuality,
                showQuality = !lyricsExpanded,
                qualityFade = lyricsExpansion,
                onSeekTo = onSeekTo,
                isDark = isDark,
                isPlaying = uiState.isPlaying,
                dominantColor = animatedDominantColor
            )

            }
            Column(
                Modifier.fillMaxWidth().collapsingPlayerControls(lyricsExpansion)
                    .graphicsLayer { alpha = 1f - lyricsExpansion.value }
                    .then(if (lyricsExpanded) Modifier.clearAndSetSemantics { } else Modifier)
            ) {
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
                // 1. Synced Lyrics Toggle Button (Pill shape on bottom-left, Frosted Liquid Glass UI)
                val lyricsContentColor = appColors.textPrimary

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!showLyrics) isFlipped = false
                        showLyrics = !showLyrics
                    },
                    interactionSource = lyricsInteractionSource,
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .liquidGlassButton(appColors, shape = RoundedCornerShape(22.dp), elevation = 0.dp, isActive = showLyrics)
                        .graphicsLayer {
                            scaleX = lyricsButtonScale
                            scaleY = lyricsButtonScale
                        }
                        .testTag("player_lyrics_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {

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
                val outputContentColor = appColors.textPrimary

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showSoundOutputDialog = true
                    },
                    interactionSource = outputInteractionSource,
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .height(44.dp)
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .liquidGlassButton(appColors, shape = RoundedCornerShape(22.dp), elevation = 0.dp, isActive = isOutputActive)
                        .graphicsLayer {
                            scaleX = outputButtonScale
                            scaleY = outputButtonScale
                        }
                        .testTag("sound_output_device_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {

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
                val queueContentColor = if (appColors.isDark) Color.White else appColors.textPrimary

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenQueue()
                    },
                    interactionSource = queueInteractionSource,
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .liquidGlassButton(appColors, shape = RoundedCornerShape(22.dp), elevation = 0.dp, isActive = false)
                        .graphicsLayer {
                            scaleX = queueButtonScale
                            scaleY = queueButtonScale
                        }
                        .testTag("player_queue_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {

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
    }

    // Sound Output Devices Popup Dialog
    if (isSleepTimerOpen) {
        SleepTimerDialog(onDismiss = { isSleepTimerOpen = false })
    }
    if (isLyricsOffsetOpen) {
        LyricsOffsetBottomSheet(
            track = track,
            onDismiss = { isLyricsOffsetOpen = false }
        )
    }
    if (showSoundOutputDialog) {
        SoundOutputDeviceDialog(
            devices = effectiveDevices,
            isDark = isDark,
            onSelectDevice = { deviceId ->
                onSelectAudioDevice(deviceId)
            },
            onDismiss = { showSoundOutputDialog = false }
        )
    }
    if (showLyricsTextSizePopup) {
        LyricsTextSizePopup(
            currentSizeSp = lyricsSettings.textSizeSp,
            onSizeChange = { newSize ->
                com.example.data.local.OtherSettingsPreferences.setLyricsTextSize(context, newSize)
            },
            onDismissRequest = { showLyricsTextSizePopup = false },
            appColors = appColors
        )
    }
    if (showLyricsProviderPopup) {
        val customAvailability by produceState(false to false, track.id, lyrics?.provider) {
            value = withContext(AppDispatchers.Io) {
                com.example.data.local.CustomLyricsManager.hasCustomLyrics(context, track) to
                    com.example.data.local.CustomLyricsManager.isCustomLyricsActive(context, track)
            }
        }
        val hasCustomLyrics = customAvailability.first
        val isCustomSelected = hasCustomLyrics && (customAvailability.second ||
            lyrics?.provider == com.example.data.local.CustomLyricsManager.PROVIDER_NAME)

        LyricsProviderSelectionPopup(
            currentProvider = lyricsSettings.provider,
            onSelectProvider = { provider ->
                com.example.data.local.CustomLyricsManager.setCustomLyricsActive(context, track, false)
                com.example.data.local.OtherSettingsPreferences.setLyricsProvider(context, provider)
                onRetryLyrics()
            },
            onDismissRequest = { showLyricsProviderPopup = false },
            appColors = appColors,
            allowImport = true,
            isCustomSelected = isCustomSelected,
            hasCustomLyrics = hasCustomLyrics,
            onImportLyrics = {
                launchImportFlow()
            },
            onSelectCustomLyrics = {
                com.example.data.local.CustomLyricsManager.setCustomLyricsActive(context, track, true)
                onRetryLyrics()
            }
        )
    }
        }
    }
}

}

/** One clock and stable control measurements; only the body bounds/placements animate. */
@Composable
private fun PortraitPlayerExpansion(
    expansion: State<Float>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val fraction = expansion.value.coerceIn(0f, 1f)
        val childConstraints = Constraints(minWidth = width, maxWidth = width, maxHeight = height)
        val header = measurables[0].measure(childConstraints)
        val footer = measurables[2].measure(childConstraints.copy(maxHeight = (height - header.height).coerceAtLeast(0)))
        val controls = measurables[3].measure(childConstraints.copy(
            maxHeight = (height - header.height - footer.height).coerceAtLeast(0)))
        // Insets are applied outside this host. Keep an additional breathing room
        // for time labels when the usual bottom action row is no longer visible.
        val bottomGap = (12.dp.toPx() * fraction).roundToInt()
        val visibleControls = controls.height
        val footerY = (height - footer.height - visibleControls - bottomGap).coerceAtLeast(header.height)
        val body = measurables[1].measure(Constraints.fixed(width, (footerY - header.height).coerceAtLeast(0)))
        layout(width, height) {
            header.placeRelative(0, 0)
            body.placeRelative(0, header.height)
            footer.placeRelative(0, footerY)
            if (fraction < 1f) controls.placeRelative(0, footerY + footer.height + bottomGap)
        }
    }
}

/** Clip only the moving controls, leaving the existing artwork glow unclipped. */
private fun Modifier.collapsingPlayerControls(expansion: State<Float>): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val controls = measurable.measure(constraints)
        val visibleHeight = (controls.height * (1f - expansion.value.coerceIn(0f, 1f))).roundToInt()
        layout(controls.width, visibleHeight) {
            if (visibleHeight > 0) controls.placeRelative(0, 0)
        }
    }

/** Keep the original two-pane bounds at rest; read expansion only during measurement. */
@Composable
private fun LandscapeLyricsExpansion(
    expansion: State<Float>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val fraction = expansion.value.coerceIn(0f, 1f)
        val gap = 24.dp.roundToPx()
        val normalLeft = ((width - gap).coerceAtLeast(0) / 2.15f).roundToInt()
        val normalRight = (width - gap - normalLeft).coerceAtLeast(0)
        val footer = measurables[2].measure(Constraints(maxWidth = width, maxHeight = height))
        val footerHeight = (footer.height * fraction).roundToInt()
        val leftWidth = (width - (normalRight + gap) * (1f - fraction)).roundToInt().coerceAtLeast(0)
        val left = measurables[0].measure(Constraints.fixed(leftWidth, (height - footerHeight).coerceAtLeast(0)))
        val right = measurables[1].measure(Constraints.fixed(normalRight, height))
        layout(width, height) {
            left.placeRelative(0, 0)
            if (fraction < 1f) right.placeRelative(leftWidth + (gap * (1f - fraction)).roundToInt(), 0)
            // The focus footer enters from below as the ordinary control pane leaves.
            if (fraction > 0f) footer.placeRelative(0, height - footerHeight)
        }
    }
}

@Composable
private fun LyricsExpansionButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    // A compact 36dp glass disk inside an accessible 48dp touch target.
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).offset(x = 4.dp).liquidGlassButton(colors, elevation = 3.dp))
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().testTag("player_lyrics_expand_button")
        ) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "lyrics_expand_icon"
            ) { isExpanded ->
                Icon(
                    if (isExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (isExpanded) "Minimize lyrics" else "Maximize lyrics",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp).offset(x = 4.dp)
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
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = if (landscape) 13.sp else 11.sp,
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
                fontSize = if (landscape) 14.sp else 12.sp,
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
private fun PlayerSyncedLyrics(
    lyrics: com.example.data.model.TrackLyrics?,
    currentPositionMs: Long,
    playbackPositionFlow: StateFlow<Long>?,
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,
    onSeekTo: (Long) -> Unit,
    dominantColor: State<Color>,
    accentColor: State<Color>,
    isDark: Boolean,
    onRetry: () -> Unit,
    showRomanization: Boolean,
    showTranslation: Boolean,
    viewportAnimating: Boolean,
    lyricsOffsetMs: Int = 0,
    modifier: Modifier
) {
    // Palette fades only invalidate the lyrics subtree, never the complete player.
    SyncedLyricsView(
        lyrics = lyrics,
        currentPositionMs = currentPositionMs,
        playbackPositionFlow = playbackPositionFlow,
        isPlaying = isPlaying,
        currentPositionProvider = currentPositionProvider,
        onSeekTo = onSeekTo,
        dominantColor = dominantColor.value,
        accentColor = accentColor.value,
        isDark = isDark,
        onRetry = onRetry,
        showRomanization = showRomanization,
        showTranslation = showTranslation,
        modifier = modifier,
        viewportAnimating = viewportAnimating,
        lyricsOffsetMs = lyricsOffsetMs
    )
}

/** Keep Foundation's exact border rendering while isolating palette reads to its modifier scope. */
private fun Modifier.animatedArtworkBorder(accent: State<Color>, dark: Boolean, fallback: Color,
    shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    border(BorderStroke(1.5.dp, if (dark) accent.value.copy(alpha = 0.65f) else fallback), shape)
}

private fun Modifier.animatedArtworkShadow(accent: State<Color>, shape: androidx.compose.ui.graphics.Shape,
    albumGlow: Boolean): Modifier =
    graphicsLayer {
        shadowElevation = 28.dp.toPx()
        this.shape = shape
        clip = true
        spotShadowColor = if (albumGlow) accent.value.copy(alpha = 0.5f) else Color.Black
        ambientShadowColor = Color.Black
    }

private fun Modifier.animatedFillMaxWidth(fraction: State<Float>): Modifier = layout { measurable, constraints ->
    // Match fillMaxWidth's constraints exactly, reading the spring during measurement
    // instead of recomposing every control and shared-element target on each frame.
    val widthConstraints = if (constraints.hasBoundedWidth) {
        val width = (constraints.maxWidth * fraction.value).roundToInt()
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        constraints.copy(minWidth = width, maxWidth = width)
    } else constraints
    val placeable = measurable.measure(widthConstraints)
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** Diffuse only the artwork backdrop at 1/4 resolution, retaining the same on-screen blur. */
@Composable
private fun Modifier.playerBackdropBlur(radius: Dp): Modifier {
    val layer = rememberGraphicsLayer()
    return drawWithCache {
        val processingScale = 0.25f
        val bufferSize = IntSize(
            ceil(size.width * processingScale).toInt().coerceAtLeast(1),
            ceil(size.height * processingScale).toInt().coerceAtLeast(1)
        )
        val blurPx = radius.toPx() * processingScale
        layer.renderEffect = BlurEffect(radiusX = blurPx, radiusY = blurPx, edgeTreatment = TileMode.Decal)
        layer.clip = true
        onDrawWithContent {
            // One small image node is recorded here, never the foreground player.
            // Re-recording follows Coil's real image/crossfade updates without a ticker.
            layer.record(bufferSize) {
                scale(processingScale, pivot = Offset.Zero) {
                    this@onDrawWithContent.drawContent()
                }
            }
            scale(1f / processingScale, pivot = Offset.Zero) { drawLayer(layer) }
        }
    }
}

@Composable
internal fun PlayerGlassBackground(
    artworkUrl: String,
    artworkRequest: ImageRequest? = null,
    dominantColor: State<Color>,
    accentColor: State<Color>,
    vibrantColor: State<Color> = accentColor,
    isDark: Boolean = true,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val dynamicGlass = LocalDynamicGlassEnabled.current
    if (!dynamicGlass || !appColors.dynamicAlbumGlow) {
        Box(modifier.fillMaxSize().testTag("player_neutral_backdrop").xtremeSharedBackdropSource(enabled = dynamicGlass)
            .background(appColors.scaffoldBackground))
        return
    }
    val context = LocalContext.current
    // This small derivative is only for diffusion. The main album artwork stays full resolution.
    val backdropRequest = remember(artworkUrl, context, artworkRequest) {
        (artworkRequest?.newBuilder() ?: ImageRequest.Builder(context).data(artworkUrl))
            .size(256).crossfade(650).build()
    }
    val base = appColors.scaffoldBackground
    Box(modifier.fillMaxSize().testTag("player_album_glow_backdrop").xtremeSharedBackdropSource(enabled = dynamicGlass).background(base)) {
        // Broad color fields provide the same soft finish on devices without RenderEffect.
        Box(Modifier.fillMaxSize().drawWithCache {
            val field = Brush.linearGradient(listOf(dominantColor.value, base, vibrantColor.value.copy(alpha = 0.55f)),
                Offset.Zero, Offset(size.width, size.height))
            val vibrantField = Brush.radialGradient(listOf(vibrantColor.value.copy(alpha = if (isDark) 0.50f else 0.25f), Color.Transparent),
                center = Offset(size.width * 0.82f, size.height * 0.48f), radius = size.maxDimension * 0.7f)
            val accentField = Brush.radialGradient(listOf(accentColor.value.copy(alpha = if (isDark) 0.22f else 0.16f), Color.Transparent),
                center = Offset(0f, size.height * 0.85f), radius = size.maxDimension * 0.6f)
            onDrawBehind {
                drawRect(field)
                drawRect(vibrantField)
                drawRect(accentField)
            }
        })
        if (dynamicGlass && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            AsyncImage(
                model = backdropRequest, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 1.18f; scaleY = 1.18f; alpha = if (isDark) 0.58f else 0.32f }
                    .playerBackdropBlur(56.dp)
            )
        }
        // High contrast strengthens only the neutral text backdrop, never text size/layout.
        Box(Modifier.fillMaxSize().drawWithCache {
            val veil = if (isDark) Color.Black else base
            val veilBrush = Brush.verticalGradient(
                0f to veil.copy(alpha = if (appColors.highContrastTypography) 0.80f else if (isDark) 0.42f else 0.40f),
                0.35f to veil.copy(alpha = if (appColors.highContrastTypography) 0.72f else if (isDark) 0.16f else 0.28f),
                0.65f to veil.copy(alpha = if (appColors.highContrastTypography) 0.80f else if (isDark) 0.40f else 0.58f),
                1f to veil.copy(alpha = if (appColors.highContrastTypography) 0.88f else if (isDark) 0.66f else 0.76f)
            )
            val reflection = Brush.linearGradient(listOf(Color.White.copy(alpha = if (isDark) 0.08f else 0.25f),
                Color.Transparent, Color.Transparent), Offset.Zero, Offset(size.width, size.height * 0.7f))
            onDrawBehind {
                drawRect(veilBrush)
                drawRect(reflection)
            }
        })
    }
}

@Composable
private fun PlayerOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    track: MusicTrack,
    onOpenQueue: () -> Unit = {},
    onAddToQueue: (MusicTrack) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenLyricsOffset: () -> Unit = {},
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
                    if (!appColors.isAmoled && appColors.dynamicGlassEnabled) {
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

            listOf(
                Triple("Sleep Mode", Icons.Default.Timer, onOpenSleepTimer),
                Triple("Equalizer", Icons.Default.Equalizer, onOpenEqualizer),
                Triple("Lyrics Offset", Icons.Default.Tune, onOpenLyricsOffset)
            ).forEach { (title, icon, action) ->
                DropdownMenuItem(
                    text = { Text(title, style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold, color = appColors.textPrimary)) },
                    leadingIcon = {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(itemIconBg), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = appColors.primaryAccent, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = { AppHaptics.performTap(context); onDismissRequest(); action() },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            if (expanded) SourceUpgradeMenuItem(track.id, onDismissRequest, itemIconBg, appColors)

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
                        putExtra(android.content.Intent.EXTRA_TEXT,
                            com.example.ui.util.buildTrackShareText(track))
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
    trackId: String,
    currentPositionProvider: () -> Long,
    playbackPositionFlow: StateFlow<Long>?,
    trackDurationProvider: () -> Long,
    qualityChipText: String = "",
    onSeekTo: (Long) -> Unit,
    isDark: Boolean,
    isPlaying: Boolean = false,
    dominantColor: State<Color>,
    modifier: Modifier = Modifier,
    showQuality: Boolean = true,
    active: Boolean = true,
    qualityFade: State<Float>? = null
) {
    val appColors = LocalAppColors.current
    var isUserScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    // Dynamic real-time playback position state
    var livePosition by remember(trackId) { mutableLongStateOf(currentPositionProvider()) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestPositionProvider by rememberUpdatedState(currentPositionProvider)
    // Same visible cadence, suspended while the activity is not resumed.
    LaunchedEffect(trackId, isPlaying, playbackPositionFlow, lifecycle, active) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!isUserScrubbing) livePosition = latestPositionProvider()
            if (isPlaying) while (isActive) {
                if (!isUserScrubbing) livePosition = latestPositionProvider()
                delay(150)
            } else {
                // Paused seeks still update immediately without a polling or composition loop.
                playbackPositionFlow?.collect {
                    if (!isUserScrubbing) livePosition = latestPositionProvider()
                }
            }
        }
    }

    val trackDuration = trackDurationProvider().coerceAtLeast(1L)
    val currentPosition = if (isUserScrubbing) {
        (scrubPosition * trackDuration).toLong()
    } else {
        livePosition
    }
    val sliderValue = if (isUserScrubbing) scrubPosition else (currentPosition.toFloat() / trackDuration.toFloat()).coerceIn(0f, 1f)

    // Contrast calculation according to theme and effective background
    val isEffectivelyDark by remember(isDark, appColors.isDark, appColors.isAmoled, appColors.dynamicAlbumGlow, dominantColor, appColors.scaffoldBackground) { derivedStateOf {
        val color = if (appColors.dynamicAlbumGlow) dominantColor.value else appColors.scaffoldBackground
        if (isDark || appColors.isDark || appColors.isAmoled) {
            true
        } else if (color != Color.Unspecified) {
            val base = appColors.scaffoldBackground
            val blendedRed = color.red * 0.25f + base.red * 0.75f
            val blendedGreen = color.green * 0.25f + base.green * 0.75f
            val blendedBlue = color.blue * 0.25f + base.blue * 0.75f
            val lum = 0.299f * blendedRed + 0.587f * blendedGreen + 0.114f * blendedBlue
            lum < 0.50f
        } else {
            false
        }
    } }

    val liveProgressColor = if (isEffectivelyDark) Color.White else Color.Black
    val inactiveProgressColor = if (isEffectivelyDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.16f)

    val chipContentColor = if (isEffectivelyDark) Color.White else Color(0xFF0F172A)
    val chipBorderColor = if (isEffectivelyDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.20f)
    val chipBgColor = if (isEffectivelyDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val timeLabelColor = if (appColors.highContrastTypography) {
        if (isEffectivelyDark) Color.White else Color.Black
    } else if (isEffectivelyDark) Color.White.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.65f)

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
            isDark = isEffectivelyDark,
            barColor = liveProgressColor,
            inactiveBarColor = inactiveProgressColor,
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
                    color = timeLabelColor,
                    fontSize = 12.sp
                )
            )

            // Dynamic Quality Chip adapting white or dark color with proper background contrast
            if (qualityChipText.isNotBlank()) Box(
                Modifier.graphicsLayer {
                    alpha = qualityFade?.let { 1f - it.value } ?: if (showQuality) 1f else 0f
                }.then(if (!showQuality) Modifier.clearAndSetSemantics { } else Modifier)
            ) {
                AudioQualityBadge(trackId = trackId, fallbackText = qualityChipText,
                    background = chipBgColor, border = chipBorderColor, content = chipContentColor, enabled = showQuality && active)
            }

            Text(
                text = formatTime(trackDuration),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = timeLabelColor,
                    fontSize = 12.sp
                )
            )
        }
    }
}

private const val LYRICS_ARTWORK_TRANSITION_MS = 420
private const val LYRICS_EXPANSION_MS = 420

/** Keep the content fade alive for the entire shared artwork/header journey. */
private fun AnimatedContentTransitionScope<Boolean>.lyricsArtworkTransition(): ContentTransform =
    (fadeIn(tween(
        durationMillis = if (targetState) 260 else LYRICS_ARTWORK_TRANSITION_MS,
        delayMillis = if (targetState) 160 else 0,
        easing = FastOutSlowInEasing
    )) togetherWith fadeOut(tween(220, easing = FastOutSlowInEasing))).using(null)

/** Kept next to the player menu so source selection never becomes a DSP setting. */
@Composable
internal fun SourceUpgradeMenuItem(trackId: String, onDismiss: () -> Unit,
    iconBackground: Color = Color.Transparent,
    colors: com.example.ui.theme.AppThemeColors = LocalAppColors.current) {
    val context = LocalContext.current.applicationContext
    val monitor = remember(context) { com.example.playback.NetworkQualityMonitor.getInstance(context) }
    val policy by monitor.sourcePolicy.collectAsStateWithLifecycle()
    val path by com.example.playback.AudioSignalPath.state.collectAsStateWithLifecycle()
    val current = path.trackId == trackId && path.quality.verified && path.sourceId.isNotBlank()
    val upgraded = current && policy.hasVerifiedUpgrade(path)
    DropdownMenuItem(
        text = { Text(if (upgraded) "Revert to Original" else "Upgrade Audio",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary) },
        leadingIcon = {
            Box(Modifier.size(34.dp).clip(CircleShape).background(iconBackground), contentAlignment = Alignment.Center) {
                Icon(if (upgraded) Icons.AutoMirrored.Filled.Undo else Icons.Default.AutoAwesome, contentDescription = null,
                    tint = colors.primaryAccent, modifier = Modifier.size(18.dp))
            }
        },
        enabled = current && !path.sourceTransitionInProgress,
        onClick = {
            AppHaptics.performTap(context)
            // Revert explicitly selects the original YouTube recording, including
            // upgrades whose first remembered transport was an AAC fallback.
            monitor.setAudioEnhancer(!upgraded, returnToYouTube = upgraded, trackId = trackId)
            onDismiss()
        }, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).testTag("player_audio_upgrade")
    )
}
