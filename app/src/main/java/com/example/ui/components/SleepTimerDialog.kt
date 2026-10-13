package com.example.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.playback.SleepTimerManager
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(context) { SleepTimerManager.initialize(context) }
    val deadline by SleepTimerManager.deadline.collectAsStateWithLifecycle()
    var remaining by remember(deadline) { mutableLongStateOf((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)) }
    LaunchedEffect(deadline, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (deadline > 0L) {
                remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remaining == 0L) break
                delay(1_000L)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = colors.cardBackground,
            border = BorderStroke(1.2.dp, colors.cardBorder.copy(alpha = if (colors.isDark) 0.60f else 0.75f)),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Sleep Timer", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                val seconds = (remaining + 999L) / 1000L
                Text(if (deadline > 0L) "Playback stops in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                    else "Pause playback automatically after:",
                    style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                listOf(5, 15, 30, 45, 60, 90).forEach { minutes ->
                    TextButton(onClick = { SleepTimerManager.start(context, minutes * 60_000L); onDismiss() },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes", color = colors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (deadline > 0L) TextButton(onClick = { SleepTimerManager.cancel(); onDismiss() }) {
                        Text("Cancel Timer", color = colors.primaryAccent)
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = colors.textSecondary) }
                }
            }
        }
    }
}
