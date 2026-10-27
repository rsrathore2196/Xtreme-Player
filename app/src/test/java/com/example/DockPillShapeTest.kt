package com.example

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.components.DockViewportShape
import com.example.ui.components.DockPillShape
import com.example.ui.components.backdrop.internal.ShapeProvider
import androidx.compose.runtime.mutableFloatStateOf
import org.junit.Assert.*
import org.junit.Test

class DockPillShapeTest {
    @Test fun compactSelectedPillIsCircularAndCenteredInsideItsUnchangedTouchTarget() {
        for (direction in LayoutDirection.entries) {
            val shape = DockPillShape(48f, direction == LayoutDirection.Rtl, 48f)
            val round = (shape.createOutline(Size(140f, 52f), direction, Density(1f)) as Outline.Rounded).roundRect
            assertEquals(48f, round.width, 0f)
            assertEquals(round.width, round.height, 0f)
            assertEquals(2f, round.top, 0f)
            assertEquals(24f, round.topLeftCornerRadius.x, 0f)
            assertEquals(24f, round.topLeftCornerRadius.y, 0f)
        }
    }

    @Test fun cachedBackdropOutlineFollowsTheLiveDockWidthInsteadOfKeepingTheFirstTabShape() {
        val scale = mutableFloatStateOf(.75f)
        // Production retains one shape instance across every tab and spring frame.
        val shape = DockViewportShape(horizontalScale = { scale.floatValue })
        val retainedProvider = ShapeProvider { shape }
        for (direction in LayoutDirection.entries) {
            for (widthScale in listOf(.75f, .9f, 1.05f, 1f, .9f, .75f)) {
                scale.floatValue = widthScale
                val outline = retainedProvider.shape.createOutline(Size(300f, 64f), direction, Density(1f))
                val round = (outline as Outline.Rounded).roundRect
                assertEquals("The backdrop's cached clip must track the selected tab width",
                    32f, round.topLeftCornerRadius.x * widthScale, .001f)
                assertEquals(32f, round.topLeftCornerRadius.y, 0f)
                assertSame("Unchanged geometry should reuse the prepared outline", outline,
                    retainedProvider.shape.createOutline(Size(300f, 64f), direction, Density(1f)))
            }
        }
    }

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
