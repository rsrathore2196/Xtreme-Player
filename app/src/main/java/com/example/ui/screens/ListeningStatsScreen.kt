package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.AppDynamicLogo
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.util.rememberOptimizedImageRequest
import com.example.ui.util.ListeningStatsImageRequest
import com.example.ui.util.statsCardDecoration
import com.example.ui.util.statsCardBase
import com.example.ui.util.statsCardHighlight
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import java.util.Locale

internal fun listeningTime(millis: Long): Pair<String, String> {
    val hours = millis >= 3_600_000
    val value = millis.toDouble() / if (hours) 3_600_000 else 60_000
    val compact = if (value > 0 && value < .1) "<0.1" else if (value < 10 && value % 1 != 0.0) String.format(Locale.getDefault(), "%.1f", value)
        else String.format(Locale.getDefault(), "%,.0f", value)
    return compact to if (hours) "Hours Played" else "Minutes Played"
}

@Composable
fun ListeningStatsScreen(period: ListeningPeriod, onPeriod: (ListeningPeriod) -> Unit, onBack: () -> Unit,
    onTrack: (MusicTrack, List<MusicTrack>) -> Unit, onArtist: (CatalogArtist) -> Unit,
    listState: LazyListState = rememberLazyListState(), embedded: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { ListeningStatsRepository.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reloadRevision by remember { mutableIntStateOf(0) }
    var failure by remember(period, reloadRevision) { mutableStateOf<String?>(null) }
    val summary by key(period, reloadRevision, lifecycle) {
        produceState(repository.peek(period)) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                failure = null
                repository.observe(period).catch { failure = "Listening stats couldn't load. Please retry." }
                    .collect { value = it }
            }
        }
    }
    val scope = rememberCoroutineScope()
    var playback by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val colors = LocalAppColors.current
    var sharing by remember { mutableStateOf<ListeningStatsImageRequest?>(null) }
    ListeningStatsContent(summary, period, onPeriod, onBack, { track, queue ->
        playback?.cancel()
        playback = scope.launch {
            val resolved = repository.playbackTracks(queue)
            onTrack(resolved.firstOrNull { it.id == track.id } ?: track, resolved)
        }
    }, onArtist, listState, failure, { reloadRevision++ }, embedded, modifier,
        onShare = { snapshot, selectedPeriod ->
            sharing = ListeningStatsImageRequest(snapshot, selectedPeriod, colors.primaryAccent.toArgb())
        })
    sharing?.let { request -> ListeningStatsShareDialog(request, onDismiss = { sharing = null }) }
}

@Composable
internal fun ListeningStatsContent(summary: ListeningSummary?, period: ListeningPeriod,
    onPeriod: (ListeningPeriod) -> Unit, onBack: () -> Unit,
    onTrack: (MusicTrack, List<MusicTrack>) -> Unit, onArtist: (CatalogArtist) -> Unit,
    listState: LazyListState = rememberLazyListState(), failure: String? = null, onRetry: () -> Unit = {},
    embedded: Boolean = false, modifier: Modifier = Modifier,
    onShare: (ListeningSummary, ListeningPeriod) -> Unit = { _, _ -> }) {
    val colors = LocalAppColors.current
    BackHandler(onBack = onBack)
    val queue = remember(summary?.songs) { summary?.songs.orEmpty().map { it.track() } }
    Box(modifier.fillMaxSize().background(colors.scaffoldBackground), contentAlignment = Alignment.TopCenter) {
    LazyColumn(state = listState, modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
        .then(if (embedded) Modifier else Modifier.statusBarsPadding()).testTag("listening_stats_page"),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 180.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!embedded) item { Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.textPrimary) }
            Text("Listening Stats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ListeningPeriod.entries.forEach { choice ->
                val fill by animateColorAsState(if (choice == period) colors.primaryAccent else colors.cardBackground, label = "stats_period")
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(fill)
                    .selectable(choice == period, role = Role.Tab, onClick = { onPeriod(choice) }).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(choice.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = if (choice == period) colors.onPrimaryAccent else colors.textPrimary)
                }
            }
        } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val time = listeningTime(summary?.millis ?: 0)
                    StatCard(time.second, if (summary == null) "—" else time.first, Modifier.weight(1f))
                    StatCard("Top Song", summary?.songs?.firstOrNull()?.title ?: "—", Modifier.weight(1f), song = summary?.songs?.firstOrNull())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Top Artist", summary?.artists?.firstOrNull()?.artist ?: "—", Modifier.weight(1f), artist = summary?.artists?.firstOrNull())
                    StatCard("Songs Played", summary?.plays?.toString() ?: "—", Modifier.weight(1f))
                }
            }
        }
        if (failure != null) item {
            Text(failure, color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Retry", color = colors.primaryAccent) }
        }
        if (summary?.millis == 0L && summary.plays == 0L) item {
            Text("No listening activity in this period yet.", color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        item { Text("Top Songs", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        itemsIndexed(summary?.songs.orEmpty(), key = { _, row -> "song:${row.identity}" }) { index, row ->
            StatsRankingRow(index, row, false) { onTrack(row.track(), queue) }
        }
        item { Text("Top Artists", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        itemsIndexed(summary?.artists.orEmpty(), key = { _, row -> "artist:${row.identity}" }) { index, row ->
            StatsRankingRow(index, row, true) { onArtist(statsArtist(row)) }
        }
        item(key = "share_stats") {
            OutlinedButton(onClick = { summary?.let { onShare(it, period) } }, enabled = summary != null,
                modifier = Modifier.fillMaxWidth().testTag("share_listening_stats"),
                shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, colors.primaryAccent.copy(alpha = .55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary)) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Share the stats", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
    }
}
private fun statsArtist(row: ListeningTotal): CatalogArtist = ArtistPhotoRepository.cachedArtist(row.artist).let {
    if (it.id.isBlank() && row.artistId.startsWith("UC")) it.copy(id = row.artistId) else it
}
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier, song: ListeningTotal? = null, artist: ListeningTotal? = null) {
    val colors = LocalAppColors.current
    val savedSurface = if (colors.neutralSurfaces) colors.cardBackgroundElevated else colors.cardBackground
    val neutralAmoled = colors.isDark && colors.isAmoled
    val cardAccent = if (neutralAmoled) Color(0xFF353535) else colors.primaryAccent
    val cardColor = remember(colors.primaryAccent, savedSurface, neutralAmoled) {
        if (neutralAmoled) Color(0xFF101010) else statsCardBase(colors.primaryAccent, savedSurface)
    }
    val minimumContrast = if (colors.highContrastTypography) 7f else 4.5f
    val surfaces = remember(cardColor, cardAccent) {
        listOf(cardColor, statsCardHighlight(cardAccent, cardColor),
            cardAccent.copy(alpha = .30f).compositeOver(cardColor))
    }
    val textColor = remember(colors.textPrimary, surfaces, minimumContrast) {
        LiquidGlass.contrastColor(colors.textPrimary, surfaces, minimumContrast)
    }
    val labelColor = remember(colors.textSecondary, surfaces, minimumContrast) {
        LiquidGlass.contrastColor(colors.textSecondary, surfaces, minimumContrast)
    }
    Surface(modifier.aspectRatio(1f).testTag("stats_card_$label"), shape = RoundedCornerShape(24.dp),
        color = cardColor, tonalElevation = 0.dp, shadowElevation = 6.dp) {
        BoxWithConstraints(Modifier.statsCardDecoration(cardAccent, cardColor)) {
            val compact = maxWidth < 176.dp
            val media = song != null || artist != null
            Column(Modifier.fillMaxSize().padding(if (media) { if (compact) 10.dp else 12.dp } else if (compact) 12.dp else 16.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label.uppercase(Locale.getDefault()), Modifier.weight(1f), color = labelColor,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.1.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AppDynamicLogo(Modifier.size(if (compact) 24.dp else 28.dp)
                        .testTag("stats_logo_$label"), cutoutColor = cardColor, showGlow = false)
                }
                if (media) {
                    val row = song ?: artist!!
                    StatsMediaContent(row, artist != null, label, value, textColor, labelColor,
                        Modifier.fillMaxWidth().weight(1f))
                } else {
                    Text(value, color = textColor, style = MaterialTheme.typography.headlineLarge,
                        fontSize = if (compact) 38.sp else 48.sp, lineHeight = if (compact) 44.sp else 54.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
@Composable
private fun StatsMediaContent(row: ListeningTotal, artist: Boolean, label: String, name: String,
    foreground: androidx.compose.ui.graphics.Color, secondary: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Start)
    val detailStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Start)
    val time = com.example.ui.util.statsShortTime(row.listenedMs)
    BoxWithConstraints(modifier) {
        val sizing = remember(name, row.artist, artist, time, constraints, density, measurer, titleStyle, detailStyle) {
            val width = Constraints(maxWidth = constraints.maxWidth)
            val detailsHeight = measurer.measure(time, detailStyle, constraints = width).size.height +
                if (artist) 0 else measurer.measure(row.artist, detailStyle, constraints = width).size.height
            val gaps = with(density) { (if (artist) 6.dp else 9.dp).roundToPx() }
            var result = titleStyle.copy(fontSize = 11.sp, lineHeight = 14.sp) to 24.dp
            choose@ for (font in listOf(16f, 15f, 14f, 13f, 12f, 11f)) {
                val style = titleStyle.copy(fontSize = font.sp, lineHeight = (font + 3).sp)
                val textHeight = measurer.measure(name, style, constraints = width).size.height + detailsHeight + gaps
                for (image in listOf(48.dp, 40.dp, 32.dp, 24.dp)) {
                    if (textHeight + with(density) { image.roundToPx() } <= constraints.maxHeight) {
                        result = style to image
                        break@choose
                    }
                }
            }
            result
        }
        // Full-width wrapping is the normal path. Exceptionally long metadata or
        // accessibility font scales remain readable through this local viewport.
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            StatsImage(row, artist, Modifier.size(sizing.second).testTag("stats_artwork_$label"), roundArtist = true)
            Text(name, Modifier.fillMaxWidth().testTag("stats_name_$label"), style = sizing.first, color = foreground)
            if (!artist) Text(row.artist, Modifier.fillMaxWidth().testTag("stats_song_artist"), style = detailStyle, color = secondary)
            Text(time, Modifier.fillMaxWidth(), style = detailStyle, color = secondary)
        }
    }
}
@Composable
private fun StatsImage(row: ListeningTotal, artist: Boolean, modifier: Modifier, roundArtist: Boolean = false) {
    val clipped = modifier.clip(if (artist && roundArtist) CircleShape else RoundedCornerShape(12.dp))
    if (artist) ArtistPortrait(statsArtist(row), clipped, targetSize = 200, backgroundColor = LocalAppColors.current.cardBackground)
    else AsyncImage(rememberOptimizedImageRequest(row.track(), 120), row.title, clipped, contentScale = ContentScale.Crop)
}
@Composable
private fun StatsRankingRow(index: Int, row: ListeningTotal, artist: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${index + 1}", Modifier.width(20.dp), color = colors.textMuted, fontWeight = FontWeight.Bold)
        StatsImage(row, artist, Modifier.size(52.dp))
        Column(Modifier.weight(1f)) {
            Text(if (artist) row.artist else row.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (artist) listeningTime(row.listenedMs).let { "${it.first} ${it.second.lowercase()}" } else row.artist,
                color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!artist) Text("${row.plays} plays", color = colors.textSecondary, style = MaterialTheme.typography.labelMedium)
    }
}
