package com.example.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BackupRestoreManager
import com.example.data.local.MusicDatabase
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BackupRestorePage(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { MusicDatabase.getDatabase(context) }

    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val inputBg = appColors.cardBackgroundElevated
    val accentColor = appColors.primaryAccent

    // Auto backup switch: DEFAULT IS FALSE (off)
    var autoBackupEnabled by remember {
        mutableStateOf(BackupRestoreManager.isAutoBackupEnabled(context))
    }

    // Auto backup location path
    var autoBackupLocation by remember {
        mutableStateOf(BackupRestoreManager.getAutoBackupLocation(context))
    }
    var showChangeLocationDialog by remember { mutableStateOf(false) }
    var locationInput by remember { mutableStateOf(autoBackupLocation) }

    // Last backup info & available backups
    var lastBackupInfo by remember { mutableStateOf(BackupRestoreManager.getLastBackupInfo(context)) }
    var availableBackups by remember { mutableStateOf<List<BackupRestoreManager.BackupInfo>>(emptyList()) }

    var isCreatingBackup by remember { mutableStateOf(false) }
    var isRestoringBackup by remember { mutableStateOf(false) }
    var statusNotification by remember { mutableStateOf<String?>(null) }
    var selectedBackupToRestore by remember { mutableStateOf<BackupRestoreManager.BackupInfo?>(null) }

    // Storage permission state & flow
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun refreshBackups() {
        lastBackupInfo = BackupRestoreManager.getLastBackupInfo(context)
        availableBackups = BackupRestoreManager.getAvailableBackups(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        BackupRestoreManager.setAskedStoragePermission(context, true)
        val isGranted = BackupRestoreManager.isStoragePermissionGranted(context)
        if (isGranted) {
            pendingAction?.invoke()
            pendingAction = null
            refreshBackups()
        } else {
            // Even if general permission was denied, try running the requested action or SAF fallback
            pendingAction?.invoke()
            pendingAction = null
            refreshBackups()
        }
    }

    fun executeWithStoragePermission(action: () -> Unit) {
        val hasAsked = BackupRestoreManager.hasAskedStoragePermission(context)
        val isGranted = BackupRestoreManager.isStoragePermissionGranted(context)

        if (isGranted) {
            action()
        } else if (!hasAsked) {
            // First time using backup or restore: ask user for storage permission!
            pendingAction = action
            showPermissionRationaleDialog = true
        } else {
            action()
        }
    }

    // Document Picker launcher to restore ANY backup file from phone storage
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isRestoringBackup = true
            statusNotification = null
            coroutineScope.launch {
                val summary = BackupRestoreManager.restoreFromUri(context, database, uri)
                isRestoringBackup = false
                statusNotification = summary.message
                refreshBackups()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshBackups()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 1. Create Backup Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Backup,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Create Backup",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Export playlists, library tracks & user preferences to storage",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        executeWithStoragePermission {
                            isCreatingBackup = true
                            statusNotification = null
                            coroutineScope.launch {
                                val result = BackupRestoreManager.createBackup(context, database)
                                isCreatingBackup = false
                                if (result.isSuccess) {
                                    val info = result.getOrNull()
                                    statusNotification = "✓ Backup saved to phone storage: ${info?.fileName} (${info?.playlistsCount} playlists, ${info?.tracksCount} tracks)"
                                    refreshBackups()
                                } else {
                                    statusNotification = "Failed to create backup: ${result.exceptionOrNull()?.message}"
                                }
                            }
                        }
                    },
                    enabled = !isCreatingBackup,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("button_create_backup")
                ) {
                    if (isCreatingBackup) {
                        CircularProgressIndicator(
                            color = appColors.onPrimaryAccent,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Saving Backup...", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Backup Now", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Last backup details if available
                lastBackupInfo?.let { info ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = inputBg,
                        border = BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "LAST BACKUP",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = appColors.textMuted
                                )
                                Text(
                                    text = info.sizeFormatted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = info.formattedDate,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "File: ${info.fileName}",
                                fontSize = 11.sp,
                                color = appColors.textMuted
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Auto Backup Option Card (Default OFF)
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Backup",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Automated backup to phone storage (Default: Off)",
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }

                    Switch(
                        checked = autoBackupEnabled,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                executeWithStoragePermission {
                                    autoBackupEnabled = true
                                    BackupRestoreManager.setAutoBackupEnabled(context, true)
                                }
                            } else {
                                autoBackupEnabled = false
                                BackupRestoreManager.setAutoBackupEnabled(context, false)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = appColors.onPrimaryAccent,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("switch_auto_backup")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Auto Backup Location Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Backup Storage Folder",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Folder location in phone storage",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = inputBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = autoBackupLocation,
                            fontSize = 12.sp,
                            color = appColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )

                        TextButton(
                            onClick = {
                                locationInput = autoBackupLocation
                                showChangeLocationDialog = true
                            }
                        ) {
                            Text(
                                text = "CHANGE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Restore Backup Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Restore Backup",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Recover playlists and tracks directly into your music library",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary restore option: Pick ANY backup file from phone storage
                Button(
                    onClick = {
                        executeWithStoragePermission {
                            openDocumentLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "application/octet-stream",
                                    "text/plain",
                                    "*/*"
                                )
                            )
                        }
                    },
                    enabled = !isRestoringBackup,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("button_restore_file_picker")
                ) {
                    if (isRestoringBackup) {
                        CircularProgressIndicator(
                            color = appColors.onPrimaryAccent,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restoring Backup...", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore Backup Now", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (availableBackups.isEmpty()) {
                    Text(
                        text = "No automatic backup files detected in Downloads/XtremeBackup. You can tap the button above to pick a backup file from anywhere in phone storage, or create a backup first.",
                        fontSize = 12.sp,
                        color = appColors.textMuted,
                        lineHeight = 16.sp
                    )
                } else {
                    Text(
                        text = "DETECTED BACKUP FILES IN PHONE STORAGE:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = appColors.textMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    availableBackups.take(5).forEach { backup ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = inputBg,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    executeWithStoragePermission {
                                        selectedBackupToRestore = backup
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = backup.fileName,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = appColors.textPrimary
                                    )
                                    Text(
                                        text = "${backup.formattedDate} • ${backup.sizeFormatted} • ${backup.playlistsCount} playlists, ${backup.tracksCount} tracks",
                                        fontSize = 10.5.sp,
                                        color = appColors.textMuted
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = accentColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "RESTORE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = accentColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Status Notification Banner
        if (statusNotification != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accentColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusNotification!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appColors.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Storage Permission Rationale Dialog (shown on first use)
    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = {
                showPermissionRationaleDialog = false
                pendingAction = null
            },
            containerColor = appColors.cardBackground,
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Storage Permission Required",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "To save backup files directly to your phone storage (in Downloads/XtremeBackup) and restore your music library, Xtreme Player requires storage permission.",
                    fontSize = 13.sp,
                    color = appColors.textMuted,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionRationaleDialog = false
                        BackupRestoreManager.setAskedStoragePermission(context, true)
                        permissionLauncher.launch(BackupRestoreManager.getRequiredStoragePermissions())
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = appColors.onPrimaryAccent
                    )
                ) {
                    Text("Grant Permission", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermissionRationaleDialog = false
                        BackupRestoreManager.setAskedStoragePermission(context, true)
                        // User dismissed rationale, proceed with action (which can fall back to SAF)
                        pendingAction?.invoke()
                        pendingAction = null
                    }
                ) {
                    Text("Not Now", color = appColors.textSecondary)
                }
            }
        )
    }

    // Change Location Dialog
    if (showChangeLocationDialog) {
        AlertDialog(
            onDismissRequest = { showChangeLocationDialog = false },
            containerColor = appColors.cardBackground,
            title = {
                Text(
                    text = "Backup Storage Location",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = appColors.textPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Enter the directory path in phone storage where automated backups should be saved:",
                        fontSize = 12.5.sp,
                        color = appColors.textMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = locationInput,
                        onValueChange = { locationInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = appColors.textPrimary,
                            unfocusedTextColor = appColors.textPrimary,
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (locationInput.isNotBlank()) {
                            autoBackupLocation = locationInput.trim()
                            BackupRestoreManager.setAutoBackupLocation(context, autoBackupLocation)
                        }
                        showChangeLocationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = appColors.onPrimaryAccent
                    )
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeLocationDialog = false }) {
                    Text("Cancel", color = appColors.textSecondary)
                }
            }
        )
    }

    // Confirm Restore Dialog
    selectedBackupToRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { selectedBackupToRestore = null },
            containerColor = appColors.cardBackground,
            title = {
                Text(
                    text = "Restore Backup?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to restore from '${backup.fileName}'?\n\nThis will restore ${backup.playlistsCount} playlists and ${backup.tracksCount} tracks directly into your music library.",
                    fontSize = 13.sp,
                    color = appColors.textMuted,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileToRestore = File(backup.filePath)
                        val uriStr = backup.contentUri
                        selectedBackupToRestore = null
                        isRestoringBackup = true
                        statusNotification = null
                        coroutineScope.launch {
                            val summary = BackupRestoreManager.restoreBackup(
                                context,
                                database,
                                backupFile = fileToRestore,
                                uriString = uriStr
                            )
                            isRestoringBackup = false
                            statusNotification = summary.message
                            refreshBackups()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = appColors.onPrimaryAccent
                    )
                ) {
                    Text("Restore Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedBackupToRestore = null }) {
                    Text("Cancel", color = appColors.textSecondary)
                }
            }
        )
    }
}
