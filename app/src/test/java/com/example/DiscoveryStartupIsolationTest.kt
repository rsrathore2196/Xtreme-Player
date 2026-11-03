package com.example

import android.app.Application
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DiscoveryStartupIsolationTest {
    @Test fun homeAccountInspectionDoesNotConstructPlaybackOrProbeNetworkClients() {
        val service = YouTubeAudioStreamService
        fun initialized(name: String) = (service.javaClass.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }.get(service) as Lazy<*>).isInitialized()
        assertEquals("anonymous", YouTubeMusicApiService.discoveryAccountIdentity())
        assertFalse(YouTubeMusicApiService.hasAuthenticatedAccount())
        assertNull(service.discoveryAccountChanges.value)
        assertFalse("An account-only Home read must not construct TLS/playback networking", initialized("http"))
        assertFalse("An account-only Home read must not construct probe networking", initialized("mediaHttp"))
        service.releaseResources()
        assertFalse(initialized("http"))
        assertFalse(initialized("mediaHttp"))
    }
}
