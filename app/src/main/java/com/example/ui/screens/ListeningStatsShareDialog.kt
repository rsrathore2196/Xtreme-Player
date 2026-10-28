package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.UserProfilePreferences
import com.example.performance.AppDispatchers
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.ListeningStatsImage
import com.example.ui.util.ListeningStatsImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ListeningStatsShareDialog(request: ListeningStatsImageRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val colors = LocalAppColors.current
    val foreground = if (colors.isDark) Color.White else Color.Black
    val scope = rememberCoroutineScope()
    var image by remember(request) { mutableStateOf<File?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var retry by remember(request) { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { destination ->
        val source = pendingSave
        pendingSave = null
        if (destination != null && source != null) scope.launch {
            saving = true
            try {
                ListeningStatsImage.save(app, source, destination)
                Toast.makeText(context, "Stats image saved", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { error = "The image couldn't be saved. Please choose another location." }
            finally { saving = false }
        }
    }
    LaunchedEffect(request, retry) {
        image = null; error = null
        try {
            val name = withContext(AppDispatchers.Io) { UserProfilePreferences.getUserProfile(app).name }
            image = ListeningStatsImage.create(app, request, name)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { error = "The stats image couldn't be created. Please retry." }
    }
    val previewHeight = (LocalConfiguration.current.screenHeightDp * .85f - 180f).coerceIn(48f, 360f).dp
    val previewRequest = remember(context, image) {
        image?.let { ImageRequest.Builder(context).data(it).allowHardware(true)
            .interceptorDispatcher(AppDispatchers.Io).fetcherDispatcher(AppDispatchers.Io)
            .decoderDispatcher(AppDispatchers.CpuBounded).build() }
    }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(shape = RoundedCornerShape(28.dp), color = if (colors.isDark) Color.Black else Color.White,
            modifier = Modifier.fillMaxWidth().testTag("listening_stats_share_popup")) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Share the stats", Modifier.weight(1f), color = foreground,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = foreground)
                    }
                }
                Box(Modifier.height(previewHeight).aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF020A18)),
                    contentAlignment = Alignment.Center) {
                    if (previewRequest != null) AsyncImage(previewRequest, "Listening stats image preview",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else if (error == null) CircularProgressIndicator(Modifier.size(28.dp), color = colors.primaryAccent)
                }
                error?.let { message ->
                    Text(message, color = foreground, style = MaterialTheme.typography.bodySmall)
                    if (image == null) TextButton(onClick = { retry++ }) { Text("Retry", color = foreground) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        pendingSave = image
                        save.launch("Xtreme-Player-" + request.period.label + "-Stats.png")
                    }, enabled = image != null && !saving && !launching, modifier = Modifier.weight(1f).testTag("save_stats_image"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (saving) "Saving…" else "Save image", maxLines = 1)
                    }
                    Button(onClick = {
                        image?.let { file ->
                            scope.launch {
                                launching = true
                                try {
                                    val intent = withContext(AppDispatchers.Io) { ListeningStatsImage.shareIntent(app, file) }
                                    context.startActivity(Intent.createChooser(intent, "Share listening stats"))
                                } catch (cancelled: CancellationException) { throw cancelled }
                                  catch (_: Exception) { error = "No app is available to share the image." }
                                finally { launching = false }
                            }
                        }
                    }, enabled = image != null && !saving && !launching, modifier = Modifier.weight(1f).testTag("share_stats_image"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primaryAccent, contentColor = colors.onPrimaryAccent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share", maxLines = 1)
                    }
                }
            }
        }
    }
}
