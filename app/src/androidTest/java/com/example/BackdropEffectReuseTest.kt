package com.example

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.components.backdrop.BackdropEffectScopeImpl
import com.example.ui.components.backdrop.effects.blur
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackdropEffectReuseTest {
    @Test fun springOffsetsReuseTheIdenticalBlurGraph() {
        val scope = object : BackdropEffectScopeImpl() { override val shape = RectangleShape }
        scope.apply { blur(12f) }
        val original = scope.renderEffect!!
        val native = original.asAndroidRenderEffect()
        repeat(240) { frame ->
            scope.contentOffset = Offset(frame / 240f, frame / 480f)
            scope.apply { blur(12f) }
            assertSame(original, scope.renderEffect)
            assertSame(native, scope.renderEffect!!.asAndroidRenderEffect())
            assertEquals(36f, scope.padding)
        }
        scope.apply { blur(13f) }
        assertNotSame(original, scope.renderEffect)
        val larger = scope.renderEffect
        scope.apply { blur(13f, TileMode.Decal) }
        assertNotSame(larger, scope.renderEffect)
        val input = BlurEffect(null, 2f, 2f, TileMode.Clamp)
        assertNotSame(scope.blurEffect(null, 13f, TileMode.Decal), scope.blurEffect(input, 13f, TileMode.Decal))
        val beforeReset = scope.blurEffect(null, 12f, TileMode.Clamp)
        scope.reset()
        assertNotSame(beforeReset, scope.blurEffect(null, 12f, TileMode.Clamp))
    }
}
