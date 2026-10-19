package com.example

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.components.DockViewportShape
import org.junit.Assert.*
import org.junit.Test

class DockPillShapeTest {
    @Test fun allRestingWidthsKeepCircularEndsAfterTheExistingLayerTransform() {
        for (direction in LayoutDirection.entries) for (scale in listOf(0.75f, 0.9f, 1f, 1.05f)) {
            val shape = DockViewportShape(horizontalScale = { scale })
            val round = (shape.createOutline(Size(300f, 64f), direction, Density(1f)) as Outline.Rounded).roundRect
            assertEquals(round.topLeftCornerRadius.y, round.topLeftCornerRadius.x * scale, 0.001f)
            assertEquals(32f, round.topLeftCornerRadius.y, 0f)
            assertEquals(Size(300f, 64f), Size(round.width, round.height))
        }
    }
}
