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
import com.example.ui.theme.contrastingContentColor
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
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import com.example.playback.SoundOutputDevice
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.bouncyClickable
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

    val appColors = LocalAppColors.current

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
                        else Color(0xFF0F172A).copy(alpha = 0.38f * scrimAlpha)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { closeWithAnimation() }
                    ),
                contentAlignment = Alignment.Center
            ) {
            val dialogShape = RoundedCornerShape(22.dp)
            val opaqueDialogBaseColor = if (isDark) Color(0xFF161E2C) else Color(0xFFFFFFFF)
            val opaqueDialogBrush = if (isDark) {
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF222B3D),
                        Color(0xFF161E2C),
                        Color(0xFF0F141E)
                    )
                )
            } else {
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFF8FAFC),
                        Color(0xFFF1F5F9)
                    )
                )
            }

            Surface(
                shape = dialogShape,
                color = opaqueDialogBaseColor,
                border = BorderStroke(
                    1.2.dp,
                    LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.45f else 0.55f)
                ),
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 360.dp)
                    .shadow(
                        elevation = 20.dp,
                        shape = dialogShape,
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.60f else 0.15f),
                        ambientColor = Color.Transparent
                    )
                    .clip(dialogShape)
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(opaqueDialogBrush)
                ) {
                    // 1. Theme accent ambient chromatic blur glow
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        appColors.primaryAccent.copy(alpha = if (isDark) 0.18f else 0.12f),
                                        Color.Transparent
                                    ),
                                    radius = 550f
                                )
                            )
                            .blur(20.dp)
                    )
                    // 2. Optical frosted diffusion blur layer
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                if (isDark) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.10f),
                                            Color.White.copy(alpha = 0.02f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.40f),
                                            Color.White.copy(alpha = 0.15f)
                                        )
                                    )
                                }
                            )
                            .blur(16.dp)
                    )
                    // 3. Top specular reflection sheen across top curvature (like 3-dot menu)
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = if (isDark) 0.20f else 0.35f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = 48f
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        // COMPACT MODERN HEADER
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
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            appColors.primaryAccent.copy(alpha = if (isDark) 0.20f else 0.15f)
                                        )
                                        .border(
                                            1.dp,
                                            LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.40f else 0.50f),
                                            RoundedCornerShape(12.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(Brush.radialGradient(listOf(appColors.primaryAccent.copy(alpha = 0.40f), Color.Transparent)))
                                            .blur(8.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.SpeakerGroup,
                                        contentDescription = null,
                                        tint = appColors.primaryAccent,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column {
                                    Text(
                                        text = "Output Devices",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = appColors.textPrimary
                                    )
                                    Text(
                                        text = "Choose audio playback target",
                                        fontSize = 11.sp,
                                        color = appColors.textMuted
                                    )
                                }
                            }

                            // 3D Liquid Glass Close button
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .shadow(
                                        elevation = 4.dp,
                                        shape = CircleShape,
                                        spotColor = Color.Black.copy(alpha = if (isDark) 0.35f else 0.08f)
                                    )
                                    .clip(CircleShape)
                                    .background(
                                        if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f)
                                    )
                                    .border(
                                        1.dp,
                                        LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.35f else 0.45f),
                                        CircleShape
                                    )
                                    .bouncyClickable { closeWithAnimation() }
                                    .testTag("close_output_devices_dialog"),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (isDark) 0.15f else 0.30f), Color.Transparent)))
                                        .blur(8.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (isDark) 0.22f else 0.35f), Color.Transparent), startY = 0f, endY = 16f))
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = appColors.textPrimary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // COMPACT DEVICE LIST
                        Text(
                            text = "AVAILABLE OUTPUTS (${devices.size})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = appColors.primaryAccent,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 210.dp),
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

                        // BLUETOOTH HINT BANNER (3D Liquid Glass Pill)
                        if (devices.none { it.isBluetooth }) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.30f else 0.40f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(
                                        elevation = 2.dp,
                                        shape = RoundedCornerShape(12.dp),
                                        spotColor = Color.Black.copy(alpha = if (isDark) 0.30f else 0.05f)
                                    )
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(LiquidGlass.miniPlayerAndBottomBarBrush(appColors))
                            ) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    // Inner blur
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                if (isDark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent))
                                                else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent))
                                            )
                                            .blur(12.dp)
                                    )
                                    // Top sheen
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.White.copy(alpha = if (isDark) 0.16f else 0.28f), Color.Transparent),
                                                    startY = 0f,
                                                    endY = 18f
                                                )
                                            )
                                    )
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BluetoothAudio,
                                            contentDescription = null,
                                            tint = appColors.primaryAccent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(7.dp))
                                        Text(
                                            text = "Connect Bluetooth headphones to route audio.",
                                            fontSize = 11.sp,
                                            color = appColors.textSecondary,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // COMPACT ACTIONS FOOTER: 3D Liquid Glass Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. System Output Switcher button (3D Liquid Glass Pill)
                            val switcherBorder = BorderStroke(
                                1.2.dp,
                                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.40f else 0.50f)
                            )
                            val switcherBg = LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Transparent,
                                border = switcherBorder,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .shadow(
                                        elevation = 4.dp,
                                        shape = RoundedCornerShape(14.dp),
                                        spotColor = Color.Black.copy(alpha = if (isDark) 0.40f else 0.08f),
                                        ambientColor = Color.Transparent
                                    )
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(switcherBg)
                                    .bouncyClickable { openSystemAudioSwitcher(context) }
                                    .testTag("open_system_switcher_button")
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Inner Gaussian blur
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                if (isDark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent))
                                                else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent))
                                            )
                                            .blur(12.dp)
                                    )
                                    // Top specular sheen
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.White.copy(alpha = if (isDark) 0.18f else 0.30f), Color.Transparent),
                                                    startY = 0f,
                                                    endY = 22f
                                                )
                                            )
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            tint = appColors.textPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "System Switcher",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = appColors.textPrimary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // 2. Done Button (Theme-tinted 3D Liquid Glass Pill)
                            val accentLum = (0.299 * appColors.primaryAccent.red + 0.587 * appColors.primaryAccent.green + 0.114 * appColors.primaryAccent.blue)
                            val isAccentWhite = appColors.primaryAccent == Color.White || (appColors.isAmoled && appColors.primaryAccent == Color.White) || accentLum > 0.70f
                            val doneBorder = BorderStroke(
                                1.2.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = if (isDark) 0.65f else 0.85f),
                                        appColors.primaryAccent.copy(alpha = 0.45f)
                                    )
                                )
                            )
                            val doneBg = if (isAccentWhite) {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFFFFFFF),
                                        Color(0xFFE4E4E7)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = if (isDark) 0.94f else 0.96f),
                                        appColors.primaryAccent.copy(alpha = if (isDark) 0.78f else 0.84f)
                                    )
                                )
                            }
                            val doneTextColor = if (isAccentWhite) Color(0xFF0A0A0A) else contrastingContentColor(appColors.primaryAccent)

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Transparent,
                                border = doneBorder,
                                modifier = Modifier
                                    .weight(0.75f)
                                    .height(42.dp)
                                    .shadow(
                                        elevation = 8.dp,
                                        shape = RoundedCornerShape(14.dp),
                                        spotColor = if (isAccentWhite) Color.White.copy(alpha = 0.35f) else appColors.primaryAccent.copy(alpha = 0.50f),
                                        ambientColor = Color.Transparent
                                    )
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(doneBg)
                                    .bouncyClickable { closeWithAnimation() }
                                    .testTag("done_output_devices_button")
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!isAccentWhite) {
                                        // Inner Gaussian blur
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(
                                                    Brush.radialGradient(
                                                        listOf(Color.White.copy(alpha = 0.25f), Color.Transparent)
                                                    )
                                                )
                                                .blur(12.dp)
                                        )
                                        // Top specular sheen
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(Color.White.copy(alpha = 0.25f), Color.Transparent),
                                                        startY = 0f,
                                                        endY = 22f
                                                    )
                                                )
                                        )
                                    }
                                    Text(
                                        text = "Done",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = doneTextColor
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
}

@Composable
private fun DeviceItemRow(
    device: SoundOutputDevice,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isSelected = device.isSelected
    val icon = getDeviceIcon(device)
    val itemShape = RoundedCornerShape(16.dp)

    val itemBorder = if (isSelected) {
        BorderStroke(
            1.2.dp,
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (isDark) 0.65f else 0.85f),
                    appColors.primaryAccent.copy(alpha = 0.55f),
                    Color.White.copy(alpha = if (isDark) 0.20f else 0.30f)
                )
            )
        )
    } else {
        BorderStroke(
            1.dp,
            LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.32f else 0.42f)
        )
    }

    val itemBg = if (isSelected) {
        if (isDark) {
            Brush.verticalGradient(
                listOf(
                    appColors.primaryAccent.copy(alpha = 0.34f),
                    appColors.primaryAccent.copy(alpha = 0.18f),
                    Color(0xFF141C2B).copy(alpha = 0.25f)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    appColors.primaryAccent.copy(alpha = 0.26f),
                    appColors.primaryAccent.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.32f)
                )
            )
        }
    } else {
        LiquidGlass.miniPlayerAndBottomBarBrush(appColors)
    }

    Surface(
        shape = itemShape,
        color = Color.Transparent,
        border = itemBorder,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isSelected) 6.dp else 2.dp,
                shape = itemShape,
                spotColor = if (isSelected) appColors.primaryAccent.copy(alpha = if (isDark) 0.50f else 0.30f) else Color.Black.copy(alpha = if (isDark) 0.30f else 0.05f),
                ambientColor = Color.Transparent
            )
            .clip(itemShape)
            .background(itemBg)
            .bouncyClickable(targetScaleOnPress = 0.97f) { onClick() }
            .testTag("device_row_${device.id}")
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSelected) {
                // 1. Theme accent Gaussian blur layer
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(itemShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    appColors.primaryAccent.copy(alpha = if (isDark) 0.55f else 0.40f),
                                    appColors.primaryAccent.copy(alpha = if (isDark) 0.25f else 0.18f),
                                    Color.Transparent
                                )
                            )
                        )
                        .blur(14.dp)
                )
                // 2. Optical frosted diffusion blur layer
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(itemShape)
                        .background(
                            if (isDark) {
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.03f)))
                            } else {
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.16f)))
                            }
                        )
                        .blur(10.dp)
                )
                // 3. Top specular reflection sheen
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(itemShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isDark) 0.38f else 0.50f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 24f
                            )
                        )
                )
            } else {
                // Unselected subtle frosted Gaussian blur layer
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(itemShape)
                        .background(
                            if (isDark) {
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent))
                            } else {
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent))
                            }
                        )
                        .blur(10.dp)
                )
                // Unselected top specular sheen
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(itemShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isDark) 0.15f else 0.25f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 20f
                            )
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) {
                                    appColors.primaryAccent.copy(alpha = if (isDark) 0.25f else 0.18f)
                                } else {
                                    if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
                                }
                            )
                            .border(
                                1.dp,
                                if (isSelected) {
                                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), appColors.primaryAccent.copy(alpha = 0.40f)))
                                } else {
                                    LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.25f else 0.35f)
                                },
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Brush.radialGradient(listOf(appColors.primaryAccent.copy(alpha = 0.40f), Color.Transparent)))
                                    .blur(8.dp)
                            )
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) appColors.primaryAccent else appColors.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.name,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = if (isSelected && !isDark) appColors.primaryAccent else appColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = device.typeName,
                            fontSize = 10.5.sp,
                            color = if (isSelected) appColors.primaryAccent else appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right: Active badge or Radio pill
                if (isSelected) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.60f),
                                    appColors.primaryAccent.copy(alpha = 0.50f)
                                )
                            )
                        ),
                        modifier = Modifier
                            .shadow(
                                elevation = 4.dp,
                                shape = RoundedCornerShape(8.dp),
                                spotColor = appColors.primaryAccent.copy(alpha = 0.40f)
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        appColors.primaryAccent.copy(alpha = if (isDark) 0.30f else 0.22f),
                                        appColors.primaryAccent.copy(alpha = if (isDark) 0.15f else 0.10f)
                                    )
                                )
                            )
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Brush.radialGradient(listOf(appColors.primaryAccent.copy(alpha = 0.40f), Color.Transparent)))
                                    .blur(6.dp)
                            )
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = if (appColors.isAmoled) appColors.onPrimaryAccent else if (isDark) Color.White else appColors.primaryAccent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "ACTIVE",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (appColors.isAmoled) appColors.onPrimaryAccent else if (isDark) Color.White else appColors.primaryAccent
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
                            .border(
                                1.2.dp,
                                LiquidGlass.specularBorderBrush(appColors, highlightAlpha = if (isDark) 0.30f else 0.40f),
                                CircleShape
                            )
                    )
                }
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
