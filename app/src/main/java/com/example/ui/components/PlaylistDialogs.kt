package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.PlaylistEntity
import com.example.data.model.MusicTrack
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue

@Composable
fun CreatePlaylistDialog(
    isDarkMode: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String) -> Unit
) {
    val isDark = isDarkMode
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    val appColors = LocalAppColors.current

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides appColors) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF0F2238) else Color(0xFFFFFFFF)
                ),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF1B3C64) else Color(0xFFCBD5E1)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
                ) {
                    Text(
                        text = "New Playlist",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFF0F9FF) else Color(0xFF0F172A)
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Playlist Name") },
                        placeholder = { Text("e.g. Chill Beats 320k") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = appColors.primaryAccent,
                            unfocusedBorderColor = if (isDark) Color(0xFF1E3A60) else Color(0xFFCBD5E1),
                            focusedLabelColor = appColors.primaryAccent,
                            unfocusedLabelColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            focusedTextColor = if (isDark) Color(0xFFF0F9FF) else Color(0xFF0F172A),
                            unfocusedTextColor = if (isDark) Color(0xFFF0F9FF) else Color(0xFF0F172A),
                            focusedContainerColor = if (isDark) Color(0xFF071424) else Color(0xFFF8FAFC),
                            unfocusedContainerColor = if (isDark) Color(0xFF071424) else Color(0xFFF8FAFC)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (Optional)") },
                        placeholder = { Text("High bitrate music collection") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = appColors.primaryAccent,
                            unfocusedBorderColor = if (isDark) Color(0xFF1E3A60) else Color(0xFFCBD5E1),
                            focusedLabelColor = appColors.primaryAccent,
                            unfocusedLabelColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            focusedTextColor = if (isDark) Color(0xFFF0F9FF) else Color(0xFF0F172A),
                            unfocusedTextColor = if (isDark) Color(0xFFF0F9FF) else Color(0xFF0F172A),
                            focusedContainerColor = if (isDark) Color(0xFF071424) else Color(0xFFF8FAFC),
                            unfocusedContainerColor = if (isDark) Color(0xFF071424) else Color(0xFFF8FAFC)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("CANCEL", color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (title.isNotBlank()) {
                                    onConfirm(title.trim(), description.trim())
                                }
                            },
                            enabled = title.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = appColors.primaryAccent,
                                contentColor = if (isDark) Color(0xFF031428) else Color.White
                            )
                        ) {
                            Text("CREATE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(
    track: MusicTrack,
    playlists: List<PlaylistEntity>,
    isDarkMode: Boolean = false,
    onPlaylistSelected: (Long) -> Unit,
    onCreateNewPlaylist: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isDarkMode
    val appColors = LocalAppColors.current

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides appColors) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF0F2238) else Color(0xFFFFFFFF)
                ),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF1B3C64) else Color(0xFFCBD5E1)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Add to Playlist",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFF0F9FF) else Color(0xFF0F172A)
                        )
                    )
                    Text(
                        text = "\"${track.title}\" by ${track.artist}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isDark) Color(0xFF93C5FD) else Color(0xFF1E40AF)
                        ),
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Create new playlist button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF13253F) else Color(0xFFF1F5F9))
                            .border(1.dp, if (isDark) Color(0xFF1E3A60) else Color(0xFFCBD5E1), RoundedCornerShape(12.dp))
                            .clickable {
                                onDismiss()
                                onCreateNewPlaylist()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Create New Playlist",
                            color = appColors.primaryAccent,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (playlists.isEmpty()) {
                        Text(
                            text = "No custom playlists yet. Create your first one above!",
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0xFF13253F) else Color(0xFFF8FAFC))
                                        .border(1.dp, if (isDark) Color(0xFF1E3A60) else Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                                        .clickable {
                                            onPlaylistSelected(playlist.playlistId)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = appColors.primaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = playlist.title,
                                            color = if (isDark) Color(0xFFF0F9FF) else Color(0xFF0F172A),
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                        if (playlist.description.isNotBlank()) {
                                            Text(
                                                text = playlist.description,
                                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) {
                            Text("CLOSE", color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B))
                        }
                    }
                }
            }
        }
    }
}
