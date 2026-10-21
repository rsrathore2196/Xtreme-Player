package com.example.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.playback.AudioSignalPath
import com.example.playback.OutputPrecision
import com.example.playback.SignalPathState
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

/** Output telemetry is collected here so unrelated playback changes cannot rebuild Settings. */
@Composable
internal fun OutputPrecisionSetting(selected: OutputPrecision, skipSilence: Boolean, onSelect: (OutputPrecision) -> Unit) {
    val colors = LocalAppColors.current
    val summaryFlow = remember { AudioSignalPath.state.map { it.precisionSummary() }.distinctUntilChanged() }
    val summary by summaryFlow.collectAsStateWithLifecycle(initialValue = AudioSignalPath.state.value.precisionSummary())
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Output Precision", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(summary, color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.testTag("output_precision_status"))
            }
        }
        Row(Modifier.padding(start = 34.dp, top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(colors.textPrimary.copy(alpha = .07f))
            .padding(4.dp).selectableGroup()) {
            OutputPrecision.entries.forEach { precision ->
                val active = selected == precision
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (active) colors.primaryAccent else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = active, role = Role.RadioButton, onClick = { if (!active) onSelect(precision) })
                    .testTag("output_precision_${precision.name}").padding(horizontal = 6.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center) {
                    Text(precision.label, color = if (active) colors.onPrimaryAccent else colors.textMuted,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (selected == OutputPrecision.FLOAT_32) {
            Text(if (skipSilence) "Skip Silence uses 16-bit PCM. Float resumes when it is off."
                else "Float on compatible external outputs; 16-bit PCM on other routes.",
                color = colors.textMuted, fontSize = 10.5.sp, modifier = Modifier.padding(start = 34.dp, top = 8.dp))
        }
    }
}

private fun SignalPathState.precisionSummary(): String {
    if (outputEncoding == androidx.media3.common.C.ENCODING_INVALID) return "AudioTrack · Output available during playback"
    val rate = outputRateHz.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f kHz", it / 1000.0) }
    val encoding = when (outputEncoding) {
        androidx.media3.common.C.ENCODING_PCM_FLOAT -> "32-bit float"
        androidx.media3.common.C.ENCODING_PCM_16BIT -> "16-bit PCM"
        else -> outputFormat.substringBefore(" • ")
    }
    return listOfNotNull("AudioTrack", outputDevice, rate, encoding).joinToString(" · ")
}
