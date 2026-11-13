package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.floor

internal data class CollectionDownloadAction(
    val progress: CollectionDownloadProgress, val loading: Boolean, val error: Boolean,
    val enabled: Boolean, val description: String, val onDownload: () -> Unit
)

/** Lives outside lazy headers so scrolling cannot cancel collection preparation. */
@Composable
internal fun rememberCollectionDownloadAction(
    tracks: List<MusicTrack>, name: String, collectionKey: String,
    collectionComplete: Boolean = true, loadTracks: (suspend () -> List<MusicTrack>)? = null,
    store: OfflineDownloads = OfflineDownloads.get(LocalContext.current)
): CollectionDownloadAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(collectionKey) { mutableStateOf(false) }
    var error by remember(collectionKey) { mutableStateOf(false) }
    LaunchedEffect(store) {
        try { store.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    val progress by remember(store, tracks, collectionKey, collectionComplete) {
        store.observeCollectionProgress(tracks, collectionKey, collectionComplete)
    }.collectAsStateWithLifecycle(CollectionDownloadProgress(null, 0, tracks.size, false, false))
    val busy = loading || progress.active
    val percentage = progress.fraction?.let { floor(it * 100).toInt().coerceIn(0, if (progress.complete) 100 else 99) }
    val description = when {
        loading -> "Preparing download"
        progress.complete -> "Downloaded · ${progress.completed} tracks saved"
        busy -> "Downloading${percentage?.let { " · $it%" }.orEmpty()} · ${progress.completed} of ${progress.total} tracks saved"
        error -> "Could not start download. Tap to retry"
        percentage != null && percentage > 0 -> "Resume download · $percentage% · ${progress.completed} of ${progress.total} tracks saved"
        else -> "Download $name"
    }
    return CollectionDownloadAction(progress, loading, error,
        (tracks.isNotEmpty() || loadTracks != null) && !progress.complete && !busy, description) {
        scope.launch {
            loading = true
            error = false
            try { store.enqueue(loadTracks?.invoke() ?: tracks, name, collectionKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                error = true
                Toast.makeText(context, "Could not start download. Tap the download icon to retry.", Toast.LENGTH_SHORT).show()
            } finally { loading = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionDownloadButton(action: CollectionDownloadAction, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(action.description) } }, state = rememberTooltipState()) {
        IconButton(onClick = action.onDownload, enabled = action.enabled,
            modifier = modifier.size(48.dp).testTag("download_collection")) {
            Box(Modifier.size(44.dp).liquidGlassButton(colors, shape = CircleShape, elevation = 3.dp),
                contentAlignment = Alignment.Center) {
                val progress = action.progress
                val showRing = action.loading || progress.active || progress.complete || (progress.fraction ?: 0f) > 0f
                if (showRing) {
                    val ring = Modifier.matchParentSize().padding(2.dp).testTag("collection_download_progress")
                    if (action.loading || progress.fraction == null) {
                        VisibleCircularProgressIndicator(modifier = ring, color = colors.primaryAccent, strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(progress = { progress.fraction }, modifier = ring,
                            color = colors.primaryAccent, strokeWidth = 2.dp,
                            trackColor = colors.primaryAccent.copy(alpha = .18f), gapSize = 0.dp)
                    }
                }
                Icon(when { progress.complete -> Icons.Default.DownloadDone; action.error -> Icons.Default.Refresh
                    else -> Icons.Default.Download }, contentDescription = action.description,
                    tint = colors.primaryAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
