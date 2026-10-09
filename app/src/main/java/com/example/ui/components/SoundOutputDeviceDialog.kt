package com.example.ui.components

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.playback.AudioDeviceManager
import com.example.playback.SoundOutputDevice
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.bouncyClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Custom Adaptive Aesthetic Output Devices Picker Popup & Audio Management.
 *
 * Key Architectural Highlights:
 * 1. Layout & Interaction Overhaul:
 *    - Completely removed redundant system switcher header and manual "Done" / "Apply" buttons.
 *    - Instant Selection: Tapping any device item immediately selects & routes audio to that endpoint.
 *    - Clean filtering: Displays only physical and wireless output targets, eliminating generic system aliases.
 * 2. Full Dark / Light Theme Adaptability & Accessibility:
 *    - Adapts automatically to Light, Dark, or AMOLED themes with WCAG AAA compliant semantic tokens.
 * 3. Native Permissions & Dynamic Device Discovery:
 *    - Native permission flow checking BLUETOOTH_CONNECT (API 31+) with runtime discovery.
 * 4. Integrated Minimal Modern Volume Slider:
 *    - Direct STREAM_MUSIC output volume management for the active audio target with hardware button sync.
 */
@Composable
fun SoundOutputDeviceDialog(
    devices: List<SoundOutputDevice>,
    isDark: Boolean = LocalAppColors.current.isDark,
    onSelectDevice: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val appColors = LocalAppColors.current

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

    // Smooth opening and exit spring animations tuned for 90Hz / 120Hz displays
    var isClosing by remember { mutableStateOf(false) }
    var isMounted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isMounted = true
    }

    val closeWithAnimation: () -> Unit = {
        if (!isClosing) {
            isClosing = true
            coroutineScope.launch {
                delay(170)
                onDismiss()
            }
        }
    }

    BackHandler(enabled = true) {
        closeWithAnimation()
    }

    val isPresented = isMounted && !isClosing

    val scale by animateFloatAsState(
        targetValue = if (isPresented) 1.0f else 0.84f,
        animationSpec = if (isPresented) {
            spring(dampingRatio = 0.74f, stiffness = 480f)
        } else {
            tween(durationMillis = 160, easing = FastOutLinearInEasing)
        },
        label = "dialog_scale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (isPresented) 1.0f else 0.0f,
        animationSpec = if (isPresented) {
            tween(durationMillis = 180, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 140, easing = FastOutLinearInEasing)
        },
        label = "dialog_alpha"
    )

    val translateY by animateFloatAsState(
        targetValue = if (isPresented) 0f else 26f,
        animationSpec = if (isPresented) {
            spring(dampingRatio = 0.75f, stiffness = 500f)
        } else {
            tween(durationMillis = 160, easing = FastOutLinearInEasing)
        },
        label = "dialog_translate_y"
    )

    val scrimAlpha by animateFloatAsState(
        targetValue = if (isPresented) 1.0f else 0.0f,
        animationSpec = tween(
            durationMillis = if (isPresented) 200 else 160,
            easing = if (isPresented) LinearOutSlowInEasing else FastOutLinearInEasing
        ),
        label = "scrim_alpha"
    )

    Dialog(
        onDismissRequest = { closeWithAnimation() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CompositionLocalProvider(
            LocalAppColors provides appColors
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isDark) Color.Black.copy(alpha = 0.65f * scrimAlpha)
                        else Color(0xFF0F172A).copy(alpha = 0.36f * scrimAlpha)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { closeWithAnimation() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val dialogShape = RoundedCornerShape(24.dp)
                val dialogBaseColor = if (appColors.isAmoled) Color(0xFF000000) else appColors.cardBackground

                val dialogBorderStroke = if (appColors.isAmoled) {
                    BorderStroke(1.dp, Color(0xFF222222))
                } else {
                    BorderStroke(
                        1.dp,
                        appColors.cardBorder.copy(alpha = if (isDark) 0.55f else 0.70f)
                    )
                }

                Surface(
                    shape = dialogShape,
                    color = dialogBaseColor,
                    border = dialogBorderStroke,
                    modifier = Modifier
                        .fillMaxWidth(0.90f)
                        .widthIn(max = 380.dp)
                        .shadow(
                            elevation = 24.dp,
                            shape = dialogShape,
                            spotColor = Color.Black.copy(alpha = if (isDark) 0.60f else 0.16f),
                            ambientColor = Color.Transparent
                        )
                        .clip(dialogShape)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            this.translationY = translateY * density
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* Consume clicks inside popup */ }
                        )
                        .testTag("sound_output_devices_dialog")
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Ambient chromatic soft glow
                        if (!appColors.isAmoled) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                appColors.primaryAccent.copy(alpha = if (isDark) 0.12f else 0.08f),
                                                Color.Transparent
                                            ),
                                            radius = 500f
                                        )
                                    )
                                    .blur(20.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                        ) {
                            // 1. Sleek Modern Header (No redundant switcher or apply buttons)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                appColors.primaryAccent.copy(alpha = if (isDark) 0.18f else 0.12f)
                                            )
                                            .border(
                                                1.dp,
                                                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.35f else 0.45f),
                                                RoundedCornerShape(12.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SpeakerGroup,
                                            contentDescription = null,
                                            tint = appColors.primaryAccent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Text(
                                            text = "Audio Output",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = appColors.textPrimary
                                        )
                                        Text(
                                            text = "Tap target to route audio instantly",
                                            fontSize = 11.5.sp,
                                            color = appColors.textMuted
                                        )
                                    }
                                }

                                // Liquid Glass Close Icon
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isDark) Color.White.copy(alpha = 0.08f)
                                            else Color.Black.copy(alpha = 0.05f)
                                        )
                                        .border(
                                            1.dp,
                                            LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.30f else 0.40f),
                                            CircleShape
                                        )
                                        .bouncyClickable { closeWithAnimation() }
                                        .testTag("close_output_devices_dialog"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = appColors.textPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(
                                color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                                thickness = 1.dp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            // 2. Filtered Physical & Wireless Target Devices List
                            Text(
                                text = "AVAILABLE TARGETS (${devices.size})",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = appColors.primaryAccent,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(devices, key = { it.id }) { device ->
                                    ModernDeviceItemRow(
                                        device = device,
                                        isDark = isDark,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            // Instant Selection: immediately select & route without extra button clicks
                                            onSelectDevice(device.id)
                                            AudioDeviceManager.selectDevice(context, device.id)
                                        }
                                    )
                                }
                            }

                            // 3. Bluetooth Discovery Prompt (If permission needed or no bluetooth active)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, appColors.primaryAccent.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(appColors.primaryAccent.copy(alpha = 0.08f))
                                        .bouncyClickable {
                                            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = appColors.primaryAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Allow Bluetooth Discovery",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = appColors.textPrimary
                                            )
                                            Text(
                                                text = "Tap to discover paired headphones & speakers",
                                                fontSize = 10.sp,
                                                color = appColors.textMuted
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(
                                color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                                thickness = 1.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // 4. Modern Minimal Thin-Line Volume Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "OUTPUT VOLUME",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    color = appColors.textMuted
                                )
                                Text(
                                    text = "${((currentVolume.toFloat() / maxVolume) * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.primaryAccent
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val volumeIcon = when {
                                    currentVolume == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                                    currentVolume < maxVolume / 2 -> Icons.AutoMirrored.Filled.VolumeDown
                                    else -> Icons.AutoMirrored.Filled.VolumeUp
                                }

                                Icon(
                                    imageVector = volumeIcon,
                                    contentDescription = "Volume",
                                    tint = appColors.textSecondary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            // Toggle Mute
                                            val newVol = if (currentVolume > 0) 0 else (maxVolume * 0.4f).roundToInt()
                                            currentVolume = newVol
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                        }
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                Slider(
                                    value = currentVolume.toFloat(),
                                    onValueChange = { newVal ->
                                        val targetVol = newVal.roundToInt().coerceIn(0, maxVolume)
                                        if (targetVol != currentVolume) {
                                            currentVolume = targetVol
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    valueRange = 0f..maxVolume.toFloat(),
                                    steps = 0,
                                    colors = SliderDefaults.colors(
                                        thumbColor = appColors.primaryAccent,
                                        activeTrackColor = appColors.primaryAccent,
                                        inactiveTrackColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(26.dp)
                                        .testTag("device_output_volume_slider")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modern Device Item Row with instant selection styling and WCAG AAA high contrast tokens.
 */
@Composable
private fun ModernDeviceItemRow(
    device: SoundOutputDevice,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isSelected = device.isSelected
    val icon = getDeviceIcon(device)
    val itemShape = RoundedCornerShape(16.dp)

    val itemBorder = if (isSelected) {
        BorderStroke(1.2.dp, appColors.primaryAccent)
    } else {
        if (appColors.isAmoled) {
            BorderStroke(1.dp, Color(0xFF222222))
        } else {
            BorderStroke(1.dp, appColors.cardBorder.copy(alpha = if (isDark) 0.45f else 0.65f))
        }
    }

    val itemBg = if (isSelected) {
        if (appColors.isAmoled) {
            Brush.verticalGradient(listOf(appColors.primaryAccent.copy(alpha = 0.20f), Color(0xFF000000)))
        } else if (isDark) {
            Brush.verticalGradient(listOf(appColors.primaryAccent.copy(alpha = 0.18f), appColors.cardBackgroundElevated))
        } else {
            Brush.verticalGradient(listOf(appColors.primaryAccent.copy(alpha = 0.14f), appColors.cardBackground))
        }
    } else {
        if (appColors.isAmoled) {
            Brush.verticalGradient(listOf(Color(0xFF0E0E0E), Color(0xFF060606)))
        } else if (isDark) {
            Brush.verticalGradient(listOf(appColors.cardBackgroundElevated, appColors.cardBackground))
        } else {
            Brush.verticalGradient(listOf(Color.White, appColors.chipBackground))
        }
    }

    Surface(
        shape = itemShape,
        color = Color.Transparent,
        border = itemBorder,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isSelected) 6.dp else 1.dp,
                shape = itemShape,
                spotColor = if (isSelected) appColors.primaryAccent.copy(alpha = 0.35f) else Color.Black.copy(alpha = if (isDark) 0.25f else 0.04f),
                ambientColor = Color.Transparent
            )
            .clip(itemShape)
            .background(itemBg)
            .bouncyClickable(targetScaleOnPress = 0.97f) { onClick() }
            .testTag("device_row_${device.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Device Icon Container
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (isSelected) appColors.primaryAccent.copy(alpha = if (isDark) 0.24f else 0.16f)
                            else if (isDark) Color.White.copy(alpha = 0.06f)
                            else Color.Black.copy(alpha = 0.04f)
                        )
                        .border(
                            1.dp,
                            if (isSelected) LiquidGlass.specularBorderBrush(appColors, highlightAlpha = 0.45f)
                            else LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.22f else 0.30f),
                            RoundedCornerShape(11.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) appColors.primaryAccent else appColors.textMuted,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        color = if (isSelected && !isDark) appColors.primaryAccent else appColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = device.typeName,
                        fontSize = 11.sp,
                        color = if (isSelected) appColors.primaryAccent.copy(alpha = 0.85f) else appColors.textMuted
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Active Target Indicator Badge
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = appColors.primaryAccent.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, appColors.primaryAccent.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            color = appColors.primaryAccent
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.04f))
                        .border(
                            1.dp,
                            LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.25f else 0.35f),
                            CircleShape
                        )
                )
            }
        }
    }
}

private fun getDeviceIcon(device: SoundOutputDevice): ImageVector {
    return when {
        device.isCar -> Icons.Default.DirectionsCar
        device.isBluetooth -> Icons.Default.BluetoothAudio
        device.isWired -> Icons.Default.Headphones
        device.isUsb -> Icons.Default.Usb
        device.isBuiltInSpeaker -> Icons.Default.Speaker
        device.type == AudioDeviceInfo.TYPE_HEARING_AID -> Icons.Default.Hearing
        else -> Icons.Default.Speaker
    }
}
