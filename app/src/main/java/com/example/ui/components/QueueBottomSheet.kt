package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queue: List<MusicTrack>,
    currentTrack: MusicTrack?,
    onTrackClick: (MusicTrack) -> Unit,
    onMoveTrack: (fromIndex: Int, toIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    isAutoplayEnabled: Boolean = true,
    nextRecommendedTrack: MusicTrack? = null,
    onToggleAutoplay: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (isDark) Color(0xFF0B1726) else Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isDark) Color(0xFF1B3C64) else Color(0xFFCBD5E1))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = if (isDark) XtremeLightBlue else Color(0xFF0284C7),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Now Playing & Queue",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) TextPrimary else Color(0xFF0F172A)
                            )
                        )
                        Text(
                            text = "${queue.size} high-fidelity tracks in session",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isDark) TextMuted else Color(0xFF64748B)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Infinity Autoplay Banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onToggleAutoplay() },
                color = if (isAutoplayEnabled) {
                    if (isDark) Color(0xFF0F2644) else Color(0xFFEFF6FF)
                } else {
                    if (isDark) Color(0xFF121820) else Color(0xFFF1F5F9)
                },
                border = BorderStroke(
                    1.dp,
                    if (isAutoplayEnabled) (if (isDark) Color(0xFF1D4ED8) else Color(0xFF93C5FD))
                    else (if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (isAutoplayEnabled) (if (isDark) XtremeCyan.copy(alpha = 0.2f) else Color(0xFFDBEAFE)) else Color(0xFF334155)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AllInclusive,
                                contentDescription = null,
                                tint = if (isAutoplayEnabled) (if (isDark) XtremeCyan else Color(0xFF0284C7)) else Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Infinity Autoplay",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (isDark) TextPrimary else Color(0xFF0F172A)
                            )
                            Text(
                                text = if (isAutoplayEnabled && nextRecommendedTrack != null) {
                                    "Next: ${nextRecommendedTrack.title} • ${nextRecommendedTrack.artist}"
                                } else if (isAutoplayEnabled) {
                                    "Continuous play using recommendation engine"
                                } else {
                                    "Queue stops when tracks finish"
                                },
                                fontSize = 11.sp,
                                color = if (isDark) TextSecondary else Color(0xFF64748B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Switch(
                        checked = isAutoplayEnabled,
                        onCheckedChange = { onToggleAutoplay() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = if (isDark) XtremeCyan else Color(0xFF0284C7),
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = if (isDark) Color(0xFF1E293B) else Color(0xFFCBD5E1)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(queue) { index, track ->
                    val isCurrentlyPlaying = track.id == currentTrack?.id

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isCurrentlyPlaying) {
                                    if (isDark) Color(0xFF163456) else Color(0xFFE0F2FE)
                                } else {
                                    if (isDark) Color(0xFF0F2238) else Color(0xFFF8FAFC)
                                }
                            )
                            .clickable { onTrackClick(track) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork
                        AsyncImage(
                            model = track.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = if (isCurrentlyPlaying) {
                                    if (isDark) XtremeLightBlue else Color(0xFF0284C7)
                                } else {
                                    if (isDark) TextPrimary else Color(0xFF0F172A)
                                },
                                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrentlyPlaying) {
                                    Icon(
                                        imageVector = Icons.Default.Equalizer,
                                        contentDescription = null,
                                        tint = if (isDark) XtremeLightBlue else Color(0xFF0284C7),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = track.artist,
                                    color = if (isDark) TextSecondary else Color(0xFF64748B),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Reorder buttons (Move Up / Move Down)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (index > 0) {
                                IconButton(
                                    onClick = { onMoveTrack(index, index - 1) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Move Up",
                                        tint = if (isDark) TextMuted else Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (index < queue.lastIndex) {
                                IconButton(
                                    onClick = { onMoveTrack(index, index + 1) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Move Down",
                                        tint = if (isDark) TextMuted else Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
