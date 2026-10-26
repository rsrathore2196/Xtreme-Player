package com.example.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import com.example.ui.components.playerLiquidGlassButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.data.local.OtherSettingsPreferences
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import kotlin.math.roundToInt

/**
 * Compact round button for changing lyrics text size.
 * Matches the exact liquid glass effect, method, 36dp disk, and 48dp touch target of the lyrics maximize button.
 */
@Composable
fun LyricsTextSizeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(36.dp)
                .offset(x = (-4).dp)
                .playerLiquidGlassButton(colors, shape = CircleShape, elevation = 3.dp)
        )
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .testTag("player_lyrics_text_size_button")
        ) {
            Icon(
                imageVector = Icons.Default.FormatSize,
                contentDescription = "Change lyrics text size",
                tint = colors.textPrimary,
                modifier = Modifier.size(20.dp).offset(x = (-4).dp)
            )
        }
    }
}

@Composable
internal fun LyricsTextTransformButton(
    romanize: Boolean,
    active: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val action = if (romanize) "Romanize lyrics" else "Translate lyrics"
    val offset = if (romanize) (-4).dp else 4.dp
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).offset(x = offset).playerLiquidGlassButton(colors, shape = CircleShape, elevation = 3.dp))
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.fillMaxSize().testTag(if (romanize) "lyrics_romanize_button" else "lyrics_translate_button")
                .semantics { contentDescription = action; stateDescription = if (loading) "Loading" else if (active) "On" else "Off" }) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp).offset(x = offset), color = colors.primaryAccent, strokeWidth = 2.dp)
            else Icon(if (romanize) Icons.Default.Language else Icons.Default.Translate,
                contentDescription = null, modifier = Modifier.size(20.dp).offset(x = offset),
                tint = if (active) colors.primaryAccent else colors.textPrimary.copy(alpha = if (enabled) 1f else 0.4f))
        }
    }
}

/**
 * Compact pill button displaying the current lyrics provider in the bottom center of the lyrics view.
 * Sized 30% larger with a clean integrated background surface and no separate outer frame.
 */
@Composable
fun LyricsProviderPill(
    providerName: String,
    onClick: () -> Unit,
    isEffectivelyDark: Boolean = true,
    modifier: Modifier = Modifier
) {
    val chipContentColor = if (isEffectivelyDark) Color.White else Color(0xFF0F172A)
    val chipBorderColor = if (isEffectivelyDark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.22f)
    val chipBgColor = if (isEffectivelyDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    Surface(
        onClick = onClick,
        color = chipBgColor,
        border = BorderStroke(1.dp, chipBorderColor),
        shape = RoundedCornerShape(13.dp),
        modifier = modifier.testTag("lyrics_provider_pill")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(chipContentColor)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = lyricsProviderDisplayName(providerName),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = chipContentColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun lyricsProviderDisplayName(providerName: String): String =
    providerName.trim().takeWhile { !it.isWhitespace() }.ifBlank { "Auto" }

/**
 * Modern compact round corner popup with a smooth slider to adjust lyrics text size.
 * Changes apply directly in real-time as the slider moves without needing an apply button.
 * Window dimming is disabled so the music player and album art stay visible without a harsh black backdrop.
 */
@Composable
fun LyricsTextSizePopup(
    currentSizeSp: Int,
    onSizeChange: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    appColors: AppThemeColors = LocalAppColors.current
) {
    @Suppress("NAME_SHADOWING") val appColors = rememberPlayerPopupColors(appColors)
    PlayerPopupTheme(appColors) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            DisposableEffect(window) {
                window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.setDimAmount(0f)
                onDispose { }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 340.dp)
                        .padding(20.dp)
                        .testTag("lyrics_text_size_popup")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Consume taps inside card */ },
                    shape = RoundedCornerShape(26.dp),
                    color = appColors.cardBackground,
                    shadowElevation = 20.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Lyrics Text Size",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = appColors.textPrimary
                                )
                            )
                            Surface(
                                color = appColors.primaryAccent.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "$currentSizeSp sp",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = appColors.primaryAccent
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        var sliderValue by remember(currentSizeSp) { mutableFloatStateOf(currentSizeSp.toFloat()) }
                        Slider(
                            value = sliderValue,
                            onValueChange = { newValue ->
                                sliderValue = newValue
                                onSizeChange(newValue.roundToInt())
                            },
                            valueRange = 18f..44f,
                            colors = SliderDefaults.colors(
                                thumbColor = appColors.primaryAccent,
                                activeTrackColor = appColors.primaryAccent,
                                inactiveTrackColor = appColors.textSecondary.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("lyrics_size_slider")
                        )

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("18 sp", style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted, fontSize = 11.sp))
                            Text("Default (32 sp)", style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted, fontSize = 11.sp))
                            Text("44 sp", style = MaterialTheme.typography.bodySmall.copy(color = appColors.textMuted, fontSize = 11.sp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modern compact round corner popup for selecting lyrics providers.
 * Tapping a provider directly applies the change and dismisses the popup (no close button).
 * Has transparent window properties so it appears cleanly over the music player screen.
 */
@Composable
fun LyricsProviderSelectionPopup(
    currentProvider: String,
    onSelectProvider: (String) -> Unit,
    onDismissRequest: () -> Unit,
    appColors: AppThemeColors = LocalAppColors.current,
    allowImport: Boolean = false,
    isCustomSelected: Boolean = false,
    hasCustomLyrics: Boolean = false,
    onImportLyrics: () -> Unit = {},
    onSelectCustomLyrics: () -> Unit = {}
) {
    // The shared Settings selector keeps its existing presentation.
    val usePlayerTheme = LocalPlayerPopupColors.current != null || allowImport
    val popupColors = rememberPlayerPopupColors(appColors)
    @Suppress("NAME_SHADOWING") val appColors = if (usePlayerTheme) popupColors else appColors
    PlayerPopupTheme(appColors) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            DisposableEffect(window) {
                window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.setDimAmount(0f)
                onDispose { }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 300.dp, max = 380.dp)
                        .fillMaxWidth(0.88f)
                        .padding(20.dp)
                        .testTag("lyrics_provider_popup")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Consume taps */ },
                    shape = RoundedCornerShape(26.dp),
                    color = if (usePlayerTheme) appColors.cardBackground else if (appColors.isAmoled) Color(0xFF141414) else appColors.cardBackground,
                    border = if (usePlayerTheme) null else BorderStroke(1.2.dp, appColors.cardBorder.copy(alpha = if (appColors.isDark) 0.5f else 0.7f)),
                    shadowElevation = 20.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    ) {
                        Text(
                            text = "Lyrics Provider",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = appColors.textPrimary
                            ),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        Text(
                            text = "Tap a provider to apply immediately",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = appColors.textMuted,
                                fontSize = 11.5.sp
                            ),
                            modifier = Modifier.padding(bottom = 14.dp)
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (allowImport) {
                                Surface(
                                    onClick = {
                                        if (hasCustomLyrics && !isCustomSelected) {
                                            onSelectCustomLyrics()
                                            onDismissRequest()
                                        } else {
                                            onImportLyrics()
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isCustomSelected) appColors.primaryAccent.copy(alpha = 0.12f) else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isCustomSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = appColors.primaryAccent,
                                                unselectedColor = appColors.textMuted
                                            )
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = "Import Lyrics",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isCustomSelected) appColors.primaryAccent else appColors.textPrimary,
                                                    fontSize = 14.sp
                                                )
                                            )
                                            Text(
                                                text = "Import Custom Lyrics .lrc file",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = appColors.textMuted,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            OtherSettingsPreferences.LYRICS_PROVIDERS.forEach { provider ->
                                val isSelected = !isCustomSelected && currentProvider.equals(provider, ignoreCase = true)
                                Surface(
                                    onClick = {
                                        onSelectProvider(provider)
                                        onDismissRequest()
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) appColors.primaryAccent.copy(alpha = 0.12f) else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = appColors.primaryAccent,
                                                unselectedColor = appColors.textMuted
                                            )
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = provider,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) appColors.primaryAccent else appColors.textPrimary,
                                                    fontSize = 14.sp
                                                )
                                            )
                                            val description = OtherSettingsPreferences.getProviderDescription(provider)
                                            if (description.isNotBlank()) {
                                                Text(
                                                    text = description,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = appColors.textMuted,
                                                        fontSize = 11.sp
                                                    )
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
    }
}
