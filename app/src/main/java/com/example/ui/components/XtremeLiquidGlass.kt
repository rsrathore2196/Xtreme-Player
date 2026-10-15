package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import kotlin.math.abs
import kotlin.math.ceil

val LocalDynamicGlassEnabled = compositionLocalOf { true }

/** A shared GPU display list. Glass controls must remain outside their own source. */
class LiquidGlassBackdrop internal constructor(
    internal val layer: GraphicsLayer,
    // Retained for callers that used region capture. The shared display list is now
    // always full resolution; only the individual glass samples are downscaled.
    internal val captureVisibleRegions: Boolean
) {
    internal var position = Offset.Zero
    internal var sampleScale by mutableFloatStateOf(XtremeLiquidGlassDefaults.BackdropSampleScale)
    internal var revision by mutableIntStateOf(0)
    internal var geometryRevision by mutableIntStateOf(0)
        private set
    internal var captureActive by mutableStateOf(false)
        private set
    internal var hasCapture = false
    internal var isRecording = false
    private val regions = mutableListOf<LiquidGlassRegion>()
    private var sourceCoordinates: LayoutCoordinates? = null
    private var sourceSize = IntSize.Zero
    private var publishedPosition = Offset.Zero
    private var hasSourceGeometry = false
    private var recordingSerial = 0
    internal fun recorded() {
        hasCapture = true
        revision = ++recordingSerial
    }

    internal fun clearSource() {
        sourceCoordinates = null
        sourceSize = IntSize.Zero
        hasSourceGeometry = false
        hasCapture = false
        captureActive = false
        revision = 0
    }

    internal fun register(region: LiquidGlassRegion) {
        regions.add(region)
        regionsChanged()
    }

    internal fun unregister(region: LiquidGlassRegion) {
        if (regions.remove(region)) regionsChanged()
    }

    internal fun regionsChanged() {
        // Moving a pill must only redraw that pill. A full-size source needs a new
        // capture only when glass becomes visible, not whenever its bounds change.
        captureActive = regions.any { it.intersects(position, sourceSize) }
    }

    internal fun updateSource(coordinates: LayoutCoordinates) {
        sourceCoordinates = coordinates
        val origin = coordinates.positionInWindow()
        position = origin
        if (!hasSourceGeometry || differsByPixel(publishedPosition, origin) || sourceSize != coordinates.size) {
            hasSourceGeometry = true
            publishedPosition = origin
            sourceSize = coordinates.size
            geometryRevision++
            regionsChanged()
        }
    }

    internal fun sourceOrigin(): Offset = sourceCoordinates?.takeIf { it.isAttached }
        ?.positionInWindow() ?: position
}

val LocalLiquidGlassBackdrop = compositionLocalOf<LiquidGlassBackdrop?> { null }

/** Draw-only bookkeeping: foreground changes must not invalidate an unchanged glass sample. */
private class GlassSampleCache {
    var source: GraphicsLayer? = null
    var offset = Offset.Unspecified
    var size = IntSize.Zero
    var effect: RenderEffect? = null
    val region = LiquidGlassRegion()
}

/** Reused region registration; rapidly changing coordinates are never composition state. */
internal class LiquidGlassRegion {
    var coordinates: LayoutCoordinates? = null
    var geometryRevision by mutableIntStateOf(0)
        private set
    private var position = Offset.Unspecified
    private var size = IntSize.Zero
    private var bounds = Rect.Zero
    private var publishedBounds = Rect.Zero
    private var hasGeometry = false

    fun intersects(origin: Offset, sourceSize: IntSize): Boolean =
        coordinates?.isAttached == true && !bounds.isEmpty &&
            bounds.right > origin.x && bounds.bottom > origin.y &&
            bounds.left < origin.x + sourceSize.width && bounds.top < origin.y + sourceSize.height

    fun update(coordinates: LayoutCoordinates): Boolean {
        this.coordinates = coordinates
        val newPosition = coordinates.positionInWindow()
        val newBounds = coordinates.boundsInWindow()
        bounds = newBounds
        val changed = !hasGeometry || differsByPixel(position, newPosition) || size != coordinates.size ||
            publishedBounds.isEmpty != newBounds.isEmpty ||
            abs(publishedBounds.left - newBounds.left) >= 1f || abs(publishedBounds.top - newBounds.top) >= 1f ||
            abs(publishedBounds.right - newBounds.right) >= 1f || abs(publishedBounds.bottom - newBounds.bottom) >= 1f
        if (changed) {
            hasGeometry = true
            position = newPosition
            size = coordinates.size
            publishedBounds = newBounds
            geometryRevision++
        }
        return changed
    }
}

private fun differsByPixel(first: Offset, second: Offset): Boolean =
    abs(first.x - second.x) >= 1f || abs(first.y - second.y) >= 1f

/** Round capacity up so growing surfaces do not resize their sample every pixel. */
private fun sampleDimension(pixels: Float): Int = ceil(pixels / 16f).toInt().coerceAtLeast(1) * 16

@Composable
fun rememberLiquidGlassBackdrop(captureVisibleRegions: Boolean = false): LiquidGlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer, captureVisibleRegions) { LiquidGlassBackdrop(layer, captureVisibleRegions) }
}

object XtremeLiquidGlassDefaults {
    val BlurRadius: Dp = 20.dp
    const val SurfaceTintAlpha: Float = 0.12f
    val RimBorderWidth: Dp = 0.8.dp
    val PillShape: Shape = CircleShape
    val Elevation: Dp = 10.dp
    const val BackdropSampleScale: Float = 0.33f
}

/** Record once for all floating surfaces; no bitmap readback or per-frame coroutine. */
@Composable
fun Modifier.xtremeSharedBackdropSource(
    enabled: Boolean = true,
    sampleScale: Float = XtremeLiquidGlassDefaults.BackdropSampleScale,
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current
): Modifier {
    if (!enabled || !LocalDynamicGlassEnabled.current || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val processingScale = sampleScale.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f)
        ?: XtremeLiquidGlassDefaults.BackdropSampleScale
    DisposableEffect(backdrop) {
        onDispose { backdrop.clearSource() }
    }
    SideEffect { backdrop.sampleScale = processingScale }
    return this.onGloballyPositioned(backdrop::updateSource)
        .drawWithContent {
            if (!backdrop.captureActive || size.width < 1f || size.height < 1f) {
                backdrop.hasCapture = false
                drawContent()
                return@drawWithContent
            }
            backdrop.position = backdrop.sourceOrigin()
            backdrop.isRecording = true
            try {
                // Record the sharp viewport once, then replay its hardware display list.
                // Downsampling is confined to each blurred pill; page text/art stay native.
                backdrop.layer.record(size = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) {
                    this@drawWithContent.drawContent()
                }
            } finally {
                backdrop.isRecording = false
            }
            backdrop.recorded()
            drawLayer(backdrop.layer)
        }
}

/**
 * Smoky glass with real blurred backdrop, a clear center and a reflective curved rim.
 * Only the sampled background is blurred; foreground text, icons and artwork stay sharp.
 * Android 11 and the reduced-effects preference use the same finish without GPU blur.
 */
@Composable
fun Modifier.xtremeLiquidGlassBackground(
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    backdrop: LiquidGlassBackdrop? = LocalLiquidGlassBackdrop.current
): Modifier {
    if (!enabled || !LocalDynamicGlassEnabled.current) return this
        .shadow(elevation, shape)
        .clip(shape)
        .background(appColors.cardBackground.copy(alpha = 1f))
        .border(borderWidth, appColors.cardBorder, shape)

    val density = LocalDensity.current
    val radius = with(density) { blurRadius.toPx() }
    val processingScale = backdrop?.sampleScale ?: XtremeLiquidGlassDefaults.BackdropSampleScale
    val supportsBlur = enabled && radius > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blur = remember(supportsBlur, radius, processingScale) {
        if (supportsBlur) android.graphics.RenderEffect.createBlurEffect(
            radius * processingScale, radius * processingScale, android.graphics.Shader.TileMode.CLAMP
        ).asComposeRenderEffect() else null
    }
    val sample = rememberGraphicsLayer()
    val sampleCache = remember(sample) { GlassSampleCache() }
    val bleed = ceil(radius * 2).toInt()
    DisposableEffect(backdrop, supportsBlur, sampleCache) {
        val registered = backdrop?.takeIf { supportsBlur }
        registered?.register(sampleCache.region)
        onDispose { registered?.unregister(sampleCache.region) }
    }
    val dark = appColors.isDark
    val rim = remember(dark) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.48f else 0.92f),
            Color.White.copy(alpha = if (dark) 0.08f else 0.32f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.15f),
            (if (dark) Color.White else Color(0xFF708090)).copy(alpha = 0.30f)
        ))
    }
    return this
        .shadow(elevation, shape, clip = false,
            spotColor = Color.Black.copy(alpha = if (dark) 0.42f else 0.14f),
            ambientColor = Color.Black.copy(alpha = 0.08f))
        .onGloballyPositioned {
            if (sampleCache.region.update(it) && supportsBlur) backdrop?.regionsChanged()
        }
        .clip(shape)
        .drawWithCache {
            val base = if (dark) Color(0xFF101013) else Color(0xFFF8FAFD)
            val tint = tintColor.copy(alpha = surfaceTintAlpha.coerceIn(0f, 0.65f) * tintColor.alpha)
            val colorWash = Brush.linearGradient(listOf(tint, Color.Transparent, tint.copy(alpha = tint.alpha * 0.45f)),
                Offset.Zero, Offset(size.width, size.height))
            val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.10f else 0.36f),
                0.32f to Color.White.copy(alpha = 0.015f), 0.65f to Color.Transparent,
                1f to Color.White.copy(alpha = if (dark) 0.055f else 0.14f))
            val reflection = Brush.radialGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.065f else 0.18f), Color.Transparent),
                center = Offset(size.width * 0.72f, size.height * 1.6f), radius = size.width.coerceAtLeast(size.height) * 0.65f)
            // Extra pixels avoid dark seams when the blur kernel reaches the pill's edge.
            val sampleSize = IntSize(
                sampleDimension((size.width + bleed * 2) * processingScale),
                sampleDimension((size.height + bleed * 2) * processingScale))
            onDrawWithContent {
                // Never add a dependency on a layer while that same layer is recording.
                val source = backdrop?.takeUnless { it.isRecording }
                val revision = source?.revision ?: 0
                if (source != null) {
                    source.geometryRevision
                    sampleCache.region.geometryRevision
                }
                val coordinates = sampleCache.region.coordinates?.takeIf { it.isAttached }
                val visible = coordinates != null && !coordinates.boundsInWindow().isEmpty
                val ready = blur != null && source != null && source.hasCapture && revision != 0 && visible && !source.layer.isReleased
                if (ready) {
                    val offset = source.position - coordinates.positionInWindow()
                    // Keep capacity when a surface contracts, including when it oscillates
                    // across a bucket boundary. The shape clip limits the visible sample.
                    val allocationSize = IntSize(
                        maxOf(sampleCache.size.width, sampleSize.width),
                        maxOf(sampleCache.size.height, sampleSize.height))
                    // drawLayer records a live RenderNode reference, not a frozen bitmap.
                    // Content revisions still invalidate this draw, but only geometry/source/
                    // effect changes require rebuilding the sample's display list. Child layer
                    // changes continue to propagate through the GPU dependency graph.
                    if (sampleCache.source !== source.layer ||
                        differsByPixel(sampleCache.offset, offset) || sampleCache.size != allocationSize || sampleCache.effect !== blur) {
                        if (sampleCache.effect !== blur) sample.renderEffect = blur
                        sample.record(size = allocationSize) {
                            scale(processingScale, pivot = Offset.Zero) {
                                translate(offset.x + bleed, offset.y + bleed) { drawLayer(source.layer) }
                            }
                        }
                        // Update only after recording succeeds. Keep these fields out of snapshot state.
                        sampleCache.source = source.layer
                        sampleCache.offset = offset
                        sampleCache.size = allocationSize
                        sampleCache.effect = blur
                    }
                    translate(-bleed.toFloat(), -bleed.toFloat()) {
                        scale(1f / processingScale, pivot = Offset.Zero) { drawLayer(sample) }
                    }
                }
                drawRect(base.copy(alpha = if (ready || (backdrop == null && enabled)) { if (dark) 0.50f else 0.64f } else 0.90f))
                drawRect(colorWash)
                drawRect(sheen)
                drawRect(reflection)
                drawContent()
            }
        }
        .border(BorderStroke(borderWidth, rim), shape)
}

/** Opt-in control finish; other screens keep their existing button appearance. */
@Composable
fun Modifier.playerLiquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 4.dp,
    isActive: Boolean = false,
    translucency: Float = 0.75f,
    borderWidth: Dp = 0.8.dp
): Modifier = xtremeLiquidGlassBackground(
    shape = shape, elevation = elevation, appColors = colors,
    tintColor = colors.primaryAccent,
    surfaceTintAlpha = if (isActive) 0.30f else 0.07f,
    borderWidth = borderWidth
)

@Composable
fun Modifier.playerPrimaryLiquidGlassButton(
    colors: AppThemeColors,
    shape: Shape = CircleShape,
    elevation: Dp = 10.dp,
    translucency: Float = 0.82f,
    borderWidth: Dp = 1.dp
): Modifier = xtremeLiquidGlassBackground(
    shape = shape, elevation = elevation, appColors = colors,
    tintColor = colors.primaryAccent, surfaceTintAlpha = 0.25f, borderWidth = borderWidth
)

/**
 * Reusable modifier alias for backwards compatibility.
 * Note: To prevent child blurring, prefer using [XtremeLiquidGlassContainer] or applying
 * [xtremeLiquidGlassBackground] on a dedicated Layer 1 background Box.
 */
@Composable
fun Modifier.xtremeLiquidGlass(
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current
): Modifier = xtremeLiquidGlassBackground(
    shape = shape,
    blurRadius = blurRadius,
    surfaceTintAlpha = surfaceTintAlpha,
    tintColor = tintColor,
    borderWidth = borderWidth,
    elevation = elevation,
    enabled = enabled,
    appColors = appColors
)

/**
 * Reusable Liquid Glass Container enforcing the 2-layer stacked architecture:
 * LAYER 1: Glass background surface only (blur, AGSL shader, tint, sheen, border)
 * LAYER 2: Crisp foreground content, completely unblurred and sharp.
 */
@Composable
fun XtremeLiquidGlassContainer(
    modifier: Modifier = Modifier,
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        // LAYER 1: GLASS BACKGROUND SURFACE ONLY
        Box(
            modifier = Modifier
                .matchParentSize()
                .xtremeLiquidGlassBackground(
                    shape = shape,
                    blurRadius = blurRadius,
                    surfaceTintAlpha = surfaceTintAlpha,
                    tintColor = tintColor,
                    borderWidth = borderWidth,
                    elevation = elevation,
                    enabled = enabled,
                    appColors = appColors
                )
        )

        // LAYER 2: UNBLURRED FOREGROUND CONTENT
        content()
    }
}

/**
 * Reusable Liquid Glass Surface component wrapping content with Xtreme Liquid Glass styling
 * using the 2-layer stacked architecture so child composables remain sharp.
 */
@Composable
fun XtremeLiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = XtremeLiquidGlassDefaults.PillShape,
    blurRadius: Dp = XtremeLiquidGlassDefaults.BlurRadius,
    surfaceTintAlpha: Float = XtremeLiquidGlassDefaults.SurfaceTintAlpha,
    tintColor: Color = LocalAppColors.current.primaryAccent,
    borderWidth: Dp = XtremeLiquidGlassDefaults.RimBorderWidth,
    elevation: Dp = XtremeLiquidGlassDefaults.Elevation,
    enabled: Boolean = LocalDynamicGlassEnabled.current,
    appColors: AppThemeColors = LocalAppColors.current,
    content: @Composable () -> Unit
) {
    XtremeLiquidGlassContainer(
        modifier = modifier,
        shape = shape,
        blurRadius = blurRadius,
        surfaceTintAlpha = surfaceTintAlpha,
        tintColor = tintColor,
        borderWidth = borderWidth,
        elevation = elevation,
        enabled = enabled,
        appColors = appColors
    ) {
        content()
    }
}
