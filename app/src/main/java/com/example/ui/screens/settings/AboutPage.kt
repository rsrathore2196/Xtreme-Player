package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.contrastingContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.bouncyClickable

@Composable
fun AboutPage(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent
    val dividerColor = appColors.dividerColor

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // App Hero Branding Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App Logo Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    accentColor,
                                    appColors.secondaryAccent
                                )
                            )
                        )
                        .border(
                            BorderStroke(1.5.dp, Color.White.copy(alpha = 0.3f)),
                            RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Xtreme Player Logo",
                        modifier = Modifier.size(56.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Xtreme Player",
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    color = appColors.textPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier.liquidGlassPill(
                        colors = appColors,
                        shape = RoundedCornerShape(20.dp),
                        isActive = true,
                        elevation = 2.dp
                    )
                ) {
                    Text(
                        text = "VERSION $APP_VERSION • AUDIO ENGINE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = appColors.onPrimaryAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "High-performance studio-grade music player engineered for audiophiles. Delivering 320 kbps ultra-high definition streaming, real-time 5-band parametric equalization, dynamic bass enhancement, and 3D spatial audio.",
                    fontSize = 12.5.sp,
                    color = appColors.textMuted,
                    lineHeight = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Information & Developer Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // App Version
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Version",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = APP_VERSION,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            textAlign = TextAlign.End
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Check for Update Action Row / Button
                val context = LocalContext.current
                val updateStatus by com.example.updater.AppUpdateManager.updateStatus.collectAsState()
                val downloadProgress by com.example.updater.AppUpdateManager.downloadProgress.collectAsState()
                var showReleaseNotesDialog by remember { mutableStateOf(false) }

                val isChecking = updateStatus is com.example.updater.UpdateStatus.Checking
                val isDownloading = updateStatus is com.example.updater.UpdateStatus.Downloading
                val isAvailable = updateStatus is com.example.updater.UpdateStatus.UpdateAvailable
                val isReadyToInstall = updateStatus is com.example.updater.UpdateStatus.ReadyToInstall
                val availableInfo = (updateStatus as? com.example.updater.UpdateStatus.UpdateAvailable)?.updateInfo

                Surface(
                    onClick = {
                        when (val status = updateStatus) {
                            is com.example.updater.UpdateStatus.UpdateAvailable -> {
                                showReleaseNotesDialog = true
                            }
                            is com.example.updater.UpdateStatus.ReadyToInstall -> {
                                com.example.updater.AppUpdateManager.installDownloadedApk(context, status.apkFile)
                            }
                            is com.example.updater.UpdateStatus.Checking,
                            is com.example.updater.UpdateStatus.Downloading -> {
                                // Already in progress
                            }
                            else -> {
                                com.example.updater.AppUpdateManager.checkForUpdate(
                                    context = context,
                                    isManual = true
                                ) { result ->
                                    if (result is com.example.updater.UpdateStatus.UpToDate) {
                                        Toast.makeText(
                                            context,
                                            "Xtreme Player is up to date (v$APP_VERSION)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (result is com.example.updater.UpdateStatus.Error) {
                                        Toast.makeText(
                                            context,
                                            result.message,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isAvailable || isReadyToInstall) accentColor else (if (appColors.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)),
                    border = BorderStroke(
                        1.dp,
                        if (isAvailable || isReadyToInstall) accentColor
                        else if (appColors.isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("check_for_update_button")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isChecking) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = accentColor
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Checking for update...",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.textPrimary
                                )
                            }
                        } else if (isDownloading) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Downloading Update...",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = appColors.textPrimary
                                    )
                                    Text(
                                        text = "$downloadProgress%",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = accentColor,
                                    trackColor = accentColor.copy(alpha = 0.20f)
                                )
                            }
                        } else if (isReadyToInstall) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Install Update",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        } else if (isAvailable && availableInfo != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Download Update v${availableInfo.versionName}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Check for Update",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.textPrimary
                                )
                            }
                        }
                    }
                }

                // Modal Dialog for Update Available with Release Notes
                if (showReleaseNotesDialog && availableInfo != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showReleaseNotesDialog = false },
                        containerColor = appColors.cardBackgroundElevated,
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Update Available",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary
                                )
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Version v${availableInfo.versionName} is ready to install.",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.textPrimary
                                )
                                if (availableInfo.releaseTitle.isNotBlank() && availableInfo.releaseTitle != availableInfo.versionName) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = availableInfo.releaseTitle,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = accentColor
                                    )
                                }
                                if (availableInfo.releaseNotes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Release Notes:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = appColors.textMuted
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (appColors.isDark) Color.Black.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.05f))
                                            .padding(10.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = availableInfo.releaseNotes,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            color = appColors.textSecondary
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showReleaseNotesDialog = false
                                    com.example.updater.AppUpdateManager.downloadAndInstallUpdate(context, availableInfo)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Download Now",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showReleaseNotesDialog = false }
                            ) {
                                Text(
                                    text = "Later",
                                    color = appColors.textMuted
                                )
                            }
                        }
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // Developer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Developer",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    }

                    Text(
                        text = "RS Rathore",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        textAlign = TextAlign.End
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // Build Verification
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Build Verification",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDarkMode) Color(0xFF0F3A2A) else Color(0xFFD1FAE5),
                        border = BorderStroke(1.dp, if (isDarkMode) Color(0xFF10B981) else Color(0xFF059669))
                    ) {
                        Text(
                            text = "STABLE",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            color = if (isDarkMode) Color(0xFF34D399) else Color(0xFF059669),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // Audio Engine Architecture
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Audio Architecture",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    }

                    Text(
                        text = "16-Bit PCM / 5-Band DSP",
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appColors.textMuted,
                        textAlign = TextAlign.End
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Support & Donation Card (Last Section)
        SupportDonationCard(
            isDarkMode = isDarkMode,
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = accentColor,
            dividerColor = dividerColor
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SupportDonationCard(
    isDarkMode: Boolean,
    cardBg: Color,
    cardBorder: Color,
    accentColor: Color,
    dividerColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current

    val upiId = "rajaasingh88-4@okicici"
    val presetAmounts = listOf("50", "100", "250", "Custom")
    var selectedPreset by remember { mutableStateOf("100") }
    var customAmount by remember { mutableStateOf("") }

    val innerBg = appColors.cardBackgroundElevated
    val innerBorder = appColors.cardBorder

    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header: Title & Tagline
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFFF9800),
                                    Color(0xFFE65100)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Support Development",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "Support Development",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = appColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Support me by any amount if you like my work",
                        fontSize = 12.sp,
                        color = appColors.textMuted,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ================= INDIAN (UPI) DONATION =================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(innerBg)
                    .border(BorderStroke(1.dp, innerBorder), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                // Indian Badge & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "🇮🇳", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Indian Supporters",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = appColors.textPrimary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Instant payment via Google Pay, PhonePe, Paytm, BHIM, Cred, or any UPI app.",
                    fontSize = 11.5.sp,
                    color = appColors.textMuted,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Donation Chips
                Text(
                    text = "SELECT AMOUNT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = appColors.textSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetAmounts.forEach { preset ->
                        val isSelected = selectedPreset == preset
                        val label = if (preset == "Custom") "Custom" else "₹$preset"

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .liquidGlassPill(
                                    colors = appColors,
                                    shape = RoundedCornerShape(12.dp),
                                    isActive = isSelected,
                                    elevation = if (isSelected) 4.dp else 1.dp
                                )
                                .bouncyClickable { selectedPreset = preset }
                                .testTag("donation_chip_$preset"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isSelected) contrastingContentColor(appColors.primaryAccent) else appColors.textPrimary
                            )
                        }
                    }
                }

                // Custom amount field
                if (selectedPreset == "Custom") {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customAmount,
                        onValueChange = { input ->
                            // Only allow numbers
                            if (input.all { it.isDigit() } && input.length <= 6) {
                                customAmount = input
                            }
                        },
                        label = { Text("Enter Custom Amount (INR)", fontSize = 11.5.sp) },
                        placeholder = { Text("e.g. 500", fontSize = 12.sp) },
                        prefix = { Text("₹ ", fontWeight = FontWeight.Bold, color = accentColor) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = appColors.cardBorder,
                            focusedLabelColor = accentColor,
                            unfocusedLabelColor = appColors.textSecondary,
                            focusedTextColor = appColors.textPrimary,
                            unfocusedTextColor = appColors.textPrimary,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_amount_input")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary CTA Button: Donate via UPI with 3D Liquid Glass styling
                val donateShape = RoundedCornerShape(14.dp)
                val accentLum = (0.299 * accentColor.red + 0.587 * accentColor.green + 0.114 * accentColor.blue)
                val isAccentWhite = accentColor == Color.White || (appColors.isAmoled && accentColor == Color.White) || accentLum > 0.70f
                val donateBrush = if (isAccentWhite) {
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFFFFFFF),
                            Color(0xFFE4E4E7)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.94f),
                            accentColor.copy(alpha = 0.78f)
                        )
                    )
                }
                val donateTextColor = if (isAccentWhite) Color(0xFF0A0A0A) else contrastingContentColor(accentColor)
                val donateBorder = BorderStroke(
                    1.2.dp,
                    Brush.verticalGradient(
                        listOf(
                            if (isAccentWhite) Color(0xFFCCCCCC) else Color.White.copy(alpha = 0.50f),
                            accentColor.copy(alpha = 0.40f)
                        )
                    )
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = donateShape,
                            spotColor = if (isAccentWhite) Color.White.copy(alpha = 0.25f) else accentColor.copy(alpha = 0.45f),
                            ambientColor = Color.Transparent
                        )
                        .clip(donateShape)
                        .background(donateBrush)
                        .border(donateBorder, donateShape)
                        .drawWithContent {
                            if (!isAccentWhite) {
                                // Top specular highlight sheen drawn behind text
                                val sheen = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = size.height * 0.48f
                                )
                                drawRect(sheen)
                            }
                            drawContent()
                        }
                        .bouncyClickable {
                            val amountDigits = if (selectedPreset == "Custom") {
                                customAmount.trim().filter { it.isDigit() }
                            } else {
                                selectedPreset
                            }
                            val amountInt = amountDigits.toIntOrNull()

                            val baseUpiUrl = "upi://pay?pa=$upiId&pn=Xtreme%20Player&tn=Support%20Xtreme%20Player%20Development&cu=INR"
                            val finalUpiUrl = if (amountInt != null && amountInt > 0) {
                                "$baseUpiUrl&am=$amountInt"
                            } else {
                                baseUpiUrl
                            }

                            val upiUri = Uri.parse(finalUpiUrl)
                            val upiIntent = Intent(Intent.ACTION_VIEW, upiUri)
                            val chooser = Intent.createChooser(upiIntent, "Donate via UPI App")

                            try {
                                context.startActivity(chooser)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No UPI app found on your device.", Toast.LENGTH_LONG).show()
                            }
                        }
                        .testTag("donate_via_upi_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Donate via UPI 🚀",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = donateTextColor
                    )
                }
            }
        }
    }
}
