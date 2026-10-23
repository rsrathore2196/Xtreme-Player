package com.example.ui.components

import com.example.performance.AppDispatchers

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LyricLine
import com.example.data.model.LyricWord
import com.example.data.model.TrackLyrics
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Word metadata pre-mapped to character index ranges inside line.text.
 */
private data class MeasuredWord(
    val word: LyricWord,
    val charStart: Int,
    val charEnd: Int
)

/**
 * Continuous Karaoke-Style Time-Synced Lyrics View for Xtreme Player.
 *
 * 1. Fluid Karaoke Highlighting & Line Transitions:
 *    - Continuous overlay reveal across the stable existing text.
 *    - Identical glyph positions, font sizing, and line wraps with zero layout recalculation.
 *    - Lifts timed shaped runs in the draw phase without changing wrapping or measurement.
 *
 * 2. Provider-Timed Animation:
 *    - Preserves genuine syllable progression in every language; otherwise uses reliable word timing.
 *    - Preserves script shaping, combining marks, matras, and conjuncts intact.
 *
 * 3. Vocal Timings & Silent Gaps:
 *    - Progress = clamp((position - startTime) / (endTime - startTime), 0, 1).
 *    - Begins when unit begins, finishes at vocal end, and holds completed through silent pauses.
 *
 * 4. High-Fidelity Scrolling & Seeking:
 *    - Driven by currentPositionProvider() on 60/120Hz display refresh frames.
 *    - Smooth centered scrolling decoupled from vocal timing.
 */
@Composable
fun SyncedLyricsView(
    lyrics: TrackLyrics?,
    currentPositionMs: Long = 0L,
    playbackPositionFlow: StateFlow<Long>? = null,
    isPlaying: Boolean = true,
    onSeekTo: (Long) -> Unit,
    dominantColor: Color = Color.Transparent,
    accentColor: Color = Color.White,
    isDark: Boolean = LocalAppColors.current.isDark,
    onRetry: () -> Unit = {},
    currentPositionProvider: () -> Long = { playbackPositionFlow?.value ?: currentPositionMs },
    modifier: Modifier = Modifier,
    viewportAnimating: Boolean = false,
    showRomanization: Boolean = true,
    showTranslation: Boolean = true
) {
    val appColors = LocalAppColors.current
    val context = LocalContext.current
    val lyricsPreferences = remember(context) { com.example.data.local.OtherSettingsPreferences.observeLyricsSettings(context) }
    val lyricsSettings by lyricsPreferences.collectAsStateWithLifecycle()
    val lyricsTextSize = lyricsSettings.textSizeSp
    val lyricsTextScale = lyricsTextSize / 28f
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    val lines = lyrics?.lines ?: emptyList()
    val isSynced = lyrics?.isSynced == true

    // Always read the latest callback: the default callback captures currentPositionMs.
    // Do not take max(flow, player): a delayed flow emission must not undo a backward seek.
    val latestPositionProvider by rememberUpdatedState(currentPositionProvider)
    var currentPlayheadMs by remember(lyrics?.trackId) { mutableLongStateOf(latestPositionProvider()) }
    // One display-frame clock feeds both centering and the draw phase. Never tween the playhead:
    // easing a timestamp introduces vocal lag and can keep highlighting after a pause or seek.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(isSynced, isPlaying, lyrics?.trackId, lifecycle) {
        if (!isSynced) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            currentPlayheadMs = latestPositionProvider()
            if (isPlaying) while (isActive) {
                withFrameMillis { currentPlayheadMs = latestPositionProvider() }
            }
        }
    }
    LaunchedEffect(currentPositionMs, isPlaying, lyrics?.trackId) {
        currentPlayheadMs = latestPositionProvider()
    }
    LaunchedEffect(playbackPositionFlow, isPlaying, lyrics?.trackId, lifecycle) {
        if (!isPlaying) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            playbackPositionFlow?.collect { currentPlayheadMs = latestPositionProvider() }
        }
    }

    val activeLineIndex by remember(lyrics?.trackId, lines, isSynced) {
        derivedStateOf {
            if (!isSynced) -1
            else lines.indexOfLast { currentPlayheadMs >= it.timestampMs }
        }
    }

    // A gesture temporarily suspends following; the fling can finish before we resume.
    // Programmatic scrolling does not count as a user gesture and never drops a line change.
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var followPlayback by remember(lyrics?.trackId) { mutableStateOf(true) }
    LaunchedEffect(isDragged, lyrics?.trackId) {
        if (isDragged) {
            followPlayback = false
        } else if (!followPlayback) {
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            delay(1_200L)
            followPlayback = true
        }
    }

    val viewportSize by remember { derivedStateOf { listState.layoutInfo.viewportSize } }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val lyricStyle = LocalTextStyle.current.copy(fontSize = lyricsTextSize.sp, lineHeight = (40 * lyricsTextScale).sp,
        fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Start)
    val romanizationStyle = LocalTextStyle.current.copy(fontSize = (14 * lyricsTextScale).sp, lineHeight = (18 * lyricsTextScale).sp,
        fontWeight = FontWeight.Medium, textAlign = TextAlign.Start)
    val rowHeights = remember(lyrics?.trackId, lines, viewportSize.width, density, lyricStyle, romanizationStyle,
        showRomanization, showTranslation) { mutableMapOf<Int, Int>() }
    var hasInitialScrolled by remember(lyrics?.trackId) { mutableStateOf(false) }
    LaunchedEffect(lyrics?.trackId, lines, activeLineIndex, isSynced, followPlayback, isDragged, viewportSize, rowHeights, viewportAnimating) {
        // Expansion changes viewport bounds every frame. Follow once after it
        // settles instead of canceling/restarting a 350ms scroll on every resize.
        if (viewportAnimating) return@LaunchedEffect
        if (!isSynced || lines.isEmpty() || !followPlayback || isDragged) return@LaunchedEffect
        // Before the first vocal, show the first line without marking it active.
        val targetIndex = activeLineIndex.coerceAtLeast(0)
        snapshotFlow { listState.layoutInfo }.first {
            it.viewportSize.height > 0 && it.totalItemsCount == lines.size
        }

        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        // Item offsets include the negative viewport start caused by content padding.
        // Using viewportSize / 2 alone shifts the vocal line down by the top padding.
        val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
        if (item == null) {
            // Measure only an unseen target using its unchanged text styles, including wrapped romanization.
            // Animate directly to its final center rather than chaining two different scroll animations.
            val targetHeight = rowHeights[targetIndex] ?: run {
                val line = lines[targetIndex]
                val constraints = Constraints(maxWidth = layout.viewportSize.width.coerceAtLeast(1))
                val mainHeight = textMeasurer.measure(line.text, style = lyricStyle, constraints = constraints).size.height
                val romanizationHeight = line.romanization?.takeIf { showRomanization && it.isNotBlank() }?.let {
                    textMeasurer.measure(it, style = romanizationStyle, constraints = constraints).size.height + with(density) { 4.dp.roundToPx() }
                } ?: 0
                val translationHeight = line.translation?.takeIf { showTranslation && it.isNotBlank() }?.let {
                    textMeasurer.measure(it, style = romanizationStyle, constraints = constraints).size.height + with(density) { 4.dp.roundToPx() }
                } ?: 0
                mainHeight + romanizationHeight + translationHeight + with(density) { 6.dp.roundToPx() * 2 }
            }
            val scrollOffset = (targetHeight / 2f - viewportCenter).roundToInt()
            if (hasInitialScrolled) listState.animateScrollToItem(targetIndex, scrollOffset)
            else listState.scrollToItem(targetIndex, scrollOffset)
        } else {
            val distanceToCenter = item.offset + item.size / 2f - viewportCenter
            if (kotlin.math.abs(distanceToCenter) > 1f) {
                if (hasInitialScrolled) {
                    listState.animateScrollBy(
                        distanceToCenter,
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                    )
                } else {
                    listState.scrollBy(distanceToCenter)
                }
            }
        }
        hasInitialScrolled = true
    }

    val activeTextColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val inactiveTextColor = if (appColors.highContrastTypography) {
        if (isDark) Color.White.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.90f)
    } else if (isDark) Color(0xFFFFFFFF).copy(alpha = 0.62f) else Color(0xFF000000).copy(alpha = 0.60f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .testTag("synced_lyrics_view")
    ) {
        val boxHeight = maxHeight
        // Half a viewport permits exact centering even for the first and last wrapped rows.
        val verticalCenterPadding = boxHeight / 2
        if (lyrics == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = activeTextColor,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Searching live lyrics...",
                        color = inactiveTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = inactiveTextColor,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Lyrics not available",
                        color = activeTextColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No synchronized lyrics found for this song",
                        color = inactiveTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = activeTextColor
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.25f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry Search",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Retry Search",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp),
                contentPadding = if (isSynced) PaddingValues(top = verticalCenterPadding, bottom = verticalCenterPadding) else PaddingValues(top = 36.dp, bottom = 48.dp),
                verticalArrangement = if (isSynced) Arrangement.spacedBy(24.dp) else Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(lines, key = { index, item -> "${item.timestampMs}_$index" }) { index, item ->
                    val distance = if (activeLineIndex >= 0 && isSynced) kotlin.math.abs(index - activeLineIndex) else 0
                    val isActive = isSynced && index == activeLineIndex
                    val isPast = isSynced && (index < activeLineIndex)

                    val targetAlpha = if (!isSynced) {
                        1.0f
                    } else when {
                        // Upcoming text must remain legible through the intro and silent lead-in.
                        activeLineIndex < 0 -> when (index) { 0 -> 1f; 1 -> 0.82f; else -> 0.65f }
                        isActive -> 1.0f
                        isPast -> 0.44f
                        distance == 1 -> 0.82f
                        distance == 2 -> 0.65f
                        else -> 0.50f
                    }

                    val animatedAlpha by animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                        label = "lyric_alpha_$index"
                    )

                    val animatedScale by animateFloatAsState(
                        targetValue = if (isActive) 1.03f else 0.98f,
                        animationSpec = spring(dampingRatio = 0.95f, stiffness = 360f),
                        label = "lyric_scale_$index"
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { rowHeights[index] = it.height }
                            .graphicsLayer {
                                alpha = animatedAlpha
                                scaleX = animatedScale
                                scaleY = animatedScale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .clickable(
                                enabled = isSynced,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    followPlayback = true
                                    onSeekTo(item.timestampMs)
                                }
                            )
                            .padding(vertical = if (isSynced) 6.dp else 3.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 1. Romanization subtitle (if available)
                        if (showRomanization && !item.romanization.isNullOrBlank()) {
                            Text(
                                text = item.romanization,
                                fontSize = romanizationStyle.fontSize,
                                fontWeight = FontWeight.Medium,
                                lineHeight = romanizationStyle.lineHeight,
                                color = if (isActive) activeTextColor.copy(alpha = 0.85f) else inactiveTextColor.copy(alpha = 0.55f),
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 2. Karaoke Line Text Composable
                        KaraokeLineItem(
                            textSizeSp = lyricsTextSize,
                            line = item,
                            nextLine = lines.getOrNull(index + 1),
                            isActive = isActive,
                            isPast = isPast,
                            isSynced = isSynced,
                            isPlaying = isPlaying,
                            viewportAnimating = viewportAnimating,
                            trackLanguage = lyrics.language,
                            trackArtist = lyrics.artist,
                            activeTextColor = activeTextColor,
                            inactiveTextColor = if (isActive) inactiveTextColor else
                                if (isDark) Color.White.copy(alpha = 0.40f) else Color.Black.copy(alpha = 0.38f),
                            glowColor = lerp(activeTextColor, accentColor.takeIf { it.alpha > 0f } ?: activeTextColor, 0.25f),
                            currentPositionProvider = {
                                currentPlayheadMs
                            }
                        )
                        if (showTranslation && !item.translation.isNullOrBlank()) {
                            Text(text = item.translation, style = romanizationStyle,
                                color = if (isActive) activeTextColor.copy(alpha = 0.85f) else inactiveTextColor.copy(alpha = 0.55f),
                                modifier = Modifier.fillMaxWidth().testTag("lyrics_translation_$index"))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a single lyric line with continuous karaoke reveal:
 * - Base layer: Unaltered full text rendered in inactive color. Stable layout & glyph positions.
 * - Overlay layer: Exact same text in active color, clipped in Draw phase according to vocal intervals.
 * - Supports wrapped multi-line text cleanly without horizontal/vertical bleeding.
 */
@Composable
private fun KaraokeLineItem(
    textSizeSp: Int,
    line: LyricLine,
    nextLine: LyricLine?,
    isActive: Boolean,
    isPast: Boolean,
    isSynced: Boolean,
    isPlaying: Boolean,
    viewportAnimating: Boolean,
    trackLanguage: String?,
    trackArtist: String?,
    activeTextColor: Color,
    inactiveTextColor: Color,
    glowColor: Color,
    currentPositionProvider: () -> Long
) {
    // Keep line wrapping and row height fixed as activity changes. Emphasis is a
    // top-anchored graphics transform, so it cannot invalidate the centering target.
    val fontSize = textSizeSp.sp
    val fontWeight = FontWeight.ExtraBold
    val lineHeight = (textSizeSp * 40f / 28f).sp
    val glowFocus = animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "lyric_glow_focus"
    )

    if (!isSynced) {
        Text(
            text = line.text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = FontFamily.SansSerif,
            lineHeight = lineHeight,
            color = activeTextColor,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }

    val measuredWords = remember(line.text, line.words) {
        val result = mutableListOf<MeasuredWord>()
        var cursor = 0
        for (word in line.words) {
            val match = line.text.indexOf(word.text, startIndex = cursor)
            if (word.text.isBlank() || match < 0) continue
            cursor = match + word.text.length
            if (word.isReliableTiming && word.durationMs > 0) {
                result.add(MeasuredWord(word, match, cursor))
            }
        }
        result
    }
    var textLayoutResult by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    val latestPosition by rememberUpdatedState(currentPositionProvider)
    val density = LocalDensity.current
    val broadRadius = with(density) { 3.dp.toPx() }
    val coreRadius = with(density) { 1.dp.toPx() }
    val wordRuns = remember(textLayoutResult, measuredWords) {
        textLayoutResult?.let { buildWordRuns(it, measuredWords) } ?: emptyList()
    }
    val revealUnits = remember(wordRuns) { wordRuns.flatMap { it.reveals } }
    val fallbackRegions = remember(textLayoutResult, revealUnits) {
        val layout = textLayoutResult
        if (layout == null || revealUnits.isNotEmpty()) emptyList() else
            (0 until layout.lineCount).map { index ->
                RevealRegion(Rect(layout.getLineLeft(index), layout.getLineTop(index), layout.getLineRight(index), layout.getLineBottom(index)),
                    layout.getParagraphDirection(layout.getLineStart(index)) == ResolvedTextDirection.Rtl)
            }
    }
    // Only active lines prepare halos. Keep the finished immutable masks through
    // the outgoing fade; provider-frame updates never restart this preparation.
    var glyphGlows by remember(line.text, measuredWords, density) { mutableStateOf<PreparedGlyphGlows?>(null) }
    LaunchedEffect(textLayoutResult, isActive, broadRadius, coreRadius, viewportAnimating) {
        val layout = textLayoutResult ?: return@LaunchedEffect
        // Width changes can reshape the paragraph each frame in landscape.
        // Never allocate new halo bitmaps for intermediate transition geometry.
        if (viewportAnimating || !isActive || glyphGlows?.matches(layout) == true) return@LaunchedEffect
        val regions = if (revealUnits.isEmpty()) fallbackRegions else revealUnits.flatMap { it.regions }
        glyphGlows = prepareGlyphGlows(layout, regions, broadRadius, coreRadius)
    }

    Text(
        text = line.text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = FontFamily.SansSerif,
        lineHeight = lineHeight,
        // Sung and upcoming inactive lines share a fainter color; the live reveal stays unchanged.
        color = inactiveTextColor,
        textAlign = TextAlign.Start,
        onTextLayout = { textLayoutResult = it },
        modifier = Modifier.fillMaxWidth().drawWithCache {
            val layout = textLayoutResult
            // Glyph bounds and wrapped/bidirectional runs are prepared once, not per frame.
            val units = revealUnits
            val runs = wordRuns
            val fallbackRows = fallbackRegions
            val completed = Path()
            val moving = Path()
            val liftedCompleted = Path()
            val liftHeight = 2.dp.toPx()
            val feather = 8.dp.toPx()
            val revealPaint = RevealPaint(activeTextColor, glowColor, broadRadius, glyphGlows?.takeIf { layout != null && it.matches(layout) })
            onDrawWithContent {
                // Read focus only while drawing, so the outgoing line's halo fades without relaying out text.
                val focus = glowFocus.value
                if ((!isActive && focus <= 0f) || layout == null) {
                    drawContent()
                    return@onDrawWithContent
                }
                val position = latestPosition()
                if (position < line.timestampMs) {
                    drawContent()
                    return@onDrawWithContent
                }
                completed.reset()
                if (units.isEmpty()) {
                    // Line-only sources cannot locate individual vocals. Preserve a smooth visual
                    // sweep as an approximation; do not manufacture word/syllable timestamps.
                    val end = line.endTimeMs.takeIf { line.isReliableDuration && it > line.timestampMs }
                        ?: nextLine?.timestampMs?.takeIf { it > line.timestampMs }
                        ?: line.endTimeMs.takeIf { it > line.timestampMs } ?: (line.timestampMs + 3_000)
                    val progress = ((position - line.timestampMs).toFloat() / (end - line.timestampMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    // A line-only document moves as one unit; no invented word timings.
                    translate(top = -liftHeight * vocalLift(position, line.timestampMs, end)) {
                        this@onDrawWithContent.drawContent()
                        drawReveal(layout, fallbackRows, progress, activeTextColor, feather, completed, revealPaint,
                            glow = vocalGlow(position, line.timestampMs, end) * focus)
                        clipPath(completed) { drawText(layout, color = activeTextColor) }
                    }
                } else {
                    // Move whole shaped words, retaining authentic syllable reveal
                    // inside them. Independent character fragments break joined scripts
                    // and make fast syllables look detached from their baseline.
                    moving.reset()
                    for (run in runs) {
                        if (run.canLift && vocalLift(position, run.start, run.end) > 0.001f) {
                            for (region in run.regions) moving.addRect(region.bounds)
                        }
                    }
                    clipPath(moving, clipOp = ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                    for (run in runs) {
                        if (position < run.start) continue
                        val lift = if (run.canLift) vocalLift(position, run.start, run.end) else 0f
                        if (lift > 0.001f) {
                            liftedCompleted.reset()
                            translate(top = -liftHeight * lift) {
                                for (region in run.regions) {
                                    val bounds = region.bounds
                                    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                                        drawText(layout, color = inactiveTextColor)
                                    }
                                }
                                for (unit in run.reveals) {
                                    val progress = ((position - unit.start).toFloat() / (unit.end - unit.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                                    drawReveal(layout, unit.regions, progress, activeTextColor, feather, liftedCompleted, revealPaint,
                                        vocalGlow(position, unit.start, unit.end) * focus)
                                }
                                clipPath(liftedCompleted) { drawText(layout, color = activeTextColor) }
                            }
                        } else {
                            for (unit in run.reveals) {
                                val progress = ((position - unit.start).toFloat() / (unit.end - unit.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                                drawReveal(layout, unit.regions, progress, activeTextColor, feather, completed, revealPaint,
                                    vocalGlow(position, unit.start, unit.end) * focus)
                            }
                        }
                    }
                    // Completed stationary runs stay one draw, even on long lines.
                    clipPath(completed) { drawText(layout, color = activeTextColor) }
                }
            }
        }
    )
}

private data class RevealRegion(val bounds: Rect, val rtl: Boolean)
private data class RevealUnit(val start: Long, val end: Long, val regions: List<RevealRegion>)
private data class WordRun(
    val start: Long, val end: Long, val regions: List<RevealRegion>,
    val reveals: List<RevealUnit>, val canLift: Boolean
)

/** Bounded rise and overlapping settle: short vocals never force a two-frame bounce. */
private fun vocalLift(position: Long, start: Long, end: Long): Float {
    if (position <= start || position >= end + 280L) return 0f
    val riseMs = (end - start).coerceIn(180L, 360L).toFloat()
    val rise = ((position - start) / riseMs).coerceIn(0f, 1f)
    // Zero velocity at both ends keeps the larger lift smooth at vocal boundaries.
    val easedRise = rise * rise * (3f - 2f * rise)
    val settle = (1f - (position - end).coerceAtLeast(0L) / 280f).coerceIn(0f, 1f)
    return easedRise * settle * settle * (3f - 2f * settle)
}

/** Animate illumination only: the reveal edge still follows the provider's exact vocal clock. */
private fun vocalGlow(position: Long, start: Long, end: Long): Float {
    if (position < start) return 0f
    fun smooth(value: Float): Float {
        val amount = value.coerceIn(0f, 1f)
        return amount * amount * (3f - 2f * amount)
    }
    if (position >= end) return 0.82f * (1f - smooth((position - end) / 260f))
    val duration = (end - start).coerceAtLeast(1L)
    val progress = (position - start).toFloat() / duration
    val attack = smooth((position - start) / minOf(80f, duration * 0.25f).coerceAtLeast(1f))
    return attack * (0.82f + 0.18f * sin(PI * progress).toFloat())
}

/** Native feather shaders and isolated glyph masks are reused across display frames. */
private class RevealPaint(
    color: Color, glowColor: Color, val broadRadius: Float, val glyphs: PreparedGlyphGlows?
) {
    private val forward = android.graphics.LinearGradient(-1f, 0f, 1f, 0f,
        intArrayOf(color.toArgb(), android.graphics.Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
    private val reverse = android.graphics.LinearGradient(-1f, 0f, 1f, 0f,
        intArrayOf(android.graphics.Color.TRANSPARENT, color.toArgb()), null, android.graphics.Shader.TileMode.CLAMP)
    private val forwardBrush = ShaderBrush(forward)
    private val reverseBrush = ShaderBrush(reverse)
    private val matrix = android.graphics.Matrix()
    val tint = ColorFilter.tint(glowColor)
    val layerPaint = Paint()

    fun feather(edge: Float, width: Float, rtl: Boolean): Brush {
        matrix.setScale(width, 1f)
        matrix.postTranslate(edge, 0f)
        (if (rtl) reverse else forward).setLocalMatrix(matrix)
        return if (rtl) reverseBrush else forwardBrush
    }
}

private data class GlyphHalo(val image: ImageBitmap, val origin: Offset)
private data class GlyphGlow(val broad: GlyphHalo, val core: GlyphHalo)
private data class PreparedGlyphGlows(val layout: TextLayoutResult, val masks: Map<Rect, GlyphGlow>) {
    fun matches(other: TextLayoutResult): Boolean {
        if (layout === other) return true
        val original = layout.layoutInput
        val candidate = other.layoutInput
        return layout.size == other.size && original.text == candidate.text &&
            original.style.hasSameLayoutAffectingAttributes(candidate.style) &&
            original.constraints == candidate.constraints && original.density == candidate.density &&
            original.layoutDirection == candidate.layoutDirection &&
            original.fontFamilyResolver === candidate.fontFamilyResolver
    }
}

/**
 * Crop the shaped glyph alpha BEFORE blurring. Shadowing the whole paragraph and
 * then clipping to a word rectangle pulls neighboring letters' shadows into that
 * rectangle, which creates a flat luminous band instead of letter-shaped light.
 * Android alpha masks work on every supported API, without a per-frame bitmap or
 * RenderEffect allocation. CPU mask extraction runs on Default; only the one
 * paragraph paint stays on Main because it shares Android's mutable text paint.
 */
private suspend fun prepareGlyphGlows(
    layout: TextLayoutResult, regions: List<RevealRegion>, broad: Float, core: Float
): PreparedGlyphGlows {
    val source = withContext(AppDispatchers.CpuBounded) {
        android.graphics.Bitmap.createBitmap(
            layout.size.width.coerceAtLeast(1), layout.size.height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ALPHA_8
        )
    }
    try {
        layout.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(source.asImageBitmap()), color = Color.White)
        val masks = withContext(AppDispatchers.CpuBounded) {
            val broadPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                // Keep only light outside the shaped letters. NORMAL also paints the
                // solid glyph body twice, and wide overlapping halos flatten into a band.
                maskFilter = android.graphics.BlurMaskFilter(broad, android.graphics.BlurMaskFilter.Blur.OUTER)
            }
            val corePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = android.graphics.BlurMaskFilter(core, android.graphics.BlurMaskFilter.Blur.OUTER)
            }
            val result = HashMap<Rect, GlyphGlow>()
            for (region in regions) {
                ensureActive()
                val bounds = region.bounds
                if (bounds.width <= 0f || bounds.height <= 0f || bounds in result) continue
                val left = floor(bounds.left).toInt()
                val top = floor(bounds.top).toInt()
                val bitmap = android.graphics.Bitmap.createBitmap(
                    (ceil(bounds.right).toInt() - left).coerceAtLeast(1),
                    (ceil(bounds.bottom).toInt() - top).coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ALPHA_8
                )
                try {
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.translate(-left.toFloat(), -top.toFloat())
                    canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    canvas.drawBitmap(source, 0f, 0f, null)
                    fun halo(paint: android.graphics.Paint): GlyphHalo {
                        val offset = IntArray(2)
                        val alpha = bitmap.extractAlpha(paint, offset)
                        return GlyphHalo(alpha.asImageBitmap(), Offset(left + offset[0].toFloat(), top + offset[1].toFloat()))
                    }
                    result[bounds] = GlyphGlow(halo(broadPaint), halo(corePaint))
                } finally {
                    bitmap.recycle()
                }
            }
            result
        }
        return PreparedGlyphGlows(layout, masks)
    } finally {
        source.recycle()
    }
}

private fun buildWordRuns(layout: TextLayoutResult, words: List<MeasuredWord>): List<WordRun> {
    fun regions(start: Int, end: Int): List<RevealRegion> {
        if (start >= end || end > layout.layoutInput.text.length) return emptyList()
        val result = mutableListOf<RevealRegion>()
        var cursor = start
        while (cursor < end) {
            val row = layout.getLineForOffset(cursor)
            val direction = layout.getBidiRunDirection(cursor)
            val begin = cursor
            cursor++
            while (cursor < end && layout.getLineForOffset(cursor) == row && layout.getBidiRunDirection(cursor) == direction) cursor++
            // Union all glyph boxes: combining marks and ligatures must retain the original shaping.
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (index in begin until cursor) {
                val bounds = layout.getBoundingBox(index)
                left = minOf(left, bounds.left); right = maxOf(right, bounds.right)
            }
            if (right > left) result.add(RevealRegion(Rect(left, layout.getLineTop(row), right, layout.getLineBottom(row)), direction == ResolvedTextDirection.Rtl))
        }
        return result
    }
    return words.map { measured ->
        val word = measured.word
        val syllables = word.syllables.takeIf { parts ->
            parts.isNotEmpty() && parts.all { it.isReliableTiming && it.durationMs > 0 } &&
                parts.joinToString("") { it.text } == word.text
        }
        val reveals = if (syllables == null) listOf(RevealUnit(word.timestampMs, word.endTimeMs, regions(measured.charStart, measured.charEnd)))
        else {
            var cursor = measured.charStart
            syllables.map { part ->
                val end = (cursor + part.text.length).coerceAtMost(measured.charEnd)
                RevealUnit(part.timestampMs, part.endTimeMs, regions(cursor, end)).also { cursor = end }
            }
        }
        val wordRegions = regions(measured.charStart, measured.charEnd)
        WordRun(word.timestampMs, word.endTimeMs, wordRegions, reveals,
            canLift = wordRegions.isNotEmpty() && wordRegions.all { it.bounds.top == wordRegions.first().bounds.top })
    }
}

private fun DrawScope.drawReveal(
    layout: TextLayoutResult, regions: List<RevealRegion>, progress: Float, color: Color,
    feather: Float, completed: Path, paint: RevealPaint, glow: Float
) {
    if (progress <= 0f) return
    var remaining = regions.sumOf { it.bounds.width.toDouble() }.toFloat() * progress
    for (region in regions) {
        if (remaining <= 0f) break
        val bounds = region.bounds
        val amount = minOf(remaining, bounds.width)
        val complete = amount >= bounds.width
        val edge = if (region.rtl) bounds.right - amount else bounds.left + amount
        val softWidth = minOf(feather, amount, (bounds.width - amount).coerceAtLeast(0f))
        val solid = if (region.rtl) Rect(edge + softWidth, bounds.top, bounds.right, bounds.bottom)
            else Rect(bounds.left, bounds.top, edge - softWidth, bounds.bottom)
        if (solid.width > 0f) completed.addRect(solid)
        if (glow > 0f || !complete) {
            val brush = if (complete || softWidth <= 0f) null else paint.feather(edge, softWidth, region.rtl)
            val clipLeft = if (region.rtl && !complete) maxOf(bounds.left, edge - softWidth) else bounds.left
            val clipRight = if (!region.rtl && !complete) minOf(bounds.right, edge + softWidth) else bounds.right
            val glyphs = paint.glyphs?.masks?.get(bounds)
            if (glow > 0f && glyphs != null) {
                // Leave room for a true halo around the glyph outlines rather than
                // cutting it into the word's rectangular top/bottom boundaries.
                val bleed = paint.broadRadius * 3f
                val glowBounds = Rect(
                    if (region.rtl && !complete) clipLeft else bounds.left - bleed,
                    bounds.top - bleed,
                    if (!region.rtl && !complete) clipRight else bounds.right + bleed,
                    bounds.bottom + bleed
                )
                clipRect(glowBounds.left, glowBounds.top, glowBounds.right, glowBounds.bottom) {
                    // Feather only this isolated glow; the crisp text's original
                    // reveal path, edge and timing remain exactly the same.
                    if (brush != null) drawContext.canvas.saveLayer(glowBounds, paint.layerPaint)
                    drawImage(glyphs.broad.image, topLeft = glyphs.broad.origin,
                        alpha = glow.coerceIn(0f, 1f) * 0.24f, colorFilter = paint.tint)
                    drawImage(glyphs.core.image, topLeft = glyphs.core.origin,
                        alpha = glow.coerceIn(0f, 1f) * 0.48f, colorFilter = paint.tint)
                    if (brush != null) {
                        drawRect(brush, topLeft = glowBounds.topLeft, size = glowBounds.size,
                            blendMode = BlendMode.DstIn)
                        drawContext.canvas.restore()
                    }
                }
            }
            clipRect(clipLeft, bounds.top, clipRight, bounds.bottom) {
                if (brush == null) drawText(layout, color = color)
                else drawText(layout, brush = brush)
            }
        }
        remaining -= bounds.width
    }
}
