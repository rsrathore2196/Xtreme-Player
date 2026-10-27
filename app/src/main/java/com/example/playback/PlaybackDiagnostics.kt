package com.example.playback

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Bounded, event-driven diagnostics. No URLs, tokens, polling or Compose state. */
internal object PlaybackDiagnostics {
    enum class Reason(val description: String) {
        SUCCESS("ready"), NETWORK("network/API failure"), NO_MATCH("no matching recording"),
        INCOMPLETE_METADATA("incomplete recording metadata"),
        NO_FORMAT("no playable audio format"), UNSUPPORTED_TRANSPORT("unsupported stream transport"),
        INVALID_URL("invalid or expired stream URL"), POLICY("source disabled or quality restricted"),
        DECODER("decoder failure"), TIMEOUT("playback lookup timed out")
    }
    enum class Stage { DISCOVERY, EXTRACTION, VALIDATION, RESOLUTION, PREBUFFER, ACTIVATION, LOADING }
    class Failure(val reason: Reason, val stage: Stage, cause: Throwable? = null) :
        IOException(reason.description, cause)
    data class Attempt(val quality: AudioQualityCeiling, val policyKey: String, val source: PlaybackSourceKind?,
        val enabledSources: List<PlaybackSourceKind>, val stage: Stage, val reason: Reason, val atMs: Long,
        val sourceId: String = "", val elapsedMs: Long? = null)
    private val history = linkedMapOf<String, List<Attempt>>()
    const val STARTUP_TRACE_EXTRA = "playback_startup_trace"
    enum class StartupStage {
        SELECTED, RESOLUTION_STARTED, CONTROLLER_READY, RESOLVED, PREPARE_REQUESTED, STREAM_LOADING,
        FORMAT_READY, DECODER_READY, FIRST_AUDIO, CANCELLED, FAILED
    }
    data class Startup(val trackId: String, val entry: String, val startedAtMs: Long,
        val stages: Map<StartupStage, Long>) {
        val pending: Boolean get() = stages.keys.none {
            it == StartupStage.FIRST_AUDIO || it == StartupStage.CANCELLED || it == StartupStage.FAILED
        }
    }
    private val startups = linkedMapOf<Long, Startup>()
    private var nextStartup = 0L

    fun beginStartup(trackId: String, entry: String = "selection"): Long = synchronized(startups) {
        val id = ++nextStartup
        startups[id] = Startup(trackId, entry, SystemClock.elapsedRealtime(), mapOf(StartupStage.SELECTED to 0L))
        while (startups.size > 64) startups.remove(startups.keys.first())
        id
    }
    fun startupPending(id: Long, trackId: String): Boolean = synchronized(startups) {
        startups[id]?.let { it.trackId == trackId && it.pending } == true
    }
    fun markStartup(id: Long, stage: StartupStage, trackId: String? = null) {
        val completed = synchronized(startups) {
            val old = startups[id] ?: return
            if (!old.pending || trackId != null && old.trackId != trackId || stage in old.stages) return
            val updated = old.copy(stages = old.stages + (stage to (SystemClock.elapsedRealtime() - old.startedAtMs).coerceAtLeast(0L)))
            startups[id] = updated
            updated.takeIf { stage == StartupStage.FIRST_AUDIO || stage == StartupStage.FAILED }
        }
        // The sink's first advancing-audio callback measures actual Media3 output,
        // rather than treating isPlaying or a discovered URL as audible playback.
        if (completed != null) Log.d("PlaybackStartup", "trace=$id, entry=${completed.entry}, elapsedMs=${completed.stages}")
    }
    fun startup(id: Long): Startup? = synchronized(startups) { startups[id] }

    data class YouTubeEvent(val code: String, val client: String, val transport: String,
        val httpStatus: Int?, val reason: Reason?, val atMs: Long)
    private val youtubeHistory = linkedMapOf<String, List<YouTubeEvent>>()
    fun youtubeEvent(videoId: String, code: String, client: String = "", transport: String = "",
        httpStatus: Int? = null, reason: Reason? = null) {
        val event = YouTubeEvent(code, client, transport, httpStatus, reason, SystemClock.elapsedRealtime())
        synchronized(youtubeHistory) {
            val key = videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }.orEmpty()
            youtubeHistory[key] = (youtubeHistory[key].orEmpty() + event).takeLast(32)
            while (youtubeHistory.size > 64) youtubeHistory.remove(youtubeHistory.keys.first())
        }
        Log.d("YouTubePlayback", "stage=$code, client=$client, transport=$transport, http=$httpStatus, result=$reason")
    }
    fun recentYouTube(videoId: String): List<YouTubeEvent> = synchronized(youtubeHistory) { youtubeHistory[videoId].orEmpty() }

    fun record(trackId: String, policy: PlaybackSourcePolicy, source: PlaybackSourceKind?, stage: Stage, reason: Reason,
        sourceId: String = "", elapsedMs: Long? = null) {
        val attempt = Attempt(policy.ceiling, policy.key, source, policy.playbackSources.map { it.kind }, stage, reason,
            SystemClock.elapsedRealtime(), sourceId, elapsedMs)
        val changed = synchronized(history) {
            val old = history[trackId].orEmpty()
            history[trackId] = (old + attempt).takeLast(16)
            while (history.size > 64) history.remove(history.keys.first())
            old.lastOrNull()?.let { it.policyKey != attempt.policyKey || it.source != source || it.reason != reason ||
                it.stage != stage || attempt.atMs - it.atMs > 5_000 } ?: true
        }
        if (changed) Log.d("PlaybackRouting", "quality=${attempt.quality}, enabled=${attempt.enabledSources}, source=$source, stage=$stage, result=$reason, elapsedMs=$elapsedMs")
    }
    fun recent(trackId: String, policy: PlaybackSourcePolicy): List<Attempt> = synchronized(history) {
        history[trackId].orEmpty().filter { it.policyKey == policy.key }
    }
    fun reason(error: Throwable): Reason {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return causes.filterIsInstance<Failure>().firstOrNull()?.reason ?: when {
            causes.any { it is SocketTimeoutException || it is kotlinx.coroutines.TimeoutCancellationException } -> Reason.TIMEOUT
            causes.filterIsInstance<com.metrolist.innertubex.extraction.StreamResolveException>()
                .any { it.reason == com.metrolist.innertubex.extraction.StreamResolveException.Reason.NETWORK } -> Reason.NETWORK
            causes.any { it is UnknownHostException || it is IOException } -> Reason.NETWORK
            else -> Reason.NO_FORMAT
        }
    }
    /** Caller uses IO; Media3's callback only captures its immutable identity/policy. */
    fun playerFailure(trackId: String, policy: PlaybackSourcePolicy, uri: String,
        error: androidx.media3.common.PlaybackException) {
        val sourceId = SourceTransitionOrigin.sourceId(trackId, android.net.Uri.parse(uri), policy)
        val source = policy.configuredSources.firstOrNull { it.id == sourceId }?.kind
        val result = when (error.errorCode) {
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> Reason.DECODER
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Reason.TIMEOUT
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val status = generateSequence<Throwable>(error) { it.cause }.take(8)
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                if (status == 408 || status == 429 || status != null && status >= 500) Reason.NETWORK else Reason.INVALID_URL
            }
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> Reason.UNSUPPORTED_TRANSPORT
            else -> reason(error)
        }
        record(trackId, policy, source, Stage.LOADING, result, sourceId.orEmpty())
    }
    fun unavailable(trackId: String, title: String, policy: PlaybackSourcePolicy): String {
        if (policy.playbackSources.isEmpty()) return "No playback source is enabled for this streaming quality. Enable a source in Streaming Addons."
        val failures = recent(trackId, policy).asReversed().filter { it.source != null && it.reason != Reason.SUCCESS }
            .distinctBy { it.source }.take(4)
        val details = failures.joinToString("; ") { attempt ->
            val provider = when (attempt.source) {
                PlaybackSourceKind.YOUTUBE -> "YouTube Music"
                PlaybackSourceKind.JIOSAAVN -> "JioSaavn"
                PlaybackSourceKind.INTERNET_ARCHIVE -> "Internet Archive"
                else -> "Custom server"
            }
            "$provider: ${attempt.reason.description}"
        }
        return "Unable to play '${title.take(90)}'." + details.takeIf(String::isNotBlank)?.let { " $it." }.orEmpty()
    }
}
