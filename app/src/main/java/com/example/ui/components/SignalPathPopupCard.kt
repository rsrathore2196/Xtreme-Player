package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.playback.AudioEffectsManager
import com.example.playback.AudioSignalPath
import com.example.playback.SignalPathState
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassCard
import java.util.Locale
import kotlin.math.log10

/** Collect at the badge, not at the player root: volume/buffer events do not recompose lyrics/art. */
@Composable
fun AudioQualityBadge(trackId: String, fallbackText: String, background: Color, border: Color,
    content: Color, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val path by AudioSignalPath.state.collectAsStateWithLifecycle()
    val visibility = remember { MutableTransitionState(false) }
    val label = if (path.trackId == trackId && path.quality.verified) path.quality.label else fallbackText
    Box(modifier) {
        Surface(color = Color.Transparent, border = BorderStroke(1.dp, border), shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(background)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Show signal path") { visibility.targetState = true }
                .padding(horizontal = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(content))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall.copy(color = content,
                    fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, letterSpacing = 0.5.sp), maxLines = 1)
            }
        }
        if (visibility.currentState || visibility.targetState || !visibility.isIdle) {
            val position = remember { object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0,
                        (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val y = (anchorBounds.top - popupContentSize.height - 12).coerceIn(0,
                        (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            } }
            Popup(popupPositionProvider = position, onDismissRequest = { visibility.targetState = false },
                properties = PopupProperties(focusable = true)) {
                AnimatedVisibility(visibleState = visibility,
                    enter = scaleIn(spring(dampingRatio = 0.84f, stiffness = 650f), initialScale = 0.94f) +
                        fadeIn(spring(stiffness = 650f)) + expandVertically(spring(dampingRatio = 0.9f, stiffness = 650f)),
                    exit = scaleOut(spring(stiffness = 750f), targetScale = 0.96f) + fadeOut(spring(stiffness = 750f))) {
                    SignalPathPopupCard(if (path.trackId == trackId) path else SignalPathState(),
                        onClarityChange = AudioEffectsManager::setCrystalClarityEnabled)
                }
            }
        }
    }
}

@Composable
fun SignalPathPopupCard(state: SignalPathState, onClarityChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val rate: (Int) -> String = { if (it > 0) String.format(Locale.ROOT, "%.1f kHz", it / 1000.0) else "Not reported" }
    Column(modifier.widthIn(max = 350.dp).width(330.dp).heightIn(max = 450.dp)
        .liquidGlassCard(colors, RoundedCornerShape(24.dp), elevation = 8.dp)
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Text("Signal Path", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Surface(shape = CircleShape, color = colors.primaryAccent.copy(alpha = 0.18f)) {
                Text(state.status, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = colors.primaryAccent,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        SignalNode("Source", "${state.source} • ${state.quality.label}")
        SignalNode("App Resampler", when {
            state.inputRateHz <= 0 || state.outputRateHz <= 0 -> "Awaiting audio format"
            state.appResampling -> "Active — Playback speed / pitch processing"
            state.inputRateHz == state.outputRateHz -> "Bypassed — ${rate(state.inputRateHz)} / ${rate(state.outputRateHz)}"
            else -> "Active — ${rate(state.inputRateHz)} → ${rate(state.outputRateHz)}"
        })
        SignalNode("DSP Chain", when {
            state.bitPerfect -> "Bypassed (Bit-Perfect)"
            state.directApproved -> "Bypassed (Direct PCM)"
            state.dspActive -> "Active — Custom EQ & Clarity"
            else -> "Bypassed (Pure)"
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Clarity Enhancer", color = colors.textPrimary, style = MaterialTheme.typography.labelMedium)
            Switch(checked = state.clarityEnabled, onCheckedChange = onClarityChange, enabled = !state.directApproved)
        }
        val gain = if (state.appVolume > 0f) String.format(Locale.ROOT, "%.1f dB", 20 * log10(state.appVolume.toDouble())) else "Muted"
        SignalNode("Volume / Gain", "App: $gain${if (state.appVolume == 1f) " (Unity)" else ""}\n" +
            "System: ${state.systemVolume}/${state.systemVolumeMax} (Digital Attenuation)")
        SignalNode("Android Mixer", if (state.bitPerfect) "Bit-Perfect Direct" else if (state.directApproved)
            "Direct PCM • ${rate(state.outputRateHz)}" else "Shared AudioTrack • ${rate(state.outputRateHz)} (client)")
        SignalNode("Output Device", "${state.outputDevice} • ${state.outputType}")
        HorizontalDivider(color = colors.textPrimary.copy(alpha = 0.12f))
        Text("Mode: ${state.mode}  •  Buffer: ${if (state.buffering) "Buffering" else "Healthy"}  •  Glitches: ${state.glitches}",
            color = colors.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SignalNode(title: String, value: String) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(colors.primaryAccent))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}
