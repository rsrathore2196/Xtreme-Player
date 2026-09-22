package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.PlaylistWithTracks
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue

@Composable
fun PlaylistDetailScreen(
    playlistWithTracks: PlaylistWithTracks,
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    onBackClick: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onDeletePlaylist: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onRenamePlaylist: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val playlist = playlistWithTracks.playlist
    val tracks = playlistWithTracks.tracks.map { it.toMusicTrack() }
    val currentPlayingId = currentPlayingTrackId ?: playerUiState?.currentTrack?.id
    val appColors = LocalAppColors.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    var newTitleInput by remember(playlist.title) { mutableStateOf(playlist.title) }
    var trackPendingRemoval by remember { mutableStateOf<MusicTrack?>(null) }

    if (trackPendingRemoval != null) {
        val pendingTrack = trackPendingRemoval!!
        AlertDialog(
            onDismissRequest = { trackPendingRemoval = null },
            title = {
                Text(
                    text = "Remove from Playlist",
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove \"${pendingTrack.title}\" from this playlist?",
                    color = appColors.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveTrack(pendingTrack.id)
                        trackPendingRemoval = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = appColors.primaryAccent,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Remove", color = appColors.onPrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { trackPendingRemoval = null }
                ) {
                    Text("Cancel", color = appColors.textMuted)
                }
            },
            containerColor = appColors.cardBackground,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDeletePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePlaylistDialog = false },
            title = {
                Text(
                    text = "Delete Playlist",
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${playlist.title}\"? This action cannot be undone.",
                    color = appColors.textSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeletePlaylistDialog = false
                        onDeletePlaylist()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeletePlaylistDialog = false }
                ) {
                    Text("Cancel", color = appColors.textMuted)
                }
            },
            containerColor = appColors.cardBackground,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = "Edit Playlist Name",
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter a new name for your playlist:",
                        fontSize = 13.sp,
                        color = appColors.textMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newTitleInput,
                        onValueChange = { newTitleInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = appColors.primaryAccent,
                            unfocusedBorderColor = appColors.cardBorder,
                            focusedTextColor = appColors.textPrimary,
                            unfocusedTextColor = appColors.textPrimary,
                            cursorColor = appColors.primaryAccent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_playlist_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newTitleInput.trim()
                        if (trimmed.isNotBlank()) {
                            onRenamePlaylist(trimmed)
                        }
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = appColors.primaryAccent,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    modifier = Modifier.testTag("save_playlist_name_button")
                ) {
                    Text("Save", color = appColors.onPrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = appColors.textMuted)
                }
            },
            containerColor = appColors.cardBackground,
            shape = RoundedCornerShape(18.dp)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(appColors.screenBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .testTag("playlist_detail_screen")
        ) {
        // TOP NAVIGATION BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = appColors.textPrimary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        newTitleInput = playlist.title
                        showRenameDialog = true
                    },
                    modifier = Modifier.testTag("edit_playlist_name_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Playlist Name",
                        tint = appColors.primaryAccent
                    )
                }
                IconButton(onClick = { showDeletePlaylistDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Playlist",
                        tint = appColors.textMuted
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // PLAYLIST HEADER
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(160.dp)
                            .shadow(
                                elevation = 20.dp,
                                shape = RoundedCornerShape(16.dp),
                                spotColor = appColors.primaryAccent.copy(alpha = 0.4f)
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .background(appColors.cardBackgroundElevated)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (playlist.description.isNotBlank()) {
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = appColors.textSecondary
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Text(
                        text = "${tracks.size} tracks • 320kbps High Fidelity",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = appColors.primaryAccent,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // ACTION BUTTONS (PLAY ALL & SHUFFLE)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    onPlayTrack(tracks.first(), tracks)
                                }
                            },
                            enabled = tracks.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = appColors.primaryAccent,
                                contentColor = appColors.onPrimaryAccent
                            ),
                            shape = CircleShape,
                            modifier = Modifier.height(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = appColors.onPrimaryAccent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PLAY ALL", color = appColors.onPrimaryAccent, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        OutlinedButton(
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    val shuffled = tracks.shuffled()
                                    onPlayTrack(shuffled.first(), shuffled)
                                }
                            },
                            enabled = tracks.isNotEmpty(),
                            border = BorderStroke(1.dp, appColors.cardBorder),
                            shape = CircleShape,
                            modifier = Modifier.height(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                tint = appColors.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SHUFFLE", color = appColors.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            if (tracks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Playlist is empty",
                            color = appColors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Add tracks from the Home or Search screen using the 3-dot menu.",
                            color = appColors.textMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                items(tracks, key = { "pl_track_${it.id}" }) { track ->
                    TrackListItem(
                        track = track,
                        isPlaying = track.id == currentPlayingId,
                        onClick = { onPlayTrack(track, tracks) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        onRemoveClick = { trackPendingRemoval = track },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
}
