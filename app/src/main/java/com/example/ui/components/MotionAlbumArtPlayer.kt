package com.example.ui.components

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.example.R

/**
 * Multi-tier Visual Artwork Component (Priority 1: Looped motion artwork, Priority 2: Static artwork):
 *
 * Requirements:
 * 1. Looped motion artwork (MP4 / WebM video canvas) if supplied by content source.
 * 2. Fallback to static album artwork with smooth crossfade if decoding fails or video unavailable.
 * 3. Video track MUST be MUTED (zero audio output) and set to Player.REPEAT_MODE_ALL.
 * 4. Automatically pause/release video player resources when off-screen or when app enters background.
 */
@OptIn(UnstableApi::class)
@Composable
fun MotionAlbumArtPlayer(
    videoUrl: String?,
    staticCoverUrl: String,
    contentDescription: String? = "Album Artwork",
    shape: Shape = RoundedCornerShape(24.dp),
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderMemoryCacheKey: MemoryCache.Key? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isVideoReady by remember(videoUrl) { mutableStateOf(false) }
    var hasVideoError by remember(videoUrl) { mutableStateOf(false) }

    val visualActive = LocalVisualEffectsActive.current
    val shouldPlayVideo = !videoUrl.isNullOrBlank() && !hasVideoError

    val exoPlayer = remember(videoUrl, hasVideoError) {
        if (!videoUrl.isNullOrBlank() && !hasVideoError) {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f // MUTED - Zero audio interference with music playback
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        // Decoder readiness can precede an actual video frame; retain the cover until then.
                        isVideoReady = true
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        hasVideoError = true
                        isVideoReady = false
                    }
                })
                setMediaItem(MediaItem.fromUri(videoUrl))
                prepare()
            }
        } else {
            null
        }
    }

    // Ownership and visibility are independent: replacing a lifecycle owner must not
    // release a remembered player that is still in use by the same composition.
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }
    DisposableEffect(exoPlayer, lifecycleOwner, shouldPlayVideo, visualActive) {
        fun updatePlayback() {
            if (visualActive && shouldPlayVideo && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                exoPlayer?.play()
            } else exoPlayer?.pause()
        }
        val observer = LifecycleEventObserver { _, _ -> updatePlayback() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updatePlayback()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer?.pause()
        }
    }
    val artworkRequest = remember(context, staticCoverUrl, placeholderMemoryCacheKey) {
        ImageRequest.Builder(context).data(staticCoverUrl)
            .placeholderMemoryCacheKey(placeholderMemoryCacheKey).crossfade(180).build()
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF10141D))
    ) {
        // Fallback or underlying base: High-Res Static Album Art
        AsyncImage(
            model = artworkRequest,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )

        // Priority 1 Video Canvas Layer
        if (shouldPlayVideo && exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    // TextureView participates in Compose clipping, scaling and shared-element layers.
                    (LayoutInflater.from(ctx).inflate(R.layout.motion_artwork_player, null, false) as PlayerView).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        alpha = if (isVideoReady) 1f else 0f
                    }
                },
                update = { view ->
                    view.player = exoPlayer
                    view.alpha = if (isVideoReady) 1f else 0f
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
