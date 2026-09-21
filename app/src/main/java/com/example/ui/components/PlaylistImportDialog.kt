package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.importer.MatchResult
import com.example.data.importer.MatchStatus
import com.example.data.importer.PlaylistImportSummary
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeBorder
import com.example.ui.theme.XtremeCard
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeGradients
import com.example.ui.theme.XtremeGreen
import com.example.ui.theme.XtremePurple
import com.example.ui.theme.XtremeRose
import com.example.ui.viewmodel.PlaylistImportStep
import com.example.ui.viewmodel.PlaylistImportUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistImportDialog(
    state: PlaylistImportUiState,
    isDarkMode: Boolean = false,
    onDismiss: () -> Unit,
    onInputChanged: (String) -> Unit,
    onPlatformChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onCustomTitleChanged: (String) -> Unit,
    onCustomDescriptionChanged: (String) -> Unit,
    onLoadSample: (String) -> Unit,
    onStartImport: () -> Unit,
    onSavePlaylist: (onSaved: (Long) -> Unit) -> Unit,
    onResetStep: () -> Unit,
    onOpenPlaylist: (Long) -> Unit
) {
    val context = LocalContext.current
    var showAdvancedAuth by remember { mutableStateOf(false) }

    val isDark = isDarkMode
    val appColors = LocalAppColors.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides appColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isDark) 0.75f else 0.45f))
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = appColors.cardBackground
                ),
                border = BorderStroke(1.dp, appColors.cardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .heightIn(max = 580.dp)
                    .testTag("playlist_import_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(18.dp)
                ) {
                    // TOP BAR
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Import Playlist",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Spotify, YouTube, Apple or Amazon Music",
                                style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted)
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("close_import_dialog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = appColors.textMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // DYNAMIC BODY BASED ON CURRENT STEP
                    when (val currentStep = state.step) {
                        is PlaylistImportStep.Idle -> {
                            ImportSetupContent(
                                state = state,
                                showAdvancedAuth = showAdvancedAuth,
                                onToggleAdvanced = { showAdvancedAuth = !showAdvancedAuth },
                                onInputChanged = onInputChanged,
                                onPlatformChanged = onPlatformChanged,
                                onTokenChanged = onTokenChanged,
                                onLoadSample = onLoadSample,
                                onPasteFromClipboard = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                    if (clipText.isNotBlank()) {
                                        onInputChanged(clipText)
                                    }
                                },
                                onStartImport = onStartImport
                            )
                        }

                        is PlaylistImportStep.Fetching -> {
                            ImportLoadingContent(
                                title = "Connecting to ${currentStep.platform}",
                                subtitle = currentStep.message,
                                isIndeterminate = true,
                                progress = 0f
                            )
                        }

                        is PlaylistImportStep.Matching -> {
                            ImportLoadingContent(
                                title = "Matching 320kbps Audio Streams",
                                subtitle = "Matching ${currentStep.current} of ${currentStep.total}: ${currentStep.currentTrackName}",
                                isIndeterminate = false,
                                progress = if (currentStep.total > 0) currentStep.current.toFloat() / currentStep.total else 0f,
                                matchedCount = currentStep.matchedCount
                            )
                        }

                        is PlaylistImportStep.Summary -> {
                            ImportSummaryContent(
                                summary = currentStep.summary,
                                customTitle = state.customTitle,
                                customDescription = state.customDescription,
                                onCustomTitleChanged = onCustomTitleChanged,
                                onCustomDescriptionChanged = onCustomDescriptionChanged,
                                onCancel = onResetStep,
                                onSave = {
                                    onSavePlaylist { playlistId ->
                                        // Handled by viewmodel
                                    }
                                }
                            )
                        }

                        is PlaylistImportStep.Saving -> {
                            ImportLoadingContent(
                                title = "Saving to Library",
                                subtitle = "Adding matched tracks to '${currentStep.title}'...",
                                isIndeterminate = true,
                                progress = 0f
                            )
                        }

                        is PlaylistImportStep.Success -> {
                            ImportSuccessContent(
                                playlistId = currentStep.playlistId,
                                playlistTitle = currentStep.playlistTitle,
                                trackCount = currentStep.trackCount,
                                onOpenPlaylist = {
                                    onOpenPlaylist(currentStep.playlistId)
                                    onDismiss()
                                },
                                onClose = onDismiss
                            )
                        }

                        is PlaylistImportStep.Error -> {
                            ImportErrorContent(
                                message = currentStep.message,
                                onRetry = onResetStep
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun ImportSetupContent(
    state: PlaylistImportUiState,
    showAdvancedAuth: Boolean,
    onToggleAdvanced: () -> Unit,
    onInputChanged: (String) -> Unit,
    onPlatformChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onLoadSample: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onStartImport: () -> Unit
) {
    val platforms = listOf("Link", "Spotify", "YouTube Music", "Apple Music", "Amazon Music", "CSV/Text")

    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .verticalScroll(rememberScrollState())
    ) {
        // Platform Selection Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            platforms.forEach { platform ->
                val isSelected = state.selectedPlatform == platform
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isSelected) {
                                appColors.primaryAccent.copy(alpha = if (isDark) 0.22f else 0.14f)
                            } else {
                                if (isDark) appColors.chipBackground else Color(0xFFF1F5F9)
                            }
                        )
                        .border(
                            1.dp,
                            if (isSelected) {
                                appColors.primaryAccent
                            } else {
                                if (isDark) appColors.chipBorder else Color(0xFFCBD5E1)
                            },
                            RoundedCornerShape(20.dp)
                        )
                        .clickable { onPlatformChanged(platform) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("platform_chip_$platform")
                ) {
                    Text(
                        text = platform,
                        color = if (isSelected) {
                            appColors.primaryAccent
                        } else {
                            if (isDark) appColors.textSecondary else Color(0xFF475569)
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Input Field
        val isCsv = state.selectedPlatform == "CSV/Text"
        val placeholder = when (state.selectedPlatform) {
            "Spotify" -> "Paste Spotify playlist URL (e.g. open.spotify.com/playlist/...)"
            "YouTube Music" -> "Paste YouTube Music playlist URL (e.g. music.youtube.com/playlist?list=...)"
            "Apple Music" -> "Paste Apple Music playlist URL (e.g. music.apple.com/.../playlist/...)"
            "Amazon Music" -> "Paste Amazon Music playlist URL (e.g. music.amazon.com/user-playlists/...)"
            "CSV/Text" -> "Paste lines: Song Title, Artist Name (or Artist - Title)"
            else -> "Paste playlist link from Spotify, YouTube, Apple Music, Amazon Music or CSV..."
        }

        OutlinedTextField(
            value = state.inputUrlOrText,
            onValueChange = onInputChanged,
            label = { Text(if (isCsv) "Track List / CSV" else "Playlist URL or Link", color = appColors.textMuted) },
            placeholder = { Text(placeholder, color = appColors.textMuted, fontSize = 13.sp) },
            singleLine = !isCsv,
            maxLines = if (isCsv) 6 else 1,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = appColors.primaryAccent,
                unfocusedBorderColor = appColors.cardBorder,
                focusedTextColor = appColors.textPrimary,
                unfocusedTextColor = appColors.textPrimary,
                cursorColor = appColors.primaryAccent,
                focusedLabelColor = appColors.primaryAccent,
                unfocusedLabelColor = appColors.textMuted,
                focusedContainerColor = appColors.inputBackground,
                unfocusedContainerColor = appColors.inputBackground
            ),
            trailingIcon = {
                if (state.inputUrlOrText.isNotBlank()) {
                    IconButton(onClick = { onInputChanged("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = appColors.textMuted)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isCsv) Modifier.height(140.dp) else Modifier)
                .testTag("playlist_input_field")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Paste from clipboard & sample options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPasteFromClipboard,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = appColors.primaryAccent
                ),
                border = BorderStroke(
                    1.dp,
                    appColors.primaryAccent.copy(alpha = 0.5f)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("paste_clipboard_button")
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Paste Link", fontSize = 12.sp)
            }

            // Sample tester
            Text(
                text = "Load Sample",
                color = appColors.primaryAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val samplePlatform = if (state.selectedPlatform == "Link") "Spotify" else state.selectedPlatform
                        onLoadSample(samplePlatform)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("load_sample_button")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Advanced Direct API Credentials Dropdown (Optional)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) appColors.cardBackgroundElevated else Color(0xFFF8FAFC)
            ),
            border = BorderStroke(
                1.dp,
                appColors.cardBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleAdvanced() }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = appColors.textMuted, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Direct API Token (Optional)",
                            color = appColors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Icon(
                        imageVector = if (showAdvancedAuth) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = appColors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (showAdvancedAuth) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "For private libraries: provide a Spotify Bearer token or YouTube Data API key. Public playlist links work automatically without any token.",
                        color = appColors.textMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.directApiKeyOrToken,
                        onValueChange = onTokenChanged,
                        placeholder = { Text("Bearer token / API key...", color = appColors.textMuted, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = appColors.primaryAccent,
                            unfocusedBorderColor = appColors.cardBorder,
                            focusedTextColor = appColors.textPrimary,
                            unfocusedTextColor = appColors.textPrimary,
                            cursorColor = appColors.primaryAccent,
                            focusedContainerColor = if (isDark) appColors.inputBackground else Color(0xFFFFFFFF),
                            unfocusedContainerColor = if (isDark) appColors.inputBackground else Color(0xFFFFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Bottom Start Button
        Button(
            onClick = onStartImport,
            enabled = state.inputUrlOrText.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = appColors.primaryAccent,
                contentColor = if (isDark) Color(0xFF101014) else Color.White,
                disabledContainerColor = if (isDark) appColors.cardBorder.copy(alpha = 0.5f) else Color(0xFFE2E8F0),
                disabledContentColor = appColors.textMuted
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("start_import_button")
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Extract & Match Tracks", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ImportLoadingContent(
    title: String,
    subtitle: String,
    isIndeterminate: Boolean,
    progress: Float,
    matchedCount: Int = 0
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = appColors.primaryAccent,
                strokeWidth = 4.dp,
                modifier = Modifier.size(72.dp)
            )
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = appColors.primaryAccent,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = appColors.textPrimary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(color = appColors.textSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (isIndeterminate) {
            LinearProgressIndicator(
                color = appColors.primaryAccent,
                trackColor = if (isDark) appColors.cardBorder else Color(0xFFE2E8F0),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = appColors.primaryAccent,
                    trackColor = if (isDark) appColors.cardBorder else Color(0xFFE2E8F0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${(progress * 100).toInt()}% • $matchedCount tracks matched in 320kbps",
                    color = if (isDark) XtremeGreen else Color(0xFF059669),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ImportSummaryContent(
    summary: PlaylistImportSummary,
    customTitle: String,
    customDescription: String,
    onCustomTitleChanged: (String) -> Unit,
    onCustomDescriptionChanged: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    var selectedSummaryTab by remember { mutableIntStateOf(0) } // 0 = Matched, 1 = Unmatched
    var isEditingTitle by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
    ) {
        // PLAYLIST HEADER PREVIEW CARD
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) appColors.cardBackgroundElevated else Color(0xFFF8FAFC)
            ),
            border = BorderStroke(1.dp, appColors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = summary.coverUrl.ifBlank { "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80" },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (isEditingTitle) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = customTitle.ifBlank { summary.playlistTitle },
                                onValueChange = onCustomTitleChanged,
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDark) appColors.inputBackground else Color.White)
                                    .border(1.dp, appColors.primaryAccent, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(appColors.primaryAccent)
                                    .clickable { isEditingTitle = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Save title",
                                    tint = if (isDark) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = customTitle.ifBlank { summary.playlistTitle },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) Color(0xFF27272A) else Color(0xFFE2E8F0))
                                    .clickable { isEditingTitle = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit playlist title",
                                    tint = appColors.textSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Source: ${summary.platform} • ${summary.totalItems} tracks extracted",
                        style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isDark) XtremeGreen.copy(alpha = 0.2f) else Color(0xFFD1FAE5)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "✓ ${summary.matchedItems.size} Matched (${summary.matchRatePercent}%)",
                                color = if (isDark) XtremeGreen else Color(0xFF059669),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (summary.unmatchedItems.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isDark) Color(0xFFFFA726).copy(alpha = 0.2f) else Color(0xFFFEF3C7)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                                Text(
                                    text = "${summary.unmatchedItems.size} Unmatched",
                                    color = if (isDark) Color(0xFFFFA726) else Color(0xFFD97706),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Toggle Tabs: Matched vs Unmatched
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isDark) appColors.chipBackground else Color(0xFFF1F5F9))
                .padding(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedSummaryTab == 0) {
                            appColors.primaryAccent.copy(alpha = if (isDark) 0.22f else 0.12f)
                        } else Color.Transparent
                    )
                    .clickable { selectedSummaryTab = 0 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Matched Tracks (${summary.matchedItems.size})",
                    color = if (selectedSummaryTab == 0) {
                        appColors.primaryAccent
                    } else appColors.textMuted,
                    fontWeight = if (selectedSummaryTab == 0) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedSummaryTab == 1) {
                            if (isDark) Color(0xFFFFA726).copy(alpha = 0.2f) else Color(0xFFFEF3C7)
                        } else Color.Transparent
                    )
                    .clickable { selectedSummaryTab = 1 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Unmatched (${summary.unmatchedItems.size})",
                    color = if (selectedSummaryTab == 1) {
                        if (isDark) Color(0xFFFFA726) else Color(0xFFD97706)
                    } else appColors.textMuted,
                    fontWeight = if (selectedSummaryTab == 1) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // LIST OF TRACKS
        val displayList = if (selectedSummaryTab == 0) summary.matchedItems else summary.unmatchedItems

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isDark) appColors.inputBackground else Color(0xFFF8FAFC)),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(displayList) { item ->
                TrackMatchRow(item = item)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ACTION BUTTONS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isDark) appColors.textSecondary else Color(0xFF475569)
                ),
                border = BorderStroke(1.dp, appColors.cardBorder),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(0.9f)
                    .height(48.dp)
                    .testTag("summary_back_button")
            ) {
                Text("Back", fontSize = 14.sp)
            }

            Button(
                onClick = onSave,
                enabled = summary.matchedItems.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = appColors.primaryAccent,
                    contentColor = if (isDark) Color(0xFF101014) else Color.White,
                    disabledContainerColor = if (isDark) appColors.cardBorder.copy(alpha = 0.5f) else Color(0xFFE2E8F0),
                    disabledContentColor = appColors.textMuted
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(2.1f)
                    .height(48.dp)
                    .testTag("save_imported_playlist_button")
            ) {
                Icon(Icons.Default.DownloadDone, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Save to Library",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TrackMatchRow(item: MatchResult) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) appColors.cardBackgroundElevated else Color(0xFFFFFFFF)
        ),
        border = BorderStroke(1.dp, appColors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.matchedTrack != null) {
                AsyncImage(
                    model = item.matchedTrack.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDark) appColors.chipBackground else Color(0xFFF1F5F9)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = appColors.textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.matchedTrack?.title ?: item.original.originalTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = appColors.textPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.matchedTrack?.artist ?: item.original.originalArtist,
                    style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (item.matchStatus != MatchStatus.NOT_FOUND && item.matchedTrack != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isDark) XtremeGreen.copy(alpha = 0.15f) else Color(0xFFD1FAE5)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.matchedTrack.qualityBadge,
                            color = if (isDark) XtremeGreen else Color(0xFF059669),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.confidenceScore.toInt()}% match",
                        color = appColors.textMuted,
                        fontSize = 10.sp
                    )
                }
            } else {
                Text(
                    text = "Not Found",
                    color = if (isDark) Color(0xFFFFA726) else Color(0xFFD97706),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ImportSuccessContent(
    playlistId: Long,
    playlistTitle: String,
    trackCount: Int,
    onOpenPlaylist: () -> Unit,
    onClose: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 30.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (isDark) XtremeGreen.copy(alpha = 0.2f) else Color(0xFFD1FAE5)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isDark) XtremeGreen else Color(0xFF059669),
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Playlist Imported!",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = appColors.textPrimary
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "'$playlistTitle' with $trackCount matched tracks is now saved in your Library.",
            style = MaterialTheme.typography.bodyMedium.copy(color = appColors.textSecondary),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onOpenPlaylist,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = appColors.primaryAccent,
                contentColor = if (isDark) Color(0xFF101014) else Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("open_imported_playlist_button")
        ) {
            Text("Open Playlist Now", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onClose,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isDark) appColors.textSecondary else Color(0xFF475569)
            ),
            border = BorderStroke(1.dp, appColors.cardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Done")
        }
    }
}

@Composable
private fun ImportErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 30.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (isDark) Color(0xFFFF5252).copy(alpha = 0.2f) else Color(0xFFFEE2E2)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (isDark) Color(0xFFFF5252) else Color(0xFFDC2626),
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Import Failed",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = appColors.textPrimary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = appColors.primaryAccent,
                contentColor = if (isDark) Color(0xFF101014) else Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("retry_import_button")
        ) {
            Text("Try Again", fontWeight = FontWeight.Bold)
        }
    }
}
