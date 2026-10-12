package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LyricLine
import com.example.data.model.LyricWord
import com.example.data.model.TrackLyrics
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

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
 *    - Never replaces strings or moves individual characters.
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
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
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
    LaunchedEffect(isSynced, isPlaying, lyrics?.trackId) {
        if (!isSynced) return@LaunchedEffect
        currentPlayheadMs = latestPositionProvider()
        if (isPlaying) while (isActive) {
            withFrameMillis { currentPlayheadMs = latestPositionProvider() }
        }
    }
    LaunchedEffect(currentPositionMs, isPlaying, lyrics?.trackId) {
        currentPlayheadMs = latestPositionProvider()
    }
    LaunchedEffect(playbackPositionFlow, isPlaying, lyrics?.trackId) {
        if (!isPlaying) playbackPositionFlow?.collect { currentPlayheadMs = latestPositionProvider() }
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
    var hasInitialScrolled by remember(lyrics?.trackId, lines) { mutableStateOf(false) }
    LaunchedEffect(lyrics?.trackId, lines, activeLineIndex, isSynced, followPlayback, isDragged, viewportSize) {
        if (!isSynced || lines.isEmpty() || !followPlayback || isDragged) return@LaunchedEffect
        // Before the first vocal, show the first line without marking it active.
        val targetIndex = activeLineIndex.coerceAtLeast(0)
        snapshotFlow { listState.layoutInfo }.first {
            it.viewportSize.height > 0 && it.totalItemsCount == lines.size
        }

        if (listState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
            // Bring an offscreen row into layout before using its actual wrapped height.
            if (hasInitialScrolled) listState.animateScrollToItem(targetIndex)
            else listState.scrollToItem(targetIndex)
        }
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            ?: return@LaunchedEffect
        // Item offsets include the negative viewport start caused by content padding.
        // Using viewportSize / 2 alone shifts the vocal line down by the top padding.
        val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
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
        hasInitialScrolled = true
    }

    // Container theme colors
    val boxBgColor = if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground
    val boxBorderColor = if (appColors.isAmoled) Color(0xFF222222) else appColors.cardBorder

    val activeTextColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val inactiveTextColor = if (isDark) Color(0xFFFFFFFF).copy(alpha = 0.38f) else Color(0xFF000000).copy(alpha = 0.35f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(boxBgColor)
            .border(BorderStroke(1.dp, boxBorderColor), RoundedCornerShape(24.dp))
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
                        isActive -> 1.0f
                        isPast -> 0.44f
                        distance == 1 -> 0.32f
                        distance == 2 -> 0.20f
                        else -> 0.14f
                    }

                    val animatedAlpha by animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        label = "lyric_alpha_$index"
                    )

                    val animatedScale by animateFloatAsState(
                        targetValue = if (isActive) 1.03f else 0.98f,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        label = "lyric_scale_$index"
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
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
                        if (!item.romanization.isNullOrBlank()) {
                            Text(
                                text = item.romanization,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 18.sp,
                                color = if (isActive) activeTextColor.copy(alpha = 0.85f) else inactiveTextColor.copy(alpha = 0.55f),
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 2. Karaoke Line Text Composable
                        KaraokeLineItem(
                            line = item,
                            nextLine = lines.getOrNull(index + 1),
                            isActive = isActive,
                            isPast = isPast,
                            isSynced = isSynced,
                            isPlaying = isPlaying,
                            trackLanguage = lyrics.language,
                            trackArtist = lyrics.artist,
                            activeTextColor = activeTextColor,
                            inactiveTextColor = inactiveTextColor,
                            currentPositionProvider = {
                                currentPlayheadMs
                            }
                        )
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
    line: LyricLine,
    nextLine: LyricLine?,
    isActive: Boolean,
    isPast: Boolean,
    isSynced: Boolean,
    isPlaying: Boolean,
    trackLanguage: String?,
    trackArtist: String?,
    activeTextColor: Color,
    inactiveTextColor: Color,
    currentPositionProvider: () -> Long
) {
    // Keep line wrapping and row height fixed as activity changes. Emphasis is a
    // top-anchored graphics transform, so it cannot invalidate the centering target.
    val fontSize = 24.sp
    val fontWeight = FontWeight.Bold
    val lineHeight = 34.sp

    if (!isSynced) {
        Text(
            text = line.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 29.sp,
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
            if (word.text.isBlank() || !word.isReliableTiming || word.durationMs <= 0 || match < 0) break
            result.add(MeasuredWord(word, match, match + word.text.length))
            cursor = match + word.text.length
        }
        if (result.size == line.words.size) result else emptyList()
    }
    var textLayoutResult by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    val latestPosition by rememberUpdatedState(currentPositionProvider)

    Text(
        text = line.text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        color = if (isPast) activeTextColor else inactiveTextColor,
        textAlign = TextAlign.Start,
        onTextLayout = { textLayoutResult = it },
        modifier = Modifier.fillMaxWidth().drawWithCache {
            val layout = textLayoutResult
            // Glyph bounds and wrapped/bidirectional runs are prepared once, not per frame.
            val units = if (layout == null) emptyList() else buildRevealUnits(layout, measuredWords)
            val fallbackRows = if (layout == null || units.isNotEmpty()) emptyList() else
                (0 until layout.lineCount).map { index ->
                    RevealRegion(Rect(layout.getLineLeft(index), layout.getLineTop(index), layout.getLineRight(index), layout.getLineBottom(index)),
                        layout.getParagraphDirection(layout.getLineStart(index)) == ResolvedTextDirection.Rtl)
                }
            val completed = Path()
            val feather = 5.dp.toPx()
            val glowRadius = 7.dp.toPx()
            onDrawWithContent {
                drawContent()
                if (!isActive || layout == null) return@onDrawWithContent
                val position = latestPosition()
                if (position < line.timestampMs) return@onDrawWithContent
                completed.reset()
                if (units.isEmpty()) {
                    // Line-only sources cannot locate individual vocals. Preserve a smooth visual
                    // sweep as an approximation; do not manufacture word/syllable timestamps.
                    val end = line.endTimeMs.takeIf { line.isReliableDuration && it > line.timestampMs }
                        ?: nextLine?.timestampMs?.takeIf { it > line.timestampMs }
                        ?: line.endTimeMs.takeIf { it > line.timestampMs } ?: (line.timestampMs + 3_000)
                    val progress = ((position - line.timestampMs).toFloat() / (end - line.timestampMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    drawReveal(layout, fallbackRows, progress, activeTextColor, feather, glowRadius, completed, glow = 0.22f)
                } else {
                    for (unit in units) {
                        if (position < unit.start) continue
                        val progress = ((position - unit.start).toFloat() / (unit.end - unit.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val glow = if (position <= unit.end) 0.32f else
                            (1f - (position - unit.end) / 180f).coerceIn(0f, 1f) * 0.32f
                        drawReveal(layout, unit.regions, progress, activeTextColor, feather, glowRadius, completed, glow)
                    }
                }
                // Completed syllables are one draw, regardless of how many words were sung.
                clipPath(completed) { drawText(layout, color = activeTextColor) }
            }
        }
    )
}

private data class RevealRegion(val bounds: Rect, val rtl: Boolean)
private data class RevealUnit(val start: Long, val end: Long, val regions: List<RevealRegion>)

private fun buildRevealUnits(layout: TextLayoutResult, words: List<MeasuredWord>): List<RevealUnit> {
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
    return words.flatMap { measured ->
        val word = measured.word
        val syllables = word.syllables.takeIf { parts ->
            parts.isNotEmpty() && parts.all { it.isReliableTiming && it.durationMs > 0 } &&
                parts.joinToString("") { it.text } == word.text
        }
        if (syllables == null) listOf(RevealUnit(word.timestampMs, word.endTimeMs, regions(measured.charStart, measured.charEnd)))
        else {
            var cursor = measured.charStart
            syllables.map { part ->
                val end = (cursor + part.text.length).coerceAtMost(measured.charEnd)
                RevealUnit(part.timestampMs, part.endTimeMs, regions(cursor, end)).also { cursor = end }
            }
        }
    }
}

private fun ContentDrawScope.drawReveal(
    layout: TextLayoutResult, regions: List<RevealRegion>, progress: Float, color: Color,
    feather: Float, glowRadius: Float, completed: Path, glow: Float
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
            val brush = if (complete || softWidth <= 0f) Brush.linearGradient(listOf(color, color)) else {
                val colors = if (region.rtl) listOf(Color.Transparent, color) else listOf(color, Color.Transparent)
                Brush.linearGradient(colors, Offset(edge - softWidth, 0f), Offset(edge + softWidth, 0f))
            }
            // The shadow must not illuminate letters ahead of the vocal reveal.
            val clipLeft = if (region.rtl && !complete) maxOf(bounds.left, edge - softWidth) else bounds.left
            val clipRight = if (!region.rtl && !complete) minOf(bounds.right, edge + softWidth) else bounds.right
            clipRect(clipLeft, bounds.top, clipRight, bounds.bottom) {
                drawText(layout, brush = brush,
                    shadow = if (glow > 0f) Shadow(color.copy(alpha = glow), Offset.Zero, glowRadius) else null)
            }
        }
        remaining -= bounds.width
    }
}
