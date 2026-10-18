package com.example.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.data.model.MusicTrack

/** The expanded player owns artwork/palette work; the collapsed dock never prefetches it. */
fun playerPaletteKey(track: MusicTrack, dark: Boolean, accent: Color, base: Color): String =
    "${track.id}|${track.coverUrl}|$dark|${accent.toArgb()}|${base.toArgb()}"
