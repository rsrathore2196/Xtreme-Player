package com.example.ui.components

import android.content.Intent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.XtremeRose
import com.example.ui.theme.bouncyClickable
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest
import kotlinx.coroutines.launch

/**
 * Modern rounded 3-dot action sheet for tracks with liquid glass styling,
 * providing Add to Queue, Infinite Autoplay, Like/Favorite, and Share actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionSheet(
    track: MusicTrack,
    onDismiss: () -> Unit,
    onPlayNow: (MusicTrack) -> Unit,
    onAddToQueue: (MusicTrack) -> Unit,
    onPlayInfiniteRadio: (MusicTrack) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(42.dp)
                    .height(4.5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(appColors.cardBorder.copy(alpha = 0.8f))
            )
        },
        modifier = Modifier.testTag("track_action_sheet_${track.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Track Preview Header with Liquid Glass Surface
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isDark) Color(0xFF14243B).copy(alpha = 0.75f) else Color.White.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(appColors.cardBorder)
                    ) {
                        AsyncImage(
                            model = rememberOptimizedImageRequest(track = track, targetSize = ImageConfig.LIST_ITEM_SIZE),
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(54.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.cleanTitle,
                            color = appColors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.artist}${if (track.album.isNotBlank()) " • " + track.album else ""}",
                            color = appColors.textSecondary,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (track.year.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = track.year,
                                fontSize = 11.sp,
                                color = appColors.textMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = appColors.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action List - Uniform Liquid Glass buttons adapting theme color, with descriptions removed
            ActionSheetItem(
                icon = Icons.Default.PlayArrow,
                title = "Play Now",
                tint = appColors.primaryAccent,
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onDismiss()
                        onPlayNow(track)
                    }
                }
            )

            ActionSheetItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Add to Queue",
                tint = appColors.primaryAccent,
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onDismiss()
                        onAddToQueue(track)
                    }
                }
            )

            ActionSheetItem(
                icon = Icons.Default.AllInclusive,
                title = "Infinite Autoplay",
                tint = appColors.primaryAccent,
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onDismiss()
                        onPlayInfiniteRadio(track)
                    }
                }
            )

            ActionSheetItem(
                icon = if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                title = if (track.isLiked) "Remove from Liked" else "Add to Liked Songs",
                tint = if (track.isLiked) XtremeRose else appColors.primaryAccent,
                onClick = {
                    onToggleFavorite(track)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }
            )

            ActionSheetItem(
                icon = Icons.Default.Share,
                title = "Share Song",
                tint = appColors.primaryAccent,
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onDismiss()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, track.title)
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Listening to \"${track.title}\" by ${track.artist} on Xtreme Player!"
                            )
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share \"${track.title}\""))
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActionSheetItem(
    icon: ImageVector,
    title: String,
    tint: Color,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LiquidGlass.glassBrush(appColors, translucency = 0.84f, tintAccent = false))
            .border(
                BorderStroke(1.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.35f)),
                RoundedCornerShape(16.dp)
            )
            .bouncyClickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = appColors.textPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
