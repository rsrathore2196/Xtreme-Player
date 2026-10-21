package com.example.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs

/** One renderer/sink: verified native PCM on supported USB hardware, otherwise Media3's shared sink.
 * All configure/write/drain calls run on ExoPlayer's playback thread. A crossfade tail
 * reuses the processing path without native output or signal-path publication.
 * Native integer PCM never goes through DefaultAudioSink's 24/32 -> float/16-bit converters.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BitPerfectAudioSink private constructor(
    private val context: Context, private var shared: AudioSink,
    private val observation: TrackObservation,
    private val allowDirect: () -> Boolean,
    private val outputPrecision: () -> OutputPrecision,
    private val floatRouteOverride: (() -> Boolean)?,
    private val sharedFactory: ((Boolean, Int) -> AudioSink)? = null
) : AudioSink {
    constructor(context: Context, reportSignalPath: Boolean = true, allowDirect: () -> Boolean = { true },
        outputPrecision: () -> OutputPrecision = { OutputPrecision.PCM_16 }) :
        this(context.applicationContext, TrackObservation(reportSignalPath), allowDirect, outputPrecision)
    internal constructor(context: Context, shared: AudioSink,
        outputPrecision: () -> OutputPrecision = { OutputPrecision.PCM_16 },
        floatRouteSupported: (() -> Boolean)? = null, sharedFactory: ((Boolean, Int) -> AudioSink)? = null) :
        this(context.applicationContext, shared, TrackObservation(false), { false }, outputPrecision, floatRouteSupported, sharedFactory)
    private constructor(context: Context, observation: TrackObservation, allowDirect: () -> Boolean,
        outputPrecision: () -> OutputPrecision) : this(context,
        sharedSink(context, observation, false), observation, allowDirect, outputPrecision, null,
        { skip, channels -> sharedSink(context, observation, skip, channels) })

    companion object {
        internal const val MIN_SILENCE_US = 1_000_000L
        internal fun silenceProcessor(channels: Int = 2) = androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor(
            // Media3 1.5.1 sizes its silence byte buffer from a frame count.
            // Compensate for bytes/frame to retain the intended ONE second on
            // mono/stereo/multichannel, rather than shortening musical pauses.
            MIN_SILENCE_US * channels * 2,
            androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
            androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
            androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL)
        private fun sharedSink(context: Context, observation: TrackObservation, skip: Boolean, channels: Int = 2): AudioSink =
            DefaultAudioSink.Builder(context)
                // Media3 omits silence skipping on float output. Use its PCM16
                // processing path while requested. The explicit PCM format passed
                // to configure selects PCM16/float independently of the decoder.
                .setEnableFloatOutput(!skip).setEnableAudioTrackPlaybackParams(true)
                .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(emptyArray<AudioProcessor>(),
                    silenceProcessor(channels), androidx.media3.common.audio.SonicAudioProcessor()))
                .setAudioTrackProvider { config, attributes, sessionId ->
                    DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId).also {
                        observation.attach(it, false, null)
                    }
                }.build()
    }

    private fun publishSignal(block: (SignalPathState) -> SignalPathState) {
        if (observation.reportSignalPath) AudioSignalPath.update(block)
    }

    @Volatile private var directUnavailable = false
    init { observation.directLost = { directUnavailable = true } }
    private val processor = PsychoacousticAudioProcessor()
    private var format = Format.Builder().build()
    private var sourceEncoding = C.ENCODING_INVALID
    private var failedDirectFormat: String? = null

    fun sourceFormat(source: Format) {
        sourceEncoding = source.pcmEncoding.takeIf { source.sampleMimeType in setOf(MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW) } ?: C.ENCODING_INVALID
    }
    fun preferredPcmEncoding(source: Format): Int? {
        sourceFormat(source)
        return if (outputPrecision() == OutputPrecision.PCM_16 && source.pcmEncoding == C.ENCODING_PCM_16BIT &&
            allowDirect() && Build.VERSION.SDK_INT >= 34 && volume == 1f && parameters == PlaybackParameters.DEFAULT &&
            !skipSilence && !PsychoacousticAudioProcessor.spatialEnabled)
            NativeUsb.preferredEncoding(context, source, attributes, preferred) else null
    }
    private var attributes = AudioAttributes.DEFAULT
    private var preferred: AudioDeviceInfo? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var native: DirectLease? = null
    private var listener: AudioSink.Listener? = null
    private var playing = false
    private var volume = 1f
    private var parameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var sharedSkipSilence = false
    private var sharedProcessingChannels = 2
    private var sharedEncoding = C.ENCODING_PCM_16BIT
    private var sharedConfigured = false
    private var initialFloatSupport = false
    private var preferredFloatSupport: Boolean? = null
    private var failedFloatRoute: Int? = null
    private var lastPrecision = outputPrecision()
    private var sharedDraining = false
    private var previousPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var configuredBufferSize = 0
    private var configuredChannels: IntArray? = null
    private var playerId: androidx.media3.exoplayer.analytics.PlayerId? = null
    private var clock: androidx.media3.common.util.Clock? = null
    private var auxEffect = androidx.media3.common.AuxEffectInfo(0, 0f)
    private var streamOffsetUs = 0L
    private var tunneling = false
    private var offloadMode = AudioSink.OFFLOAD_MODE_DISABLED
    private var startUs = C.TIME_UNSET
    private var bytesWritten = 0L
    private var headWrap = 0L
    private var lastHead = 0L
    private var lastWriteMs = 0L
    private var discontinuity = false
    private var draining = false
    private var pending: ByteBuffer? = null
    private var pendingInput: ByteBuffer? = null
    // Retain the exact processed/converted buffer through partial writes and toggles.
    private var pendingEncoding = C.ENCODING_INVALID
    private val outputConverter = OutputPcmConverter()
    private val fallbackConverter = OutputPcmConverter()
    private var conversionBuffer = ByteBuffer.allocateDirect(0)
    private var nativeInput: ByteBuffer? = null
    private data class PendingConfiguration(val format: Format, val bufferSize: Int, val channels: IntArray?)
    private var pendingConfiguration: PendingConfiguration? = null

    // Decode compressed formats instead of offloading them around the custom DSP.
    override fun getFormatSupport(format: Format): Int = if (skipSilence && format.sampleMimeType == MimeTypes.AUDIO_RAW)
        shared.getFormatSupport(format) else if (format.sampleMimeType == MimeTypes.AUDIO_RAW &&
        format.pcmEncoding in intArrayOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else if (format.sampleMimeType == MimeTypes.AUDIO_RAW) shared.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun setListener(listener: AudioSink.Listener) { this.listener = listener; shared.setListener(listener) }
    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) {
        this.playerId = playerId; shared.setPlayerId(playerId)
    }
    override fun setClock(clock: androidx.media3.common.util.Clock) { this.clock = clock; shared.setClock(clock) }
    override fun getFormatOffloadSupport(format: Format) = shared.getFormatOffloadSupport(format)
    override fun getAudioAttributes() = attributes
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
        auxEffect = auxEffectInfo; shared.setAuxEffectInfo(auxEffectInfo)
    }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs; shared.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }
    override fun enableTunnelingV21() { tunneling = true; shared.enableTunnelingV21() }
    override fun disableTunneling() { tunneling = false; shared.disableTunneling() }
    override fun setOffloadMode(offloadMode: Int) { this.offloadMode = offloadMode; shared.setOffloadMode(offloadMode) }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        shared.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        attributes = audioAttributes; shared.setAudioAttributes(audioAttributes)
    }
    override fun setAudioSessionId(audioSessionId: Int) { sessionId = audioSessionId; shared.setAudioSessionId(audioSessionId) }
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferred = audioDeviceInfo
        preferredFloatSupport = audioDeviceInfo?.let { OutputPrecisionPolicy.supportsFloat(it,
            format.sampleRate.takeIf { rate -> rate > 0 } ?: 48000,
            format.channelCount.takeIf { channels -> channels > 0 } ?: 2, attributes.audioAttributesV21.audioAttributes) }
        native?.track?.setPreferredDevice(audioDeviceInfo)
        shared.setPreferredDevice(audioDeviceInfo)
        observation.refresh()
    }
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // Drain a previous native item before replacing it at a normal queue boundary.
        // Media3 normally calls configure after its renderer has drained the old format.
        if (hasPendingData()) {
            pendingConfiguration = PendingConfiguration(inputFormat, specifiedBufferSize, outputChannels?.clone())
            return
        }
        closeNative()
        directUnavailable = false
        format = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredChannels = outputChannels?.clone()
        resetCounters()
        initialFloatSupport = resolveInitialFloatSupport()
        val identityChannels = outputChannels == null || outputChannels.size == inputFormat.channelCount && outputChannels.withIndex().all { it.index == it.value }
        // Float is exact for a known 24-bit FLAC/ALAC source. Repack its integer
        // samples without DSP, quantization noise, rate conversion or attenuation.
        val repack24 = inputFormat.pcmEncoding == C.ENCODING_PCM_FLOAT && sourceEncoding == C.ENCODING_PCM_24BIT
        val nativeFormat = if (repack24) inputFormat.buildUpon().setPcmEncoding(C.ENCODING_PCM_24BIT).build() else inputFormat
        val nativeKey = "${inputFormat.pcmEncoding}|${inputFormat.sampleRate}|${inputFormat.channelCount}|${preferred?.id}"
        native = if (desiredEncoding() == C.ENCODING_PCM_16BIT && nativeFormat.pcmEncoding == C.ENCODING_PCM_16BIT &&
            allowDirect() && Build.VERSION.SDK_INT >= 34 && failedDirectFormat != nativeKey && identityChannels &&
            (sourceEncoding == C.ENCODING_INVALID || sourceEncoding == nativeFormat.pcmEncoding) && parameters == PlaybackParameters.DEFAULT &&
            volume == 1f && !skipSilence && !PsychoacousticAudioProcessor.spatialEnabled &&
            inputFormat.encoderDelay == 0 && inputFormat.encoderPadding == 0)
            NativeUsb.open(context, nativeFormat, attributes, preferred, sessionId, specifiedBufferSize) else null
        native?.let { lease ->
            shared.flush()
            observation.attach(lease.track, true, lease)
            publishSignal { it.copy(inputRateHz = inputFormat.sampleRate, outputRateHz = lease.track.sampleRate,
                dspActive = false, eqActive = false, spatialActive = false, skipSilenceActive = false,
                directApproved = true, mode = "Direct") }
            listener?.onAudioTrackInitialized(AudioSink.AudioTrackConfig(lease.encoding,
                inputFormat.sampleRate, lease.track.channelConfiguration, false, false, lease.bufferBytes))
            if (playing) lease.track.play()
            return
        }
        configureShared(inputFormat, specifiedBufferSize, outputChannels)
    }
    private fun configureShared(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?, resetProcessor: Boolean = true) {
        val encoding = desiredEncoding()
        if (sharedFactory != null && (sharedDraining || sharedSkipSilence != skipSilence ||
            sharedConfigured && sharedEncoding != encoding ||
            skipSilence && sharedProcessingChannels != inputFormat.channelCount)) replaceSharedSink()
        sharedEncoding = encoding
        sharedConfigured = true
        val spatial = PsychoacousticAudioProcessor.spatialEnabled && inputFormat.channelCount == 2
        publishSignal { it.copy(inputRateHz = inputFormat.sampleRate, bitPerfect = false,
            directApproved = false, mode = "Shared", eqActive = AudioEffectsManager.effectsState.value.isEnabled,
            spatialActive = spatial, skipSilenceActive = skipSilence,
            dspActive = AudioEffectsManager.effectsState.value.isEnabled || spatial || skipSilence) }
        if (resetProcessor) { processor.configure(AudioProcessor.AudioFormat(inputFormat)); processor.flush() }
        shared.configure(inputFormat.buildUpon().setPcmEncoding(sharedEncoding).build(), specifiedBufferSize, outputChannels)
        shared.setPlaybackParameters(parameters)
        shared.setSkipSilenceEnabled(skipSilence)
        if (playing) shared.play()
    }

    private fun resolveInitialFloatSupport(): Boolean {
        floatRouteOverride?.let { return it() }
        preferred?.let { return preferredFloatSupport == true }
        observation.routeFloatSupported?.let { return it }
        // Only at configuration, on Media3's audio thread; never a per-buffer
        // Binder query or a synchronous settings read on the application looper.
        return runCatching {
            val manager = context.getSystemService(AudioManager::class.java) ?: return false
            val device = if (Build.VERSION.SDK_INT >= 33)
                manager.getAudioDevicesForAttributes(attributes.audioAttributesV21.audioAttributes).firstOrNull()
            else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).let { devices ->
                devices.firstOrNull { it.type in intArrayOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET) }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            }
            device != null && OutputPrecisionPolicy.supportsFloat(device, format.sampleRate, format.channelCount,
                attributes.audioAttributesV21.audioAttributes)
        }.getOrDefault(false)
    }

    private fun desiredEncoding(): Int {
        val precision = outputPrecision()
        if (lastPrecision != precision) { failedFloatRoute = null; lastPrecision = precision }
        val supported = floatRouteOverride?.invoke() ?: observation.routeFloatSupported
            ?: preferredFloatSupport ?: initialFloatSupport
        return OutputPrecisionPolicy.encoding(precision,
            supported && failedFloatRoute != (observation.routeId ?: preferred?.id ?: -1), skipSilence)
    }

    /** Only the audio thread swaps sinks, after draining accepted PCM. The MediaItem,
     * decoder, source, queue and position are retained; signed streams are not resolved again.
     */
    private fun replaceSharedSink() {
        val factory = sharedFactory ?: return
        val replacement = factory(skipSilence, format.channelCount)
        shared.release()
        shared = replacement
        sharedSkipSilence = skipSilence
        sharedProcessingChannels = format.channelCount
        sharedDraining = false
        listener?.let(shared::setListener)
        playerId?.let(shared::setPlayerId)
        clock?.let(shared::setClock)
        shared.setAudioAttributes(attributes)
        shared.setAudioSessionId(sessionId)
        shared.setPreferredDevice(preferred)
        shared.setAuxEffectInfo(auxEffect)
        shared.setOutputStreamOffsetUs(streamOffsetUs)
        shared.setOffloadMode(offloadMode)
        if (tunneling) shared.enableTunnelingV21() else shared.disableTunneling()
        shared.setVolume(volume)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        pendingConfiguration?.let { configuration ->
            if (hasPendingData()) return false
            pendingConfiguration = null
            configure(configuration.format, configuration.bufferSize, configuration.channels)
        }
        // Route loss changes only the sink. Drain the already-written native audio,
        // then continue at the next unconsumed PCM buffer without a queue/source reset.
        if (native != null && (directUnavailable || PsychoacousticAudioProcessor.spatialEnabled ||
            desiredEncoding() != native?.encoding) && nativeInput == null) {
            if (hasPendingData()) return false
            closeNative(); directUnavailable = false
            configureShared(format, 0, null)
        }
        val lease = native
        if (lease == null) {
            if (sharedFactory != null && (sharedDraining || sharedSkipSilence != skipSilence ||
                sharedEncoding != desiredEncoding()) && pending == null) {
                if (!sharedDraining) {
                    previousPositionUs = shared.getCurrentPositionUs(false)
                    sharedDraining = true
                }
                shared.playToEndOfStream()
                if (shared.hasPendingData()) return false
                shared.getCurrentPositionUs(true).takeUnless { it == AudioSink.CURRENT_POSITION_NOT_SET }
                    ?.let { previousPositionUs = it }
                configureShared(format.buildUpon().setEncoderDelay(0).build(), configuredBufferSize,
                    configuredChannels, resetProcessor = false)
            }
            publishProcessingState()
            // Do not process a partially written buffer twice. DefaultAudioSink may
            // consume only part of this output and call back with the SAME input.
            if (pending != null) {
                check(pendingInput === buffer)
            } else {
                val processed = if (processor.isProcessing && buffer.hasRemaining()) {
                    processor.queueInput(buffer)
                    processor.output
                } else buffer
                pending = outputConverter.convert(processed, format.pcmEncoding, sharedEncoding)
                pendingEncoding = sharedEncoding
                pendingInput = buffer
            }
            val done = try { shared.handleBuffer(pending!!, presentationTimeUs, encodedAccessUnitCount) }
            catch (failure: AudioSink.InitializationException) {
                // A route can advertise float yet reject AudioTrack creation.
                // No PCM has reached it: retry these SAME samples as PCM16,
                // without resolving another URL or replacing the MediaItem.
                if (sharedEncoding != C.ENCODING_PCM_FLOAT || sharedFactory == null || shared.hasPendingData()) throw failure
                failedFloatRoute = observation.routeId ?: preferred?.id ?: -1
                pending = fallbackConverter.convert(pending!!, pendingEncoding, C.ENCODING_PCM_16BIT)
                pendingEncoding = C.ENCODING_PCM_16BIT
                configureShared(format, configuredBufferSize, configuredChannels, resetProcessor = false)
                shared.handleBuffer(pending!!, presentationTimeUs, encodedAccessUnitCount)
            }
            observation.health()
            if (done) { pending = null; pendingInput = null }
            return done
        }
        val frameSize = Util.getPcmFrameSize(lease.encoding, format.channelCount)
        if (nativeInput == null) {
            nativeInput = buffer
            if (startUs == C.TIME_UNSET) startUs = presentationTimeUs.coerceAtLeast(0L)
            val expectedUs = startUs + bytesWritten / frameSize * 1_000_000L / format.sampleRate
            if (discontinuity || abs(presentationTimeUs - expectedUs) > 200_000L) {
                startUs += presentationTimeUs - expectedUs
                discontinuity = false
                listener?.onPositionDiscontinuity()
            }
        } else check(nativeInput === buffer)
        val output = if (format.pcmEncoding == C.ENCODING_PCM_FLOAT && lease.encoding == C.ENCODING_PCM_24BIT) {
            if (pending == null) {
                val bytes = buffer.remaining() / 4 * 3
                if (conversionBuffer.capacity() < bytes) conversionBuffer = ByteBuffer.allocateDirect(bytes)
                conversionBuffer.clear(); conversionBuffer.limit(bytes)
                try { BitPerfectPcm.repack24(buffer, conversionBuffer) }
                catch (_: IllegalArgumentException) {
                    failedDirectFormat = "${format.pcmEncoding}|${format.sampleRate}|${format.channelCount}|${preferred?.id}"
                    throw AudioSink.WriteException(AudioTrack.ERROR_BAD_VALUE, format, true)
                }
                conversionBuffer.flip(); pending = conversionBuffer
            }
            pending!!
        } else buffer
        val written = try { lease.track.write(output, output.remaining(), AudioTrack.WRITE_NON_BLOCKING) }
            catch (_: RuntimeException) { AudioTrack.ERROR_DEAD_OBJECT }
        if (written < 0) {
            failedDirectFormat = "${format.pcmEncoding}|${format.sampleRate}|${format.channelCount}|${preferred?.id}"
            throw AudioSink.WriteException(written, format, true)
        }
        if (written > 0) {
            bytesWritten += written
            lastWriteMs = SystemClock.elapsedRealtime()
        } else if (playing && lastWriteMs > 0 && SystemClock.elapsedRealtime() - lastWriteMs > 4_000L) {
            throw AudioSink.WriteException(AudioTrack.ERROR_DEAD_OBJECT, format, true)
        }
        observation.health()
        val complete = !output.hasRemaining()
        if (complete) { pending = null; nativeInput = null }
        return complete
    }
    private fun playedFrames(): Long {
        val head = native?.track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: return 0
        if (head < lastHead) headWrap += 1L shl 32
        lastHead = head
        return headWrap + head
    }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = if (native == null) shared.getCurrentPositionUs(sourceEnded)
        .let { if (it == AudioSink.CURRENT_POSITION_NOT_SET) previousPositionUs else it }
        else if (startUs == C.TIME_UNSET) AudioSink.CURRENT_POSITION_NOT_SET
        else startUs + minOf(playedFrames(), writtenFrames()) * 1_000_000L / format.sampleRate
    private fun writtenFrames() = bytesWritten / Util.getPcmFrameSize(native?.encoding ?: format.pcmEncoding, format.channelCount)
    override fun hasPendingData() = if (native == null) shared.hasPendingData() else playedFrames() < writtenFrames()
    override fun isEnded() = if (native == null) shared.isEnded() else draining && !hasPendingData()
    override fun playToEndOfStream() { if (native == null) shared.playToEndOfStream() else draining = true }
    override fun handleDiscontinuity() { if (native == null) shared.handleDiscontinuity() else discontinuity = true }
    override fun play() { playing = true; lastWriteMs = 0; native?.track?.play() ?: shared.play() }
    override fun pause() { playing = false; native?.track?.pause() ?: shared.pause() }
    override fun setVolume(volume: Float) {
        this.volume = volume; native?.track?.setVolume(volume); shared.setVolume(volume)
        observation.unity = volume == 1f && parameters == PlaybackParameters.DEFAULT
        observation.refresh()
        publishSignal { it.copy(appVolume = volume) }
    }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        parameters = playbackParameters
        publishSignal { it.copy(appResampling = playbackParameters != PlaybackParameters.DEFAULT,
            playbackSpeed = playbackParameters.speed, pitch = playbackParameters.pitch) }
        native?.track?.let { track ->
            track.playbackParams = android.media.PlaybackParams().allowDefaults()
                .setSpeed(playbackParameters.speed).setPitch(playbackParameters.pitch)
        } ?: shared.setPlaybackParameters(playbackParameters)
        observation.unity = volume == 1f && parameters == PlaybackParameters.DEFAULT
        observation.refresh()
    }
    override fun getPlaybackParameters() = if (native != null) parameters else shared.getPlaybackParameters()
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
        if (skipSilenceEnabled && native != null) directUnavailable = true
        if (sharedFactory == null || sharedSkipSilence == skipSilenceEnabled && !sharedDraining)
            shared.setSkipSilenceEnabled(skipSilenceEnabled)
    }
    override fun getSkipSilenceEnabled() = if (native != null) false else shared.getSkipSilenceEnabled()
    override fun flush() {
        native?.track?.let { it.pause(); it.flush() }
        pendingConfiguration?.let {
            pendingConfiguration = null
            closeNative()
            configure(it.format, it.bufferSize, it.channels)
        }
        resetCounters(); processor.flush(); shared.flush()
        if (playing) native?.track?.play()
    }
    private fun resetCounters() {
        startUs = C.TIME_UNSET; bytesWritten = 0; headWrap = 0; lastHead = 0
        lastWriteMs = 0; draining = false; discontinuity = false; pending = null; pendingInput = null; nativeInput = null
        previousPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
    }
    private var lastProcessingState = -1
    private fun publishProcessingState() {
        val eq = PsychoacousticAudioProcessor.settings.get().isEnabled
        val spatial = PsychoacousticAudioProcessor.spatialEnabled && format.channelCount == 2
        val key = (if (eq) 1 else 0) or (if (spatial) 2 else 0) or (if (sharedSkipSilence) 4 else 0)
        if (lastProcessingState == key) return
        lastProcessingState = key
        publishSignal { it.copy(eqActive = eq, spatialActive = spatial, skipSilenceActive = sharedSkipSilence,
            dspActive = eq || spatial || sharedSkipSilence) }
    }
    private fun closeNative() {
        native?.let {
            observation.detach()
            it.track.pause(); it.track.flush(); it.track.release()
            runCatching { it.clear() }
            listener?.onAudioTrackReleased(AudioSink.AudioTrackConfig(it.encoding,
                format.sampleRate, Util.getAudioTrackChannelConfig(format.channelCount), false, false, it.bufferBytes))
        }
        native = null
    }
    override fun reset() { pendingConfiguration = null; closeNative(); observation.detach(); processor.reset(); resetCounters(); shared.reset() }
    override fun release() { pendingConfiguration = null; closeNative(); observation.detach(); processor.reset(); shared.release() }

    private data class DirectLease(val track: AudioTrack, val bufferBytes: Int, val encoding: Int,
        val clear: () -> Unit, val approved: () -> Boolean)

    /** API-34 references are isolated so Android 7–13 always use the shared sink. */
    @RequiresApi(34)
    private object NativeUsb {
        fun preferredEncoding(context: Context, source: Format, attributes: AudioAttributes, preferred: AudioDeviceInfo?): Int? {
            if (source.pcmEncoding !in intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) return null
            val manager = context.getSystemService(AudioManager::class.java) ?: return null
            return runCatching {
                val attrs = attributes.audioAttributesV21.audioAttributes
                val device = preferred ?: manager.getAudioDevicesForAttributes(attrs).firstOrNull() ?: return null
                if (device.type !in intArrayOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY)) return null
                manager.getSupportedMixerAttributes(device).firstOrNull {
                    it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                        it.format.encoding == source.pcmEncoding && it.format.sampleRate == source.sampleRate &&
                        it.format.channelMask == Util.getAudioTrackChannelConfig(source.channelCount)
                }?.format?.encoding?.let { if (it == C.ENCODING_PCM_24BIT) C.ENCODING_PCM_FLOAT else it }
            }.getOrNull()
        }
        fun open(context: Context, input: Format, attributes: AudioAttributes, preferred: AudioDeviceInfo?,
            session: Int, requestedBuffer: Int): DirectLease? {
            if (input.sampleMimeType != MimeTypes.AUDIO_RAW || input.sampleRate <= 0 ||
                input.pcmEncoding !in intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) return null
            val manager = context.getSystemService(AudioManager::class.java) ?: return null
            val attrs = attributes.audioAttributesV21.audioAttributes
            val route = preferred ?: runCatching { manager.getAudioDevicesForAttributes(attrs).firstOrNull() }.getOrNull()
            if (route == null || route.type !in intArrayOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY)) return null
            val channelMask = Util.getAudioTrackChannelConfig(input.channelCount)
            val desired = AudioFormat.Builder().setEncoding(input.pcmEncoding).setSampleRate(input.sampleRate)
                .setChannelMask(channelMask).build()
            var selected = false
            var track: AudioTrack? = null
            try {
                val profiles = manager.getDirectProfilesForAttributes(attrs)
                // Direct profiles can describe offload/passthrough instead of USB PCM.
                // An exact BIT_PERFECT mixer profile and accepted preference are authoritative.
                val directProfile = profiles.any { it.format == desired.encoding &&
                    desired.sampleRate in it.sampleRates && desired.channelMask in it.channelMasks }
                val mixer = manager.getSupportedMixerAttributes(route).firstOrNull {
                    it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && it.format == desired
                } ?: return null
                if (!manager.setPreferredMixerAttributes(attrs, route, mixer)) return null
                selected = true
                if (!directProfile) android.util.Log.d("AudioSignalPath", "Using the device's explicit bit-perfect USB mixer profile")
                val min = AudioTrack.getMinBufferSize(desired.sampleRate, channelMask, desired.encoding)
                if (min <= 0) { manager.clearPreferredMixerAttributes(attrs, route); return null }
                val frameSize = Util.getPcmFrameSize(input.pcmEncoding, input.channelCount)
                val bytes = maxOf(min * 2, requestedBuffer, input.sampleRate / 4 * frameSize)
                    .coerceAtMost(4 * 1024 * 1024).let { (it + frameSize - 1) / frameSize * frameSize }
                track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(desired)
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes)
                    .setSessionId(session.takeIf { it > 0 } ?: AudioManager.AUDIO_SESSION_ID_GENERATE).build()
                if (track.state != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(route)) {
                    track.release(); manager.clearPreferredMixerAttributes(attrs, route); return null
                }
                return DirectLease(track, bytes, input.pcmEncoding, { manager.clearPreferredMixerAttributes(attrs, route) }, {
                    track.routedDevice?.id == route.id && manager.getPreferredMixerAttributes(attrs, route) == mixer
                })
            } catch (_: RuntimeException) {
                track?.release()
                if (selected) runCatching { manager.clearPreferredMixerAttributes(attrs, route) }
                return null
            }
        }
    }

    private class TrackObservation(val reportSignalPath: Boolean) {
        private fun publishSignal(block: (SignalPathState) -> SignalPathState) {
            if (reportSignalPath) AudioSignalPath.update(block)
        }
        private var track: AudioTrack? = null
        private var direct = false
        private var lease: DirectLease? = null
        @Volatile var unity = true
        var directLost: () -> Unit = {}
        private var lastHealthMs = 0L
        private var underruns = 0
        @Volatile var routeFloatSupported: Boolean? = null
            private set
        @Volatile var routeId: Int? = null
            private set
        private var probedRate = 0
        private var probedChannels = 0
        private val routeListener = AudioRouting.OnRoutingChangedListener { refresh() }
        fun attach(value: AudioTrack, direct: Boolean, lease: DirectLease?) {
            detach(); track = value; this.direct = direct; this.lease = lease; underruns = 0
            value.addOnRoutingChangedListener(routeListener, Handler(Looper.myLooper() ?: Looper.getMainLooper()))
            refresh()
        }
        fun refresh() {
            val audio = track ?: return
            runCatching {
                val route = audio.routedDevice
                if (route != null && (routeId != route.id || probedRate != audio.sampleRate || probedChannels != audio.channelCount)) {
                    routeFloatSupported = OutputPrecisionPolicy.supportsFloat(route, audio.sampleRate, audio.channelCount, audio.audioAttributes)
                    probedRate = audio.sampleRate; probedChannels = audio.channelCount
                    routeId = route.id
                }
                val usb = route?.type in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
                val approved = direct && usb && lease?.approved?.invoke() == true
                val pure = approved && unity
                if (direct && route != null && !approved) directLost()
                publishSignal { it.copy(outputDevice = route?.productName?.toString() ?: "System output",
                    outputType = when (route?.type) {
                        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headphones"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Device Speaker"
                        else -> "System output"
                    }, outputRateHz = audio.sampleRate, bitPerfect = pure, directApproved = approved,
                    outputEncoding = audio.audioFormat, outputChannels = audio.channelCount,
                    outputRouteVerified = route != null,
                    mode = if (approved) "Direct" else if (Build.VERSION.SDK_INT >= 29 && audio.isOffloadedPlayback) "Offload" else "Shared",
                    eqActive = !direct && AudioEffectsManager.effectsState.value.isEnabled,
                    spatialActive = !direct && audio.channelCount == 2 && PsychoacousticAudioProcessor.spatialEnabled,
                    skipSilenceActive = !direct && it.skipSilenceActive,
                    dspActive = !direct && (AudioEffectsManager.effectsState.value.isEnabled ||
                        audio.channelCount == 2 && PsychoacousticAudioProcessor.spatialEnabled || it.skipSilenceActive)) }
            }
        }
        fun health() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastHealthMs < 1_000L) return
            lastHealthMs = now
            runCatching { val count = track?.underrunCount ?: 0
                if (count != underruns) { underruns = count; publishSignal { it.copy(glitches = count) } }
            }
        }
        fun detach() {
            track?.let { runCatching { it.removeOnRoutingChangedListener(routeListener) } }
            track = null; lease = null; direct = false
            publishSignal { it.copy(outputEncoding = C.ENCODING_INVALID, outputChannels = 0,
                outputRouteVerified = false, outputRateHz = 0, directApproved = false, bitPerfect = false) }
        }
    }
}
