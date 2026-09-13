package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeBorder
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremePurple
import com.example.ui.theme.XtremeRose
import com.example.ui.viewmodel.SearchUiState

@Composable
fun SearchScreen(
    searchState: SearchUiState,
    playerUiState: PlayerUiState,
    onQueryChange: (String) -> Unit,
    onSelectGenre: (String) -> Unit,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPlayingId = playerUiState.currentTrack?.id
    val hasQuery = searchState.query.isNotBlank()
    val appColors = LocalAppColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(XtremeGradients.ScreenBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .testTag("search_screen"),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
        // SEARCH INPUT BAR
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
                Text(
                    text = "Search Online Music",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "Stream millions of high-res 320kbps tracks",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = "What do you want to play?",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = XtremeLightBlue
                        )
                    },
                    trailingIcon = {
                        if (searchState.isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = XtremeLightBlue
                            )
                        } else if (hasQuery) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = TextMuted
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = appColors.inputBackground,
                        unfocusedContainerColor = appColors.inputBackground,
                        focusedBorderColor = appColors.primaryAccent,
                        unfocusedBorderColor = appColors.cardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_query_input")
                )
            }
        }

        // GENRE QUICK-FILTER CHIPS
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                items(MusicDataSource.genres) { genre ->
                    val isSelected = searchState.selectedGenre.equals(genre, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) appColors.primaryAccent else appColors.chipBackground,
                        border = if (!isSelected) BorderStroke(1.dp, appColors.chipBorder) else null,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelectGenre(genre) }
                    ) {
                        Text(
                            text = genre,
                            color = if (isSelected) Color.White else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        // IF QUERY ACTIVE -> SHOW CATEGORIZED RESULTS
        if (hasQuery || searchState.selectedGenre != "All") {
            val results = searchState.result

            // TOP RESULT CARD
            results.topResult?.let { top ->
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            text = "Top Result",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = XtremeCard),
                            border = BorderStroke(1.dp, XtremeBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTrackClick(top, results.songs) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = top.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = top.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Song • ${top.artist}",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = TextSecondary
                                        ),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        color = XtremeLightBlue.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "HQ • 320 KBPS",
                                            color = XtremeLightBlue,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // Play button
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(XtremeGradients.ButtonGradient),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color(0xFF031428),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SONGS LIST
            if (results.songs.isNotEmpty()) {
                item {
                    Text(
                        text = "Songs",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
                    )
                }

                items(results.songs) { song ->
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId,
                        onClick = { onTrackClick(song, results.songs) },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // ARTISTS & ALBUMS CATEGORIES
            if (results.artists.isNotEmpty() || results.albums.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(
                            text = "Artists & Albums",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        results.artists.forEach { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onQueryChange(artist) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF163255)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = XtremeLightBlue,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = artist, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(text = "Artist • Tap to view songs", color = TextMuted, fontSize = 12.sp)
                                }
                            }
                        }

                        results.albums.forEach { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onQueryChange(album) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF163255)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Album,
                                        contentDescription = null,
                                        tint = XtremeLightBlue,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = album, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(text = "Album • Tap to view songs", color = TextMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // NO QUERY ACTIVE -> SHOW BROWSE ALL GENRE TILES
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = "Browse All Genres",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    val browseCategories = listOf(
                        Pair("Pop", Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFE11D48)))),
                        Pair("Hip-Hop", Brush.linearGradient(listOf(Color(0xFFD97706), Color(0xFFB45309)))),
                        Pair("Rock", Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFF97316)))),
                        Pair("Electronic", Brush.linearGradient(listOf(Color(0xFF06B6D4), Color(0xFF3B82F6)))),
                        Pair("Bollywood", Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)))),
                        Pair("Punjabi", Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))),
                        Pair("Lo-Fi", Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1)))),
                        Pair("Synthwave", Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF4F46E5))))
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (i in 0 until (browseCategories.size + 1) / 2) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val c1 = browseCategories[i * 2]
                                val c2 = browseCategories.getOrNull(i * 2 + 1)

                                GenreTile(
                                    title = c1.first,
                                    brush = c1.second,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelectGenre(c1.first) }
                                )
                                if (c2 != null) {
                                    GenreTile(
                                        title = c2.first,
                                        brush = c2.second,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onSelectGenre(c2.first) }
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
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
fun GenreTile(
    title: String,
    brush: Brush,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(95.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(brush)
            .clickable { onClick() }
            .padding(14.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}
