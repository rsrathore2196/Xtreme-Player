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
package com.example.ui.components.backdrop.internal

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

@Immutable
internal class ShapeProvider(val shapeBlock: () -> Shape) {

    // The provider is an implementation cache for this shape lambda. Wrapper
    // allocation must not invalidate backdrop effects on every parent recomposition.
    override fun equals(other: Any?): Boolean = this === other ||
        other is ShapeProvider && shapeBlock == other.shapeBlock

    override fun hashCode(): Int = shapeBlock.hashCode()

    private var _shape: Shape? = null
    private var _outline: State<Outline>? = null
    private var _size: Size = Size.Unspecified
    private var _layoutDirection: LayoutDirection? = null
    private var _density: Float? = null

    val innerShape
        get() = shapeBlock()

    val shape = object : Shape {

        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            val shape = shapeBlock()
            if (_shape != shape) {
                _shape = shape
                _outline = null
            }
            if (_outline == null || _size != size || _layoutDirection != layoutDirection || _density != density.density) {
                _size = size
                _layoutDirection = layoutDirection
                _density = density.density
                // A retained shape can read animated snapshot state (the dock's
                // width compensation). Keep those dependencies observed rather
                // than freezing its first outline behind this second cache.
                // Static shapes still compute once for unchanged geometry.
                _outline = derivedStateOf { shape.createOutline(size, layoutDirection, density) }
            }

            return _outline!!.value
        }
    }
}
