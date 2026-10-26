package com.example.ui.screens

import android.content.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.ui.components.ArtistPortrait
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.rememberOptimizedImageRequest
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
    listState: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { ListeningStatsRepository.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var calendarRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { calendarRevision++ }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED) }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    var failure by remember(period, calendarRevision) { mutableStateOf<String?>(null) }
    val summary by key(period, calendarRevision, lifecycle) {
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
    ListeningStatsContent(summary, period, onPeriod, onBack, { track, queue ->
        playback?.cancel()
        playback = scope.launch {
            val resolved = repository.playbackTracks(queue)
            onTrack(resolved.firstOrNull { it.id == track.id } ?: track, resolved)
        }
    }, onArtist, listState, failure, { calendarRevision++ })
}

@Composable
internal fun ListeningStatsContent(summary: ListeningSummary?, period: ListeningPeriod,
    onPeriod: (ListeningPeriod) -> Unit, onBack: () -> Unit,
    onTrack: (MusicTrack, List<MusicTrack>) -> Unit, onArtist: (CatalogArtist) -> Unit,
    listState: LazyListState = rememberLazyListState(), failure: String? = null, onRetry: () -> Unit = {}) {
    val colors = LocalAppColors.current
    BackHandler(onBack = onBack)
    val queue = remember(summary?.songs) { summary?.songs.orEmpty().map { it.track() } }
    Box(Modifier.fillMaxSize().background(colors.scaffoldBackground), contentAlignment = Alignment.TopCenter) {
    LazyColumn(state = listState, modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
        .statusBarsPadding().testTag("listening_stats_page"), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 180.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
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
    }
    }
}
private fun statsArtist(row: ListeningTotal): CatalogArtist = ArtistPhotoRepository.cachedArtist(row.artist).let {
    if (it.id.isBlank() && row.artistId.startsWith("UC")) it.copy(id = row.artistId) else it
}
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier, song: ListeningTotal? = null, artist: ListeningTotal? = null) {
    val colors = LocalAppColors.current
    Surface(modifier.aspectRatio(1f).testTag("stats_card_$label"), shape = RoundedCornerShape(22.dp),
        color = colors.cardBackground, shadowElevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = colors.textSecondary, style = MaterialTheme.typography.labelLarge)
            if (song != null || artist != null) StatsImage(song ?: artist!!, artist != null, Modifier.size(48.dp))
            Text(value, color = colors.textPrimary, style = if (song != null || artist != null) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable
private fun StatsImage(row: ListeningTotal, artist: Boolean, modifier: Modifier) {
    val clipped = modifier.clip(RoundedCornerShape(12.dp))
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
