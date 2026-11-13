package com.example.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun DownloadsPage(modifier: Modifier = Modifier, store: OfflineDownloads = OfflineDownloads.get(LocalContext.current)) {
    val records by store.records.collectAsStateWithLifecycle()
    val preferences by store.preferences.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = LocalAppColors.current
    var error by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Downloads could not be updated. Check available storage and try again." }
    } }
    LaunchedEffect(store) {
        try { store.load() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Saved downloads could not be loaded." }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Remove all downloads?") },
        text = { Text("This removes saved audio from this device. Your playlists stay in your library.") },
        confirmButton = { TextButton(onClick = { clear = false; action { store.clear() } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
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
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = colors.cardBorder)
                    DownloadStorageSummary(
                        downloadedBytes = records.values.sumOf { it.bytes.coerceAtLeast(0) },
                        savedTracks = records.values.count { it.status == DownloadStatus.COMPLETE },
                        hasPartialAudio = records.values.any { it.status != DownloadStatus.COMPLETE && it.bytes > 0 }
                    )
                }
            }
        }
        error?.let { message -> item { Text(message, color = colors.primaryAccent) } }
        if (records.isNotEmpty()) item {
            TextButton(onClick = { clear = true }, modifier = Modifier.testTag("clear_downloads")) { Text("Remove all downloads") }
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
