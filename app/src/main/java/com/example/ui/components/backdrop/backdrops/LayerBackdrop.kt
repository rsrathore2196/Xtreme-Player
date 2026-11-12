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
package com.example.ui.components.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.example.ui.components.backdrop.Backdrop
import com.example.ui.components.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)
    internal var isRecording = false

    // These are draw-node dependencies, not snapshot state observed by the page.
    // Invalidating a reader's isolated layer must never recapture its source.
    private val drawListeners = mutableSetOf<() -> Unit>()
    internal fun addDrawListener(listener: () -> Unit) { drawListeners.add(listener) }
    internal fun removeDrawListener(listener: () -> Unit) { drawListeners.remove(listener) }
    internal fun notifyContentChanged() { drawListeners.forEach { it() } }
    private val sourceToSurface = Matrix()

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        if (!coordinates.isAttached || !layerCoordinates.isAttached || isRecording || graphicsLayer.isReleased) return
        if (layerBlock == null) {
            // Translation alone cannot map a source through the dock's ancestor
            // scale/press/morph layers. Use the complete source-to-surface matrix,
            // retaining fractional pixels rather than snapping the sampling phase.
            sourceToSurface.reset()
            try {
                coordinates.transformFrom(layerCoordinates, sourceToSurface)
            } catch (_: Exception) {
                // Separate window/lookahead coordinate trees keep the previous
                // window-position fallback instead of breaking their controls.
                sourceToSurface.reset()
                val offset = coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                sourceToSurface.translate(-offset.x, -offset.y)
            }
            withTransform({ transform(sourceToSurface) }) { drawLayer(graphicsLayer) }
            return
        }
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
