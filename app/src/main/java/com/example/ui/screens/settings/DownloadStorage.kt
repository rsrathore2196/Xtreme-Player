package com.example.ui.screens.settings

import android.content.Context
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** Storage on the same volume as permanent offline audio, without a storage permission. */
internal data class DeviceStorage(val totalBytes: Long, val availableBytes: Long) {
    companion object {
        fun read(context: Context): DeviceStorage = StatFs(context.noBackupFilesDir.absolutePath).let {
            DeviceStorage(it.totalBytes, it.availableBytes)
        }
    }
}

internal data class DownloadStorageUsage(val device: DeviceStorage, val downloadedBytes: Long) {
    val totalBytes = device.totalBytes.coerceAtLeast(0)
    val availableBytes = device.availableBytes.coerceIn(0, totalBytes)
    val usedBytes = totalBytes - availableBytes
    // A stale ledger must never overflow the bar or give other storage a negative size.
    val audioBytes = downloadedBytes.coerceIn(0, usedBytes)
    val otherBytes = usedBytes - audioBytes
    val usedFraction = fraction(usedBytes)
    val audioFraction = fraction(audioBytes)
    private fun fraction(bytes: Long) = if (totalBytes > 0) (bytes.toDouble() / totalBytes).toFloat() else 0f
}

@Composable
internal fun DownloadStorageSummary(downloadedBytes: Long, savedTracks: Int, hasPartialAudio: Boolean) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var device by remember(context) { mutableStateOf<DeviceStorage?>(null) }
    var loading by remember(context) { mutableStateOf(true) }
    // Refresh on first display, when saved audio changes, and after returning to the app.
    LaunchedEffect(context, lifecycle, downloadedBytes) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            device = withContext(Dispatchers.IO) { runCatching { DeviceStorage.read(context) }.getOrNull() }
            loading = false
            awaitCancellation()
        }
    }
    DownloadStorageCard(device, downloadedBytes, savedTracks, hasPartialAudio, loading)
}

@Composable
internal fun DownloadStorageCard(
    device: DeviceStorage?, downloadedBytes: Long, savedTracks: Int,
    hasPartialAudio: Boolean = false, loading: Boolean = false
) {
    val colors = LocalAppColors.current
    val usage = device?.takeIf { it.totalBytes > 0 }?.let { DownloadStorageUsage(it, downloadedBytes) }
    val audioColor = colors.primaryAccent
    val otherColor = colors.textSecondary.copy(alpha = if (colors.isDark) .45f else .3f)
    val availableColor = colors.cardBorder.copy(alpha = .5f)
    Column(Modifier.fillMaxWidth().testTag("download_storage_summary"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(audioColor.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Storage, null, Modifier.size(20.dp), tint = audioColor)
            }
            Column(Modifier.weight(1f)) {
                Text("Device storage", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(usage?.let { "${formatDownloadBytes(it.totalBytes)} total" }
                    ?: if (loading) "Checking storage…" else "Storage info unavailable",
                    color = colors.textSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("download_storage_total"))
            }
        }
        if (usage != null) {
            val description = "Device storage: ${formatDownloadBytes(usage.totalBytes)} total, " +
                "${formatDownloadBytes(downloadedBytes)} downloads, ${formatDownloadBytes(usage.availableBytes)} available"
            Canvas(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).testTag("download_storage_bar")
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(usage.usedFraction, 0f..1f)
                }) {
                drawRect(availableColor)
                drawRect(otherColor, size = Size(size.width * usage.usedFraction, size.height))
                drawRect(audioColor, size = Size(size.width * usage.audioFraction, size.height))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageMetric("Downloads", formatDownloadBytes(downloadedBytes), audioColor, Modifier.weight(1f))
            StorageMetric("Other used", usage?.let { formatDownloadBytes(it.otherBytes) } ?: "—", otherColor, Modifier.weight(1f))
            StorageMetric("Available", usage?.let { formatDownloadBytes(it.availableBytes) } ?: "—", availableColor, Modifier.weight(1f))
        }
        Text("$savedTracks ${if (savedTracks == 1) "track" else "tracks"} saved on this device" +
            if (hasPartialAudio) " · Includes in-progress audio" else "",
            color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StorageMetric(label: String, value: String, color: Color, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(label, color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Text(value, color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
