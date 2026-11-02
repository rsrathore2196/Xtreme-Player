package com.example

import android.app.Application
import android.util.LruCache
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.CatalogArtist
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.components.ArtistPortrait
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.util.LocalArtworkRequestsActive
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistPortraitLifecycleUiTest {
    @get:Rule val compose = createComposeRule()
    @Before fun prepare() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("artist_images_v2", 0).edit().clear().commit()
        (ArtistPhotoRepository::class.java.getDeclaredField("memory").apply { isAccessible = true }.get(null) as LruCache<*, *>).evictAll()
        BackgroundWorkCoordinator.foreground(true); BackgroundWorkCoordinator.firstFrameRendered(); BackgroundWorkCoordinator.scrolling(false)
    }
    @After fun reset() { ArtistPhotoRepository.identityRequest = YouTubeMusicApiService::artistIdentity; BackgroundWorkCoordinator.foreground(false) }
    @Test fun interactionDoesNotRestartAVisibleLookupButLeavingTheViewportCancelsIt() {
        val calls = AtomicInteger(); val cancellations = AtomicInteger()
        val allowed = mutableStateOf(false); val active = mutableStateOf(true)
        ArtistPhotoRepository.identityRequest = { _, _ ->
            calls.incrementAndGet()
            try { awaitCancellation() } finally { cancellations.incrementAndGet() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalArtworkRequestsActive provides active.value, LocalVisualEffectsActive provides false) {
                ArtistPortrait(CatalogArtist("UCvisible", "Visible Artist"), Modifier.size(124.8.dp),
                    deferEnrichment = true, enrichmentAllowed = allowed.value)
            }
        }
        compose.runOnIdle { assertEquals(0, calls.get()); allowed.value = true }
        compose.waitForIdle()
        compose.waitUntil(3_000) { calls.get() == 1 }
        compose.runOnIdle { allowed.value = false }
        compose.waitForIdle()
        compose.runOnIdle { allowed.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, calls.get()); assertEquals(0, cancellations.get()); active.value = false }
        compose.waitForIdle()
        compose.waitUntil(3_000) { cancellations.get() == 1 }
    }
}
