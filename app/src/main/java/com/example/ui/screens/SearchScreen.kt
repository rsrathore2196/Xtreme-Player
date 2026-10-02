package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.bouncyClickable
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextMuted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.data.local.RecentSearchPreferences
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.util.ImageConfig
import com.example.ui.util.rememberOptimizedImageRequest
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
import com.example.playback.PlayerUiState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.XtremeBorder
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremeLightBlue
import com.example.ui.theme.XtremePurple
import com.example.ui.theme.XtremeRose
import com.example.ui.viewmodel.SearchUiState

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    searchState: SearchUiState,
    playerUiState: PlayerUiState? = null,
    currentPlayingTrackId: String? = null,
    isPlaying: Boolean = playerUiState?.isPlaying == true,
    isLoading: Boolean = playerUiState?.isLoading == true,
    onQueryChange: (String) -> Unit,
    onSelectGenre: (String) -> Unit,
    onSelectSource: (String) -> Unit = {},
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onToggleFavorite: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPlayingId = currentPlayingTrackId ?: playerUiState?.currentTrack?.id
    val isCurrentlyPlaying = isPlaying || (playerUiState?.isPlaying == true)
    val isCurrentlyLoading = isLoading || (playerUiState?.isLoading == true)
    var pendingLoadingTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPlayingId, isCurrentlyPlaying, isCurrentlyLoading) {
        if (currentPlayingId == pendingLoadingTrackId && isCurrentlyPlaying && !isCurrentlyLoading) {
            pendingLoadingTrackId = null
        }
    }

    val hasQuery = searchState.query.isNotBlank()
    val isGenreActive = !searchState.selectedGenre.equals("All", ignoreCase = true)
    val appColors = LocalAppColors.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    var selectedTypeFilter by remember { mutableStateOf<String?>(null) }

    // Gracefully handle back press: Return to default "All" genres page rather than closing the app
    BackHandler(enabled = isGenreActive || hasQuery || selectedTypeFilter != null) {
        keyboardController?.hide()
        focusManager.clearFocus()
        if (selectedTypeFilter != null) {
            selectedTypeFilter = null
        } else if (isGenreActive) {
            onSelectGenre("All")
        } else if (hasQuery) {
            onQueryChange("")
        }
    }

    // Automatically close keyboard when user starts scrolling the results list
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    val context = LocalContext.current
    var recentSearches by remember {
        mutableStateOf(RecentSearchPreferences.getRecentSearches(context))
    }

    val triggerSearch: (String) -> Unit = { queryText ->
        val clean = queryText.trim()
        keyboardController?.hide()
        focusManager.clearFocus()
        if (clean.isNotEmpty()) {
            recentSearches = RecentSearchPreferences.addRecentSearch(context, clean)
            onQueryChange(clean)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(XtremeGradients.ScreenBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .testTag("search_screen"),
            contentPadding = PaddingValues(bottom = 150.dp)
        ) {
        // SEARCH INPUT BAR
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
                Text(
                    text = "Search Online Music",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        letterSpacing = (-0.6).sp,
                        color = appColors.textPrimary
                    )
                )
                Text(
                    text = "Stream millions of high-res 320kbps tracks",
                    style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted)
                )

                Spacer(modifier = Modifier.height(22.dp))

                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = { newText ->
                        if (newText.contains('\n')) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onQueryChange(newText.replace("\n", "").trim())
                        } else {
                            onQueryChange(newText)
                        }
                    },
                    placeholder = {
                        Text(
                            text = "What do you want to play?",
                            color = appColors.textMuted,
                            fontSize = 14.sp
                        )
                    },
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            if (searchState.isSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = appColors.primaryAccent
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            } else if (hasQuery) {
                                IconButton(
                                    onClick = { 
                                        onQueryChange("") 
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = appColors.textMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            // Wide pill-shaped 3D clear water glass search action button (icon only)
                            val searchPillShape = remember { RoundedCornerShape(17.dp) }
                            val isSearchActive = hasQuery

                            val searchPillBrush = if (isSearchActive) {
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = 0.32f),
                                        appColors.primaryAccent.copy(alpha = 0.18f)
                                    )
                                )
                            } else {
                                LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                            }

                            val searchPillBorder = BorderStroke(
                                1.2.dp,
                                if (isSearchActive) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.40f),
                                            appColors.primaryAccent.copy(alpha = 0.45f)
                                        )
                                    )
                                } else {
                                    LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.38f else 0.45f)
                                }
                            )

                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(searchPillShape)
                                    .background(searchPillBrush)
                                    .border(searchPillBorder, searchPillShape)
                                    .clickable {
                                        if (searchState.query.isNotBlank()) {
                                            triggerSearch(searchState.query)
                                        } else {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                    .testTag("finish_search_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = if (isSearchActive) {
                                        if (appColors.isDark) Color.White else appColors.primaryAccent
                                    } else {
                                        if (appColors.isDark) Color.White.copy(alpha = 0.75f) else appColors.textMuted
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            triggerSearch(searchState.query)
                        },
                        onDone = {
                            triggerSearch(searchState.query)
                        },
                        onGo = {
                            triggerSearch(searchState.query)
                        }
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = appColors.inputBackground,
                        unfocusedContainerColor = appColors.inputBackground,
                        focusedBorderColor = appColors.primaryAccent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = appColors.textPrimary,
                        unfocusedTextColor = appColors.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = if (appColors.isDark) 4.dp else 6.dp,
                            shape = RoundedCornerShape(16.dp),
                            spotColor = if (appColors.isDark) Color.Black.copy(alpha = 0.40f) else appColors.primaryAccent.copy(alpha = 0.10f),
                            ambientColor = if (appColors.isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
                        )
                        .border(
                            BorderStroke(
                                1.3.dp,
                                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (appColors.isDark) 0.35f else 0.55f)
                            ),
                            RoundedCornerShape(16.dp)
                        )
                        .testTag("search_query_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Pill-shaped category filter buttons attached under the search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filterCategories = listOf("Tracks", "Artists", "Albums", "Playlists")
                    filterCategories.forEach { filterName ->
                        val isSelected = selectedTypeFilter == filterName
                        Box(
                            modifier = Modifier
                                .liquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(20.dp),
                                    isActive = isSelected,
                                    elevation = if (isSelected) 6.dp else 2.dp
                                )
                                .bouncyClickable {
                                    selectedTypeFilter = if (selectedTypeFilter == filterName) null else filterName
                                }
                                .testTag("filter_pill_${filterName.lowercase()}")
                        ) {
                            Text(
                                text = filterName,
                                color = if (isSelected) appColors.onPrimaryAccent else (if (appColors.isDark) Color.White else appColors.textPrimary),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }

        // RECENT SEARCHES SECTION (Positioned directly above the genre pills)
        if (!hasQuery && recentSearches.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Searches",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary,
                                fontSize = 18.sp
                            )
                        )
                        Text(
                            text = "Clear all",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = appColors.textMuted,
                                fontSize = 11.5.sp
                            ),
                            modifier = Modifier
                                .clickable {
                                    RecentSearchPreferences.clearRecentSearches(context)
                                    recentSearches = emptyList()
                                }
                                .padding(4.dp)
                                .testTag("clear_recent_searches_button")
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recentSearches.forEach { queryItem ->
                            Box(
                                modifier = Modifier
                                    .liquidGlassPill(
                                        colors = appColors,
                                        shape = RoundedCornerShape(20.dp),
                                        isActive = false,
                                        elevation = 3.dp
                                    )
                                    .testTag("recent_search_chip_$queryItem")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .bouncyClickable { triggerSearch(queryItem) }
                                        .padding(start = 14.dp, end = 7.dp, top = 6.dp, bottom = 6.dp)
                                ) {
                                    Text(
                                        text = queryItem,
                                        color = if (appColors.isDark) Color.White else appColors.textPrimary,
                                        fontSize = 13.8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                recentSearches = RecentSearchPreferences.removeRecentSearch(context, queryItem)
                                            }
                                            .testTag("remove_recent_search_$queryItem"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Remove $queryItem",
                                            tint = if (appColors.isDark) Color.White.copy(alpha = 0.70f) else appColors.textMuted,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // GENRE QUICK-FILTER CHIPS (Multi-line FlowRow with fixed 8dp spacing)
        if (!hasQuery) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp)
                ) {
                    Text(
                        text = "Explore genres & moods",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary,
                            fontSize = 18.sp
                        ),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MusicDataSource.genres.forEach { genre ->
                            val isSelected = searchState.selectedGenre.equals(genre, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .liquidGlassPill(
                                        colors = appColors,
                                        shape = RoundedCornerShape(23.dp),
                                        isActive = isSelected,
                                        elevation = if (isSelected) 6.dp else 2.dp
                                    )
                                    .bouncyClickable {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        onSelectGenre(genre)
                                    }
                                    .testTag("genre_chip_${genre.lowercase()}")
                            ) {
                                Text(
                                    text = genre,
                                    color = if (isSelected) appColors.onPrimaryAccent else (if (appColors.isDark) Color.White else appColors.textPrimary),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    fontSize = 14.5.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // IF QUERY ACTIVE OR FILTER SELECTED -> SHOW CATEGORIZED RESULTS
        if (hasQuery || searchState.selectedGenre != "All" || selectedTypeFilter != null) {
            val results = searchState.result
            val showTracks = selectedTypeFilter == null || selectedTypeFilter == "Tracks"
            val showArtists = selectedTypeFilter == null || selectedTypeFilter == "Artists"
            val showAlbums = selectedTypeFilter == null || selectedTypeFilter == "Albums"
            val showPlaylists = selectedTypeFilter == "Playlists"

            // GENRE BREADCRUMB & RESHUFFLE BAR
            if (isGenreActive) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .liquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(20.dp),
                                    isActive = false,
                                    elevation = 3.dp
                                )
                                .bouncyClickable {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    onSelectGenre("All")
                                }
                                .testTag("back_to_all_genres_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to all genres",
                                    tint = appColors.primaryAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                                 Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "All Genres",
                                    color = appColors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .liquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(20.dp),
                                    isActive = true,
                                    elevation = 4.dp
                                )
                                .bouncyClickable {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    onSelectGenre(searchState.selectedGenre)
                                }
                                .testTag("reshuffle_genre_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle songs",
                                    tint = appColors.onPrimaryAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Shuffle Songs",
                                    color = appColors.onPrimaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // TOP RESULT CARD
            if (showTracks) {
                results.topResult?.let { top ->
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                text = "Top Result",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary,
                                    fontSize = 18.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            val isTopLoading = (top.id == pendingLoadingTrackId) || (top.id == currentPlayingId && isCurrentlyLoading)
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = appColors.cardBackground),
                                border = BorderStroke(1.dp, appColors.cardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        pendingLoadingTrackId = top.id
                                        onTrackClick(top, results.songs)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = rememberOptimizedImageRequest(top.coverUrl, ImageConfig.LIST_ITEM_SIZE),
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
                                                color = appColors.textPrimary
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Song • ${top.artist}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = appColors.textSecondary
                                            ),
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(
                                            color = appColors.primaryAccent.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "HQ • 320 KBPS",
                                                color = appColors.primaryAccent,
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
                                        if (isTopLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.5.dp,
                                                color = appColors.onPrimaryAccent
                                            )
                                        } else {
                                            Icon(
                                                imageVector = if (top.id == currentPlayingId && isCurrentlyPlaying) Icons.Default.Equalizer else Icons.Default.PlayArrow,
                                                contentDescription = if (top.id == currentPlayingId && isCurrentlyPlaying) "Playing" else "Play",
                                                tint = appColors.onPrimaryAccent,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // EXACT MATCHES / SONGS MATCHING QUERY (Strictly 4 to 5 options when searching)
            val rawList = if (results.exactMatches.isNotEmpty()) results.exactMatches else results.songs
            val directList = when (searchState.selectedSource) {
                "HD Stream" -> (results.jioMatches.ifEmpty { rawList.filter { it.source != "Extended Stream" && !it.id.startsWith("yt_") } })
                "Extended Stream" -> (results.ytMatches.ifEmpty { rawList.filter { it.source == "Extended Stream" || it.id.startsWith("yt_") } })
                else -> rawList
            }
            val displayDirectList = if (hasQuery) directList.take(5) else directList

            if (showTracks && displayDirectList.isNotEmpty()) {
                item {
                    val sectionTitle = if (hasQuery) {
                        "Songs Matching \"${searchState.query.trim()}\""
                    } else {
                        "${searchState.selectedGenre} Hits (Shuffled)"
                    }
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 18.sp
                        ),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
                    )
                }

                items(displayDirectList, key = { "direct_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // RECOMMENDED TRACKS VIA RECOMMENDATION ENGINE (Strictly 5 to 6 songs matching mood, language, genre, vibe and artist)
            if (showTracks && hasQuery && results.recommendedTracks.isNotEmpty()) {
                item(key = "rec_section_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recommended For You",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = appColors.primaryAccent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Recommendation Engine",
                                color = appColors.primaryAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                items(results.recommendedTracks.take(6), key = { "rec_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // SAME SINGER'S OTHER SONGS
            if (showArtists && results.artistSongs.isNotEmpty()) {
                item(key = "artist_songs_header") {
                    val singerTitle = if (results.matchedArtistName.isNotBlank()) {
                        "More by ${results.matchedArtistName}"
                    } else {
                        "Songs by Same Singer"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = singerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = XtremePurple.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Same Singer",
                                color = XtremePurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                items(results.artistSongs, key = { "artist_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // SAME TYPE / SAME GENRE SONGS
            if (showTracks && results.similarTypeSongs.isNotEmpty() && !isGenreActive) {
                item(key = "similar_type_header") {
                    val genreTitle = if (results.matchedGenreOrType.isNotBlank()) {
                        "Similar Songs • ${results.matchedGenreOrType} Vibe"
                    } else {
                        "Similar Songs & Same Type"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = genreTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = XtremeCyan.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Same Vibe",
                                color = XtremeCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                items(results.similarTypeSongs, key = { "sim_${it.id}" }, contentType = { "track" }) { song ->
                    val isTrackLoading = (song.id == pendingLoadingTrackId) || (song.id == currentPlayingId && isCurrentlyLoading)
                    TrackListItem(
                        track = song,
                        isPlaying = song.id == currentPlayingId && isCurrentlyPlaying,
                        isLoading = isTrackLoading,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            pendingLoadingTrackId = song.id
                            onTrackClick(song, results.songs)
                        },
                        onToggleFavorite = { onToggleFavorite(song) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            // ARTISTS & ALBUMS CATEGORIES
            val hasArtistsToShow = showArtists && results.artists.isNotEmpty()
            val hasAlbumsToShow = showAlbums && results.albums.isNotEmpty()
            if (hasArtistsToShow || hasAlbumsToShow) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        val headerLabel = if (hasArtistsToShow && hasAlbumsToShow) "Artists & Albums" else if (hasArtistsToShow) "Artists" else "Albums"
                        Text(
                            text = headerLabel,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (hasArtistsToShow) {
                            results.artists.forEach { artist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            onQueryChange(artist)
                                        }
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
                        }

                        if (hasAlbumsToShow) {
                            results.albums.forEach { album ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            onQueryChange(album)
                                        }
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
            }

            // PLAYLISTS CATEGORY
            if (showPlaylists) {
                val matchingGenres = MusicDataSource.genres.filter {
                    it.contains(searchState.query, ignoreCase = true)
                }.ifEmpty { MusicDataSource.genres.take(6) }

                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(
                            text = "Playlists",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 18.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        matchingGenres.forEach { genreName ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(appColors.chipBackground)
                                    .border(BorderStroke(1.dp, appColors.chipBorder), RoundedCornerShape(14.dp))
                                    .clickable {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        onSelectGenre(genreName)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(appColors.primaryAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        tint = appColors.primaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "$genreName Essentials",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Curated Playlist • HD 320k",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
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
