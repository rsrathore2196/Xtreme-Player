package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGreen

@Composable
fun PlaylistDetailScreen(
    playlistWithTracks: PlaylistWithTracks,
    playerUiState: PlayerUiState,
    onBackClick: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    onDeletePlaylist: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val playlist = playlistWithTracks.playlist
    val tracks = playlistWithTracks.tracks.map { it.toMusicTrack() }
    val currentPlayingId = playerUiState.currentTrack?.id

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0E))
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
                    tint = TextPrimary
                )
            }

            IconButton(onClick = onDeletePlaylist) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete Playlist",
                    tint = TextMuted
                )
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
                                spotColor = XtremeCyan.copy(alpha = 0.4f)
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF222631))
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (playlist.description.isNotBlank()) {
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextSecondary
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Text(
                        text = "${tracks.size} tracks • 320kbps High Fidelity",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = XtremeGreen,
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
                                containerColor = XtremeGreen,
                                contentColor = Color.Black
                            ),
                            shape = CircleShape,
                            modifier = Modifier.height(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PLAY ALL", fontWeight = FontWeight.Bold)
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
                            shape = CircleShape,
                            modifier = Modifier.height(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SHUFFLE", color = TextPrimary, fontWeight = FontWeight.Bold)
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
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Add tracks from the Home or Search screen using the 3-dot menu.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                items(tracks) { track ->
                    TrackListItem(
                        track = track,
                        isPlaying = track.id == currentPlayingId,
                        onClick = { onPlayTrack(track, tracks) },
                        onToggleFavorite = { onToggleFavorite(track) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
