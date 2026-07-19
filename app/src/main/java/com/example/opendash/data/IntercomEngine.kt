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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var factory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val activeAudioTracks = ConcurrentHashMap<String, AudioTrack>()
    private var signalingCallback: ((targetPeerId: String?, type: String, payload: String) -> Unit)? = null
    private var selfId: String = ""

    @Volatile private var lastVoiceTime = 0L
    @Volatile private var isMusicPausedByIntercom = false
    private var voxJob: Job? = null

    private fun handleAudioData(source: String, buffer: java.nio.ByteBuffer?) {
        buffer ?: return
        val limit = buffer.limit()
        if (limit <= 0) return
        var sum = 0.0
        val count = limit / 2
        for (i in 0 until count) {
            val low = buffer.get(i * 2).toInt() and 0xFF
            val high = buffer.get(i * 2 + 1).toInt()
            val sample = ((high shl 8) or low).toShort()
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / count)
        if (rms > 1000.0) {
            // Update active speakers set
            val currentSpeakers = _state.value.activeSpeakers
            if (!currentSpeakers.contains(source) && source != "local") {
                _state.value = _state.value.copy(activeSpeakers = currentSpeakers + source)
            }

            // Trigger VOX music pause
            lastVoiceTime = System.currentTimeMillis()
            if (!isMusicPausedByIntercom) {
                isMusicPausedByIntercom = true
                scope.launch(Dispatchers.Main) {
                    com.example.opendash.media.MediaInfoProvider.currentInstance?.pause()
                }
            }
        } else {
            // Speaker became silent
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
                    launch(Dispatchers.Main) {
                        com.example.opendash.media.MediaInfoProvider.currentInstance?.play()
                    }
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
            scope.launch(Dispatchers.Main) {
                com.example.opendash.media.MediaInfoProvider.currentInstance?.play()
            }
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
                val adm = org.webrtc.audio.JavaAudioDeviceModule.builder(appContext!!)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()

                factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
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
        }
        if (appContext != null) init(appContext!!)
        val fac = factory ?: run {
            DebugLog.e(TAG, message = { "Cannot start intercom: WebRTC factory is null" })
            return
        }

        runCatching {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            localAudioSource = fac.createAudioSource(audioConstraints)
            localAudioTrack = fac.createAudioTrack("ARDAMSa0", localAudioSource).apply {
                setEnabled(!_state.value.isMuted)
                addSink { data, _, _, _, _, _ ->
                    handleAudioData("local", data)
                }
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
        return if (sdp.contains("useinbandfec=1")) {
            sdp.replace("useinbandfec=1", "useinbandfec=1;usedtx=1;maxaveragebitrate=12000")
        } else if (sdp.contains("opus/48000/2")) {
            sdp.replace("opus/48000/2", "opus/48000/2\r\na=fmtp:111 useinbandfec=1;usedtx=1;maxaveragebitrate=12000")
        } else sdp
    }

    private fun applyLowBitrateAudioParams(pc: PeerConnection) {
        runCatching {
            pc.senders.forEach { sender ->
                if (sender.track()?.kind() == "audio") {
                    val params = sender.parameters
                    params.encodings.forEach { encoding ->
                        encoding.maxBitrateBps = 12_000 // 12 kbps ultra-low network cap
                    }
                    sender.parameters = params
                }
            }
        }
    }

    fun handleIceCandidate(fromPeerId: String, sdpMid: String, sdpMLineIndex: Int, candidateStr: String) {
        val pc = peerConnections[fromPeerId] ?: return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
        pc.addIceCandidate(candidate)
    }

    fun removePeer(peerId: String) {
        activeAudioTracks.remove(peerId)
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
                audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
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
