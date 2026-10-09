package com.example.ui.components

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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import coil.request.ImageRequest

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
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isVideoReady by remember(videoUrl) { mutableStateOf(false) }
    var hasVideoError by remember(videoUrl) { mutableStateOf(false) }

    val shouldPlayVideo = !videoUrl.isNullOrBlank() && !hasVideoError

    val exoPlayer = remember(videoUrl) {
        if (!videoUrl.isNullOrBlank()) {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f // MUTED - Zero audio interference with music playback
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            isVideoReady = true
                        }
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

    // Lifecycle Observer: Pause and resume motion video with app foreground/background
    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    exoPlayer?.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (shouldPlayVideo) {
                        exoPlayer?.play()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer?.release()
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF10141D))
    ) {
        // Fallback or underlying base: High-Res Static Album Art
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(staticCoverUrl)
                .crossfade(180)
                .build(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )

        // Priority 1 Video Canvas Layer
        if (shouldPlayVideo && exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
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
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
