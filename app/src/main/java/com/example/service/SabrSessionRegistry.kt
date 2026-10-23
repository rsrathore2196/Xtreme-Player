package com.example.service

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import com.example.data.local.AudioQualityPreferences
import com.example.data.remote.YouTubeAudioStreamService
import com.example.playback.AudioQuality
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A new container representation must be reopened by playback time, never spliced by byte offset. */
internal class SabrStreamChangedException(val replacement: YouTubeAudioStreamService.AudioStream) :
    IOException("The YouTube audio representation changed; reopen at the current playback position")

/** Memory-only credentials; bounded, disposable on-disk media windows. No protocol implementation. */
@OptIn(ExperimentalSabrApi::class)
internal object SabrSessionRegistry {
    /** Invoked only on the player's application looper; no UI lifetime is required for fallback. */
    @Volatile var controllerHandlesRecovery: (() -> Boolean)? = null
    @Volatile var requestSourceRecovery: ((String, String) -> Boolean)? = null
    private data class Descriptor(val audio: YouTubeAudioStreamService.AudioStream, val quality: AudioQuality)
    private val descriptors = android.util.LruCache<String, Descriptor>(64)
    private val replacements = android.util.LruCache<String, YouTubeAudioStreamService.AudioStream>(16)
    private val sessions = mutableMapOf<String, Session>()
    private val registryLock = Any()
    private val storageMutex = Mutex()
    private var storage: File? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong()
    private const val MAX_SESSIONS = 3
    private val diskSlots = Semaphore(MAX_SESSIONS)
    private const val IDLE_TIMEOUT_MS = 90_000L
    private const val READ_TIMEOUT_MS = 75_000L
    // InnerTubeX honors server backoff up to 30s. Do not mistake that
    // legitimate policy delay for a dead connection.
    private const val CHUNK_TIMEOUT_MS = 45_000L
    private const val READAHEAD_BYTES = 1024 * 1024L
    private const val MAX_CHUNK_BYTES = 8 * 1024 * 1024
    private const val MAX_INIT_BYTES = 1024 * 1024

    fun cacheKeyFor(bootstrap: SabrBootstrap): String {
        val format = bootstrap.audioFormat
        val identity = listOf(bootstrap.videoId, format.itag, format.lastModified, format.xtags,
            bootstrap.audioTrackId, bootstrap.isDrc, bootstrap.mimeType, bootstrap.contentLengthBytes,
            // Without a modification identity, bytes from a renewed client/session
            // are not proven interchangeable. Force a time-based container reopen.
            bootstrap.serverAbrStreamingUrl.takeIf { format.lastModified <= 0 }).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "youtube-sabr:${bootstrap.videoId}:$digest"
    }

    fun uriFor(bootstrap: SabrBootstrap, qualityCapKbps: Int): Uri = Uri.Builder().scheme("xtreme-sabr").authority("audio")
        .appendPath(bootstrap.videoId).appendPath(cacheKeyFor(bootstrap).substringAfterLast(':'))
        .appendPath("stream.audio").appendQueryParameter("cap", qualityCapKbps.toString()).build()

    fun cacheKeyFromUri(uri: Uri): String {
        val videoId = YouTubeAudioStreamService.videoIdFromUri(uri) ?: throw IOException("Invalid SABR URI")
        val digest = uri.pathSegments.getOrNull(1).orEmpty()
        if (uri.scheme != "xtreme-sabr" || digest.length != 64 || digest.any { it !in '0'..'9' && it !in 'a'..'f' } ||
            uri.pathSegments.size != 3 || uri.lastPathSegment != "stream.audio") throw IOException("Invalid SABR representation")
        return "youtube-sabr:$videoId:$digest"
    }

    // MediaController may serialize an IOException cause across Binder. Keep the
    // verified replacement in-process instead of relying on its exception subtype.
    fun recordReplacement(failedUri: String, replacement: YouTubeAudioStreamService.AudioStream) {
        replacements.put(failedUri, replacement)
    }
    fun takeReplacement(failedUri: String): YouTubeAudioStreamService.AudioStream? = replacements.remove(failedUri)
        ?.takeIf { it.expiresAt > System.currentTimeMillis() + 5_000L &&
            YouTubeAudioStreamService.videoIdFromUri(Uri.parse(failedUri)) == it.videoId }

    fun register(audio: YouTubeAudioStreamService.AudioStream, quality: AudioQuality) {
        require(audio.isSabr)
        descriptors.put(audio.url, Descriptor(audio, quality))
    }

    suspend fun acquire(context: Context, uri: Uri, position: Long): Reader = withContext(Dispatchers.IO) {
        val generation = epoch.get()
        val cacheKey = cacheKeyFromUri(uri)
        val selectedQuality = AudioQualityPreferences.getSelectedQuality(context)
        val retained = synchronized(registryLock) {
            if (generation != epoch.get()) throw IOException("SABR service closed during stream resolution")
            sessions[cacheKey]?.also { it.updateQuality(selectedQuality) }?.attach(position)
        }
        if (retained != null) return@withContext retained
        val videoId = YouTubeAudioStreamService.videoIdFromUri(uri)
            ?: throw IOException("Invalid SABR recording identity")
        var descriptor = descriptors.get(uri.toString())
        if (descriptor == null) {
            val resolved = YouTubeAudioStreamService.resolveSabr(videoId, selectedQuality,
                maxBitrateBps = YouTubeAudioStreamService.bitrateCapFromUri(uri))
            if (resolved.url != uri.toString()) {
                recordReplacement(uri.toString(), resolved)
                throw SabrStreamChangedException(resolved)
            }
            descriptor = Descriptor(resolved, selectedQuality)
        }
        val root = storageMutex.withLock {
            storage ?: File(context.cacheDir, "xtreme_sabr_sessions").also { folder ->
                // Run once, before any session is created. Previous process windows
                // have no live credentials/index and must never accumulate on disk.
                if (folder.exists()) folder.listFiles()?.forEach { it.deleteRecursively() }
                if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't create the SABR buffer")
                storage = folder
            }
        }
        val unused = mutableListOf<Session>()
        val reader = synchronized(registryLock) {
            if (generation != epoch.get()) throw IOException("SABR service closed during stream resolution")
            val key = descriptor.audio.cacheKey
            val session = sessions[key] ?: run {
                while (sessions.size >= MAX_SESSIONS) {
                    val victim = sessions.values.filter { it.readerCount == 0 }.minByOrNull { it.lastUsed.get() }
                        ?: throw IOException("Too many active SABR streams")
                    sessions.remove(victim.key)
                    unused.add(victim)
                }
                Session(descriptor.audio, selectedQuality, File(root, UUID.randomUUID().toString()), context.applicationContext)
                    .also { sessions[key] = it }
            }
            session.attach(position)
        }
        unused.forEach(Session::shutdown)
        reader
    }

    private fun expire(session: Session) {
        val removed = synchronized(registryLock) {
            if (session.readerCount == 0 && sessions[session.key] === session) {
                sessions.remove(session.key); true
            } else false
        }
        if (removed) session.shutdown()
    }

    /** Cancellation is immediate; file closing/deletion never runs on the service's Main thread. */
    fun releaseAll() {
        val old = synchronized(registryLock) {
            epoch.incrementAndGet()
            sessions.values.toList().also { sessions.clear(); descriptors.evictAll(); replacements.evictAll() }
        }
        old.forEach(Session::shutdown)
    }

    internal class Reader internal constructor(private val session: Session, val id: Long, position: Long) {
        val closed = AtomicBoolean(false)
        val position = AtomicLong(position)
        val contentLength: Long? get() = session.availableLength
        val mimeType: String get() = session.mimeType
        suspend fun ready() = session.ready(this)
        suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = session.read(this, buffer, offset, length)
        fun close() { if (closed.compareAndSet(false, true)) session.detach(this) }
    }

    internal class Session(
        private var audio: YouTubeAudioStreamService.AudioStream,
        @Volatile private var quality: AudioQuality,
        private val folder: File,
        private val context: Context
    ) {
        val key = audio.cacheKey
        val contentLength = audio.contentLength
        val availableLength: Long? get() = finalLength
        val mimeType = requireNotNull(audio.sabrBootstrap).mimeType
        val lastUsed = AtomicLong(SystemClock.elapsedRealtime())
        private val closed = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val readers = ConcurrentHashMap<Long, Reader>()
        private val readerIds = AtomicLong()
        val readerCount: Int get() = readers.size
        private val changes = MutableStateFlow(0L)
        private val diskMutex = Mutex()
        private val producerMutex = Mutex()
        private var disk: WindowStore? = null
        private var ownsDiskSlot = false
        private val index = TreeMap<Long, Segment>()
        @Volatile private var producer: Job? = null
        @Volatile private var idleCleanup: Job? = null
        @Volatile private var failure: IOException? = null
        @Volatile private var complete = false
        @Volatile private var initialized = false
        @Volatile private var mediaReceived = false
        @Volatile private var lastMediaStart = -1L
        @Volatile private var lastMediaEnd = 0L
        @Volatile private var lastMediaTimeMs = 0L
        @Volatile private var finalLength: Long? = contentLength
        private val excluded = mutableSetOf<String>()
        private var clientRecoveries = 0

        private data class Segment(val end: Long, val timeMs: Long)
        fun updateQuality(selected: AudioQuality) { quality = selected }
        private fun notifyReaders() { changes.update { it + 1 } }
        fun attach(position: Long): Reader {
            check(!closed.get()) { "SABR session is closed" }
            idleCleanup?.cancel()
            lastUsed.set(SystemClock.elapsedRealtime())
            return Reader(this, readerIds.incrementAndGet(), position).also { readers[it.id] = it; notifyReaders() }
        }
        fun detach(reader: Reader) {
            readers.remove(reader.id)
            lastUsed.set(SystemClock.elapsedRealtime())
            notifyReaders()
            if (readers.isNotEmpty()) return
            idleCleanup?.cancel()
            if (failure != null) { expire(this); return }
            idleCleanup = scope.launch {
                // A grace period preserves the protocol coroutine across sniffing
                // and routine reopen; an abandoned load must release its network.
                delay(5_000L)
                producerMutex.withLock {
                    if (readers.isEmpty()) producer?.cancelAndJoin()
                }
                delay(IDLE_TIMEOUT_MS - 5_000L)
                if (readers.isEmpty()) expire(this@Session)
            }
        }

        private fun checkReader(reader: Reader) {
            if (closed.get() || reader.closed.get()) throw IOException("SABR read canceled")
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("SABR loader interrupted")
        }

        suspend fun ready(reader: Reader) {
            checkReader(reader)
            if (initialized && mediaReceived) return
            ensureProducer(reader.position.get())
            withReadDeadline {
                while (true) {
                    currentCoroutineContext().ensureActive(); checkReader(reader)
                    val revision = changes.value
                    if (initialized && mediaReceived) return@withReadDeadline
                    failure?.let { throw it }
                    changes.first { it != revision }
                }
            }
        }

        suspend fun read(reader: Reader, buffer: ByteArray, offset: Int, length: Int): Int {
            checkReader(reader)
            val position = reader.position.get()
            val end = finalLength
            if (end != null && position >= end) return C.RESULT_END_OF_INPUT
            val cached = diskMutex.withLock { disk?.read(position, buffer, offset, length) ?: 0 }
            if (cached > 0) { advance(reader, cached); return cached }
            ensureProducer(position)
            return withReadDeadline {
                while (true) {
                    currentCoroutineContext().ensureActive(); checkReader(reader)
                    val revision = changes.value
                    val count = diskMutex.withLock { disk?.read(position, buffer, offset, length) ?: 0 }
                    if (count > 0) { advance(reader, count); return@withReadDeadline count }
                    failure?.let { throw it }
                    if (complete) {
                        if (position == finalLength) return@withReadDeadline C.RESULT_END_OF_INPUT
                        throw EOFException("SABR ended with a missing requested byte range")
                    }
                    changes.first { it != revision }
                }
                @Suppress("UNREACHABLE_CODE") C.RESULT_END_OF_INPUT
            }
        }

        private fun advance(reader: Reader, count: Int) {
            reader.position.addAndGet(count.toLong())
            lastUsed.set(SystemClock.elapsedRealtime())
            notifyReaders()
        }

        private suspend fun <T> withReadDeadline(block: suspend () -> T): T =
            try { withTimeout(READ_TIMEOUT_MS) { block() } }
            catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("SABR timed out waiting for audio bytes", e)
            }

        private suspend fun ensureProducer(position: Long) = producerMutex.withLock {
            failure?.let { throw it }
            if (closed.get()) throw IOException("SABR session is closed")
            // Only known segment timestamps are used for seeks. Unknown byte
            // offsets are reached from an earlier verified anchor, never guessed
            // from bitrate/duration (which corrupts variable-bitrate containers).
            val anchor = diskMutex.withLock { index.floorEntry(position)?.value?.timeMs ?: 0L }
            val needsSeek = lastMediaStart > position || complete || anchor > lastMediaTimeMs + 5_000L
            if (producer?.isActive == true && !needsSeek) return@withLock
            producer?.cancelAndJoin()
            complete = false
            lastMediaStart = -1L
            lastMediaEnd = 0L
            lastMediaTimeMs = anchor
            producer = scope.launch { produce(anchor) }
        }

        private suspend fun awaitCapacity(chunk: SabrChunk) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val revision = changes.value
                val active = readers.values.filterNot { it.closed.get() }
                if (active.isNotEmpty() && (chunk.isInitialization || active.any {
                        chunk.startRange <= it.position.get() + READAHEAD_BYTES
                    })) return
                // Preserve the same SabrAudioStream coroutine/cookies over normal
                // close/reopen and pause. No network polling while nobody reads.
                changes.first { it != revision }
            }
        }

        private suspend fun produce(initialTimeMs: Long) {
            var startMs = initialTimeMs
            while (currentCoroutineContext().isActive) {
                try {
                    if (audio.expiresAt <= System.currentTimeMillis() + 15_000L) refreshClient(excludeCurrent = false)
                    val received = ByteRanges()
                    var firstMediaOffset: Long? = null
                    coroutineScope {
                        val incoming = YouTubeAudioStreamService.sabrChunks(audio, startMs).buffer(0).produceIn(this)
                        try {
                            while (true) {
                                val result = withTimeout(CHUNK_TIMEOUT_MS) { incoming.receiveCatching() }
                                val chunk = result.getOrNull() ?: run {
                                    result.exceptionOrNull()?.let { throw it }
                                    break
                                }
                                awaitCapacity(chunk)
                                store(chunk)
                                received.add(chunk.startRange, chunk.endRangeExclusive)
                                if (!chunk.isInitialization) firstMediaOffset = minOf(firstMediaOffset ?: chunk.startRange, chunk.startRange)
                            }
                        } finally { incoming.cancel() }
                    }
                    if (!initialized || !mediaReceived || contentLength?.let { it != lastMediaEnd } == true)
                        throw EOFException("Incomplete SABR audio container")
                    val requiredStart = if (startMs == 0L) 0L else firstMediaOffset
                        ?: throw EOFException("SABR seek returned no media bytes")
                    if (!received.covers(0L, 1L) || !received.covers(requiredStart, lastMediaEnd))
                        throw EOFException("SABR response left holes in the audio container")
                    finalLength = contentLength ?: lastMediaEnd
                    complete = true
                    notifyReaders()
                    return
                } catch (e: CancellationException) {
                    // A per-response deadline can select another supported client;
                    // actual session/seek cancellation must cancel the network too.
                    currentCoroutineContext().ensureActive()
                    if (e !is TimeoutCancellationException) throw e
                    if (!recover(IOException("SABR response stalled", e))) return
                } catch (e: Exception) {
                    if (!recover(e)) return
                }
                startMs = diskMutex.withLock {
                    val target = readers.values.maxByOrNull { it.id }?.position?.get() ?: lastMediaEnd
                    index.floorEntry(target)?.value?.timeMs ?: 0L
                }
            }
        }

        private suspend fun recover(error: Exception): Boolean {
            currentCoroutineContext().ensureActive()
            if (error is SabrStreamChangedException) {
                failure = error
                notifyReaders()
                return false
            }
            return try {
                refreshClient(excludeCurrent = true)
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failure = when (e) {
                    is SabrStreamChangedException -> e
                    else -> IOException("SABR audio is unavailable after bounded client recovery", error)
                }
                notifyReaders()
                false
            }
        }

        private suspend fun refreshClient(excludeCurrent: Boolean) {
            com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(context)
            val policy = com.example.playback.NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot()
            if (!policy.online || !policy.allows(com.example.playback.PlaybackSourceKind.YOUTUBE))
                throw com.example.playback.PlaybackDiagnostics.Failure(com.example.playback.PlaybackDiagnostics.Reason.POLICY,
                    com.example.playback.PlaybackDiagnostics.Stage.LOADING)
            // Routine signed-session renewal is not a transport failure. Only
            // failed-client attempts consume the bounded recovery budget.
            if (excludeCurrent && clientRecoveries++ >= 2) throw IOException("SABR client retry limit reached")
            if (excludeCurrent) {
                excluded.add(audio.clientId)
                YouTubeAudioStreamService.reject(audio.videoId, audio.url, audio.clientId)
            } else YouTubeAudioStreamService.invalidate(audio.videoId)
            val refreshed = YouTubeAudioStreamService.resolveSabr(audio.videoId, quality, excluded.toSet(),
                maxBitrateBps = minOf(audio.selectionCapBps, policy.ceiling.maxBitrateBps))
            if (refreshed.cacheKey != key) {
                recordReplacement(audio.url, refreshed)
                throw SabrStreamChangedException(refreshed)
            }
            audio = refreshed
        }

        private suspend fun store(chunk: SabrChunk) {
            val end = chunk.endRangeExclusive
            val startMs = chunk.startMs
            val durationMs = chunk.durationMs
            if (chunk.data.isEmpty() || chunk.data.size > MAX_CHUNK_BYTES ||
                contentLength?.let { end > it } == true) throw IOException("Invalid SABR segment byte range")
            if (chunk.isInitialization && (chunk.startRange != 0L || chunk.data.size > MAX_INIT_BYTES))
                throw IOException("Invalid SABR initialization segment")
            if (!chunk.isInitialization && (!initialized || startMs == null || startMs < 0 ||
                    durationMs == null || durationMs <= 0)) throw IOException("Invalid SABR media timing")
            diskMutex.withLock {
                val window = disk ?: run {
                    // Cleanup can overlap a queue transition. Admission counts
                    // closing windows too, keeping disk use bounded to 48 MiB.
                    diskSlots.acquire()
                    try {
                        WindowStore(folder).also { disk = it; ownsDiskSlot = true }
                    } catch (e: Exception) { diskSlots.release(); throw e }
                }
                window.write(chunk.startRange, chunk.data, chunk.isInitialization)
                if (chunk.isInitialization) initialized = true else {
                    if (index.size >= 10_000 && chunk.startRange !in index) throw IOException("SABR segment index exceeded its limit")
                    index[chunk.startRange] = Segment(end, requireNotNull(chunk.startMs))
                    lastMediaStart = chunk.startRange
                    lastMediaEnd = maxOf(lastMediaEnd, end)
                    lastMediaTimeMs = requireNotNull(startMs)
                    mediaReceived = true
                }
            }
            notifyReaders()
        }

        fun shutdown() {
            if (!closed.compareAndSet(false, true)) return
            scope.cancel()
            notifyReaders()
            cleanup.launch {
                try {
                    producer?.join()
                    diskMutex.withLock {
                        try { disk?.close() } finally {
                            disk = null
                            try { folder.deleteRecursively() } finally {
                                if (ownsDiskSlot) { ownsDiskSlot = false; diskSlots.release() }
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("SabrSession", "Couldn't remove a disposable audio window", e)
                }
            }
        }
    }

    /** Coverage metadata only; seek gaps and out-of-order chunks never become zero-filled audio. */
    private class ByteRanges {
        private val ranges = TreeMap<Long, Long>()
        fun add(start: Long, end: Long) {
            var left = start; var right = end
            ranges.floorEntry(left)?.takeIf { it.value >= left }?.let {
                left = it.key; right = maxOf(right, it.value); ranges.remove(it.key)
            }
            var next = ranges.ceilingEntry(left)
            while (next != null && next.key <= right) {
                right = maxOf(right, next.value); ranges.remove(next.key)
                next = ranges.ceilingEntry(left)
            }
            if (ranges.size >= 10_000) throw IOException("SABR range index exceeded its limit")
            ranges[left] = right
        }
        fun covers(start: Long, end: Long) = ranges.floorEntry(start)?.value?.let { it >= end } == true
    }

    /** A 16 MiB ring of disk blocks indexed by ABSOLUTE container byte offsets. */
    private class WindowStore(folder: File) {
        private class Block(val slot: Int, var pinned: Boolean) { val valid = TreeMap<Int, Int>() }
        private val blocks = LinkedHashMap<Long, Block>(256, 0.75f, true)
        private val free = java.util.ArrayDeque<Int>().apply { repeat(BLOCK_COUNT) { add(it) } }
        private val comparison = ByteArray(BLOCK_SIZE)
        private val file = run {
            if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Couldn't create the SABR session directory")
            RandomAccessFile(File(folder, "audio.window"), "rw").also {
                try { it.setLength(BLOCK_SIZE.toLong() * BLOCK_COUNT) }
                catch (e: Exception) { it.close(); throw e }
            }
        }

        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            val number = position / BLOCK_SIZE
            val within = (position % BLOCK_SIZE).toInt()
            val block = blocks[number] ?: return 0
            val span = block.valid.floorEntry(within)?.takeIf { it.value > within } ?: return 0
            val count = minOf(length, span.value - within)
            file.seek(block.slot.toLong() * BLOCK_SIZE + within)
            file.readFully(buffer, offset, count)
            return count
        }

        fun write(position: Long, data: ByteArray, initialization: Boolean) {
            var source = 0
            while (source < data.size) {
                val absolute = position + source
                val number = absolute / BLOCK_SIZE
                val within = (absolute % BLOCK_SIZE).toInt()
                val count = minOf(data.size - source, BLOCK_SIZE - within)
                val block = blocks[number] ?: run {
                    if (free.isEmpty()) {
                        val victim = blocks.entries.firstOrNull { !it.value.pinned }
                            ?: throw IOException("SABR initialization exceeds the bounded buffer")
                        blocks.remove(victim.key)
                        free.add(victim.value.slot)
                    }
                    Block(free.removeFirst(), initialization).also { blocks[number] = it }
                }
                // Replayed/overlapping ranges must be byte-identical. Never join
                // client representations or turn unwritten sparse holes into audio.
                for ((start, end) in block.valid) {
                    val left = maxOf(start, within); val right = minOf(end, within + count)
                    if (left < right) {
                        file.seek(block.slot.toLong() * BLOCK_SIZE + left)
                        file.readFully(comparison, 0, right - left)
                        for (i in 0 until right - left) if (comparison[i] != data[source + left - within + i])
                            throw IOException("SABR returned conflicting bytes for the same representation")
                    }
                }
                file.seek(block.slot.toLong() * BLOCK_SIZE + within)
                file.write(data, source, count)
                block.pinned = block.pinned || initialization
                var left = within; var right = within + count
                block.valid.floorEntry(left)?.takeIf { it.value >= left }?.let {
                    left = it.key; right = maxOf(right, it.value); block.valid.remove(it.key)
                }
                var next = block.valid.ceilingEntry(left)
                while (next != null && next.key <= right) {
                    right = maxOf(right, next.value); block.valid.remove(next.key)
                    next = block.valid.ceilingEntry(left)
                }
                block.valid[left] = right
                source += count
            }
        }
        fun close() = file.close()
        private companion object { const val BLOCK_SIZE = 64 * 1024; const val BLOCK_COUNT = 256 }
    }
}
