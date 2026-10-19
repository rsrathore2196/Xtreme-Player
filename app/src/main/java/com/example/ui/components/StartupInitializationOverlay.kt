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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** One modal layer; the existing navigation/player trees stay mounted underneath it. */
@Composable
internal fun StartupInitializationOverlay(modifier: Modifier = Modifier, firstLaunch: Boolean = false) {
    val state by BackgroundWorkCoordinator.startupReadiness.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val visibility = remember {
        MutableTransitionState(false).apply { targetState = !state.complete }
    }
    visibility.targetState = !state.complete
    LaunchedEffect(lifecycle, view, state.preparationComplete) {
        if (!state.preparationComplete || state.complete) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!BackgroundWorkCoordinator.startupReadiness.value.complete) {
                view.awaitStartupContentDraw { BackgroundWorkCoordinator.initialContentDrawn() }
                withContext(Dispatchers.IO) { BackgroundWorkCoordinator.awaitInitialPresentationSettled() }
            }
        }
    }
    if (state.complete && visibility.isIdle && !visibility.currentState) return
    val context = LocalContext.current
    BackHandler { (context as? Activity)?.moveTaskToBack(true) }
    val colors = LocalAppColors.current
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(220)),
        modifier = modifier
    ) {
        Box(Modifier.fillMaxSize()
            // Consume in the initial pass, before any underlying dock/list gesture.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
            .semantics {
                paneTitle = "Preparing your music"
                isTraversalGroup = true
            }, contentAlignment = Alignment.Center) {
            // Dim the existing preview directly. The popup is fully opaque:
            // no backdrop reads, blur, refraction shaders or image preparation.
            Box(Modifier.fillMaxSize().drawWithCache {
                val ambient = Brush.radialGradient(listOf(colors.primaryAccent.copy(alpha = .12f), Color.Transparent),
                    center = Offset(size.width * .5f, size.height * .43f), radius = size.maxDimension * .65f)
                onDrawBehind { drawRect(colors.scaffoldBackground.copy(alpha = if (colors.isDark) .32f else .24f)); drawRect(ambient) }
            })
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 400.dp)
                .animateEnterExit(
                    enter = scaleIn(initialScale = .97f, animationSpec = tween(240)),
                    exit = scaleOut(targetScale = 1.015f, animationSpec = tween(220)))) {
                Surface(modifier = Modifier.testTag("startup_initialization_panel"),
                    shape = RoundedCornerShape(28.dp), color = colors.cardBackgroundElevated.copy(alpha = 1f),
                    border = BorderStroke(1.dp, colors.cardBorder), shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(38.dp).clip(CircleShape)
                        .background(colors.primaryAccent.copy(alpha = .11f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null,
                            tint = colors.primaryAccent, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(if (!state.complete) "Curating your vibe" else if (state.hasUnavailable) "Ready to listen" else "Your vibe is ready",
                        color = colors.textPrimary, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(if (firstLaunch) "Finding music that feels like you" else "Bringing your music back in sync", color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    StartupProgress(state.progress)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.detail, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(12.dp))
                        Text("${(state.progress * 100).roundToInt()}%", color = colors.textPrimary,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (state.hasUnavailable) {
                        Spacer(Modifier.height(18.dp))
                        Text("Some content is unavailable. You can retry from Home.", color = colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun StartupProgress(completed: Float) {
    val colors = LocalAppColors.current
    val progress = animateFloatAsState(completed, tween(260), label = "real_startup_progress")
    val gradient = remember(colors.primaryAccent, colors.secondaryAccent) {
        Brush.horizontalGradient(listOf(colors.primaryAccent, colors.secondaryAccent))
    }
    Canvas(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(completed, 0f..1f)
    }) {
        drawRect(colors.textPrimary.copy(alpha = .09f))
        drawRect(gradient, size = size.copy(width = size.width * progress.value.coerceIn(0f, 1f)))
    }
}

