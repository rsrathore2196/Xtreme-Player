package com.example.ui.components

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.updater.AppUpdateManager
import com.example.updater.UpdateStatus

/**
 * Compact Version Update Pop-up Dialog.
 * Displays app name and version number at top centre, scrollable changelog list,
 * and a pill-shaped download button that transforms into install action.
 */
@Composable
fun UpdateNotificationBar(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val updateStatus by AppUpdateManager.updateStatus.collectAsStateWithLifecycle()
    val isDismissed by AppUpdateManager.isDismissedForSession.collectAsStateWithLifecycle()
    val downloadProgress by AppUpdateManager.downloadProgress.collectAsStateWithLifecycle()

    val isVisible = !isDismissed && (
        updateStatus is UpdateStatus.UpdateAvailable ||
        updateStatus is UpdateStatus.Downloading ||
        updateStatus is UpdateStatus.ReadyToInstall ||
        (updateStatus as? UpdateStatus.Error)?.showNotice == true
    )

    if (!isVisible) return

    val updateInfo = when (val s = updateStatus) {
        is UpdateStatus.UpdateAvailable -> s.updateInfo
        is UpdateStatus.Downloading -> s.updateInfo
        is UpdateStatus.ReadyToInstall -> s.updateInfo
        else -> null
    }

    Dialog(
        onDismissRequest = { AppUpdateManager.dismissForSession() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val popupShape = RoundedCornerShape(28.dp)

        Surface(
            shape = popupShape,
            color = appColors.cardBackgroundElevated,
            border = BorderStroke(
                1.5.dp,
                LiquidGlass.specularBorderBrush(
                    appColors,
                    highlightAlpha = if (appColors.isDark) 0.5f else 0.7f
                )
            ),
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.9f)
                .shadow(
                    elevation = 24.dp,
                    shape = popupShape,
                    spotColor = appColors.primaryAccent.copy(alpha = 0.45f),
                    ambientColor = Color.Black.copy(alpha = 0.25f)
                )
                .clip(popupShape)
                .testTag("auto_update_popup_dialog")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LiquidGlass.miniPlayerAndBottomBarBrush(appColors))
            ) {
                // Top specular gradient sheen
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (appColors.isDark) 0.18f else 0.35f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 48f
                            )
                        )
                )

                // Dismiss Cross Button ('X') in top-right
                IconButton(
                    onClick = { AppUpdateManager.dismissForSession() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(36.dp)
                        .testTag("dismiss_update_bar_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (appColors.isDark) Color.White.copy(alpha = 0.12f)
                                else Color.Black.copy(alpha = 0.08f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss update notice",
                            tint = appColors.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Main Pop-up Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Centre: App Name
                    Text(
                        text = "Xtreme Player",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = appColors.textPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Top Centre: Version Number
                    val versionText = updateInfo?.let { "v${it.versionName}" } ?: "v${BuildConfig.VERSION_NAME}"
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(appColors.primaryAccent.copy(alpha = 0.16f))
                            .border(
                                BorderStroke(1.dp, appColors.primaryAccent.copy(alpha = 0.4f)),
                                CircleShape
                            )
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = versionText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.primaryAccent
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Changelog Section Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Changelog",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Changelog Scrollable List
                    val changelogLines = remember(updateInfo) {
                        val rawNotes = updateInfo?.releaseNotes?.trim().orEmpty()
                        if (rawNotes.isNotBlank()) {
                            rawNotes.lines().filter { it.isNotBlank() }
                        } else {
                            val rawTitle = updateInfo?.releaseTitle?.trim().orEmpty()
                            if (rawTitle.isNotBlank()) {
                                listOf(rawTitle)
                            } else {
                                listOf(
                                    "• Performance improvements and optimizations",
                                    "• Bug fixes and stability improvements",
                                    "• Enhanced audio playback and UI refinements"
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = 190.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (appColors.isDark) Color.Black.copy(alpha = 0.28f)
                                else Color.White.copy(alpha = 0.45f)
                            )
                            .border(
                                BorderStroke(1.dp, appColors.cardBorder.copy(alpha = 0.5f)),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            changelogLines.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = appColors.textSecondary,
                                        lineHeight = 18.sp
                                    )
                                )
                            }
                        }
                    }

                    // Download Progress Bar (When Downloading)
                    if (updateStatus is UpdateStatus.Downloading) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Downloading update...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = appColors.textSecondary
                                )
                                Text(
                                    text = "$downloadProgress%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.primaryAccent
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = appColors.primaryAccent,
                                trackColor = appColors.primaryAccent.copy(alpha = 0.20f)
                            )
                        }
                    } else if (updateStatus is UpdateStatus.Error) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = (updateStatus as UpdateStatus.Error).message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Bottom Pill-shaped Download / Install Button
                    Button(
                        onClick = {
                            when (val status = updateStatus) {
                                is UpdateStatus.UpdateAvailable -> {
                                    AppUpdateManager.downloadAndInstallUpdate(context, status.updateInfo)
                                }
                                is UpdateStatus.ReadyToInstall -> {
                                    AppUpdateManager.installDownloadedApk(context, status.apkFile)
                                }
                                is UpdateStatus.Error -> {
                                    AppUpdateManager.checkForUpdate(context, isManual = true)
                                }
                                else -> {}
                            }
                        },
                        enabled = updateStatus !is UpdateStatus.Downloading,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = appColors.primaryAccent,
                            contentColor = appColors.onPrimaryAccent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("update_action_pill_button")
                    ) {
                        when (updateStatus) {
                            is UpdateStatus.Downloading -> {
                                CircularProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    color = appColors.onPrimaryAccent,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Downloading $downloadProgress%", fontWeight = FontWeight.Bold)
                            }
                            is UpdateStatus.ReadyToInstall -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Install update",
                                    tint = appColors.onPrimaryAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Install", fontWeight = FontWeight.Bold)
                            }
                            is UpdateStatus.Error -> {
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download update",
                                    tint = appColors.onPrimaryAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
