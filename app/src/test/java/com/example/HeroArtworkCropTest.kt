package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import com.example.ui.util.HeroArtworkCropTransformation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class HeroArtworkCropTest {
    private fun frame(width: Int, height: Int, left: Float, right: Float): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.GREEN)
            for (x in left.toInt() until right.toInt()) for (y in 0 until height) it.setPixel(x, y, Color.MAGENTA)
        }

    @Test fun embeddedSquareCoverFillsTheHeroWithoutItsVideoPillarbox() = runBlocking {
        val source = frame(160, 90, 35f, 125f)
        val cropped = HeroArtworkCropTransformation.transform(source, Size(350, 240))
        assertEquals(90, cropped.width)
        assertEquals(90, cropped.height)
        assertEquals(Color.MAGENTA, cropped.getPixel(0, 45))
        assertEquals(Color.MAGENTA, cropped.getPixel(89, 45))
        assertEquals("The original shared artwork must stay intact", Color.GREEN, source.getPixel(0, 45))
    }

    @Test fun squareCoversAndFullFrameImagesRetainTheirOriginalPixels() = runBlocking {
        val square = frame(100, 100, 10f, 90f)
        assertSame(square, HeroArtworkCropTransformation.transform(square, Size(350, 240)))
        val photo = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        for (x in 0 until photo.width) for (y in 0 until photo.height)
            photo.setPixel(x, y, Color.rgb(x, y, (x + y) % 255))
        assertSame(photo, HeroArtworkCropTransformation.transform(photo, Size(350, 240)))
    }

    @Test fun asymmetricBackgroundsAreNotTreatedAsEmbeddedCoverPadding() = runBlocking {
        val source = frame(160, 90, 30f, 135f)
        assertSame(source, HeroArtworkCropTransformation.transform(source, Size(350, 240)))
    }
}
