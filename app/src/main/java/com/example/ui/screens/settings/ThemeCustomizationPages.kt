package com.example.ui.screens.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.example.data.local.AppThemePreset
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePresets
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.contrastingContentColor
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import java.util.Locale
import kotlin.math.roundToInt

private fun color(hex: String): Color = runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(Color.Gray)
private fun Color.hex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

@Composable
private fun PaletteFilters(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("All", "Dark", "Light").forEachIndexed { index, title ->
            Text(title, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.liquidGlassPill(colors, isActive = selected == index)
                    .clickable { onSelect(index) }.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
fun ThemePresetsPage(state: CustomThemeState, onApplyPreset: (AppThemePreset) -> Unit) {
    val colors = LocalAppColors.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val presets = remember(filter) {
        when (filter) { 1 -> ThemePresets.darkPresets; 2 -> ThemePresets.lightPresets; else -> ThemePresets.allPresets }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("A palette for every mood", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp))
        Text("Choose a preset to apply it. Your selection is saved automatically.", color = colors.textMuted,
            fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        PaletteFilters(filter) { filter = it }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(presets, key = { it.id }, contentType = { "theme_preset" }) { preset ->
                val selected = state.presetId == preset.id && state.currentThemeName == preset.name
                val accent = remember(preset) { color(preset.accentColorHex) }
                val canvas = remember(preset) { color(preset.canvasColorHex) }
                val card = remember(preset) { color(preset.cardColorHex) }
                val foreground = remember(preset) { color(preset.textPrimaryHex) }
                Column(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(20.dp))
                    .border(if (selected) 2.dp else 1.dp, if (selected) colors.primaryAccent else colors.cardBorder,
                        RoundedCornerShape(20.dp)).clickable { onApplyPreset(preset) }.padding(12.dp)) {
                    Box(Modifier.fillMaxWidth().height(138.dp).clip(RoundedCornerShape(14.dp)).background(canvas)) {
                        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(accent))
                                Text(if (preset.isDark) "DARK" else "LIGHT", color = foreground.copy(alpha = 0.7f), fontSize = 9.sp)
                            }
                            Row(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(card).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Brush.linearGradient(listOf(accent, color(preset.secondaryAccentHex)))))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.fillMaxWidth(0.9f).height(5.dp).clip(CircleShape).background(foreground.copy(alpha = 0.8f)))
                                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(CircleShape).background(foreground.copy(alpha = 0.3f)))
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(25.dp).clip(CircleShape).background(card), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected theme",
                            tint = colors.onPrimaryAccent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                .size(22.dp).clip(CircleShape).background(colors.primaryAccent).padding(3.dp))
                    }
                    Text(preset.name, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(preset.subtitle, color = colors.textMuted, fontSize = 11.sp, lineHeight = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

private fun CustomThemeState.editable(colors: AppThemeColors): CustomThemeState = copy(
    presetId = "", currentThemeName = "Custom", isPresetDark = colors.isDark,
    isAmoled = colors.isAmoled, canvasColorHex = colors.scaffoldBackground.hex(), cardColorHex = colors.cardBackground.hex(),
    cardBorderHex = colors.cardBorder.hex(), textPrimaryHex = colors.textPrimary.hex(),
    textSecondaryHex = colors.textSecondary.hex(), textMutedHex = colors.textMuted.hex())

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CustomThemingPage(state: CustomThemeState, onUpdate: (CustomThemeState) -> Unit) {
    val colors = LocalAppColors.current
    val initial = remember(state.accentColorHex) { FloatArray(3).apply { AndroidColor.colorToHSV(color(state.accentColorHex).toArgb(), this) } }
    var hue by rememberSaveable { mutableFloatStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initial[2]) }
    LaunchedEffect(state.accentColorHex) {
        val draft = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
        if (!draft.hex().equals(state.accentColorHex, true)) {
            hue = initial[0]; saturation = initial[1]; brightness = initial[2]
        }
    }
    val latestState by rememberUpdatedState(state)
    val latestColors by rememberUpdatedState(colors)
    val latestUpdate by rememberUpdatedState(onUpdate)
    val accent = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    fun commitAccent() {
        val selected = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
        if (selected.hex() != latestState.accentColorHex) latestUpdate(latestState.editable(latestColors).copy(
            accentName = "Custom Accent", accentShade = (100 + (1f - brightness) * 800).roundToInt().toString(),
            accentColorHex = selected.hex()))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(22.dp)).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Accent color & hue", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(accent.hex(), color = colors.textSecondary, fontSize = 12.sp)
            }
            Text("Drag to adjust saturation and shade", color = colors.textMuted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            AccentGamut(hue, saturation, brightness, onChange = { s, v -> saturation = s; brightness = v },
                onFinished = ::commitAccent, modifier = Modifier.fillMaxWidth().height(200.dp))
            Text("Hue", color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))))
                Slider(value = hue, onValueChange = { hue = it }, onValueChangeFinished = ::commitAccent,
                    valueRange = 0f..360f, colors = SliderDefaults.colors(thumbColor = Color.hsv(hue % 360f, 1f, 1f),
                        activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeCustomizationPresets.accentPresets.forEach { preset ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(color(preset.hex400))
                        .border(1.dp, colors.cardBorder, CircleShape).semantics { contentDescription = preset.name }
                        .clickable {
                            val hsv = FloatArray(3).apply { AndroidColor.colorToHSV(color(preset.hex400).toArgb(), this) }
                            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; commitAccent()
                        })
                }
            }
        }
        PaletteOptions("Canvas color", state.canvasColorName, state.canvasColorHex, ThemeCustomizationPresets.canvasOptions) { option ->
            val canvas = color(option.hex)
            val foreground = contrastingContentColor(canvas)
            val current = latestState.editable(latestColors)
            latestUpdate(current.copy(canvasColorName = option.name, canvasColorHex = option.hex,
                isAmoled = option.hex == "#000000" && current.cardColorHex == "#000000",
                bgGradientColorsHex = listOf(option.hex, option.hex), bgGradientName = option.name,
                textPrimaryHex = foreground.hex(),
                textSecondaryHex = Color(ColorUtils.blendARGB(foreground.toArgb(), canvas.toArgb(), 0.22f)).hex(),
                textMutedHex = Color(ColorUtils.blendARGB(foreground.toArgb(), canvas.toArgb(), 0.40f)).hex()))
        }
        PaletteOptions("Card color", state.cardColorName, state.cardColorHex, ThemeCustomizationPresets.cardOptions) { option ->
            val current = latestState.editable(latestColors)
            latestUpdate(current.copy(cardColorName = option.name, cardColorHex = option.hex,
                isAmoled = current.canvasColorHex == "#000000" && option.hex == "#000000",
                cardGradientColorsHex = listOf(option.hex, option.hex), cardGradientName = option.name))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteOptions(title: String, selectedName: String, selectedHex: String, options: List<ColorOption>, onSelect: (ColorOption) -> Unit) {
    val colors = LocalAppColors.current
    var filter by rememberSaveable { mutableIntStateOf(if (colors.isDark) 1 else 2) }
    val visible = remember(options, filter) { options.filter { filter == 0 || it.isDark == (filter == 1) } }
    Column(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(22.dp)).padding(16.dp)) {
        Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("$selectedName · $selectedHex", color = colors.textMuted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        PaletteFilters(filter) { filter = it }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.forEach { option ->
                val value = color(option.hex)
                val selected = selectedHex.equals(option.hex, true)
                Box(Modifier.size(44.dp).clip(CircleShape).background(value)
                    .border(if (selected) 2.dp else 1.dp, if (selected) colors.primaryAccent else colors.cardBorder, CircleShape)
                    .semantics { contentDescription = option.name + if (selected) ", selected" else "" }
                    .clickable { onSelect(option) }, contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = contrastingContentColor(value), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun AccentGamut(hue: Float, saturation: Float, brightness: Float, onChange: (Float, Float) -> Unit,
    onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun update(position: Offset) {
                change((position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    (1f - position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            down.consume(); update(down.position)
            while (true) {
                val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                pointer.consume()
                if (!pointer.pressed) { finished(); break }
                update(pointer.position)
            }
        }
    }) {
        drawRect(Color.hsv(hue % 360f, 1f, 1f))
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val inset = 12.dp.toPx()
        val thumb = Offset((saturation * size.width).coerceIn(inset, (size.width - inset).coerceAtLeast(inset)),
            ((1f - brightness) * size.height).coerceIn(inset, (size.height - inset).coerceAtLeast(inset)))
        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 9.dp.toPx(), center = thumb, style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, radius = 9.dp.toPx(), center = thumb, style = Stroke(2.dp.toPx()))
    }
}
