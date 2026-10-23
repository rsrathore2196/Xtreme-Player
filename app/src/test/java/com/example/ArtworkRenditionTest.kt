package com.example

import com.example.ui.util.providerArtworkUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkRenditionTest {
    @Test fun musicThumbnailsKeepTheirIdentityAndCropFlagsAtTheRequiredResolution() {
        assertEquals("https://lh3.googleusercontent.com/recording=w600-h600-l90-rj?token=unchanged",
            providerArtworkUrl("https://lh3.googleusercontent.com/recording=w60-h60-l90-rj?token=unchanged", 600))
        assertEquals("https://yt3.ggpht.com/recording=s600-c-k", providerArtworkUrl(
            "https://yt3.ggpht.com/recording=s120-c-k", 600))
    }

    @Test fun largerArtworkIsNotDowngraded() {
        val url = "https://lh3.googleusercontent.com/recording=w1200-h1200-l90-rj"
        assertEquals(url, providerArtworkUrl(url, 600))
        val apple = "https://is1-ssl.mzstatic.com/image/thumb/Music/1200x1200bb.jpg"
        assertEquals(apple, providerArtworkUrl(apple, 600))
    }

    @Test fun appleAndVideoThumbnailsUseTheirLargerRendition() {
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/600x600bb.jpg",
            providerArtworkUrl("https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg", 600))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg?token=same",
            providerArtworkUrl("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg?token=same", 600))
    }

    @Test fun unrelatedHostsLocalFilesAndUnrecognizedPathsAreNotRewritten() {
        for (url in listOf("content://artwork/1", "invalid", "https://covers.example/w60-h60.jpg",
            "https://lh3.googleusercontent.com.example/recording=w60-h60", "https://lh3.googleusercontent.com/recording?size=60"))
            assertEquals(url, providerArtworkUrl(url, 600))
    }
}
