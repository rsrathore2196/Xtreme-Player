package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
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
import com.example.data.remote.SyllableSynthesizer
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.isActive

/**
 * Word metadata pre-mapped to character index ranges inside line.text.
 */
private data class MeasuredWord(
    val word: LyricWord,
    val charStart: Int,
    val charEnd: Int,
    val isNonEnglish: Boolean
)

/**
 * Continuous Karaoke-Style Time-Synced Lyrics View for Xtreme Player.
 *
 * 1. Fluid Karaoke Highlighting & Line Transitions:
 *    - Continuous overlay reveal across the stable existing text.
 *    - Identical glyph positions, font sizing, and line wraps with zero layout recalculation.
 *    - Never replaces strings or moves individual characters.
 *
 * 2. Language-Specific Animation:
 *    - English: Syllable progression when reliable syllable timings exist; smooth word highlighting otherwise.
 *    - Hindi, Punjabi & Non-English: Whole words as timing units, never characters or syllables.
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
    isPlaying: Boolean = true,
    onSeekTo: (Long) -> Unit,
    dominantColor: Color = Color.Transparent,
    accentColor: Color = Color.White,
    isDark: Boolean = LocalAppColors.current.isDark,
    onRetry: () -> Unit = {},
    currentPositionProvider: () -> Long = { currentPositionMs },
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    val lines = lyrics?.lines ?: emptyList()
    val isSynced = lyrics?.isSynced == true

    // High-frequency frame ticker for locked 60Hz/120Hz smooth Canvas redraw without recomposition
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isSynced) {
        if (!isSynced) return@LaunchedEffect
        while (isActive) {
            withInfiniteAnimationFrameMillis { frameTick = it }
        }
    }

    // Determine current active lyric line based on media position
    val activeLineIndex by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced || lines.isEmpty()) -1
            else {
                val pos = currentPositionProvider()
                var found = -1
                for (i in lines.indices) {
                    if (pos >= lines[i].timestampMs) {
                        found = i
                    } else {
                        break
                    }
                }
                found
            }
        }
    }

    // Smooth active-line auto-scroll: centers the active line vertically
    LaunchedEffect(activeLineIndex, isSynced) {
        if (isSynced && !listState.isScrollInProgress && activeLineIndex in lines.indices) {
            val targetIndex = (activeLineIndex - 1).coerceAtLeast(0)
            listState.animateScrollToItem(
                index = targetIndex,
                scrollOffset = 0
            )
        }
    }

    // Container theme colors
    val boxBgColor = if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground
    val boxBorderColor = if (appColors.isAmoled) Color(0xFF222222) else appColors.cardBorder

    val activeTextColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val inactiveTextColor = if (isDark) Color(0xFFFFFFFF).copy(alpha = 0.38f) else Color(0xFF000000).copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(boxBgColor)
            .border(BorderStroke(1.dp, boxBorderColor), RoundedCornerShape(24.dp))
            .testTag("synced_lyrics_view")
    ) {
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
                contentPadding = if (isSynced) PaddingValues(top = 80.dp, bottom = 96.dp) else PaddingValues(top = 36.dp, bottom = 48.dp),
                verticalArrangement = if (isSynced) Arrangement.spacedBy(24.dp) else Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(lines, key = { index, item -> "${item.timestampMs}_$index" }) { index, item ->
                    val distance = if (activeLineIndex >= 0 && isSynced) kotlin.math.abs(index - activeLineIndex) else 0
                    val isActive = isSynced && (distance == 0)
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
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .clickable(
                                enabled = isSynced,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                fontSize = if (isActive) 14.sp else 12.sp,
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
                            trackLanguage = lyrics.language,
                            trackArtist = lyrics.artist,
                            activeTextColor = activeTextColor,
                            inactiveTextColor = inactiveTextColor,
                            currentPositionProvider = currentPositionProvider,
                            frameTick = frameTick
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
    trackLanguage: String?,
    trackArtist: String?,
    activeTextColor: Color,
    inactiveTextColor: Color,
    currentPositionProvider: () -> Long,
    frameTick: Long
) {
    val fontSize = if (isActive) 24.sp else 21.sp
    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold
    val lineHeight = if (isActive) 34.sp else 30.sp

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

    // Pre-calculate word spans in line.text once per line
    val measuredWords = remember(line.text, line.words) {
        val result = mutableListOf<MeasuredWord>()
        var searchIdx = 0
        val text = line.text
        for (word in line.words) {
            val idx = text.indexOf(word.text, startIndex = searchIdx)
            if (idx >= 0) {
                val end = idx + word.text.length
                val isNonEng = SyllableSynthesizer.isNonEnglish(word.text, trackLanguage, trackArtist)
                result.add(MeasuredWord(word, idx, end, isNonEng))
                searchIdx = end
            } else {
                val fallbackIdx = text.indexOf(word.text)
                if (fallbackIdx >= 0) {
                    val end = fallbackIdx + word.text.length
                    val isNonEng = SyllableSynthesizer.isNonEnglish(word.text, trackLanguage, trackArtist)
                    result.add(MeasuredWord(word, fallbackIdx, end, isNonEng))
                }
            }
        }
        result
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // LAYER 1: Base Stable Inactive Text
        Text(
            text = line.text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            color = if (isPast) activeTextColor else inactiveTextColor,
            textAlign = TextAlign.Start,
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier.fillMaxWidth()
        )

        // LAYER 2: Active Karaoke Overlay Text (Only for active line)
        if (isActive) {
            Text(
                text = line.text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = lineHeight,
                color = activeTextColor,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        // Suppress unused warning on frameTick: forces redraw on frame tick
                        @Suppress("UNUSED_VARIABLE")
                        val tick = frameTick

                        val layout = textLayoutResult ?: return@drawWithContent
                        val currentPos = currentPositionProvider()

                        renderKaraokeDraw(
                            layout = layout,
                            line = line,
                            nextLine = nextLine,
                            measuredWords = measuredWords,
                            currentPos = currentPos
                        )
                    }
            )
        }
    }
}

/**
 * Canvas drawing phase for continuous karaoke text overlay reveal.
 * Operates with zero Compose node allocations or string modifications.
 */
private fun ContentDrawScope.renderKaraokeDraw(
    layout: TextLayoutResult,
    line: LyricLine,
    nextLine: LyricLine?,
    measuredWords: List<MeasuredWord>,
    currentPos: Long
) {
    if (currentPos < line.timestampMs) {
        // Line has not started singing yet
        return
    }

    // =========================================================================
    // CASE A: WORD-LEVEL (AND SYLLABLE-LEVEL) TIMING AVAILABLE
    // =========================================================================
    if (measuredWords.isNotEmpty()) {
        for (mw in measuredWords) {
            val word = mw.word
            val wordStart = word.timestampMs
            val wordEnd = word.endTimeMs

            if (currentPos < wordStart) {
                // Future word: not sung yet
                continue
            }

            val isWordCompleted = currentPos >= wordEnd

            if (mw.isNonEnglish || word.syllables.size <= 1 || !word.isReliableTiming) {
                // Non-English (Hindi, Punjabi, Romanized Indic, etc.) or single-syllable word:
                // Whole word is the timing unit. Preserves script shaping and conjuncts.
                val wordDur = (wordEnd - wordStart).coerceAtLeast(1L)
                val wordProgress = if (isWordCompleted) 1f else {
                    ((currentPos - wordStart).toFloat() / wordDur.toFloat()).coerceIn(0f, 1f)
                }

                clipWordRegion(layout, mw.charStart, mw.charEnd, wordProgress)
            } else {
                // English word with reliable multiple syllables:
                // Reveal syllable by syllable during each syllable's sung interval
                var sylCharOffset = mw.charStart
                for (syllable in word.syllables) {
                    val sylLen = syllable.text.length
                    val sStart = syllable.timestampMs
                    val sEnd = syllable.endTimeMs
                    val sCharStart = sylCharOffset
                    val sCharEnd = (sCharStart + sylLen).coerceAtMost(mw.charEnd)
                    sylCharOffset = sCharEnd

                    if (currentPos < sStart) {
                        continue
                    }

                    val isSylCompleted = currentPos >= sEnd
                    val sDur = (sEnd - sStart).coerceAtLeast(1L)
                    val sProgress = if (isSylCompleted) 1f else {
                        ((currentPos - sStart).toFloat() / sDur.toFloat()).coerceIn(0f, 1f)
                    }

                    clipWordRegion(layout, sCharStart, sCharEnd, sProgress)
                }
            }
        }
        return
    }

    // =========================================================================
    // CASE B: LINE-SYNC FALLBACK (e.g. Standard LRC without word timestamps)
    // =========================================================================
    val lineStart = line.timestampMs
    val vocalEnd = line.endTimeMs
    val duration = (vocalEnd - lineStart).coerceAtLeast(500L)

    val lineProgress = if (currentPos >= vocalEnd) 1f else {
        ((currentPos - lineStart).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    if (lineProgress <= 0f) return
    if (lineProgress >= 1f) {
        // Line vocals finished: hold completed throughout silence
        drawContent()
        return
    }

    // Smooth reveal across wrapped visual lines sequentially
    val lineCount = layout.lineCount
    val totalWidth = (0 until lineCount).sumOf { l ->
        (layout.getLineRight(l) - layout.getLineLeft(l)).toDouble().coerceAtLeast(1.0)
    }.toFloat().coerceAtLeast(1f)

    var remainingTargetPx = totalWidth * lineProgress

    for (l in 0 until lineCount) {
        val lineLeft = layout.getLineLeft(l)
        val lineRight = layout.getLineRight(l)
        val lineWidth = (lineRight - lineLeft).coerceAtLeast(1f)
        val top = layout.getLineTop(l)
        val bottom = layout.getLineBottom(l)

        if (remainingTargetPx <= 0f) break

        if (remainingTargetPx >= lineWidth) {
            // Entire visual line is completed
            clipRect(left = lineLeft, top = top, right = lineRight, bottom = bottom) {
                this@renderKaraokeDraw.drawContent()
            }
            remainingTargetPx -= lineWidth
        } else {
            // Actively revealing visual line
            val activeX = lineLeft + remainingTargetPx
            clipRect(left = lineLeft, top = top, right = activeX, bottom = bottom) {
                this@renderKaraokeDraw.drawContent()
            }
            remainingTargetPx = 0f
        }
    }
}

/**
 * Clips a specific character span [charStart, charEnd] on the text layout with horizontal progress 0f..1f.
 * Handles wrapped visual lines safely.
 */
private fun ContentDrawScope.clipWordRegion(
    layout: TextLayoutResult,
    charStart: Int,
    charEnd: Int,
    progress: Float
) {
    if (progress <= 0f || charStart >= charEnd || charStart >= layout.layoutInput.text.length) return

    val startLine = layout.getLineForOffset(charStart)
    val endCharClamped = (charEnd - 1).coerceIn(charStart, layout.layoutInput.text.length - 1)
    val endLine = layout.getLineForOffset(endCharClamped)

    if (startLine == endLine) {
        // Word is on a single visual line (standard case)
        val left = layout.getBoundingBox(charStart).left
        val right = layout.getBoundingBox(endCharClamped).right
        val top = layout.getLineTop(startLine)
        val bottom = layout.getLineBottom(startLine)

        val revealRight = if (progress >= 1f) right else (left + (right - left) * progress)

        if (revealRight > left) {
            clipRect(left = left, top = top, right = revealRight, bottom = bottom) {
                this@clipWordRegion.drawContent()
            }
        }
    } else {
        // Word wraps across multiple visual lines
        var p = progress
        for (l in startLine..endLine) {
            val top = layout.getLineTop(l)
            val bottom = layout.getLineBottom(l)
            val left = if (l == startLine) layout.getBoundingBox(charStart).left else layout.getLineLeft(l)
            val right = if (l == endLine) layout.getBoundingBox(endCharClamped).right else layout.getLineRight(l)

            if (p >= 1f) {
                clipRect(left = left, top = top, right = right, bottom = bottom) {
                    this@clipWordRegion.drawContent()
                }
            } else if (p > 0f) {
                val currentWidth = (right - left).coerceAtLeast(0f)
                val activeX = left + currentWidth * p
                clipRect(left = left, top = top, right = activeX, bottom = bottom) {
                    this@clipWordRegion.drawContent()
                }
                p = 0f
            }
        }
    }
}
