package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.example.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.example.XtremeMusicApp
import com.example.data.model.MusicTrack
import com.example.data.local.TrackEntity
import com.example.data.repository.CatalogNames
import com.example.data.remote.YouTubeAudioStreamService
import kotlinx.coroutines.*

class MusicService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkpointJob: Job? = null
    private var checkpointItems: List<MediaItem> = emptyList()
    private val app: XtremeMusicApp get() = application as XtremeMusicApp

    private fun checkpoint(rebuild: Boolean = false) {
        val player = exoPlayer ?: return
        if (player.mediaItemCount == 0) return
        if (rebuild || checkpointItems.size != player.mediaItemCount) {
            checkpointItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        }
        PlaybackSessionStore.checkpoint(this, PlaybackSessionStore.Snapshot(checkpointItems,
            player.currentMediaItemIndex.coerceIn(0, checkpointItems.lastIndex), player.currentPosition.coerceAtLeast(0L)))
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try { result.set(block()) }
            catch (e: CancellationException) { result.cancel(false); throw e }
            catch (e: Exception) { result.setException(e) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    private fun realTrack(track: MusicTrack): Boolean {
        val host = Uri.parse(track.audioUrl).host.orEmpty().lowercase(java.util.Locale.ROOT)
        return host != "soundhelix.com" && !host.endsWith(".soundhelix.com") && track.id.isNotBlank() &&
            (track.audioUrl.isNotBlank() || track.id.startsWith("online_") ||
                (track.id.startsWith("yt_") && YouTubeAudioStreamService.isVideoId(track.id.removePrefix("yt_"))))
    }

    private fun paginate(items: List<MusicTrack>, index: Int, size: Int): List<MusicTrack> {
        if (index < 0 || size <= 0) return emptyList()
        val offset = index.toLong() * size
        if (offset >= items.size) return emptyList()
        return items.drop(offset.toInt()).take(size.coerceAtMost(100))
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // 1. Audio Focus & Interruption Handling
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 2. Software-Prioritized MediaCodec Selector for Audio
        // Audio decoding needs minimal CPU. Using pure OpenMAX software decoders (OMX.google.*)
        // bypasses CCodec C2 component interface resource queries completely, while
        // excluding hardware-accelerated vendor codecs prevents ResourceManagerService evictions.
        val softwarePrioritizedCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            val softwareDecoders = defaultDecoders.filter {
                it.softwareOnly ||
                it.name.startsWith("OMX.google.", ignoreCase = true) ||
                it.name.startsWith("c2.android.", ignoreCase = true) ||
                !it.hardwareAccelerated
            }.sortedWith(
                compareByDescending<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                    if (it.name.startsWith("OMX.google.", ignoreCase = true)) 3
                    else if (it.softwareOnly || it.name.startsWith("c2.android.", ignoreCase = true)) 2
                    else 1
                }
            )
            if (softwareDecoders.isNotEmpty()) {
                softwareDecoders
            } else {
                defaultDecoders
            }
        }

        // 3. Audio-only Renderers Factory: suppresses video codecs and uses software-prioritized selector
        val audioOnlyRenderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<Renderer>
            ) {
                // Audio player only: do not build video renderers or query video C2 component interfaces
            }
        }
            .setMediaCodecSelector(softwarePrioritizedCodecSelector)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .setEnableAudioFloatOutput(false)

        // 4. High-Fidelity 320kbps Audio Pipeline with Caching
        val cacheDataSourceFactory = MusicCache.createCacheDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)

        val player = ExoPlayer.Builder(this, audioOnlyRenderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000L)
            .setSeekForwardIncrementMs(10000L)
            .build()

        exoPlayer = player
        com.example.playback.SleepTimerManager.attach(this, player)

        // 4. Audio Effects & Equalizer real-time pipeline (attached on active playback session)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
                    com.example.playback.AudioEffectsManager.attachAudioSession(audioSessionId)
                }
            }
        })

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                checkpointJob?.cancel()
                checkpoint()
                if (isPlaying) checkpointJob = serviceScope.launch {
                    while (isActive) { delay(15_000L); checkpoint() }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
                if (queueChanged || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    checkpoint(queueChanged)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val sessionId = player.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0) {
                        com.example.playback.AudioEffectsManager.attachAudioSession(sessionId)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("MusicService", "Player error encountered: ${error.errorCodeName} - ${error.message}")
                // The controller owns bounded stream recovery. Do not retry indefinitely here.
            }
        })

        // 4. PendingIntent to reopen Xtreme Player UI on notification click
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 5. MediaLibrarySession Setup for Phone, Tablet, and Android Auto Head Units
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, CustomMediaLibrarySessionCallback())
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        checkpoint()
        checkpointJob?.cancel()
        serviceScope.cancel()
        exoPlayer?.let(com.example.playback.SleepTimerManager::detach)
        com.example.playback.AudioEffectsManager.detachAudioSession()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        exoPlayer = null
        super.onDestroy()
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SET_EQUALIZER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_OUTPUT_DEVICE, Bundle.EMPTY))
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val player = exoPlayer
            if (player != null && player.mediaItemCount > 0 &&
                (0 until player.mediaItemCount).all { PlaybackSessionStore.isReal(player.getMediaItemAt(it)) }) {
                val currentIdx = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                val currentPos = player.currentPosition
                val items = mutableListOf<MediaItem>()
                for (i in 0 until player.mediaItemCount) {
                    items.add(player.getMediaItemAt(i))
                }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        currentIdx,
                        currentPos
                    )
                )
            }
            return future {
                val saved = PlaybackSessionStore.restore(this@MusicService)
                MediaSession.MediaItemsWithStartPosition(saved?.items.orEmpty(),
                    saved?.index ?: C.INDEX_UNSET, saved?.positionMs ?: C.TIME_UNSET)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootExtras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putBoolean("androidx.media.contentstyle.SUPPORTED", true)
                putInt("androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT", 1) // Grid
                putInt("androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT", 2) // List
                putInt("androidx.media.contentstyle.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE", 1)
                putInt("androidx.media.contentstyle.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE", 2)
            }
            val libraryParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .setOffline(false)
                .setRecent(false)
                .setSuggested(false)
                .build()

            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_MEDIA_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Xtreme Player Library")
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setExtras(rootExtras)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return when (parentId) {
                ROOT_MEDIA_ID -> {
                    // Android Auto Root Browsable Menu Categories
                    val categories = listOf(
                        createBrowsableCategory("category_driving", "Highway Driving Beats", "Optimized acoustic mixes for car cabins"),
                        createBrowsableCategory("category_recommended", "Recommended For You", "Top smart autoplay picks"),
                        createBrowsableCategory("category_punjabi", "Punjabi Hits", "Sidhu, Karan Aujla, Diljit, AP Dhillon"),
                        createBrowsableCategory("category_bollywood", "Bollywood & Hindi", "Arijit Singh, Shreya Ghoshal, Atif Aslam"),
                        createBrowsableCategory("category_pop", "English & Global Pop", "The Weeknd, Dua Lipa, Ed Sheeran"),
                        createBrowsableCategory("category_synthwave", "Synthwave & Electronic", "320kbps High Octane Driving Beats"),
                        createBrowsableCategory("category_chill", "Chillhop & Lo-Fi", "Relaxing driving sounds & lo-fi grooves"),
                        createBrowsableCategory("category_all", "All Songs", "Full catalog of tracks")
                    )
                    Futures.immediateFuture(LibraryResult.ofItemList(categories, params))
                }

                else -> future {
                    val tracks = getTracksForCategory(parentId)
                    LibraryResult.ofItemList(paginate(tracks, page, pageSize).map { it.toMediaItem() }, params)
                }
            }
        }

        private suspend fun getTracksForCategory(categoryId: String): List<MusicTrack> = withContext(Dispatchers.IO) {
            val records = app.database.musicDao().getAllTracksSync()
                .filter { realTrack(it.toMusicTrack()) }
                .sortedWith(compareByDescending<TrackEntity> { it.isLiked }.thenByDescending { it.lastPlayedAt }.thenByDescending { it.addedAt })
            val tracks = records.map { it.toMusicTrack() }
            fun has(track: MusicTrack, vararg words: String) = words.any { word ->
                (word.length > 2 && track.genre.contains(word, true)) || track.language.equals(word, true)
            }
            when (categoryId) {
                "category_all" -> tracks
                "category_driving" -> tracks.filter { has(it, "synth", "electronic", "rock", "punjabi") }
                "category_punjabi" -> tracks.filter { has(it, "punjabi", "pa") }
                "category_bollywood" -> tracks.filter { has(it, "bollywood", "hindi", "hi") }
                "category_pop" -> tracks.filter { has(it, "pop", "rock", "english", "en") }
                "category_synthwave" -> tracks.filter { has(it, "synth", "electronic") }
                "category_chill" -> tracks.filter { has(it, "chill", "ambient", "jazz", "lo-fi") }
                "category_recommended" -> {
                    val discoveries = try { app.repository.getYtmQuickPicks(30) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    (discoveries + records.filter { it.isLiked || it.lastPlayedAt > 0L }.map { it.toMusicTrack() })
                        .filter(::realTrack).distinctBy { it.id }
                }
                else -> emptyList()
            }
        }

        private suspend fun searchTracks(query: String, requestedPage: Int = 1): List<MusicTrack> = withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val local = app.database.musicDao().getAllTracksSync().map { it.toMusicTrack() }.filter {
                realTrack(it) && CatalogNames.score(query, it.title, listOf(it.artist, it.album, it.genre, it.language)) > 0
            }
            val remote = mutableListOf<MusicTrack>()
            for (providerPage in 1..requestedPage.coerceIn(1, 10)) {
                val result = try { app.repository.catalogBrowser.search(query, "Tracks", providerPage) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { break }
                remote.addAll(result.tracks)
                if (result.nextPage == null) break
            }
            for (track in remote) {
                if (app.database.musicDao().getTrackById(track.id) == null)
                    app.database.musicDao().mergeTrackMetadata(TrackEntity.fromMusicTrack(track))
            }
            (local + remote).filter(::realTrack).distinctBy { it.id }
                .sortedByDescending { CatalogNames.score(query, it.title, listOf(it.artist, it.album)) }
        }

        private fun createBrowsableCategory(id: String, title: String, subtitle: String): MediaItem {
            val extras = Bundle().apply {
                putInt("androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT", 1)
                putInt("androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT", 2)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }

        private fun com.example.data.model.MusicTrack.toMediaItem(): MediaItem {
            val extras = Bundle().apply {
                putInt("androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT", 2)
                putString("media_source", source)
                putInt("bitrate", bitrateKbps)
                putString("qualityBadge", qualityBadge)
                putString("genre", genre)
                putLong("duration", durationMs)
                putBoolean("isLiked", isLiked); putBoolean("isLossless", isLossless)
                putString("singers", singers); putString("writer", writer)
                putString("language", language); putString("year", year); putString("isrc", isrc)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setUri(YouTubeAudioStreamService.playbackUri(id, audioUrl))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setGenre(genre)
                        .setArtworkUri(Uri.parse(coverUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            return future {
                val tracks = searchTracks(query.trim())
                session.notifySearchResultChanged(browser, query, tracks.size, params)
                LibraryResult.ofVoid(params)
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val needed = ((page.coerceAtLeast(0).toLong() + 1L) * pageSize.coerceIn(1, 100)).coerceAtMost(100L)
            val tracks = searchTracks(query.trim(), ((needed + 9L) / 10L).toInt())
            LibraryResult.ofItemList(paginate(tracks, page, pageSize).map { it.toMediaItem() }, params)
        }

        private fun getCategoryById(id: String): MediaItem? {
            return when (id) {
                "category_driving" -> createBrowsableCategory("category_driving", "Highway Driving Beats", "Optimized acoustic mixes for car cabins")
                "category_recommended" -> createBrowsableCategory("category_recommended", "Recommended For You", "Top smart autoplay picks")
                "category_punjabi" -> createBrowsableCategory("category_punjabi", "Punjabi Hits", "Sidhu, Karan Aujla, Diljit, AP Dhillon")
                "category_bollywood" -> createBrowsableCategory("category_bollywood", "Bollywood & Hindi", "Arijit Singh, Shreya Ghoshal, Atif Aslam")
                "category_pop" -> createBrowsableCategory("category_pop", "English & Global Pop", "The Weeknd, Dua Lipa, Ed Sheeran")
                "category_synthwave" -> createBrowsableCategory("category_synthwave", "Synthwave & Electronic", "320kbps High Octane Driving Beats")
                "category_chill" -> createBrowsableCategory("category_chill", "Chillhop & Lo-Fi", "Relaxing driving sounds & lo-fi grooves")
                "category_all" -> createBrowsableCategory("category_all", "All Songs", "Full catalog of tracks")
                else -> null
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == ROOT_MEDIA_ID) {
                return onGetLibraryRoot(session, browser, null)
            }
            val category = getCategoryById(mediaId)
            if (category != null) {
                return Futures.immediateFuture(LibraryResult.ofItem(category, null))
            }
            return future {
                val track = withContext(Dispatchers.IO) { app.database.musicDao().getTrackById(mediaId)?.toMusicTrack() }
                if (track != null && realTrack(track)) LibraryResult.ofItem(track.toMediaItem(), null)
                else LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_SET_EQUALIZER) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == COMMAND_SET_OUTPUT_DEVICE) {
                val deviceId = args.getInt("device_id", -1)
                com.example.playback.AudioDeviceManager.selectDevice(this@MusicService, deviceId, exoPlayer)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            return future {
                val items = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    if (item.mediaId.startsWith("category_")) {
                        items.addAll(getTracksForCategory(item.mediaId).take(100).map { it.toMediaItem() })
                    } else items.add(item)
                }
                val expandingCategory = mediaItems.any { it.mediaId.startsWith("category_") }
                coroutineScope {
                    val slots = kotlinx.coroutines.sync.Semaphore(3)
                    items.map { item -> async(Dispatchers.IO) {
                        slots.acquire()
                        try {
                            if (PlaybackSessionStore.isReal(item) && controller.packageName == packageName) return@async item
                            val known = app.database.musicDao().getTrackById(item.mediaId)?.toMusicTrack()
                            if (known != null && realTrack(known)) {
                                app.repository.resolvePlayableTrack(known).toMediaItem()
                            } else if (PlaybackSessionStore.isReal(item)) item
                            else throw IllegalArgumentException("No real track found for this media ID")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (expandingCategory) null else throw e }
                        finally { slots.release() }
                    } }.awaitAll().filterNotNull().toMutableList().also {
                        if (it.isEmpty() && items.isNotEmpty()) throw IllegalArgumentException("No playable tracks are available in this category")
                    }
                }
            }
        }

    }

    companion object {
        const val COMMAND_SET_EQUALIZER = "com.example.COMMAND_SET_EQUALIZER"
        const val COMMAND_SET_OUTPUT_DEVICE = "com.example.COMMAND_SET_OUTPUT_DEVICE"
        const val ROOT_MEDIA_ID = "xtreme_media_root"
    }
}
