/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 * Xtreme integration: package renamed; shader editor annotations removed.
 */
package com.example.ui.components.backdrop

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import com.example.ui.components.backdrop.backdrops.LayerBackdrop
import com.example.ui.components.backdrop.highlight.Highlight
import com.example.ui.components.backdrop.highlight.HighlightElement
import com.example.ui.components.backdrop.internal.ShapeProvider
import com.example.ui.components.backdrop.internal.BackdropRaster
import com.example.ui.components.backdrop.internal.backdropRaster
import com.example.ui.components.backdrop.internal.recordLayer
import com.example.ui.components.backdrop.shadow.InnerShadow
import com.example.ui.components.backdrop.shadow.InnerShadowElement
import com.example.ui.components.backdrop.shadow.Shadow
import com.example.ui.components.backdrop.shadow.ShadowElement

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        // Enclose the draw node, not only its child: source invalidation must
        // reach this reader even while the parent/source is recording a frame.
        .graphicsLayer {
            clip = true
            this.shape = shapeProvider.shape
            // The filtered layer already owns its offscreen buffer. An extra
            // local-space buffer here would resample it again under dock scaling.
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Auto
        }
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                backdropScale = 1f
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    // Vendored addition: records the backdrop into a layer at this fraction of the
    // surface resolution and draws it scaled back up, so the RenderEffect chain
    // processes far fewer pixels. Pixel-sized effect parameters (blur radius, lens
    // refraction) must be pre-multiplied by the same factor by the caller; the blur
    // hides the upscaling. 1f keeps the original full resolution behavior.
    backdropScale: Float = 1f,
    // Read motion state inside the reader's draw observation, never composition.
    onSample: (() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        // Enclose the draw node, not only its child: source invalidation must
        // reach this reader even while the parent/source is recording a frame.
        .graphicsLayer {
            clip = true
            this.shape = shapeProvider.shape
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Auto
        }
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                backdropScale = backdropScale.coerceIn(0.05f, 1f),
                onSample = onSample
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?,
    val backdropScale: Float,
    val onSample: (() -> Unit)? = null
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront,
            backdropScale = backdropScale,
            onSample = onSample
        )
    }

    override fun update(node: DrawBackdropNode) {
        node.updateBackdrop(backdrop)
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.backdropScale = backdropScale
        node.onSample = onSample
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false
        if (backdropScale != other.backdropScale) return false
        if (onSample != other.onSample) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        result = 31 * result + backdropScale.hashCode()
        result = 31 * result + (onSample?.hashCode() ?: 0)
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?,
    var backdropScale: Float,
    var onSample: (() -> Unit)? = null
) : DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, Modifier.Node() {

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null

    // Geometry belongs to this surface's draw node, never the shared source's
    // snapshot dependencies. Foreground layout must not recapture the whole page.
    private var layoutCoordinates: LayoutCoordinates? = null
    private var surfaceCoordinates: LayoutCoordinates? = null
    private var padding = 0f
    private var hasVisibleBackdrop = false
    private var effectsDirty = true
    private var raster: BackdropRaster? = null
    private val sourceChanged: () -> Unit = { if (isAttached) invalidateDraw() }

    fun updateBackdrop(value: Backdrop) {
        if (backdrop === value) return
        if (isAttached) (backdrop as? LayerBackdrop)?.removeDrawListener(sourceChanged)
        backdrop = value
        if (isAttached) (backdrop as? LayerBackdrop)?.addDrawListener(sourceChanged)
    }

    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val raster = requireNotNull(raster)
        val x = padding + raster.phase.x
        val y = padding + raster.phase.y
        canvas.save()
        try {
            canvas.translate(x, y)
            canvas.scale(raster.scaleX, raster.scaleY)
            onDrawBackdrop {
                with(backdrop) {
                    drawBackdrop(
                        density = effectScope,
                        coordinates = layoutCoordinates,
                        layerBlock = layerBlock
                    )
                }
            }
        } finally {
            canvas.restore()
        }
    }

    private val drawBackdropLayer: DrawScope.() -> Unit = sample@{
        hasVisibleBackdrop = false
        val layer = graphicsLayer ?: return@sample
        val coordinates = surfaceCoordinates?.takeIf { it.isAttached } ?: return@sample
        if (coordinates.boundsInWindow().isEmpty) return@sample
        val source = backdrop as? LayerBackdrop
        if (source?.isRecording == true || source?.graphicsLayer?.isReleased == true) return@sample
        if (source != null && (source.layerCoordinates?.isAttached != true ||
                source.graphicsLayer.size.width < 1 || source.graphicsLayer.size.height < 1)) return@sample
        val raster = backdropRaster(size, backdropScale,
            coordinates.takeIf { source != null && layerBlock == null }).also { this@DrawBackdropNode.raster = it }
        // Effects see the physical viewport, not the reserved width before the
        // dock's ancestor scale. Phase is part of the immutable shader snapshot.
        if (effectScope.update(this, backdropScale, raster.size, raster.phase) || effectsDirty) observeEffects()
        // Only this surface plus its filter halo is captured. The source page is
        // still recorded once, and source-only changes reuse the effect chain.
        recordLayer(
            this@DrawBackdropNode,
            layer,
            size = raster.layerSize(padding),
            block = recordBackdropBlock
        )
        layer.topLeft = IntOffset.Zero
        hasVisibleBackdrop = true
        drawRecordedBackdrop()
    }

    // Vendored change: the export below draws the layer this frame already
    // recorded, rather than recording the backdrop a second time. Upstream
    // re-runs the whole capture and effect chain for the export, which doubles
    // the surface's cost on every frame it is exported.
    private val drawRecordedBackdrop: DrawScope.() -> Unit = {
        val layer = graphicsLayer
        if (layer != null && hasVisibleBackdrop) {
            val raster = raster
            if (raster != null) {
                // Undo both downsampling and the ancestor's scale. Preserve the
                // fractional origin: rounding it would move the page by up to a
                // whole low-resolution texel (3-5 screen pixels) at spring settle.
                scale(1f / raster.scaleX, 1f / raster.scaleY, pivot = Offset.Zero) {
                    translate(-padding - raster.phase.x, -padding - raster.phase.y) {
                        drawLayer(layer)
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        // Ancestor graphicsLayer motion does not trigger layout/global-position
        // callbacks. Reading its state here keeps the sampled page anchored while
        // the chrome scales/morphs, without recomposition or a frame ticker.
        onSample?.invoke()
        onDrawBehind?.invoke(this)
        drawBackdropLayer()
        onDrawSurface?.invoke(this)
        drawContent()
        onDrawFront?.invoke(this)

        exportedBackdrop?.graphicsLayer?.let { layer ->
            recordLayer(this@DrawBackdropNode, layer) {
                onDrawBehind?.invoke(this)
                drawRecordedBackdrop()
                onDrawSurface?.invoke(this)
                onDrawFront?.invoke(this)
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            surfaceCoordinates = coordinates
            layoutCoordinates = coordinates.takeIf { backdrop.isCoordinatesDependent }
            exportedBackdrop?.layerCoordinates = coordinates
            invalidateDraw()
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        effectsDirty = true
        invalidateDraw()
    }

    private fun observeEffects() {
        // Observe the recipe after the draw size is known, including its first
        // lens uniforms. Artwork changes invalidate drawing, not this effect cache.
        observeReads { updateEffects() }
        effectsDirty = false
    }

    private fun updateEffects() {
        if (!isRenderEffectSupported()) return

        effectScope.apply(effects)
        graphicsLayer?.renderEffect = effectScope.renderEffect
        padding = effectScope.padding
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()

        (backdrop as? LayerBackdrop)?.addDrawListener(sourceChanged)
        effectsDirty = true
    }

    override fun onDetach() {
        (backdrop as? LayerBackdrop)?.removeDrawListener(sourceChanged)
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }

        effectScope.reset()
        layoutCoordinates = null
        surfaceCoordinates = null
        hasVisibleBackdrop = false
        raster = null
        effectsDirty = true
        exportedBackdrop?.layerCoordinates = null
    }
}
