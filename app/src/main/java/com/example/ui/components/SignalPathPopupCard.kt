package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.playback.AudioEffectsManager
import com.example.playback.AudioSignalPath
import com.example.playback.SignalPathState
import com.example.ui.theme.LocalAppColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Only this small subtree observes live audio data; artwork/lyrics never observe animation frames. */
@Composable
fun AudioQualityBadge(trackId: String, @Suppress("UNUSED_PARAMETER") fallbackText: String,
    background: Color, border: Color, content: Color, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val path by AudioSignalPath.state.collectAsStateWithLifecycle()
    var showPipeline by remember(trackId) { mutableStateOf(false) }
    val current = path.takeIf { it.trackId == trackId } ?: SignalPathState()
    val label = current.quality.takeIf { it.verified && it.codec.isNotBlank() }?.badgeLabel ?: "Resolving audio"
    Surface(color = background, border = androidx.compose.foundation.BorderStroke(1.dp, border),
        shape = RoundedCornerShape(10.dp), modifier = modifier.clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Show audio pipeline") { showPipeline = true }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(content))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = content,
                fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, letterSpacing = 0.5.sp), maxLines = 1)
        }
    }
    if (showPipeline) AudioPipelineDialog(current) { showPipeline = false }
}

@Composable
private fun AudioPipelineDialog(state: SignalPathState, onDismiss: () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss = { visible.targetState = false }
    LaunchedEffect(visible.currentState, visible.targetState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onDismiss()
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val root = view.rootView
            val elevation = root.elevation
            root.elevation = 0f
            onDispose { root.elevation = elevation }
        }
        AnimatedVisibility(visibleState = visible,
            enter = fadeIn(tween(180)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.95f),
            exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.97f)) {
            SignalPathPopupCard(state, Modifier.padding(16.dp), onClose = dismiss)
        }
    }
}

private fun rate(hz: Int): String = if (hz > 0) String.format(Locale.ROOT, "%.1f kHz", hz / 1000.0) else "—"
private fun encoding(value: Int): String = SignalPathState.pcmEncodingLabel(value).takeUnless { it == "Format not reported" } ?: "—"

/** Source, decoder and client output are separate measurements. Unknown hardware data stays unknown. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun SignalPathPopupCard(state: SignalPathState, modifier: Modifier = Modifier, onClose: () -> Unit = {}) {
    val colors = LocalAppColors.current
    val effects by AudioEffectsManager.effectsState.collectAsStateWithLifecycle()
    val cardColor = if (colors.isAmoled) Color(0xFF121212) else colors.cardBackground
    val height = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(120).dp
    val textColor = colors.textPrimary
    val muted = colors.textSecondary
    val quality = state.quality
    val known = quality.verified
    val centers = remember { mutableStateMapOf<Int, Offset>() }
    val bodyCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Reading progress only in Canvas avoids re-composing or re-measuring the rows each frame.
    val pulse = rememberVisibleInfiniteFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), "pipeline_signal",
        active = state.playbackStatus == "Playing")
    val flowAlpha = animateFloatAsState(if (state.playbackStatus == "Playing") 1f else 0f,
        tween(if (state.playbackStatus == "Playing") 450 else 320), label = "pipeline_power")

    Surface(shape = RoundedCornerShape(26.dp), color = cardColor, tonalElevation = 0.dp, shadowElevation = 0.dp,
        modifier = modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = height).testTag("signal_path_popup")) {
        Column {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 19.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Audio Pipeline", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor, textAlign = TextAlign.Center)
                    Text("Live signal path, decoder to output", Modifier.padding(top = 4.dp), fontSize = 13.sp,
                        lineHeight = 17.sp, color = muted, textAlign = TextAlign.Center)
                }
                FilledTonalIconButton(onClick = onClose,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = textColor.copy(alpha = 0.08f), contentColor = textColor),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(28.dp).testTag("pipeline_close")) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close audio pipeline", modifier = Modifier.size(16.dp))
                }
            }
            Box(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                .testTag("audio_pipeline_body")) {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { bodyCoordinates[0] = it }) {
                    Canvas(Modifier.matchParentSize().graphicsLayer {}) {
                        val points = (0..4).mapNotNull { centers[it] }
                        if (points.size != 5) return@Canvas
                        val radius = 11.5.dp.toPx()
                        val start = points.first().y
                        val length = points.last().y - start
                        val progress = pulse.value
                        val power = flowAlpha.value
                        val headY = start + (progress / 0.85f).coerceIn(0f, 1f) * length
                        val fade = (1f - ((progress - 0.85f) / 0.1f).coerceIn(0f, 1f)) * power
                        points.zipWithNext().forEach { (from, to) ->
                            val top = from.y + radius
                            val bottom = to.y - radius
                            if (bottom <= top) return@forEach
                            drawLine(textColor.copy(alpha = 0.2f + 0.06f * power), Offset(from.x, top),
                                Offset(from.x, bottom), 2.dp.toPx())
                            val tail = max(top, headY - 38.dp.toPx())
                            val lead = min(bottom, headY + 8.dp.toPx())
                            if (fade > 0.01f && lead > tail) {
                                val glow = Brush.verticalGradient(listOf(Color.Transparent, textColor.copy(alpha = 0.8f * fade), Color.Transparent),
                                    headY - 38.dp.toPx(), headY + 8.dp.toPx())
                                drawLine(glow, Offset(from.x, tail), Offset(from.x, lead), 5.dp.toPx(), StrokeCap.Round, alpha = 0.3f)
                                drawLine(glow, Offset(from.x, tail), Offset(from.x, lead), 2.dp.toPx(), StrokeCap.Round)
                            }
                        }
                        points.forEach { point ->
                            val activation = (1f - abs(headY - point.y) / 24.dp.toPx()).coerceIn(0f, 1f) * fade
                            drawCircle(cardColor, radius, point)
                            drawCircle(Brush.radialGradient(listOf(textColor.copy(alpha = 0.07f + activation * 0.23f), Color.Transparent),
                                point, radius + 5.dp.toPx()), radius + 5.dp.toPx(), point)
                            drawCircle(textColor.copy(alpha = 0.35f + activation * 0.55f), radius, point, style = Stroke(1.dp.toPx()))
                        }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        @Composable fun stage(index: Int, title: String, icon: ImageVector, rows: List<Pair<String, String>>) {
                            HorizontalDivider(Modifier.padding(start = 50.dp, end = 16.dp), color = colors.dividerColor.copy(alpha = 0.6f))
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 22.dp)) {
                                Icon(icon, contentDescription = null, tint = muted,
                                    modifier = Modifier.size(24.dp).onGloballyPositioned { coordinates ->
                                        bodyCoordinates[0]?.takeIf { it.isAttached && coordinates.isAttached }?.let { root ->
                                            val position = root.localPositionOf(coordinates, Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
                                            if (centers[index] != position) centers[index] = position
                                        }
                                    })
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title, color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.4.sp, modifier = Modifier.padding(bottom = 8.dp).testTag("pipeline_stage_$index"))
                                    rows.forEach { (label, value) ->
                                        Text(buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label: ") }
                                            append(value)
                                        }, color = textColor, fontSize = 13.sp, lineHeight = 19.sp,
                                            modifier = Modifier.padding(vertical = 1.dp).testTag("pipeline_${index}_$label"))
                                    }
                                }
                            }
                        }
                        stage(0, "TRACK INFO", Icons.AutoMirrored.Rounded.InsertDriveFile, listOf(
                            "Source" to state.source.takeIf { known }.orEmpty().ifBlank { "—" },
                            "Format" to quality.codec.takeIf { known && it.isNotBlank() }.orEmpty().ifBlank { "—" },
                            "Bit Depth" to if (known && quality.lossless && quality.bitDepth > 0) "${quality.bitDepth}-bit" else "—",
                            "Sample Rate" to rate(quality.sampleRateHz.takeIf { known } ?: 0),
                            "Bitrate" to quality.bitrateKbps.takeIf { known && it > 0 }?.let { "$it kbps" }.orEmpty().ifBlank { "—" },
                            "PCM Data Rate" to state.sourcePcmDataRateKbps?.let { "$it kbps" }.orEmpty().ifBlank { "—" },
                            "Channels" to SignalPathState.channelLabel(state.sourceChannels.takeIf { known } ?: 0)))
                        stage(1, "DECODER", Icons.Rounded.Memory, listOf(
                            "Decoder Name" to state.decoderName.ifBlank { "—" }, "Format" to encoding(state.decoderEncoding)))
                        val ratesKnown = state.inputRateHz > 0 && state.outputRateHz > 0
                        val sameRate = ratesKnown && state.inputRateHz == state.outputRateHz && !state.appResampling
                        stage(2, "RESAMPLER", Icons.Rounded.Tune, listOf(
                            "I/O Rate" to "${rate(state.inputRateHz)} → ${rate(state.outputRateHz)}",
                            "Type" to when { !ratesKnown -> "—"; sameRate -> "None"; state.appResampling -> "Speed / pitch processing"; else -> "Sample rate conversion" },
                            "Cutoff" to "—", "Quality" to when { !ratesKnown -> "—"; sameRate -> "Passthrough"; else -> "Processed" }))
                        val buffer = if (state.bufferSizeFrames > 0 && state.outputRateHz > 0)
                            "${state.bufferSizeFrames * 1000L / state.outputRateHz} ms, ${state.bufferSizeFrames} frames" +
                                if (state.bufferCapacityFrames > 0) " (capacity ${state.bufferCapacityFrames})" else "" else "—"
                        stage(3, "DSP", Icons.Rounded.GraphicEq, listOf(
                            "PCM Format" to encoding(state.dspEncoding), "Sample Rate" to rate(state.dspRateHz),
                            "Gain" to if (state.eqActive) String.format(Locale.ROOT, "%.1f dB (configured; automatic headroom)",
                                effects.loudnessGainMb / 100.0) else if (state.dspActive) "Automatic headroom (not measured)" else "0.0 dB (bypassed)",
                            "Measured" to "Not measured",
                            "EQ Preset" to if (state.eqActive) effects.selectedPreset else "Flat (bypassed)",
                            "Stereo Expand" to if (state.eqActive) "${100 + effects.virtualizerStrength.coerceIn(0, 1000) * 30 / 1000}% (configured)" else "100% (bypassed)",
                            "Effects" to listOfNotNull("Equalizer".takeIf { state.eqActive }, "Spatial Audio".takeIf { state.spatialActive },
                                "Skip Silence".takeIf { state.skipSilenceActive }).joinToString(" + ").ifBlank { "Bypassed" },
                            "Tempo" to String.format(Locale.ROOT, "%.2f× • pitch %.2f×", state.playbackSpeed, state.pitch),
                            "Buffers" to buffer, "Output API" to "AudioTrack",
                            "Bit-exact" to when { state.sourcePrecisionReduced -> "No — source precision reduced"; state.bitPerfect -> "Verified direct PCM • DSP bypassed"; state.appVolume != 1f -> "No — digital volume attenuation"; state.dspActive || state.appResampling -> "No — samples processed";
                                state.decoderEncoding == androidx.media3.common.C.ENCODING_INVALID || state.outputEncoding == androidx.media3.common.C.ENCODING_INVALID -> "Not yet verified";
                                state.decoderEncoding != state.outputEncoding -> "PCM format conversion • shared system path";
                                else -> "App bypassed • system mixing not verified" }))
                        stage(4, "OUTPUT DEVICE", Icons.AutoMirrored.Rounded.VolumeUp, listOf(
                            "Device Name" to state.outputDevice, "Route" to state.outputType,
                            "Transport" to "AudioTrack • ${state.mode}",
                            "Direct" to if (state.directApproved) "Active (verified direct route)" else "Not active (shared path)",
                            "AudioTrack" to state.outputFormat,
                            "System" to if (state.directApproved) "Direct path • mixer bypassed" else "Mixer / HAL format not exposed by Android",
                            "Route Verified" to if (state.outputRouteVerified) "Yes" else "Not yet verified",
                            "Buffer" to if (state.buffering) "Buffering" else state.playbackStatus,
                            "Glitches" to state.glitches.toString()))
                    }
                }
            }
        }
    }
}
