package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.data.model.TrackLyrics
import com.example.ui.theme.LocalAppColors

/**
 * Backward-compatible wrapper and alias for SyncedLyricsView.
 * Ensures consistent, unified continuous karaoke highlighting across the codebase.
 */
@Composable
fun KaraokeLyricsView(
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
    SyncedLyricsView(
        lyrics = lyrics,
        currentPositionMs = currentPositionMs,
        isPlaying = isPlaying,
        onSeekTo = onSeekTo,
        dominantColor = dominantColor,
        accentColor = accentColor,
        isDark = isDark,
        onRetry = onRetry,
        currentPositionProvider = currentPositionProvider,
        modifier = modifier
    )
}
