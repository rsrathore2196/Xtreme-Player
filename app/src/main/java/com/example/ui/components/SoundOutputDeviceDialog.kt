package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.playback.SoundOutputDevice
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XtremeCyan
import com.example.ui.theme.XtremeLightBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // Smooth opening and closing animation state supporting 90Hz / 120Hz refresh rates
    var isClosing by remember { mutableStateOf(false) }
    var isMounted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isMounted = true
    }

    val closeWithAnimation: () -> Unit = {
        if (!isClosing) {
            isClosing = true
            coroutineScope.launch {
                delay(180) // Wait for smooth GPU exit transition before dismissing
                onDismiss()
            }
        }
    }

    BackHandler(enabled = true) {
        closeWithAnimation()
    }

    val isPresented = isMounted && !isClosing

    // Hardware-accelerated GPU graphicsLayer animations tuned for 90Hz/120Hz displays
    val scale by animateFloatAsState(
        targetValue = if (isPresented) 1.0f else 0.82f,
        animationSpec = if (isPresented) {
            spring(
                dampingRatio = 0.72f, // Eye-catching, snappy subtle bounce
                stiffness = 500f     // Fast, tactile pop at 90Hz+
            )
        } else {
            tween(durationMillis = 170, easing = FastOutLinearInEasing)
        },
        label = "dialog_scale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (isPresented) 1.0f else 0.0f,
        animationSpec = if (isPresented) {
            tween(durationMillis = 180, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 150, easing = FastOutLinearInEasing)
        },
        label = "dialog_alpha"
    )

    val translateY by animateFloatAsState(
        targetValue = if (isPresented) 0f else 28f,
        animationSpec = if (isPresented) {
            spring(
                dampingRatio = 0.75f,
                stiffness = 520f
            )
        } else {
            tween(durationMillis = 170, easing = FastOutLinearInEasing)
        },
        label = "dialog_translate_y"
    )

    val scrimAlpha by animateFloatAsState(
        targetValue = if (isPresented) 1.0f else 0.0f,
        animationSpec = tween(
            durationMillis = if (isPresented) 200 else 170,
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
            LocalAppColors provides if (isDark) DarkAppColors else LightAppColors
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isDark) Color.Black.copy(alpha = 0.65f * scrimAlpha)
                        else Color(0xFF0F172A).copy(alpha = 0.38f * scrimAlpha)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { closeWithAnimation() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) Color(0xFF0D1B2E) else Color(0xFFFFFFFF)
                    ),
                    border = BorderStroke(
                        1.2.dp,
                        if (isDark) Color(0xFF1E3A5F) else Color(0xFFBFDBFE)
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = if (isDark) 16.dp else 10.dp
                    ),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            translationY = translateY * density
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* Consume clicks inside dialog card */ }
                        )
                        .testTag("sound_output_devices_dialog")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // HEADER
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
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isDark) XtremeLightBlue.copy(alpha = 0.18f)
                                            else Color(0xFFE0F2FE)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SpeakerGroup,
                                        contentDescription = null,
                                        tint = if (isDark) XtremeLightBlue else Color(0xFF0284C7),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = "Sound Output Devices",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "Select active speaker or headphones",
                                        fontSize = 12.sp,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { closeWithAnimation() },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("close_output_devices_dialog")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(
                            color = if (isDark) Color(0xFF182D47) else Color(0xFFE2E8F0),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        // DEVICE LIST
                        Text(
                            text = "AVAILABLE OUTPUTS (${devices.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = if (isDark) XtremeLightBlue else Color(0xFF0284C7),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(devices, key = { it.id }) { device ->
                                DeviceItemRow(
                                    device = device,
                                    isDark = isDark,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSelectDevice(device.id)
                                    }
                                )
                            }
                        }

                        // BLUETOOTH HINT BANNER
                        if (devices.none { it.isBluetooth }) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF132238) else Color(0xFFEFF6FF),
                                border = BorderStroke(1.dp, if (isDark) Color(0xFF1C3454) else Color(0xFFBFDBFE)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothAudio,
                                        contentDescription = null,
                                        tint = if (isDark) XtremeCyan else Color(0xFF0284C7),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Connect Bluetooth earbuds or headphones to see them here.",
                                        fontSize = 11.sp,
                                        color = if (isDark) TextSecondary else Color(0xFF1E40AF),
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(
                            color = if (isDark) Color(0xFF182D47) else Color(0xFFE2E8F0),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        // ACTIONS FOOTER
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // System Output Switcher button
                            OutlinedButton(
                                onClick = {
                                    openSystemAudioSwitcher(context)
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (isDark) Color(0xFF23446D) else Color(0xFFCBD5E1)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isDark) Color(0xFF132238) else Color(0xFFF8FAFC),
                                    contentColor = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("open_system_switcher_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "System Switcher",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }

                            // Done Button
                            Button(
                                onClick = { closeWithAnimation() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDark) XtremeLightBlue else Color(0xFF0284C7),
                                    contentColor = if (isDark) Color(0xFF021024) else Color.White
                                ),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(44.dp)
                                    .testTag("done_output_devices_button")
                            ) {
                                Text(
                                    text = "Done",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
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
private fun DeviceItemRow(
    device: SoundOutputDevice,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val isSelected = device.isSelected
    val icon = getDeviceIcon(device)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            if (isDark) Color(0xFF142C48) else Color(0xFFEFF6FF)
        } else {
            if (isDark) Color(0xFF0F1C2D) else Color(0xFFF8FAFC)
        },
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) {
                if (isDark) XtremeLightBlue else Color(0xFF0284C7)
            } else {
                if (isDark) Color(0xFF1A304C) else Color(0xFFE2E8F0)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_row_${device.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Device Icon & Name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) {
                                if (isDark) XtremeLightBlue.copy(alpha = 0.25f) else Color(0xFFDBEAFE)
                            } else {
                                if (isDark) Color(0xFF1A2A40) else Color(0xFFEDF2F7)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) {
                            if (isDark) XtremeLightBlue else Color(0xFF0284C7)
                        } else {
                            if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = device.typeName,
                        fontSize = 11.sp,
                        color = if (isSelected) {
                            if (isDark) XtremeCyan else Color(0xFF0284C7)
                        } else {
                            if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: Status badge or Radio
            if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDark) XtremeLightBlue.copy(alpha = 0.2f) else Color(0xFFDBEAFE),
                    border = BorderStroke(1.dp, if (isDark) XtremeLightBlue else Color(0xFF0284C7))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = if (isDark) XtremeLightBlue else Color(0xFF0284C7),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isDark) XtremeLightBlue else Color(0xFF0284C7)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(
                            1.5.dp,
                            if (isDark) Color(0xFF334E6F) else Color(0xFFCBD5E1),
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
        device.type == android.media.AudioDeviceInfo.TYPE_HEARING_AID -> Icons.Default.Hearing
        else -> Icons.Default.Speaker
    }
}

private fun openSystemAudioSwitcher(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = Intent("android.settings.panel.action.MEDIA_OUTPUT").apply {
                putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } else {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    } catch (_: Exception) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
