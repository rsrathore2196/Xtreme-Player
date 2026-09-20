package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.OtherSettingsPreferences
import com.example.ui.theme.LocalAppColors

@Composable
fun OtherSettingsPage(
    isDarkMode: Boolean,
    onTextScaleChanged: (Int) -> Unit = {},
    onUiScaleChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val inputBg = appColors.cardBackgroundElevated
    val accentColor = appColors.primaryAccent
    val dividerColor = appColors.dividerColor

    // Proxy state
    var isProxyEnabled by remember { mutableStateOf(OtherSettingsPreferences.isProxyEnabled(context)) }
    var proxyHost by remember { mutableStateOf(OtherSettingsPreferences.getProxyHost(context)) }
    var proxyPort by remember { mutableStateOf(OtherSettingsPreferences.getProxyPort(context).toString()) }
    var proxyType by remember { mutableStateOf(OtherSettingsPreferences.getProxyType(context)) }
    var proxySavedMessage by remember { mutableStateOf(false) }

    // Cache state
    var cacheSizeBytes by remember { mutableStateOf(OtherSettingsPreferences.calculateCacheSizeBytes(context)) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheClearedMessage by remember { mutableStateOf(false) }

    // Text Size & App UI Size states
    var selectedTextSizeIndex by remember {
        mutableIntStateOf(OtherSettingsPreferences.getTextSizeIndex(context))
    }
    var selectedUiSizeIndex by remember {
        mutableIntStateOf(OtherSettingsPreferences.getUiSizeIndex(context))
    }
    var isHapticsEnabled by remember {
        mutableStateOf(OtherSettingsPreferences.isHapticsEnabled(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 1. Clear Cache & Storage Card
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
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Storage & Cache",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Manage temporary audio chunks and image cache",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = inputBg,
                    border = BorderStroke(1.dp, cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(
                                text = "Temporary Cache Usage",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Cover art bitmaps & audio buffers",
                                fontSize = 11.sp,
                                color = appColors.textMuted
                            )
                        }

                        Text(
                            text = OtherSettingsPreferences.formatBytes(cacheSizeBytes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF4444)
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("button_clear_cache")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear Cache Now",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (cacheClearedMessage) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "✓ Cache successfully cleared!",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Text Size Setting Card
        val textOptions = OtherSettingsPreferences.TEXT_SIZE_OPTIONS
        val currentTextOption = textOptions.getOrElse(selectedTextSizeIndex) { textOptions[OtherSettingsPreferences.DEFAULT_TEXT_SIZE_INDEX] }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth().testTag("card_text_size_setting")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FormatSize,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Text Size",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Scale font size throughout the app",
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }

                    // Badge showing current selection
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = accentColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "${currentTextOption.label} (${currentTextOption.badge})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stepper Row (- and + controls with visual indicator dots)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(inputBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (selectedTextSizeIndex > 0) {
                                val newIdx = selectedTextSizeIndex - 1
                                selectedTextSizeIndex = newIdx
                                OtherSettingsPreferences.setTextSizeIndex(context, newIdx)
                                onTextScaleChanged(newIdx)
                            }
                        },
                        enabled = selectedTextSizeIndex > 0,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Decrease Text Size",
                            tint = if (selectedTextSizeIndex > 0) accentColor else appColors.textMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Step Indicator Dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        textOptions.forEachIndexed { index, opt ->
                            val isSelected = index == selectedTextSizeIndex
                            val isDefault = index == OtherSettingsPreferences.DEFAULT_TEXT_SIZE_INDEX
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 14.dp else if (isDefault) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isSelected -> accentColor
                                            isDefault -> accentColor.copy(alpha = 0.45f)
                                            else -> appColors.cardBorder
                                        }
                                    )
                                    .clickable {
                                        selectedTextSizeIndex = index
                                        OtherSettingsPreferences.setTextSizeIndex(context, index)
                                        onTextScaleChanged(index)
                                    }
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (selectedTextSizeIndex < textOptions.lastIndex) {
                                val newIdx = selectedTextSizeIndex + 1
                                selectedTextSizeIndex = newIdx
                                OtherSettingsPreferences.setTextSizeIndex(context, newIdx)
                                onTextScaleChanged(newIdx)
                            }
                        },
                        enabled = selectedTextSizeIndex < textOptions.lastIndex,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Increase Text Size",
                            tint = if (selectedTextSizeIndex < textOptions.lastIndex) accentColor else appColors.textMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Selection Chips: 3 Decrease, 1 Default, 3 Increase
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    textOptions.forEachIndexed { index, option ->
                        val isSelected = index == selectedTextSizeIndex
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor else inputBg,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedTextSizeIndex = index
                                    OtherSettingsPreferences.setTextSizeIndex(context, index)
                                    onTextScaleChanged(index)
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = when (index) {
                                        0 -> "Min"
                                        1 -> "-16%"
                                        2 -> "-8%"
                                        3 -> "Std"
                                        4 -> "+10%"
                                        5 -> "+20%"
                                        6 -> "Max"
                                        else -> "${index}"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) appColors.onPrimaryAccent else appColors.textPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Live Preview Container for Text
                Surface(
                    shape = RoundedCornerShape(12.dp),
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
                                text = "LIVE TYPOGRAPHY PREVIEW",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = accentColor
                            )
                            Text(
                                text = "Scale: ${(currentTextOption.scale * 100).toInt()}%",
                                fontSize = 9.5.sp,
                                color = appColors.textMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Blinding Lights • The Weeknd",
                            fontSize = (15 * currentTextOption.scale).sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "After Hours • Hi-Res FLAC 24-bit/96kHz Lossless Master",
                            fontSize = (12 * currentTextOption.scale).sp,
                            color = appColors.textSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. App UI Size Setting Card
        val uiOptions = OtherSettingsPreferences.UI_SIZE_OPTIONS
        val currentUiOption = uiOptions.getOrElse(selectedUiSizeIndex) { uiOptions[OtherSettingsPreferences.DEFAULT_UI_SIZE_INDEX] }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth().testTag("card_ui_size_setting")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "App UI Size",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Scale interface elements, buttons & density",
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }

                    // Badge showing current selection
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = accentColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = "${currentUiOption.label} (${currentUiOption.badge})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stepper Row (- and + controls with visual indicator dots)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(inputBg)
                        .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (selectedUiSizeIndex > 0) {
                                val newIdx = selectedUiSizeIndex - 1
                                selectedUiSizeIndex = newIdx
                                OtherSettingsPreferences.setUiSizeIndex(context, newIdx)
                                onUiScaleChanged(newIdx)
                            }
                        },
                        enabled = selectedUiSizeIndex > 0,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Decrease UI Size",
                            tint = if (selectedUiSizeIndex > 0) accentColor else appColors.textMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Step Indicator Dots: 3 decrease, 1 default, 3 increase
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        uiOptions.forEachIndexed { index, _ ->
                            val isSelected = index == selectedUiSizeIndex
                            val isDefault = index == OtherSettingsPreferences.DEFAULT_UI_SIZE_INDEX
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 14.dp else if (isDefault) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isSelected -> accentColor
                                            isDefault -> accentColor.copy(alpha = 0.45f)
                                            else -> appColors.cardBorder
                                        }
                                    )
                                    .clickable {
                                        selectedUiSizeIndex = index
                                        OtherSettingsPreferences.setUiSizeIndex(context, index)
                                        onUiScaleChanged(index)
                                    }
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (selectedUiSizeIndex < uiOptions.lastIndex) {
                                val newIdx = selectedUiSizeIndex + 1
                                selectedUiSizeIndex = newIdx
                                OtherSettingsPreferences.setUiSizeIndex(context, newIdx)
                                onUiScaleChanged(newIdx)
                            }
                        },
                        enabled = selectedUiSizeIndex < uiOptions.lastIndex,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Increase UI Size",
                            tint = if (selectedUiSizeIndex < uiOptions.lastIndex) accentColor else appColors.textMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Selection Chips: 3 Decrease, 1 Default, 3 Increase
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiOptions.forEachIndexed { index, _ ->
                        val isSelected = index == selectedUiSizeIndex
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor else inputBg,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accentColor else cardBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedUiSizeIndex = index
                                    OtherSettingsPreferences.setUiSizeIndex(context, index)
                                    onUiScaleChanged(index)
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = when (index) {
                                        0 -> "Min"
                                        1 -> "-12%"
                                        2 -> "-6%"
                                        3 -> "Std"
                                        4 -> "+6%"
                                        5 -> "+12%"
                                        6 -> "Max"
                                        else -> "${index}"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) appColors.onPrimaryAccent else appColors.textPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Live Preview Container for UI Elements
                Surface(
                    shape = RoundedCornerShape(12.dp),
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
                                text = "LIVE UI DENSITY PREVIEW",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = accentColor
                            )
                            Text(
                                text = "Density: ${(currentUiOption.scale * 100).toInt()}%",
                                fontSize = 9.5.sp,
                                color = appColors.textMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy((10 * currentUiOption.scale).dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Mini Art Box
                            Box(
                                modifier = Modifier
                                    .size((40 * currentUiOption.scale).dp)
                                    .clip(RoundedCornerShape((8 * currentUiOption.scale).dp))
                                    .background(accentColor.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size((20 * currentUiOption.scale).dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Compact Component",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.textPrimary
                                )
                                Text(
                                    text = "Adaptive scale factor ${currentUiOption.scale}x",
                                    fontSize = 11.sp,
                                    color = appColors.textMuted
                                )
                            }
                            // Mini Action Button
                            Surface(
                                shape = RoundedCornerShape((16 * currentUiOption.scale).dp),
                                color = accentColor,
                                modifier = Modifier.size(
                                    width = (48 * currentUiOption.scale).dp,
                                    height = (32 * currentUiOption.scale).dp
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = appColors.onPrimaryAccent,
                                        modifier = Modifier.size((16 * currentUiOption.scale).dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3.5. Haptic Feedback Setting Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth().testTag("card_haptic_feedback")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Vibration,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                text = "Haptic Feedback",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = "Tactile vibration response on taps & controls",
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }
                    }

                    Switch(
                        checked = isHapticsEnabled,
                        onCheckedChange = {
                            isHapticsEnabled = it
                            OtherSettingsPreferences.setHapticsEnabled(context, it)
                            if (it) {
                                com.example.util.AppHaptics.performTap(context)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = appColors.onPrimaryAccent,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("switch_haptics_enabled")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Proxy Settings Card (Privacy & Custom Network - moved to last & bottom)
        ProxySettingsCard(
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = accentColor,
            dividerColor = dividerColor,
            inputBg = inputBg,
            isProxyEnabled = isProxyEnabled,
            onProxyEnabledChange = {
                isProxyEnabled = it
                OtherSettingsPreferences.setProxyEnabled(context, it)
            },
            proxyType = proxyType,
            onProxyTypeChange = {
                proxyType = it
                OtherSettingsPreferences.setProxyType(context, it)
            },
            proxyHost = proxyHost,
            onProxyHostChange = { proxyHost = it },
            proxyPort = proxyPort,
            onProxyPortChange = { input ->
                if (input.all { it.isDigit() } && input.length <= 5) {
                    proxyPort = input
                }
            },
            onSaveProxy = {
                val portInt = proxyPort.toIntOrNull() ?: 8080
                OtherSettingsPreferences.setProxyHost(context, proxyHost)
                OtherSettingsPreferences.setProxyPort(context, portInt)
                OtherSettingsPreferences.setProxyType(context, proxyType)
                proxySavedMessage = true
            },
            proxySavedMessage = proxySavedMessage
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            containerColor = appColors.cardBackground,
            title = {
                Text(
                    text = "Clear Application Cache?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = appColors.textPrimary
                )
            },
            text = {
                Text(
                    text = "This will clear temporary album art images and stream buffers (${OtherSettingsPreferences.formatBytes(cacheSizeBytes)}). Your playlists and favorite tracks will not be affected.",
                    fontSize = 13.sp,
                    color = appColors.textMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        OtherSettingsPreferences.clearCache(context)
                        cacheSizeBytes = 0L
                        cacheClearedMessage = true
                        showClearCacheDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    )
                ) {
                    Text("Clear", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel", color = appColors.textSecondary)
                }
            }
        )
    }
}

/**
 * 4. Proxy Settings Card (Privacy & Custom Network)
 * Moved to the bottom of the Other Settings menu
 */
@Composable
private fun ProxySettingsCard(
    cardBg: Color,
    cardBorder: Color,
    accentColor: Color,
    dividerColor: Color,
    inputBg: Color,
    isProxyEnabled: Boolean,
    onProxyEnabledChange: (Boolean) -> Unit,
    proxyType: String,
    onProxyTypeChange: (String) -> Unit,
    proxyHost: String,
    onProxyHostChange: (String) -> Unit,
    proxyPort: String,
    onProxyPortChange: (String) -> Unit,
    onSaveProxy: () -> Unit,
    proxySavedMessage: Boolean,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = "Proxy Settings",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                        Text(
                            text = "Route network traffic through private proxy",
                            fontSize = 11.5.sp,
                            color = appColors.textMuted
                        )
                    }
                }

                Switch(
                    checked = isProxyEnabled,
                    onCheckedChange = onProxyEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = appColors.onPrimaryAccent,
                        checkedTrackColor = accentColor
                    ),
                    modifier = Modifier.testTag("switch_proxy_enabled")
                )
            }

            if (isProxyEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = dividerColor, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Proxy Protocol Selection (HTTP vs SOCKS5)
                Text(
                    text = "PROXY PROTOCOL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = appColors.textMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("HTTP", "SOCKS5").forEach { type ->
                        val isSelected = proxyType.equals(type, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.18f) else inputBg,
                            border = BorderStroke(1.dp, if (isSelected) accentColor else cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onProxyTypeChange(type) }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = type,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) accentColor else appColors.textPrimary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Proxy Host
                Text(
                    text = "Proxy Server Host",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = proxyHost,
                    onValueChange = onProxyHostChange,
                    placeholder = { Text("127.0.0.1 or proxy.example.com", fontSize = 12.sp, color = appColors.textMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputBg,
                        unfocusedContainerColor = inputBg,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = appColors.textPrimary,
                        unfocusedTextColor = appColors.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_proxy_host")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Proxy Port
                Text(
                    text = "Proxy Server Port",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = onProxyPortChange,
                    placeholder = { Text("8080", fontSize = 12.sp, color = appColors.textMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputBg,
                        unfocusedContainerColor = inputBg,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = appColors.textPrimary,
                        unfocusedTextColor = appColors.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_proxy_port")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Save Proxy Button
                Button(
                    onClick = onSaveProxy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("btn_save_proxy")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = appColors.onPrimaryAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Save Proxy Configuration",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = appColors.onPrimaryAccent
                    )
                }

                if (proxySavedMessage) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "✓ Proxy configuration applied successfully",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }
        }
    }
}
