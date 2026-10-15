package com.example.ui.screens.settings

import android.graphics.Color as AndroidColor
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.example.data.local.AppThemePreset
import com.example.data.local.CustomThemeState
import com.example.data.local.ThemePresets
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    textSecondaryHex = colors.textSecondary.hex(), textMutedHex = colors.textMuted.hex(),
    bgGradientColorsHex = if (isPresetDark == colors.isDark) bgGradientColorsHex
        else listOf(colors.scaffoldBackground.hex(), colors.scaffoldBackground.hex()))

private fun CustomThemeState.colorKey() = listOf(presetId, currentThemeName, isPresetDark,
    accentColorHex, canvasColorName, canvasColorHex, cardColorName, cardColorHex).joinToString("|")

private fun readableContent(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

@Composable
fun CustomThemingPage(state: CustomThemeState, onUpdate: (CustomThemeState) -> Unit) {
    val colors = LocalAppColors.current
    val initial = remember(state, colors.isDark) { state.editable(colors) }
    val initialHsv = remember(initial.accentColorHex) {
        FloatArray(3).apply { AndroidColor.colorToHSV(color(initial.accentColorHex).toArgb(), this) }
    }
    // Only this local draft changes while editing. The existing persistent callback runs on Save.
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    var canvasName by rememberSaveable { mutableStateOf(initial.canvasColorName) }
    var canvasHex by rememberSaveable { mutableStateOf(initial.canvasColorHex) }
    var cardName by rememberSaveable { mutableStateOf(initial.cardColorName) }
    var cardHex by rememberSaveable { mutableStateOf(initial.cardColorHex) }
    var baselineKey by rememberSaveable { mutableStateOf(state.colorKey()) }
    var submittedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var savedNotice by rememberSaveable { mutableStateOf(false) }
    val incomingKey = remember(state) { state.colorKey() }
    LaunchedEffect(incomingKey) {
        if (incomingKey == submittedKey) {
            // Keep the chosen hue/saturation even when saving black; RGB alone loses them.
            baselineKey = incomingKey
            submittedKey = null
        } else if (incomingKey != baselineKey) {
            hue = initialHsv[0]; saturation = initialHsv[1]; brightness = initialHsv[2]
            canvasName = initial.canvasColorName; canvasHex = initial.canvasColorHex
            cardName = initial.cardColorName; cardHex = initial.cardColorHex
            baselineKey = incomingKey; submittedKey = null; savedNotice = false
        }
    }
    val accent = Color.hsv(hue % 360f, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val accentHex = accent.hex()
    val accentChanged = !accentHex.equals(initial.accentColorHex, true)
    val canvasChanged = !canvasHex.equals(initial.canvasColorHex, true) || canvasName != initial.canvasColorName
    val cardChanged = !cardHex.equals(initial.cardColorHex, true) || cardName != initial.cardColorName
    val hasChanges = accentChanged || canvasChanged || cardChanged
    val canvas = remember(canvasHex) { color(canvasHex) }
    val card = remember(cardHex) { color(cardHex) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Make it yours", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (hasChanges) "Preview your palette, then save" else if (savedNotice) "Your colors are saved" else "Changes apply only when you save",
                    color = colors.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Button(enabled = hasChanges && submittedKey == null, shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = readableContent(accent),
                    disabledContainerColor = colors.cardBackgroundElevated, disabledContentColor = colors.textMuted),
                onClick = {
                    var updated = initial.copy(isAmoled = canvasHex == "#000000" && cardHex == "#000000")
                    if (accentChanged) updated = updated.copy(accentName = "Custom Accent",
                        accentShade = (100 + (1f - brightness) * 800).roundToInt().toString(), accentColorHex = accentHex)
                    if (canvasChanged) {
                        val foreground = readableContent(canvas)
                        updated = updated.copy(canvasColorName = canvasName, canvasColorHex = canvasHex,
                            bgGradientColorsHex = listOf(canvasHex, canvasHex), bgGradientName = canvasName,
                            textPrimaryHex = foreground.hex(),
                            textSecondaryHex = Color(ColorUtils.blendARGB(foreground.toArgb(), canvas.toArgb(), 0.22f)).hex(),
                            textMutedHex = Color(ColorUtils.blendARGB(foreground.toArgb(), canvas.toArgb(), 0.40f)).hex())
                    }
                    if (cardChanged) updated = updated.copy(cardColorName = cardName, cardColorHex = cardHex,
                        cardGradientColorsHex = listOf(cardHex, cardHex), cardGradientName = cardName)
                    submittedKey = updated.colorKey()
                    onUpdate(updated)
                    savedNotice = true
                }) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ThemeColorPreview(accent, canvas, card)
            Column(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(24.dp)).padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Accent color", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(accentHex, color = colors.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Text("Choose any color on the spectrum", color = colors.textMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                AccentGamut(hue, saturation, accentHex, onChange = { h, s ->
                    hue = h; saturation = s; savedNotice = false
                }, modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp)
                    .fillMaxWidth().aspectRatio(1f))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Color depth", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${((1f - brightness) * 100).roundToInt()}%", color = colors.textMuted, fontSize = 12.sp)
                }
                val vivid = remember(hue, saturation) { Color.hsv(hue % 360f, saturation, 1f) }
                val depthBrush = remember(vivid) { Brush.horizontalGradient(listOf(vivid, Color.Black)) }
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp).clip(CircleShape).background(depthBrush))
                    Slider(value = 1f - brightness, onValueChange = { brightness = 1f - it; savedNotice = false },
                        valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = accent,
                            activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                        modifier = Modifier.semantics { contentDescription = "Accent color depth" })
                }
                Text("A lighter or deeper shade of the same color", color = colors.textMuted, fontSize = 11.sp)
            }
            PaletteOptions("Canvas color", canvasName, canvasHex, ThemeCustomizationPresets.canvasOptions) { option ->
                canvasName = option.name; canvasHex = option.hex; savedNotice = false
            }
            PaletteOptions("Card color", cardName, cardHex, ThemeCustomizationPresets.cardOptions) { option ->
                cardName = option.name; cardHex = option.hex; savedNotice = false
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ThemeColorPreview(accent: Color, canvas: Color, card: Color) {
    val canvasText = readableContent(canvas)
    val cardText = readableContent(card)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(canvas)
        .border(1.dp, canvasText.copy(alpha = 0.12f), RoundedCornerShape(24.dp)).padding(16.dp)) {
        Text("LIVE PREVIEW", color = canvasText.copy(alpha = 0.65f), fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(card)
            .padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = readableContent(accent), modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Your music. Your colors.", color = cardText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Accent · Canvas · Cards", color = cardText.copy(alpha = 0.65f), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteOptions(title: String, selectedName: String, selectedHex: String, options: List<ColorOption>, onSelect: (ColorOption) -> Unit) {
    val colors = LocalAppColors.current
    var filter by rememberSaveable { mutableIntStateOf(if (colors.isDark) 1 else 2) }
    val visible = remember(options, filter) { options.filter { filter == 0 || it.isDark == (filter == 1) } }
    Column(Modifier.fillMaxWidth().liquidGlassCard(colors, shape = RoundedCornerShape(24.dp)).padding(18.dp)) {
        Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("$selectedName · $selectedHex", color = colors.textMuted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        PaletteFilters(filter) { filter = it }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.forEach { option ->
                val value = remember(option.hex) { color(option.hex) }
                val selected = selectedHex.equals(option.hex, true)
                Column(Modifier.width(62.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) colors.primaryAccent.copy(alpha = 0.10f) else Color.Transparent)
                    .border(1.dp, if (selected) colors.primaryAccent else colors.cardBorder, RoundedCornerShape(14.dp))
                    .semantics { contentDescription = option.name + if (selected) ", selected" else "" }
                    .clickable { onSelect(option) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(value)
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = readableContent(value), modifier = Modifier.size(18.dp))
                    }
                    Text(option.name, color = colors.textSecondary, fontSize = 9.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AccentGamut(hue: Float, saturation: Float, hex: String, onChange: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val currentHue = rememberUpdatedState(hue)
    val currentSaturation = rememberUpdatedState(saturation)
    val change by rememberUpdatedState(onChange)
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (minOf(size.width, size.height) / 2f - 12.dp.toPx()).coerceAtLeast(1f)
            if ((down.position - center).getDistance() > radius) return@awaitEachGesture
            fun update(position: Offset) {
                val offset = position - center
                val degrees = ((atan2(offset.y.toDouble(), offset.x.toDouble()) * 180.0 / Math.PI + 360.0) % 360.0).toFloat()
                change(degrees, (offset.getDistance() / radius).coerceIn(0f, 1f))
            }
            down.consume(); update(down.position)
            while (true) {
                val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!pointer.pressed) break
                pointer.consume(); update(pointer.position)
            }
        }
    }.semantics {
        contentDescription = "Accent color spectrum"
        stateDescription = hex
        customActions = listOf(
            CustomAccessibilityAction("Next hue") { change((currentHue.value + 15f) % 360f, currentSaturation.value); true },
            CustomAccessibilityAction("Previous hue") { change((currentHue.value + 345f) % 360f, currentSaturation.value); true },
            CustomAccessibilityAction("More vivid") { change(currentHue.value, (currentSaturation.value + 0.1f).coerceAtMost(1f)); true },
            CustomAccessibilityAction("Less vivid") { change(currentHue.value, (currentSaturation.value - 0.1f).coerceAtLeast(0f)); true })
    }.drawWithCache {
        val radius = (size.minDimension / 2f - 12.dp.toPx()).coerceAtLeast(1f)
        val center = Offset(size.width / 2f, size.height / 2f)
        // Cache the spectrum shaders for unchanged geometry; read selector state in drawing.
        val spectrum = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), center)
        val saturationGradient = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius)
        onDrawBehind {
            drawCircle(spectrum, radius, center)
            drawCircle(saturationGradient, radius, center)
            val radians = currentHue.value * Math.PI / 180.0
            val distance = currentSaturation.value * radius
            val thumb = center + Offset((cos(radians) * distance).toFloat(), (sin(radians) * distance).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.5f), 10.dp.toPx(), thumb, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, 10.dp.toPx(), thumb, style = Stroke(2.dp.toPx()))
        }
    })
}
