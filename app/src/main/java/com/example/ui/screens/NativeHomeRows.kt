package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeItem
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.rememberOptimizedImageRequest

/** Six native songs per page, in row-major server order. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickPicksGrid(tracks: List<MusicTrack>, state: LazyListState, playing: String?, background: Color, border: Color,
    foreground: Color, muted: Color, play: (MusicTrack, List<MusicTrack>) -> Unit) {
    val pages = remember(tracks) { tracks.chunked(6) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pageWidth = (maxWidth - 40.dp).coerceAtLeast(200.dp)
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), modifier = Modifier.testTag("native_quick_picks_grid"),
            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages, key = { it.first().id }) { page ->
                VisibleItemArtwork(state, page.first().id) {
                    Column(Modifier.width(pageWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(2) { column ->
                                    val track = page.getOrNull(row * 2 + column)
                                    if (track == null) Spacer(Modifier.weight(1f).height(62.4.dp))
                                    else HabitPillItem(track, track.id == playing, background, border, foreground, muted,
                                        Modifier.weight(1f)) { play(track, tracks) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeMusicVideoRow(items: List<ServerHomeItem.Song>, queue: List<MusicTrack>, state: LazyListState, playing: String?,
    background: Color, border: Color, foreground: Color, muted: Color, play: (MusicTrack, List<MusicTrack>) -> Unit) {
    val accent = LocalAppColors.current.primaryAccent
    val pixels = with(LocalDensity.current) { IntSize(224.dp.roundToPx(), 126.dp.roundToPx()) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("native_music_video_row")) {
        items(items, key = { it.key }) { item ->
            val selected = item.track.id == playing
            VisibleItemArtwork(state, item.key) {
                Column(Modifier.width(224.dp).clickable { play(item.track, queue) }) {
                    Box(Modifier.size(224.dp, 126.dp).testTag("native_video_art_${item.track.id}").clip(RoundedCornerShape(14.dp)).background(background)
                        .border(BorderStroke(1.dp, if (selected) accent else border), RoundedCornerShape(14.dp))) {
                        AsyncImage(model = rememberOptimizedImageRequest(
                            item.landscapeThumbnail.ifBlank { item.track.coverUrl }, targetSize = pixels.width,
                            crossfadeEnabled = false, preferStableArtwork = true, decodeSize = pixels),
                            contentDescription = item.track.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        if (selected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Equalizer, "Playing", tint = accent, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(item.track.cleanTitle, color = if (selected) accent else foreground, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(item.track.artist, color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
