package com.example.ui.components

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.theme.LocalAppColors
import kotlin.math.roundToInt

/** Presentation only. The existing coordinator and ViewModels own all preparation. */
@Composable
internal fun StartupInitializationOverlay() {
    val readiness by BackgroundWorkCoordinator.startupReadiness.collectAsStateWithLifecycle()
    val visibility = remember { MutableTransitionState(false).apply { targetState = !readiness.complete } }
    visibility.targetState = !readiness.complete
    if (visibility.isIdle && !visibility.currentState && !visibility.targetState) return
    val context = LocalContext.current
    BackHandler { (context as? Activity)?.moveTaskToBack(true) }
    val colors = LocalAppColors.current
    val progress = animateFloatAsState(readiness.progress, tween(220), label = "completed_startup_tasks")
    val gradient = remember(colors.primaryAccent, colors.secondaryAccent) {
        Brush.horizontalGradient(listOf(colors.primaryAccent, colors.secondaryAccent))
    }
    AnimatedVisibility(visibleState = visibility,
        enter = fadeIn(tween(160)), exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize().testTag("startup_initialization_overlay")) {
        Box(Modifier.fillMaxSize().background(colors.scaffoldBackground.copy(alpha = .38f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }.semantics { paneTitle = "Preparing your music" }, contentAlignment = Alignment.Center) {
            Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 400.dp)
                .animateEnterExit(enter = scaleIn(tween(180), initialScale = .98f),
                    exit = scaleOut(tween(180), targetScale = 1.01f))
                .testTag("startup_initialization_panel"),
                shape = RoundedCornerShape(28.dp), color = colors.cardBackgroundElevated.copy(alpha = 1f),
                border = BorderStroke(1.dp, colors.cardBorder)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(colors.primaryAccent.copy(alpha = .12f)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = colors.primaryAccent, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(if (!readiness.complete) "Curating your vibe" else if (readiness.hasUnavailable)
                        "Ready to listen" else "Your vibe is ready", color = colors.textPrimary,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Getting your music ready", color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(26.dp))
                    Canvas(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(readiness.progress, 0f..1f)
                    }) {
                        drawRect(colors.textPrimary.copy(alpha = .09f))
                        drawRect(gradient, size = size.copy(width = size.width * progress.value.coerceIn(0f, 1f)))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(readiness.detail, color = colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text("${(readiness.progress * 100).roundToInt()}%", color = colors.textPrimary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
