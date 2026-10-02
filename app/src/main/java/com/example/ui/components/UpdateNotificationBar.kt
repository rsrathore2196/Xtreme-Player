package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.updater.AppUpdateManager
import com.example.updater.UpdateStatus

/**
 * Floating 3D Liquid Glass Auto-Update Pop-Up Bar on the Main Screen.
 * Displays update availability, direct APK download trigger, and session dismiss action ('X').
 */
@Composable
fun UpdateNotificationBar(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val updateStatus by AppUpdateManager.updateStatus.collectAsState()
    val isDismissed by AppUpdateManager.isDismissedForSession.collectAsState()
    val downloadProgress by AppUpdateManager.downloadProgress.collectAsState()

    val isVisible = !isDismissed && (
        updateStatus is UpdateStatus.UpdateAvailable ||
        updateStatus is UpdateStatus.Downloading ||
        updateStatus is UpdateStatus.ReadyToInstall
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f)
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f)
        ) + fadeOut(),
        modifier = modifier
    ) {
        val barShape = RoundedCornerShape(20.dp)

        Surface(
            shape = barShape,
            color = Color.Transparent,
            border = BorderStroke(
                1.3.dp,
                LiquidGlass.specularBorderBrush(
                    appColors,
                    highlightAlpha = if (appColors.isDark) 0.45f else 0.65f
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = barShape,
                    spotColor = appColors.primaryAccent.copy(alpha = 0.35f),
                    ambientColor = Color.Black.copy(alpha = 0.12f)
                )
                .clip(barShape)
                .testTag("auto_update_popup_bar")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LiquidGlass.miniPlayerAndBottomBarBrush(appColors))
            ) {
                // Top Specular Curvature Light Sheen
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (appColors.isDark) 0.22f else 0.38f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 28f
                            )
                        )
                )

                // Main Clickable Content Body
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            when (val status = updateStatus) {
                                is UpdateStatus.UpdateAvailable -> {
                                    AppUpdateManager.downloadAndInstallUpdate(context, status.updateInfo)
                                }
                                is UpdateStatus.ReadyToInstall -> {
                                    AppUpdateManager.installDownloadedApk(context, status.apkFile)
                                }
                                else -> {}
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Update Badge Icon
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        appColors.primaryAccent,
                                        appColors.secondaryAccent
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when (updateStatus) {
                            is UpdateStatus.Downloading -> {
                                CircularProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    color = Color.White,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            is UpdateStatus.ReadyToInstall -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Install update",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Update available",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Text Content
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        when (val status = updateStatus) {
                            is UpdateStatus.UpdateAvailable -> {
                                Text(
                                    text = "New Update Available! (v${status.updateInfo.versionName})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = appColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Tap to download and install now",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = appColors.primaryAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            is UpdateStatus.Downloading -> {
                                Text(
                                    text = "Downloading Update... $downloadProgress%",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = appColors.textPrimary,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = appColors.primaryAccent,
                                    trackColor = appColors.primaryAccent.copy(alpha = 0.20f)
                                )
                            }
                            is UpdateStatus.ReadyToInstall -> {
                                Text(
                                    text = "Download Complete!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = appColors.textPrimary,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Tap to complete installation",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.primaryAccent,
                                    maxLines = 1
                                )
                            }
                            else -> {}
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Dismiss Cross Button ('X') - touch target >= 48dp
                    IconButton(
                        onClick = { AppUpdateManager.dismissForSession() },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("dismiss_update_bar_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
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
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
