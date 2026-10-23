package com.example.ui.components.backdrop

import android.app.Application
import androidx.test.filters.SdkSuppress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.components.backdrop.effects.lens
import com.example.ui.components.backdrop.effects.runtimeShaderEffect
import com.example.ui.components.backdrop.highlight.HighlightStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShaderCompatibilityTest {
    @Test fun android12HighlightsUseTheirExistingFallbackWithoutCompilingShaders() {
        assertTrue(isRenderEffectSupported())
        assertFalse(isRuntimeShaderSupported())
        val cache = RuntimeShaderCacheImpl()
        val scope = CanvasDrawScope()
        for (style in listOf(HighlightStyle.Default, HighlightStyle.Ambient)) {
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(ImageBitmap(32, 32)), Size(32f, 32f)) {
                assertNull(with(style) { createShader(RoundedCornerShape(8f), cache) })
            }
        }
    }

    @Test fun android12LensAndCustomShaderEffectsReturnBeforeTouchingApi33() {
        val scope = object : BackdropEffectScopeImpl() {
            override val shape = RoundedCornerShape(8f)
        }
        scope.size = Size(32f, 32f)
        scope.apply {
            lens(4f, 2f)
            runtimeShaderEffect("unsupported", "Deliberately invalid shader", "content") {
                error("Shader uniforms must not run on Android 12")
            }
        }
        assertNull(scope.renderEffect)
    }

    @Test
    @Config(sdk = [33])
    @SdkSuppress(minSdkVersion = 33)
    fun supportedDevicesRetainTheirCompiledShaderInstance() {
        assertTrue(isRuntimeShaderSupported())
        val cache = RuntimeShaderCacheImpl()
        val program = "half4 main(float2 position) { return half4(1.0); }"
        assertSame(cache.obtainRuntimeShader("test", program), cache.obtainRuntimeShader("test", program))
    }
}
