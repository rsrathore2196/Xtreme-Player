package com.example.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.XtremeMusicApp
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DownloadsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { OfflineDownloads.get(context) }
    val records by store.records.collectAsStateWithLifecycle()
    val preferences by store.preferences.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = LocalAppColors.current
    var error by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var qualityMenu by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Downloads could not be updated. Check available storage and try again." }
    } }
    fun playSaved(selected: MusicTrack, queue: List<MusicTrack>) { action {
        if (store.localTrack(selected) == null) { error = "Saved audio is missing. Download this track again."; return@action }
        val offline = store.localQueue(queue)
        val current = offline.firstOrNull { it.id == selected.id } ?: return@action
        (context.applicationContext as XtremeMusicApp).playbackManager.playTrack(current, offline, isExplicitPlaylist = true)
    } }
    LaunchedEffect(store) { try { store.load() } catch (_: Exception) { error = "Saved downloads could not be loaded." } }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Remove all downloads?") },
        text = { Text("This removes saved audio from this device. Your playlists stay in your library.") },
        confirmButton = { TextButton(onClick = { clear = false; action { store.clear() } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
    val sorted = remember(records, query, filter) { records.values.filter {
        (filter == "All" || if (filter == "Downloaded") it.status == DownloadStatus.COMPLETE else it.status != DownloadStatus.COMPLETE) &&
            (query.isBlank() || "${it.track.title} ${it.track.artist} ${it.collection}".contains(query, true))
    }.sortedByDescending { it.addedAt } }
    LazyColumn(modifier.fillMaxSize().testTag("downloads_page"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Listen anywhere", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Save tracks from their menu, or download an entire album or playlist. Saved audio stays available without internet.",
                color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = colors.cardBackground) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wi-Fi only", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Wait for an unmetered connection", color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(preferences.wifiOnly, { value -> action { store.setPreferences(preferences.copy(wifiOnly = value)) } },
                            modifier = Modifier.testTag("downloads_wifi_only"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.cardBorder)
                    Box {
                        Row(Modifier.fillMaxWidth().clickable { qualityMenu = true }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Download quality", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(preferences.quality.label, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ExpandMore, null, tint = colors.textSecondary)
                        }
                        DropdownMenu(qualityMenu, { qualityMenu = false }) {
                            DownloadQuality.entries.forEach { quality -> DropdownMenuItem(text = { Text(quality.label) }, onClick = {
                                qualityMenu = false; action { store.setPreferences(preferences.copy(quality = quality)) }
                            }) }
                        }
                    }
                    Text("${records.values.count { it.status == DownloadStatus.COMPLETE }} tracks saved · ${formatDownloadBytes(records.values.sumOf { it.bytes })}",
                        color = colors.textMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        error?.let { message -> item { Text(message, color = colors.primaryAccent) } }
        if (records.isNotEmpty()) {
            item { OutlinedTextField(query, { query = it }, label = { Text("Search downloads") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Downloaded", "In progress").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
            } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { val saved = sorted.filter { it.status == DownloadStatus.COMPLETE }.map { it.track }
                    saved.firstOrNull()?.let { playSaved(it, saved) } },
                    enabled = sorted.any { it.status == DownloadStatus.COMPLETE }) { Text("Play downloaded") }
                TextButton(onClick = { clear = true }) { Text("Remove all") }
            } }
        }
        if (sorted.isEmpty()) item { Text(if (records.isEmpty()) "Your downloaded music will appear here." else "No matching downloads.",
            color = colors.textSecondary, modifier = Modifier.padding(vertical = 28.dp)) }
        items(sorted, key = { it.track.id }) { record ->
            Surface(shape = RoundedCornerShape(16.dp), color = colors.cardBackground,
                modifier = Modifier.testTag("download_row_${record.track.id}")) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(record.artwork.takeIf { it.isNotBlank() }?.let(::File) ?: record.track.coverUrl,
                            null, Modifier.size(48.dp), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(record.track.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold)
                            Text(record.track.artist, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                            if (record.collection.isNotBlank()) Text(record.collection, color = colors.textMuted,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when (record.status) {
                            DownloadStatus.COMPLETE -> IconButton(onClick = {
                                playSaved(record.track, sorted.filter { it.status == DownloadStatus.COMPLETE }.map { it.track })
                            }) { Icon(Icons.Default.PlayArrow, "Play ${record.track.title}", tint = colors.primaryAccent) }
                            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> IconButton(onClick = { action { store.pause(record.track.id) } }) {
                                Icon(Icons.Default.Pause, "Pause download", tint = colors.textPrimary) }
                            else -> IconButton(onClick = { action { store.resume(record.track.id) } }) {
                                Icon(Icons.Default.Download, "Retry or resume download", tint = colors.primaryAccent) }
                        }
                        IconButton(onClick = { action { store.remove(record.track.id) } }) {
                            Icon(Icons.Default.Close, "Remove download", tint = colors.textMuted) }
                    }
                    Text(when (record.status) {
                        DownloadStatus.COMPLETE -> "Downloaded · ${record.track.qualityBadge} · ${formatDownloadBytes(record.bytes)}"
                        DownloadStatus.QUEUED -> if (preferences.wifiOnly) "Queued · waiting for Wi-Fi or an available slot" else "Queued"
                        DownloadStatus.PAUSED -> "Paused"
                        DownloadStatus.FAILED -> record.error ?: "Download failed. Tap retry."
                        DownloadStatus.DOWNLOADING -> if (record.total > 0) "Downloading · ${(record.fraction * 100).toInt()}%" else "Downloading · ${formatDownloadBytes(record.bytes)}"
                    }, color = colors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                    if (record.status == DownloadStatus.DOWNLOADING) {
                        if (record.total > 0) LinearProgressIndicator(progress = { record.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

internal fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "${bytes.coerceAtLeast(0) / 1024} KB"
}

@Composable
fun DownloadCollectionButton(tracks: List<MusicTrack>, name: String, modifier: Modifier = Modifier,
    collectionComplete: Boolean = true, loadTracks: (suspend () -> List<MusicTrack>)? = null) {
    val context = LocalContext.current
    val store = remember(context) { OfflineDownloads.get(context) }
    val records by store.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val complete = collectionComplete && tracks.isNotEmpty() && tracks.all { records[it.id]?.status == DownloadStatus.COMPLETE }
    val busy = loading || tracks.any { records[it.id]?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) }
    TextButton(onClick = { scope.launch { loading = true
        try { store.enqueue(loadTracks?.invoke() ?: tracks, name); error = false }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = true } finally { loading = false } } },
        enabled = (tracks.isNotEmpty() || loadTracks != null) && !complete && !busy,
        modifier = modifier.testTag("download_collection")) {
        Icon(if (complete) Icons.Default.DownloadDone else Icons.Default.Download, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(when { error -> "Could not queue. Retry"; complete -> "Downloaded"; busy -> "Downloading"; else -> "Download" })
    }
}
