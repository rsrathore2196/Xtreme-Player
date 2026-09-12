package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.MusicTrack
import com.example.service.MusicService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentTrack: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val queue: List<MusicTrack> = emptyList(),
    val currentIndex: Int = 0,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isFavorite: Boolean = false,
    val selectedQuality: AudioQuality = AudioQuality.EXTREME_320,
    val qualityBadge: String = "HD • 320 kbps",
    val errorMessage: String? = null,
    val equalizerPreset: String = "Crystal Clarity"
)

enum class RepeatMode {
    OFF, ALL, ONE
}

class PlaybackManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressTickerJob: Job? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var activeQueue = mutableListOf<MusicTrack>()

    init {
        initMediaController()
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                setupPlayerListener()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        val player = controller ?: return

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    startProgressTicker()
                } else {
                    stopProgressTicker()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val isLoading = playbackState == Player.STATE_BUFFERING
                val duration = if (player.duration > 0) player.duration else _uiState.value.durationMs
                _uiState.update {
                    it.copy(
                        isLoading = isLoading,
                        durationMs = duration,
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
                    )
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    val trackId = mediaItem.mediaId
                    val matchedTrack = activeQueue.find { it.id == trackId }
                        ?: MusicTrack(
                            id = trackId,
                            title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
                            artist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown",
                            album = mediaItem.mediaMetadata.albumTitle?.toString() ?: "",
                            durationMs = player.duration.coerceAtLeast(0L),
                            coverUrl = mediaItem.mediaMetadata.artworkUri?.toString() ?: "",
                            audioUrl = mediaItem.requestMetadata.mediaUri?.toString() ?: ""
                        )
                    val index = activeQueue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)

                    _uiState.update {
                        it.copy(
                            currentTrack = matchedTrack,
                            currentIndex = index,
                            durationMs = if (player.duration > 0) player.duration else matchedTrack.durationMs,
                            qualityBadge = matchedTrack.qualityBadge,
                            isFavorite = matchedTrack.isLiked
                        )
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                updateProgressValues()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = "Audio playback error: ${error.message}"
                    )
                }
            }
        })
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch {
            while (isActive) {
                updateProgressValues()
                delay(400) // smooth update without overloading UI thread
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        updateProgressValues()
    }

    private fun updateProgressValues() {
        val player = controller ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = if (player.duration > 0) player.duration else _uiState.value.durationMs
        val buf = player.bufferedPosition.coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                currentPositionMs = pos,
                durationMs = dur,
                bufferedPositionMs = buf
            )
        }
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = listOf(track)) {
        val player = controller ?: return
        activeQueue = queue.toMutableList()

        val mediaItems = queue.map { it.toMediaItem() }
        val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()

        _uiState.update {
            it.copy(
                currentTrack = track,
                queue = activeQueue,
                currentIndex = startIndex,
                isPlaying = true,
                isLoading = true,
                durationMs = track.durationMs,
                currentPositionMs = 0L,
                qualityBadge = track.qualityBadge,
                isFavorite = track.isLiked,
                errorMessage = null
            )
        }
    }

    fun playPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        player.seekTo(positionMs)
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun skipNext() {
        val player = controller ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (activeQueue.isNotEmpty()) {
            // Loop back to first if list has tracks
            player.seekTo(0, 0L)
        }
    }

    fun skipPrevious() {
        val player = controller ?: return
        if (player.currentPosition > 3000) {
            player.seekTo(0)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0)
        }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        val newShuffle = !_uiState.value.isShuffle
        player.shuffleModeEnabled = newShuffle
        _uiState.update { it.copy(isShuffle = newShuffle) }
    }

    fun toggleRepeat() {
        val player = controller ?: return
        val current = _uiState.value.repeatMode
        val next = when (current) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        player.repeatMode = when (next) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        _uiState.update { it.copy(repeatMode = next) }
    }

    fun updateFavoriteStatus(trackId: String, isLiked: Boolean) {
        val current = _uiState.value.currentTrack
        if (current?.id == trackId) {
            _uiState.update {
                it.copy(
                    currentTrack = current.copy(isLiked = isLiked),
                    isFavorite = isLiked
                )
            }
        }
        // Also update inside activeQueue
        val idx = activeQueue.indexOfFirst { it.id == trackId }
        if (idx >= 0) {
            activeQueue[idx] = activeQueue[idx].copy(isLiked = isLiked)
            _uiState.update { it.copy(queue = activeQueue.toList()) }
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex in activeQueue.indices && toIndex in activeQueue.indices) {
            val item = activeQueue.removeAt(fromIndex)
            activeQueue.add(toIndex, item)
            controller?.moveMediaItem(fromIndex, toIndex)
            _uiState.update { it.copy(queue = activeQueue.toList()) }
        }
    }

    fun setEqualizerPreset(preset: String) {
        _uiState.update { it.copy(equalizerPreset = preset) }
    }

    fun setAudioQuality(quality: AudioQuality) {
        _uiState.update {
            it.copy(
                selectedQuality = quality,
                qualityBadge = quality.badge
            )
        }
        val currentTrack = _uiState.value.currentTrack ?: return
        val player = controller ?: return
        if (currentTrack.audioUrl.contains("saavncdn.com")) {
            val updatedUrl = com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(
                currentTrack.audioUrl,
                quality.id
            )
            val updatedTrack = currentTrack.copy(
                audioUrl = updatedUrl,
                bitrateKbps = quality.kbps,
                qualityBadge = quality.badge
            )
            val currentPos = player.currentPosition
            val wasPlaying = player.isPlaying
            val currentIdx = player.currentMediaItemIndex
            val mediaItem = updatedTrack.toMediaItem()
            if (currentIdx in 0 until player.mediaItemCount) {
                player.replaceMediaItem(currentIdx, mediaItem)
                player.seekTo(currentIdx, currentPos)
                if (wasPlaying) player.play()
            }
            _uiState.update {
                it.copy(
                    currentTrack = updatedTrack,
                    qualityBadge = quality.badge
                )
            }
        }
    }

    fun release() {
        progressTickerJob?.cancel()
        controller?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    private fun MusicTrack.toMediaItem(): MediaItem {
        val targetQuality = _uiState.value.selectedQuality
        val effectiveUrl = if (audioUrl.contains("saavncdn.com")) {
            com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(audioUrl, targetQuality.id)
        } else {
            audioUrl
        }
        val effectiveBitrate = if (audioUrl.contains("saavncdn.com")) targetQuality.kbps else bitrateKbps
        val effectiveBadge = if (audioUrl.contains("saavncdn.com")) targetQuality.badge else qualityBadge

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(effectiveUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(Uri.parse(coverUrl))
                    .setExtras(Bundle().apply {
                        putInt("bitrate", effectiveBitrate)
                        putString("qualityBadge", effectiveBadge)
                        putString("genre", genre)
                    })
                    .build()
            )
            .build()
    }
}
