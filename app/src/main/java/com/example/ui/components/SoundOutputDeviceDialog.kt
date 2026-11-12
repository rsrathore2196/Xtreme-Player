package com.example.ui.components

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Earbuds
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import com.example.playback.AudioDeviceCategory
import com.example.playback.AudioDeviceManager
import com.example.playback.SoundOutputDevice
import com.example.ui.theme.LocalAppColors
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Connected output picker with immediate routing and media-volume controls. */
@Composable
fun SoundOutputDeviceDialog(
    devices: List<SoundOutputDevice>,
    isDark: Boolean = LocalAppColors.current.isDark,
    onSelectDevice: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val latestDismiss by rememberUpdatedState(onDismiss)
    val appColors = rememberPlayerPopupColors()
    @Suppress("NAME_SHADOWING") val isDark = appColors.isDark

    // Audio Manager & Volume Management
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    // Synchronize slider with hardware volume changes
    DisposableEffect(context) {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                try {
                    currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                } catch (_: Exception) {}
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    // Native Bluetooth Permission Flow
    var hasBluetoothPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasBluetoothPermission = isGranted
        if (isGranted) {
            AudioDeviceManager.refreshDevices(context)
        }
    }

    // Check permission on initial mount
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            AudioDeviceManager.refreshDevices(context)
        }
    }

    // One transition owns every layer; dismissal waits for its actual completion.
    var isClosing by remember { mutableStateOf(false) }
    var isMounted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isMounted = true
    }

    val closeWithAnimation: () -> Unit = {
        if (!isClosing) {
            isClosing = true
        }
    }

    BackHandler(enabled = true) {
        closeWithAnimation()
    }

    val isPresented = isMounted && !isClosing
    val presentation = updateTransition(isPresented, label = "output_dialog_presentation")

    val scale by presentation.animateFloat(
        transitionSpec = { tween(if (targetState) 300 else 240, easing = FastOutSlowInEasing) },
        label = "dialog_scale"
    ) { if (it) 1.0f else 0.96f }

    val alpha by presentation.animateFloat(
        transitionSpec = { tween(if (targetState) 220 else 180, easing = if (targetState) LinearOutSlowInEasing else FastOutLinearInEasing) },
        label = "dialog_alpha"
    ) { if (it) 1.0f else 0.0f }

    val translateY by presentation.animateFloat(
        transitionSpec = { tween(if (targetState) 300 else 240, easing = FastOutSlowInEasing) },
        label = "dialog_translate_y"
    ) { if (it) 0f else 24f }

    val scrimAlpha by presentation.animateFloat(
        transitionSpec = { tween(if (targetState) 240 else 200, easing = if (targetState) LinearOutSlowInEasing else FastOutLinearInEasing) },
        label = "scrim_alpha"
    ) { if (it) 1.0f else 0.0f }

    LaunchedEffect(isClosing, presentation) {
        if (isClosing) {
            snapshotFlow { !presentation.isRunning && !presentation.currentState }.first { it }
            latestDismiss()
        }
    }

    Dialog(
        onDismissRequest = { closeWithAnimation() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Android's default dim layer plus our animated scrim used to hide the
        // player. Keep the dialog window transparent and own just one light scrim.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
            onDispose { }
        }
        PlayerPopupTheme(appColors) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(if (isDark) Color.Black.copy(alpha = 0.18f * scrimAlpha)
                            else Color(0xFF0F172A).copy(alpha = 0.12f * scrimAlpha))
                    }
                    .pointerInput(Unit) { detectTapGestures { closeWithAnimation() } },
                contentAlignment = Alignment.Center
            ) {
                val dialogShape = RoundedCornerShape(32.dp)
                Surface(
                    shape = dialogShape,
                    color = appColors.cardBackground,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .heightIn(max = (maxHeight - 48.dp).coerceAtLeast(120.dp))
                        .shadow(24.dp, dialogShape, spotColor = Color.Black.copy(alpha = 0.24f))
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            translationY = translateY * density
                        }
                        .clip(dialogShape)
                        .pointerInput(Unit) { detectTapGestures { /* Keep taps inside the dialog. */ } }
                        .testTag("sound_output_devices_dialog")
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Audio output", color = appColors.textPrimary,
                                    fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("Choose where to listen", color = appColors.textSecondary, fontSize = 12.sp)
                            }
                            IconButton(
                                onClick = closeWithAnimation,
                                modifier = Modifier.testTag("close_output_devices_dialog")
                            ) {
                                Box(Modifier.size(32.dp).background(appColors.cardBackgroundElevated, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Close, "Close", tint = appColors.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Connected devices", color = appColors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f))
                            Text(devices.size.toString(), color = appColors.textSecondary, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.background(appColors.cardBackgroundElevated, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp).testTag("output_device_count"))
                        }
                        Spacer(Modifier.height(10.dp))
                        if (devices.isEmpty()) {
                            Text("No audio outputs detected", color = appColors.textSecondary, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp))
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(devices, key = { it.id }) { device ->
                                    ModernDeviceItemRow(device) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectDevice(device.id)
                                    }
                                }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = appColors.cardBackgroundElevated,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .clickable { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Bluetooth, null, tint = appColors.primaryAccent, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("Allow Bluetooth access", color = appColors.textPrimary, fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("Show connected device details", color = appColors.textSecondary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Surface(shape = RoundedCornerShape(20.dp), color = appColors.cardBackgroundElevated) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Volume", color = appColors.textSecondary, fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Text("${(currentVolume.toFloat() / maxVolume * 100).roundToInt()}%",
                                        color = appColors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        val newVolume = if (currentVolume > 0) 0 else (maxVolume * 0.4f).roundToInt()
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                                        currentVolume = newVolume
                                    }) {
                                        Icon(
                                            when {
                                                currentVolume == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                                                currentVolume < maxVolume / 2 -> Icons.AutoMirrored.Filled.VolumeDown
                                                else -> Icons.AutoMirrored.Filled.VolumeUp
                                            },
                                            if (currentVolume > 0) "Mute" else "Unmute",
                                            tint = appColors.textSecondary, modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Slider(
                                        value = currentVolume.toFloat(),
                                        onValueChange = { value ->
                                            val volume = value.roundToInt().coerceIn(0, maxVolume)
                                            if (volume != currentVolume) {
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                                                currentVolume = volume
                                            }
                                        },
                                        valueRange = 0f..maxVolume.toFloat(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = appColors.primaryAccent,
                                            activeTrackColor = appColors.primaryAccent,
                                            inactiveTrackColor = appColors.textPrimary.copy(alpha = 0.10f)
                                        ),
                                        modifier = Modifier.weight(1f).testTag("device_output_volume_slider")
                                            .semantics { contentDescription = "Output volume" }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernDeviceItemRow(device: SoundOutputDevice, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val selected = device.isSelected
    val shape = RoundedCornerShape(20.dp)
    val iconBackground = if (selected) colors.primaryAccent.copy(alpha = 0.12f) else colors.cardBackground
    val categoryLabel = when (device.category) {
        AudioDeviceCategory.PHONE -> "Phone speaker"
        AudioDeviceCategory.EARBUDS -> "Earbuds"
        AudioDeviceCategory.HEADPHONES -> "Headphones"
        AudioDeviceCategory.SPEAKER -> "Speaker"
        AudioDeviceCategory.CAR -> "Car audio"
        AudioDeviceCategory.HEARING_AID -> "Hearing aid"
        AudioDeviceCategory.USB -> "USB audio"
        AudioDeviceCategory.BLUETOOTH -> "Bluetooth audio"
    }
    Surface(
        shape = shape,
        color = if (selected) androidx.compose.ui.graphics.lerp(colors.cardBackground, colors.primaryAccent, 0.08f)
            else colors.cardBackgroundElevated,
        border = if (selected) BorderStroke(1.dp, colors.primaryAccent.copy(alpha = 0.40f)) else null,
        modifier = Modifier.fillMaxWidth().clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (selected) "Playing here" else "Connected" }
            .testTag("device_row_${device.id}")
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(iconBackground, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center) {
                Icon(deviceIcon(device.category), categoryLabel,
                    tint = if (selected) colors.primaryAccent else colors.textSecondary,
                    modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(if (selected) "Playing here · ${device.typeName}" else device.typeName,
                    color = if (selected) colors.primaryAccent else colors.textMuted, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(22.dp).then(
                if (selected) Modifier.background(colors.primaryAccent, CircleShape)
                else Modifier.border(1.5.dp, colors.textMuted.copy(alpha = 0.45f), CircleShape)
            ), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, null,
                    tint = if (colors.primaryAccent.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun deviceIcon(category: AudioDeviceCategory): ImageVector = when (category) {
    AudioDeviceCategory.PHONE -> Icons.Default.Smartphone
    AudioDeviceCategory.EARBUDS -> Icons.Default.Earbuds
    AudioDeviceCategory.HEADPHONES -> Icons.Default.Headphones
    AudioDeviceCategory.SPEAKER -> Icons.Default.Speaker
    AudioDeviceCategory.CAR -> Icons.Default.DirectionsCar
    AudioDeviceCategory.HEARING_AID -> Icons.Default.Hearing
    AudioDeviceCategory.USB -> Icons.Default.Usb
    AudioDeviceCategory.BLUETOOTH -> Icons.Default.BluetoothAudio
}
