package com.example.opendash.data

import android.content.Context
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.ExternalAudioProcessingFactory
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import com.example.opendash.media.MediaInfoProvider

/**
 * WebRTC P2P Audio Intercom Engine for Group Ride.
 *
 * Audio-only, low-bandwidth (Opus codec with DTX and software Echo Cancellation / Noise
 * Suppression). Connects riders directly peer-to-peer using public STUN servers for NAT
 * traversal.
 */
object IntercomEngine {
    private const val TAG = "IntercomEngine"
    private val STUN_SERVERS = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
    )

    data class State(
        val isIntercomActive: Boolean = false,
        val isMuted: Boolean = false,
        val isSpeakerMuted: Boolean = false,
        val activeSpeakers: Set<String> = emptySet(),
        val activePeers: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /**
     * Live microphone loudness, 0..1, for the UI meter. Kept out of [State] because it
     * changes every audio frame and would otherwise recompose every intercom consumer.
     */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel = _micLevel.asStateFlow()

    // Voice sits well inside this window; below the floor is helmet/wind noise, and the
    // ceiling is short of clipping so normal speech uses most of the bar.
    private const val MIC_FLOOR_DB = 45.0
    private const val MIC_CEIL_DB = 80.0
    private const val MIC_EMIT_INTERVAL_MS = 50L

    @Volatile private var smoothedMicLevel = 0f
    @Volatile private var lastMicEmitMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var factory: PeerConnectionFactory? = null
    /** RNNoise capture post-processor; lives as long as [factory] does. */
    private var rnnoise: RnnoiseProcessor? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val activeAudioTracks = ConcurrentHashMap<String, AudioTrack>()
    private var signalingCallback: ((targetPeerId: String?, type: String, payload: String) -> Unit)? = null
    private var selfId: String = ""

    @Volatile private var lastVoiceTime = 0L
    @Volatile private var isMusicPausedByIntercom = false
    private var voxJob: Job? = null

    private class VadState {
        var noiseFloor = 150.0
        var speechFrames = 0
        var silenceFrames = 0
        var isSpeaking = false
    }

    private val vadStates = ConcurrentHashMap<String, VadState>()

    private fun handleAudioData(source: String, buffer: java.nio.ByteBuffer?) {
        buffer ?: return
        val limit = buffer.limit()
        if (limit <= 0) return

        // Evaluate every 4th sample (8 bytes) to reduce CPU load by 75%
        val shortCount = limit / 2
        var sum = 0.0
        val step = 4
        var samplesEvaluated = 0
        for (i in 0 until shortCount step step) {
            val low = buffer.get(i * 2).toInt() and 0xFF
            val high = buffer.get(i * 2 + 1).toInt()
            val sample = ((high shl 8) or low).toShort()
            sum += sample * sample
            samplesEvaluated++
        }
        if (samplesEvaluated == 0) return
        processRms(source, Math.sqrt(sum / samplesEvaluated))
    }

    /**
     * Local mic level, straight off the capture device. A local [AudioTrack]'s sink is
     * never fed — capture goes through the audio device module, not the track — so the
     * mic meter and local VAD have to come from here.
     */
    private fun handleLocalSamples(data: ByteArray) {
        val shortCount = data.size / 2
        if (shortCount <= 0) return
        var sum = 0.0
        var samplesEvaluated = 0
        for (i in 0 until shortCount step 4) {
            val low = data[i * 2].toInt() and 0xFF
            val high = data[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            sum += sample.toDouble() * sample
            samplesEvaluated++
        }
        if (samplesEvaluated == 0) return
        processRms("local", Math.sqrt(sum / samplesEvaluated))
    }

    private fun processRms(source: String, rms: Double) {
        if (source == "local") updateMicLevel(rms)

        val state = vadStates.getOrPut(source) { VadState() }

        // Dynamic noise floor tracking: adapt to ambient/wind noise levels
        if (rms < state.noiseFloor) {
            state.noiseFloor = state.noiseFloor * 0.95 + rms * 0.05
        } else {
            state.noiseFloor = state.noiseFloor * 0.999 + rms * 0.001
        }
        state.noiseFloor = state.noiseFloor.coerceIn(50.0, 5000.0)

        // Hysteresis threshold: must exceed the moving noise floor significantly
        val isVoiceFrame = rms > (state.noiseFloor * 2.2) && rms > 300.0

        if (isVoiceFrame) {
            lastVoiceTime = System.currentTimeMillis() // Update last speaking time continuously
            state.silenceFrames = 0
            state.speechFrames++
            if (state.speechFrames >= 3 && !state.isSpeaking) { // 3 consecutive frames (~30ms)
                state.isSpeaking = true
                updateSpeakerState(source, true)
            }
        } else {
            state.speechFrames = 0
            state.silenceFrames++
            if (state.silenceFrames >= 20 && state.isSpeaking) { // 20 consecutive silence frames (~200ms)
                state.isSpeaking = false
                updateSpeakerState(source, false)
            }
        }
    }

    /**
     * Maps the raw RMS onto a 0..1 bar position. Log-scaled, because linear amplitude
     * makes normal speech look like a barely-moving nub.
     */
    private fun updateMicLevel(rms: Double) {
        if (_state.value.isMuted) {
            if (smoothedMicLevel != 0f) {
                smoothedMicLevel = 0f
                _micLevel.value = 0f
            }
            return
        }
        val db = 20.0 * Math.log10(rms.coerceAtLeast(1.0))
        val raw = ((db - MIC_FLOOR_DB) / (MIC_CEIL_DB - MIC_FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
        // Fast attack so speech onset registers instantly, slow release so the bar decays
        // smoothly instead of strobing between syllables.
        smoothedMicLevel = if (raw > smoothedMicLevel) raw else smoothedMicLevel * 0.82f + raw * 0.18f

        val now = System.currentTimeMillis()
        if (now - lastMicEmitMs >= MIC_EMIT_INTERVAL_MS) {
            lastMicEmitMs = now
            _micLevel.value = smoothedMicLevel
        }
    }

    private fun updateSpeakerState(source: String, isSpeaking: Boolean) {
        if (isSpeaking) {
            if (source != "local") {
                val currentSpeakers = _state.value.activeSpeakers
                if (!currentSpeakers.contains(source)) {
                    _state.value = _state.value.copy(activeSpeakers = currentSpeakers + source)
                }
            }
            if (!isMusicPausedByIntercom) {
                isMusicPausedByIntercom = true
                cancelMusicFade()   // speech mid-ramp → stop the ramp, restore the level
                scope.launch(Dispatchers.Main) {
                    com.example.opendash.media.MediaInfoProvider.currentInstance?.pause()
                }
            }
        } else {
            if (source != "local") {
                val currentSpeakers = _state.value.activeSpeakers
                if (currentSpeakers.contains(source)) {
                    _state.value = _state.value.copy(activeSpeakers = currentSpeakers - source)
                }
            }
        }
    }

    private fun startVoxMonitoring() {
        voxJob?.cancel()
        voxJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (isMusicPausedByIntercom && (now - lastVoiceTime) > 2500L) {
                    isMusicPausedByIntercom = false
                    resumeMusicWithFade()
                }
                delay(500L)
            }
        }
    }

    private fun stopVoxMonitoring() {
        voxJob?.cancel()
        voxJob = null
        if (isMusicPausedByIntercom) {
            isMusicPausedByIntercom = false
            resumeMusicWithFade()
        }
    }

    // ── Music fade-in after intercom speech ─────────────────────────────────────
    private var musicFadeJob: Job? = null
    /** Media-stream level a ramp is heading for; -1 when no ramp is in flight. */
    @Volatile private var musicFadeTarget = -1

    private fun cancelMusicFade() {
        musicFadeJob?.cancel(); musicFadeJob = null
        // Never leave the stream parked low from an interrupted ramp.
        if (musicFadeTarget >= 0) {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            runCatching {
                am?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, musicFadeTarget, 0)
            }
            musicFadeTarget = -1
        }
    }

    /**
     * Resume the rider's music after intercom speech, ramping STREAM_MUSIC from ~30%
     * back to where it was over ~1.5 s instead of slamming straight to full volume.
     * (Another app's internal volume can't be touched, so the stream level is the lever;
     * [cancelMusicFade] guarantees it's restored even if speech interrupts the ramp.)
     */
    private fun resumeMusicWithFade() {
        cancelMusicFade()
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val target = runCatching {
            am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        }.getOrNull() ?: -1
        if (am == null || target <= 1) {
            scope.launch(Dispatchers.Main) {
                com.example.opendash.media.MediaInfoProvider.currentInstance?.play()
            }
            return
        }
        musicFadeTarget = target
        musicFadeJob = scope.launch(Dispatchers.Main) {
            val start = (target * 3 / 10).coerceAtLeast(1)
            runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, start, 0) }
            com.example.opendash.media.MediaInfoProvider.currentInstance?.play()
            val stepMs = 1_500L / (target - start)
            for (v in (start + 1)..target) {
                delay(stepMs)
                runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
            }
            musicFadeTarget = -1
        }
    }

    fun init(context: Context) {
        val firstInit = appContext == null
        appContext = context.applicationContext

        runCatching {
            if (firstInit) {
                val options = PeerConnectionFactory.InitializationOptions.builder(appContext!!)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
            }

            if (factory == null) {
                val rootEglBase = EglBase.create()
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val adm = org.webrtc.audio.JavaAudioDeviceModule.builder(appContext!!)
                    .setAudioAttributes(audioAttributes)
                    .setUseHardwareAcousticEchoCanceler(true)
                    // Hardware NS off: RNNoise is the single suppressor — stacking it on
                    // the OEM DSP's NS causes musical-noise artifacts.
                    .setUseHardwareNoiseSuppressor(false)
                    .setSamplesReadyCallback { samples ->
                        samples?.data?.let { handleLocalSamples(it) }
                    }
                    .createAudioDeviceModule()

                // RNNoise runs as the APM's capture post-processor — after AEC/AGC, on
                // fullband 48 kHz float frames, before Opus encoding.
                rnnoise?.release()
                val rn = RnnoiseProcessor().also { rnnoise = it }
                val apmFactory = ExternalAudioProcessingFactory().also {
                    it.setCapturePostProcessing(rn)
                }

                factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
                    .setAudioProcessingFactory(apmFactory)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
                    .createPeerConnectionFactory()

                DebugLog.d(TAG) { "WebRTC PeerConnectionFactory initialized successfully with standard communication routing." }
            }
        }.onFailure {
            DebugLog.e(TAG, message = { "Failed to initialize WebRTC PeerConnectionFactory: ${it.message}" })
        }
    }

    private var callJob: Job? = null

    fun startIntercom(selfDeviceId: String, onSignal: (targetPeerId: String?, type: String, payload: String) -> Unit) {
        selfId = selfDeviceId
        signalingCallback = onSignal
        runCatching {
            peerConnections.values.forEach { pc ->
                runCatching { pc.close() }
                runCatching { pc.dispose() }
            }
            peerConnections.clear()
            activeAudioTracks.clear()
            factory?.dispose()
            factory = null
            rnnoise?.release()
            rnnoise = null
        }
        if (appContext != null) init(appContext!!)
        val fac = factory ?: run {
            DebugLog.e(TAG, message = { "Cannot start intercom: WebRTC factory is null" })
            return
        }

        runCatching {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                // WebRTC's spectral NS off — RNNoise (capture post-processor) is the
                // single noise suppressor; two suppressors stacked = artifacts.
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
            }
            localAudioSource = fac.createAudioSource(audioConstraints)
            // No sink here: local level comes from the ADM's samples-ready callback.
            localAudioTrack = fac.createAudioTrack("ARDAMSa0", localAudioSource).apply {
                setEnabled(!_state.value.isMuted)
            }
            setSpeakerphoneEnabled(true)
            lastVoiceTime = 0L
            isMusicPausedByIntercom = false
            startVoxMonitoring()
            _state.value = _state.value.copy(isIntercomActive = true)

            callJob?.cancel()
            callJob = scope.launch {
                com.example.opendash.media.CallInfoProvider.incomingCall.collect { call ->
                    val inCall = call != null
                    localAudioTrack?.setEnabled(!inCall && !_state.value.isMuted)
                    peerConnections.values.forEach { pc ->
                        pc.receivers.forEach { r -> r.track()?.setEnabled(!inCall && !_state.value.isSpeakerMuted) }
                    }
                }
            }

            DebugLog.d(TAG) { "Intercom started for selfId=$selfId" }
        }.onFailure {
            DebugLog.e(TAG, message = { "Error starting local audio track: ${it.message}" })
        }
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        localAudioTrack?.setEnabled(!newMuted)
        _state.value = _state.value.copy(isMuted = newMuted)
        if (newMuted) {
            smoothedMicLevel = 0f
            _micLevel.value = 0f
        }
    }

    fun toggleSpeakerMute() {
        val newSpeakerMuted = !_state.value.isSpeakerMuted
        _state.value = _state.value.copy(isSpeakerMuted = newSpeakerMuted)
        peerConnections.values.forEach { pc ->
            pc.receivers.forEach { receiver ->
                receiver.track()?.setEnabled(!newSpeakerMuted)
            }
        }
    }

    fun initiateCallTo(peerId: String) {
        if (peerId == selfId || peerConnections.containsKey(peerId)) return
        val pc = createPeerConnection(peerId) ?: return
        peerConnections[peerId] = pc
        _state.value = _state.value.copy(activePeers = peerConnections.keys.toSet())

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                val optSdp = SessionDescription(sdp.type, optimizeSdpForLowNetwork(sdp.description))
                pc.setLocalDescription(SimpleSdpObserver(), optSdp)
                applyLowBitrateAudioParams(pc)
                signalingCallback?.invoke(peerId, "offer", optSdp.description)
                DebugLog.d(TAG) { "Created optimized SDP offer for peerId=$peerId" }
            }
        }, mediaConstraints)
    }

    fun handleOffer(fromPeerId: String, sdpDescription: String) {
        if (fromPeerId == selfId) return
        var pc = peerConnections[fromPeerId]
        if (pc == null) {
            pc = createPeerConnection(fromPeerId) ?: return
            peerConnections[fromPeerId] = pc
            _state.value = _state.value.copy(activePeers = peerConnections.keys.toSet())
        }

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpDescription)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                val mediaConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp ?: return
                        val optSdp = SessionDescription(sdp.type, optimizeSdpForLowNetwork(sdp.description))
                        pc.setLocalDescription(SimpleSdpObserver(), optSdp)
                        applyLowBitrateAudioParams(pc)
                        signalingCallback?.invoke(fromPeerId, "answer", optSdp.description)
                        DebugLog.d(TAG) { "Created optimized SDP answer for peerId=$fromPeerId" }
                    }
                }, mediaConstraints)
            }
        }, remoteSdp)
    }

    fun handleAnswer(fromPeerId: String, sdpDescription: String) {
        val pc = peerConnections[fromPeerId] ?: return
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpDescription)
        pc.setRemoteDescription(SimpleSdpObserver(), remoteSdp)
        applyLowBitrateAudioParams(pc)
        DebugLog.d(TAG) { "Set remote SDP answer from peerId=$fromPeerId" }
    }

    private fun optimizeSdpForLowNetwork(sdp: String): String {
        val targetParams = "useinbandfec=1;usedtx=1;maxaveragebitrate=24000;stereo=0;sprop-stereo=0;ptime=20;maxptime=60"
        return if (sdp.contains("useinbandfec=1")) {
            sdp.replace("useinbandfec=1", targetParams)
        } else if (sdp.contains("opus/48000/2")) {
            sdp.replace("opus/48000/2", "opus/48000/2\r\na=fmtp:111 $targetParams")
        } else sdp
    }

    private fun applyLowBitrateAudioParams(pc: PeerConnection) {
        runCatching {
            pc.senders.forEach { sender ->
                if (sender.track()?.kind() == "audio") {
                    val params = sender.parameters
                    params.encodings.forEach { encoding ->
                        encoding.maxBitrateBps = 24_000 // crystal-clear mono voice at 24 kbps
                    }
                    sender.parameters = params
                }
            }
        }
    }

    private fun restartIceForPeer(peerId: String) {
        val pc = peerConnections[peerId] ?: return
        if (!_state.value.isIntercomActive) return
        DebugLog.d(TAG) { "Initiating ICE restart for peerId=$peerId" }
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                val optSdp = SessionDescription(sdp.type, optimizeSdpForLowNetwork(sdp.description))
                pc.setLocalDescription(SimpleSdpObserver(), optSdp)
                applyLowBitrateAudioParams(pc)
                signalingCallback?.invoke(peerId, "offer", optSdp.description)
                DebugLog.d(TAG) { "Created ICE restart SDP offer for peerId=$peerId" }
            }
        }, mediaConstraints)
    }

    fun handleIceCandidate(fromPeerId: String, sdpMid: String, sdpMLineIndex: Int, candidateStr: String) {
        val pc = peerConnections[fromPeerId] ?: return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
        pc.addIceCandidate(candidate)
    }

    fun removePeer(peerId: String) {
        activeAudioTracks.remove(peerId)
        vadStates.remove(peerId)
        peerConnections.remove(peerId)?.apply {
            close()
            playLeaveTone()
        }
        _state.value = _state.value.copy(
            activePeers = peerConnections.keys.toSet(),
            activeSpeakers = _state.value.activeSpeakers - peerId
        )
    }

    private fun playJoinTone() {
        runCatching {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 80)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            scope.launch {
                delay(250L)
                runCatching { tg.release() }
            }
        }
    }

    private fun playLeaveTone() {
        runCatching {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 80)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_PROMPT, 150)
            scope.launch {
                delay(250L)
                runCatching { tg.release() }
            }
        }
    }

    fun stopIntercom() {
        callJob?.cancel(); callJob = null
        stopVoxMonitoring()
        vadStates.clear()
        runCatching {
            peerConnections.values.forEach { pc ->
                runCatching { pc.close() }
                runCatching { pc.dispose() }
            }
            peerConnections.clear()
            activeAudioTracks.clear()
            localAudioTrack?.setEnabled(false)
            runCatching { localAudioTrack?.dispose() }
            runCatching { localAudioSource?.dispose() }
        }
        localAudioSource = null
        localAudioTrack = null
        setSpeakerphoneEnabled(false)
        smoothedMicLevel = 0f
        _micLevel.value = 0f
        _state.value = State()
        DebugLog.d(TAG) { "Intercom stopped and connections closed" }
    }

    fun updateIntercomVolume(volume: Float) {
        activeAudioTracks.values.forEach { track ->
            runCatching { track.setVolume(volume.toDouble()) }
        }
    }

    private fun setSpeakerphoneEnabled(on: Boolean) {
        val ctx = appContext ?: return
        runCatching {
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            if (on) {
                // MODE_IN_COMMUNICATION is what switches a Bluetooth headset into HFP/SCO so
                // its microphone works — A2DP (music) has no mic path. Required for a headset
                // intercom, so it stays even though it makes the phone act "in a call".
                audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    // Riders wear a headset and it carries the mic, so route to it first:
                    // Bluetooth (classic SCO, then LE Audio), then wired, and only fall back
                    // to the phone's own speaker+mic when nothing is connected. The old code
                    // hard-pinned the built-in speaker, which hijacked audio away from a
                    // paired helmet headset entirely.
                    val devices = audioManager.availableCommunicationDevices
                    fun pick(vararg types: Int) = devices.firstOrNull { it.type in types }
                    val target = pick(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                        ?: pick(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
                        ?: pick(
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                        )
                        ?: pick(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    if (target != null) {
                        audioManager.setCommunicationDevice(target)
                        DebugLog.d(TAG) { "Intercom audio routed to ${target.type} (${target.productName})" }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
                audioManager.mode = android.media.AudioManager.MODE_NORMAL
            }
        }
    }

    private fun createPeerConnection(peerId: String): PeerConnection? {
        val fac = factory ?: return null
        val rtcConfig = PeerConnection.RTCConfiguration(STUN_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return fac.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                signalingCallback?.invoke(peerId, "ice", "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}")
            }

            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.audioTracks?.firstOrNull() ?: return
                track.setEnabled(!_state.value.isSpeakerMuted)
                val vol = NavSettings.intercomVolume.value.toDouble()
                track.setVolume(vol)
                activeAudioTracks[peerId] = track
                track.addSink { data, _, _, _, _, _ ->
                    handleAudioData(peerId, data)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                DebugLog.d(TAG) { "ICE connection state for $peerId: $newState" }
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    playJoinTone()
                } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED || newState == PeerConnection.IceConnectionState.FAILED) {
                    playLeaveTone()
                    if (newState == PeerConnection.IceConnectionState.FAILED) {
                        restartIceForPeer(peerId)
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() as? AudioTrack ?: return
                track.setEnabled(!_state.value.isSpeakerMuted)
                val vol = NavSettings.intercomVolume.value.toDouble()
                track.setVolume(vol)
                activeAudioTracks[peerId] = track
                track.addSink { data, _, _, _, _, _ ->
                    handleAudioData(peerId, data)
                }
            }
        })?.apply {
            localAudioTrack?.let { addTrack(it, listOf("ARDAMS")) }
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(reason: String?) {
            DebugLog.e(TAG, message = { "SDP Create Failure: $reason" })
        }
        override fun onSetFailure(reason: String?) {
            DebugLog.e(TAG, message = { "SDP Set Failure: $reason" })
        }
    }
}
