package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.XtremeRose
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest

/**
 * Standard reusable track item component used across Library, Search, and Playlist screens.
 */
@Composable
fun TrackListItem(
    track: MusicTrack,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    showQualityBadge: Boolean = false,
    onClick: () -> Unit = {},
    onToggleFavorite: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val cardBg = if (isPlaying) {
        appColors.primaryAccent.copy(alpha = if (isDark) 0.16f else 0.10f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("track_item_${track.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art with playing / loading overlay
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = rememberOptimizedImageRequest(track.coverUrl, ImageConfig.LIST_ITEM_SIZE),
                contentDescription = "${track.title} album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = appColors.primaryAccent,
                        strokeWidth = 2.dp
                    )
                }
            } else if (isPlaying) {
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
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title, artist, quality badge
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = if (isPlaying) appColors.primaryAccent else appColors.textPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showQualityBadge && (track.qualityBadge.isNotBlank() || track.isLossless)) {
                    val badgeText = if (track.isLossless) "FLAC" else track.qualityBadge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(appColors.primaryAccent.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.primaryAccent
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Text(
                    text = track.artist.ifBlank { "Unknown Artist" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        color = appColors.textSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Action icons
        if (onToggleFavorite != null) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (track.isLiked) "Remove from favorites" else "Add to favorites",
                    tint = if (track.isLiked) XtremeRose else appColors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (onRemoveClick != null) {
            IconButton(
                onClick = onRemoveClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove from playlist",
                    tint = appColors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (onMoreClick != null) {
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint = appColors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
