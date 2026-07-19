package com.example.opendash.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.DashWallpaperFit
import com.example.opendash.data.DashWallpaperKind
import com.example.opendash.data.DashWallpaperInfo
import com.example.opendash.data.DashWallpaperStore
import com.example.opendash.dash.DashKeepAliveService
import com.example.opendash.dash.DashSession
import com.example.opendash.dash.DashState
import com.example.opendash.dash.DashWifiManager
import com.example.opendash.dash.WifiConnStatus
import com.example.opendash.dash.map.LocationTracker
import com.example.opendash.dash.map.MapRenderer
import com.example.opendash.dash.map.Mercator
import com.example.opendash.dash.map.TileProvider
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.NavEngine
import com.example.opendash.dash.nav.Route
import com.example.opendash.dash.nav.Router
import com.example.opendash.dash.protocol.DashCommands
import com.example.opendash.dash.video.DashEncoder
import com.example.opendash.dash.video.DashIdleRenderer
import com.example.opendash.dash.video.NalProcessor
import com.example.opendash.dash.video.RtpPacketizer
import com.example.opendash.media.CallController
import com.example.opendash.media.CallInfoProvider
import com.example.opendash.media.IncomingCall
import com.example.opendash.media.MediaInfoProvider
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnStage { OFFLINE, WIFI, AUTH, STREAMING, ERROR }
enum class GpsStatus { GOOD, WEAK, LOST }

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "",            // empty until a dash is discovered/paired (see DashConfig)
    val wifiPassword: String = "12345678",  // RE Tripper factory passphrase; rider-overridable
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val mapZoom: Int = 19,
    val remainingKm: Double? = null,
    val etaMinutes: Int? = null,
    val maneuver: String? = null,
    val maneuverType: com.example.opendash.dash.nav.ManeuverType? = null,
    val nextTurnM: Double? = null,
    // Google-style: the maneuver AFTER the next one ("Then ↱"), and the next turn's map point.
    val secondManeuverType: com.example.opendash.dash.nav.ManeuverType? = null,
    val maneuverLat: Double? = null,
    val maneuverLng: Double? = null,
    val maneuverRoad: String? = null,
    val hasGps: Boolean = false,
    val gpsStatus: GpsStatus = GpsStatus.LOST,
    val speedKmh: Int? = null,               // GPS ground speed for the preview's cluster mock
    val hasRoute: Boolean = false,
    val offRoute: Boolean = false,
    val headingUp: Boolean = true,
    val thermal: String = "OK",
    // For the in-app Google Map view
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val riderBearing: Float = 0f,
    val destLatLng: Pair<Double, Double>? = null,
    val routePoints: List<GeoPoint> = emptyList(),
    val routeCongestion: List<Int> = emptyList(),
    val wallpaperPath: String? = null,
    val wallpaperKind: DashWallpaperKind? = null,
    val wallpaperFit: DashWallpaperFit = DashWallpaperFit.CROP,
    val wallpaperCropX: Float = 0f,
    val wallpaperCropY: Float = 0f,
    val wallpaperGalleryCount: Int = 0,
    val wallpaperGalleryIndex: Int = 0,
    val wallpaperSaving: Boolean = false,
    val wallpaperError: String? = null,
    val pendingPairingSsid: String? = null,
    val showMediaOverlay: Boolean = false,
    val musicMode: Boolean = false,   // joystick is in MUSIC control mode (badge shown on dash)
    val isCustomTrail: Boolean = false,
    val trailStart: Pair<Double, Double>? = null,
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DashUiState())
    val ui = _ui.asStateFlow()

    private val session     = DashSession(viewModelScope)
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val dashConfig  = com.example.opendash.dash.DashConfig.get(app)
    private val voice        = com.example.opendash.dash.nav.VoiceManager.get(app)
    private val repo         = com.example.opendash.data.SyncRepository.get(app)
    private val recorder     = com.example.opendash.data.RideRecorder()
    private val wallpaperStore = DashWallpaperStore(app)
    private val idleRenderer = DashIdleRenderer()
    private var recordJob: Job? = null
    private val tiles       = TileProvider(app, viewModelScope)
    private val location    = LocationTracker(app)
    private val mapRenderer = MapRenderer(tiles)
    private val powerManager = app.getSystemService(Application.POWER_SERVICE) as PowerManager
    private val mediaInfo = MediaInfoProvider(app)
    private val callController = CallController(app)

    val nowPlaying = mediaInfo.nowPlaying
    val incomingCall = com.example.opendash.media.CallInfoProvider.incomingCall

    private var encoder: DashEncoder? = null
    private var streamJob: Job? = null
    private var mediaObserveJob: Job? = null
    private var lastTrackTitle: String? = null
    @Volatile private var showMediaOverlayUntilMs = 0L
    private val mediaOverlayRect = android.graphics.RectF()

    private var userWantsConnection = false

    // ── Navigation/map state read by the 4 fps frame loop ──
    @Volatile private var destLat: Double? = null
    @Volatile private var destLng: Double? = null
    @Volatile private var route: Route? = null
    // Alternative route geometries drawn faded under the active route on the dash frame.
    @Volatile private var alternateRoutes: List<List<GeoPoint>> = emptyList()
    @Volatile private var zoom = 15          // nav-level default zoom (between RE look and wide)
    @Volatile private var lastManualZoomAt = 0L   // joystick/button zoom pauses auto-zoom
    @Volatile private var headingUp = true

    // Smoothed camera — eased toward the latest GPS target every frame so the map
    // glides at 8 fps instead of jumping once per 1 Hz fix (the "cheap"/laggy feel).
    private var camLat = 0.0
    private var camLng = 0.0
    private var camHdg = 0f
    private var camInit = false
    private var lastTickNs = 0L          // for framerate-independent smoothing

    // Dead-reckoning: last GPS fix + its velocity, so the camera keeps gliding forward
    // between the 1 Hz fixes instead of easing to a stop (the "laggy" feel at speed).
    private var fixLat = 0.0
    private var fixLng = 0.0
    private var fixBearing = 0f
    private var fixSpeed = 0f             // m/s
    private var fixWallMs = 0L
    private var lastFixTime = 0L
    @Volatile private var gpsStatus = GpsStatus.LOST

    // Smoothed rider position shown on the dash frame (locked to the camera centre so the
    // marker stays put and the map slides under it). null = no GPS.
    @Volatile private var frameRiderLat: Double? = null
    @Volatile private var frameRiderLng: Double? = null
    @Volatile private var camMoving = false   // drives the dynamic frame rate

    // Stable ETA (Google-like): the raw estimate jitters with instantaneous speed, so we
    // smooth it and only recompute the absolute arrival clock occasionally — no per-frame churn.
    private var smoothEtaSec = 0.0
    @Volatile private var etaArrivalMs = 0L
    private var lastArrivalCalcMs = 0L

    // Off-route → reroute, debounced. Now feasible because the app keeps cellular
    // internet while bound to the dash (per-socket binding), so Router can run mid-ride.
    @Volatile private var offRouteSince = 0L
    @Volatile private var lastRerouteAt = 0L
    @Volatile private var rerouting = false

    // Live traffic refresh: re-fetch the route (and its congestion) while riding, so the
    // colored patches track changing traffic instead of freezing at planning time.
    @Volatile private var lastTrafficRefreshAt = 0L
    @Volatile private var refreshingTraffic = false

    // Map-matched rider position: snapped onto the route while on it (kills the GPS
    // lane/road jitter), raw GPS when genuinely off-route. Drives the marker + camera.
    @Volatile private var matchedLat: Double? = null
    @Volatile private var matchedLng: Double? = null

    // Frame cache (avoid the expensive redraw when nothing changed)
    private var frameBitmap: Bitmap? = null
    private var lastSignature = ""
    private var lastRedrawAt = 0L
    @Volatile private var wallpaperFrameRevision = 0

    companion object {
        private const val FORCE_REDRAW_MS = 2_000L
        private const val TRAFFIC_REFRESH_MS = 120_000L   // live traffic re-fetch cadence
        private const val SMOOTH_TAU = 0.28      // camera smoothing time constant (s)
        private const val FPS_MOVING = 4
        private const val FPS_IDLE = 2
        private const val MUSIC_MODE_TIMEOUT_MS = 6_000L  // idle → auto-exit back to NORMAL
        private const val DEFAULT_ZOOM = 15               // recenter/new-trip resets zoom to this
        private const val AUTO_ZOOM_HOLD_MS = 20_000L     // manual zoom pauses auto-zoom this long

        // Raw dash codes → logical joystick direction, during map projection.
        // Codes confirmed from the RE app decompile + better-dash captures; UP/DOWN/PRESS
        // are aliased (nav-context 0x13/0x14/0x15 and media-context 0x06/0x07/0x05 both map
        // to the same gesture) so we work whichever set the dash emits. LEFT/RIGHT still
        // want a one-time on-bike capture to confirm. See docs joystick findings.
        private fun decodeDir(code: Int): JoyDir? = when (code) {
            0x13, 0x06 -> JoyDir.UP
            0x14, 0x07 -> JoyDir.DOWN
            0x0A       -> JoyDir.LEFT
            0x09       -> JoyDir.RIGHT
            0x15, 0x05 -> JoyDir.PRESS
            else       -> null
        }
    }

    /** Physical joystick gestures the dash sends while projecting the map. */
    private enum class JoyDir { UP, DOWN, LEFT, RIGHT, PRESS }

    // Two-mode joystick scheme (see docs joystick findings):
    //  NORMAL → up/down zoom, press recenter, left/right enter MUSIC mode.
    //  MUSIC  → up/down volume, press play/pause, left/right prev/next; auto-exits after idle.
    @Volatile private var joyMusicMode = false
    @Volatile private var lastJoyInputAt = 0L
    @Volatile private var callActiveSinceMs = 0L   // wall-clock when the current call was answered

    /** Project a lat/lng forward [distM] metres along [bearingDeg] (great-circle). */
    private fun project(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val br = Math.toRadians(bearingDeg)
        val dr = distM / r
        val lat1 = Math.toRadians(lat); val lng1 = Math.toRadians(lng)
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr) + Math.cos(lat1) * Math.sin(dr) * Math.cos(br))
        val lng2 = lng1 + Math.atan2(
            Math.sin(br) * Math.sin(dr) * Math.cos(lat1),
            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }

    init {
        com.example.opendash.data.NavSettings.init(app)
        com.example.opendash.data.ApiKeys.init(app)
        com.example.opendash.data.GroupRide.init(app)
        if (MediaInfoProvider.isAccessGranted(app)) {
            mediaInfo.start()
        }
        viewModelScope.launch {
            mediaInfo.nowPlaying.collect { media ->
                if (media != null && media.title != lastTrackTitle) {
                    lastTrackTitle = media.title
                    triggerMediaOverlay()
                }
            }
        }
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        _ui.value = _ui.value.copy(
            ssid = dashConfig.ssid,
            wifiPassword = dashConfig.password,
        )
        publishWallpaper(wallpaperStore.currentInfo())

        // When we connect to a previously-unknown dash by prefix, Android can reveal the
        // exact SSID. Do not persist it until the rider confirms the pairing.
        wifiManager.onSsidResolved = { learned ->
            requestPairingConfirmation(learned)
        }

        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(1_200)
                            // Re-check: the user may have hit Disconnect during the delay.
                            if (userWantsConnection &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED &&
                                _ui.value.pendingPairingSsid == null
                            ) {
                                connectSessionWhenSsidResolved()
                            }
                        }
                    }
                    WifiConnStatus.ERROR -> { _ui.value = _ui.value.copy(errorMessage = ws.error); refreshStage() }
                    else -> refreshStage()
                }
            }
        }

        viewModelScope.launch {
            session.state.collect { state ->
                refreshStage()
                if (state == DashState.READY) startStream()
            }
        }

        viewModelScope.launch {
            location.location.collect { loc ->
                if (session.state.value != DashState.STREAMING) {
                    tick()
                }
            }
        }

        session.onError = { msg -> _ui.value = _ui.value.copy(errorMessage = msg); refreshStage() }
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            val call = CallInfoProvider.incomingCall.value
            val label = when {
                // 1. Active/incoming call is modal: only accept (left) / reject (right) work.
                call != null -> handleCallButton(code, call)
                // 2. No destination/route → dash shows wallpaper; joystick cycles it.
                isIdleWallpaperMode() -> handleWallpaperButton(code)
                // 3. Map projection → NORMAL / MUSIC two-mode state machine.
                else -> handleMapButton(code)
            }
            _ui.value = _ui.value.copy(lastButton = label)
        }
    }

    private fun handleCallButton(code: Int, call: IncomingCall): String =
        // Directions mirror the on-screen button sides: RIGHT = green accept, LEFT = red decline/end.
        when (decodeDir(code)) {
            JoyDir.RIGHT -> if (call.incoming) { answerCall(call); "Call answered" } else "In call"
            JoyDir.LEFT -> { endCall(call); if (call.incoming) "Call declined" else "Call ended" }
            else -> "Call — ◀ decline / accept ▶"   // everything else blocked during a call
        }

    // Idle (wallpaper) mode mirrors the nav-mode joystick grammar: UP/DOWN act on the
    // primary surface (wallpaper instead of zoom), LEFT/RIGHT enter MUSIC mode with the
    // same controls + auto-exit, and calls stay modal (handled before we get here).
    private fun handleWallpaperButton(code: Int): String {
        val dir = decodeDir(code) ?: return "code 0x${code.toString(16).uppercase()}"
        lastJoyInputAt = System.currentTimeMillis()
        if (joyMusicMode) return handleMusicMode(dir)
        return when (dir) {
            JoyDir.UP -> { cycleWallpaper(1); "Next wallpaper" }
            JoyDir.DOWN -> { cycleWallpaper(-1); "Previous wallpaper" }
            JoyDir.LEFT, JoyDir.RIGHT -> { enterMusicMode(); "Music mode" }
            JoyDir.PRESS -> ""   // unassigned while idle
        }
    }

    private fun handleMapButton(code: Int): String {
        val dir = decodeDir(code) ?: return "code 0x${code.toString(16).uppercase()}"
        lastJoyInputAt = System.currentTimeMillis()
        return if (joyMusicMode) handleMusicMode(dir) else handleNormalMode(dir)
    }

    private fun handleNormalMode(dir: JoyDir): String = when (dir) {
        JoyDir.UP -> { zoomIn(); "Zoom in" }
        JoyDir.DOWN -> { zoomOut(); "Zoom out" }
        JoyDir.PRESS -> { recenter(); "Recenter" }
        // Left/right open MUSIC mode (a deliberate sideways flick), the badge shows on the dash.
        JoyDir.LEFT, JoyDir.RIGHT -> { enterMusicMode(); "Music mode" }
    }

    private fun handleMusicMode(dir: JoyDir): String = when (dir) {
        JoyDir.UP -> { mediaInfo.volumeUp(); "Volume +" }
        JoyDir.DOWN -> { mediaInfo.volumeDown(); "Volume −" }
        JoyDir.PRESS -> { mediaInfo.playPause(); "Play / Pause" }
        JoyDir.LEFT -> { triggerMediaOverlay(); mediaInfo.skipPrevious(); "Previous track" }
        JoyDir.RIGHT -> { triggerMediaOverlay(); mediaInfo.skipNext(); "Next track" }
    }

    private fun enterMusicMode() {
        joyMusicMode = true
        lastJoyInputAt = System.currentTimeMillis()
        triggerMediaOverlay()
        _ui.value = _ui.value.copy(musicMode = true)
    }

    private fun exitMusicMode() {
        if (!joyMusicMode) return
        joyMusicMode = false
        _ui.value = _ui.value.copy(musicMode = false)
    }

    private fun refreshStage() {
        val wifi = wifiManager.state.value.status
        val dash = session.state.value
        val stage = when {
            dash == DashState.STREAMING -> ConnStage.STREAMING
            dash == DashState.ERROR || wifi == WifiConnStatus.ERROR -> ConnStage.ERROR
            dash == DashState.AUTHENTICATING || dash == DashState.CONNECTING || dash == DashState.READY -> ConnStage.AUTH
            wifi == WifiConnStatus.REQUESTING || wifi == WifiConnStatus.CONNECTED -> ConnStage.WIFI
            else -> ConnStage.OFFLINE
        }
        _ui.value = _ui.value.copy(stage = stage)
    }

    /**
     * Start the GPS + turn-by-turn engine WITHOUT a dash connection, so the in-app Navigate
     * screen can show real-time Google-style navigation. Idempotent; no WiFi/auth/stream and
     * no foreground service — just location updates driving [tick]. The dash stream, when the
     * bike is connected, runs independently via [connect].
     */
    fun startNavEngine() {
        location.start()
    }

    // ── Connection ─────────────────────────────────────────────────────────

    fun connect() {
        userWantsConnection = true
        _ui.value = _ui.value.copy(errorMessage = null)
        DashKeepAliveService.start(getApplication())
        location.start()
        startRecording()

        // We must know the EXACT dash SSID before connecting — the dash validates it inside
        // the encrypted auth handshake (DashAuth), and Android redacts the SSID of a network
        // we've already joined. So if we don't have it stored, find it from a WiFi scan.
        if (dashConfig.needsDiscovery) {
            wifiManager.findDashSsid(dashConfig.ssidPrefix)?.let { found ->
                requestPairingConfirmation(found)
                return
            }
        }

        when {
            wifiManager.state.value.status == WifiConnStatus.CONNECTED ->
                connectSessionWhenSsidResolved()
            // Known SSID (stored or just found by scan) → exact connect + correct auth.
            dashConfig.ssid.isNotBlank() ->
                wifiManager.connect(dashConfig.ssid, dashConfig.password)
            // Couldn't find it in scan results — fall back to prefix discovery so we at
            // least associate (auth may still need the SSID; a rescan usually fixes it).
            else ->
                wifiManager.connect(dashConfig.ssidPrefix, dashConfig.password, prefixMatch = true)
        }
    }

    fun disconnect() {
        userWantsConnection = false
        stopRecording()        // a connect→disconnect session = one saved ride
        teardown()
        session.disconnect()
        wifiManager.disconnect()
        location.stop()
        DashKeepAliveService.stop(getApplication())
        refreshStage()
    }

    // ── Ride recording (the connected session) ───────────────────────────────
    private fun startRecording() {
        if (recorder.isRecording) return
        recorder.start()
        recordJob = viewModelScope.launch {
            location.location.collect { loc ->
                if (loc != null) {
                    recorder.add(loc.latitude, loc.longitude, loc.speed, loc.accuracy, loc.time)
                }
            }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        if (!recorder.isRecording) return
        val ride = recorder.stop() ?: return   // null = trivial session, don't save
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { repo.addRide(ride) }
    }

    // ── Dash WiFi config (Settings) ──────────────────────────────────────────
    fun setSsid(s: String) { dashConfig.ssid = s.trim(); _ui.value = _ui.value.copy(ssid = s.trim()) }
    fun setWifiPassword(p: String) { dashConfig.password = p; _ui.value = _ui.value.copy(wifiPassword = p) }
    /** Forget the paired dash so the next connect rediscovers any RE_* dash by prefix. */
    fun forgetDash() {
        dashConfig.forgetDash()
        _ui.value = _ui.value.copy(ssid = "", pendingPairingSsid = null)
    }

    fun confirmDiscoveredDash() {
        val ssid = _ui.value.pendingPairingSsid?.trim().orEmpty()
        if (ssid.isBlank()) return
        dashConfig.ssid = ssid
        _ui.value = _ui.value.copy(ssid = ssid, pendingPairingSsid = null, errorMessage = null)
        if (!userWantsConnection) return
        when (wifiManager.state.value.status) {
            WifiConnStatus.CONNECTED -> connectSessionWhenSsidResolved()
            else -> wifiManager.connect(ssid, dashConfig.password)
        }
    }

    fun rejectDiscoveredDash() {
        _ui.value = _ui.value.copy(
            pendingPairingSsid = null,
            ssid = dashConfig.ssid,
            errorMessage = "Dash pairing cancelled",
        )
        if (dashConfig.needsDiscovery) {
            disconnect()
        }
    }

    private fun requestPairingConfirmation(learnedSsid: String) {
        val ssid = learnedSsid.trim()
        if (ssid.isBlank()) return
        if (dashConfig.ssid == ssid) {
            _ui.value = _ui.value.copy(ssid = ssid, pendingPairingSsid = null)
            return
        }
        _ui.value = _ui.value.copy(
            ssid = ssid,
            pendingPairingSsid = ssid,
            errorMessage = null,
        )
    }

    private fun connectSessionWhenSsidResolved() {
        val ssid = _ui.value.ssid.trim()
        if (ssid.isBlank() || ssid == dashConfig.ssidPrefix) {
            DebugLog.w("DashViewModel") { "Session connect deferred until exact dash SSID is resolved" }
            return
        }
        session.connect(ssid, wifiManager.network)
    }

    fun setWallpaperFromUri(
        uri: Uri,
        horizontalBias: Float = 0f,
        verticalBias: Float = 0f,
        fit: DashWallpaperFit = DashWallpaperFit.CROP,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(wallpaperSaving = true, wallpaperError = null) }
            runCatching {
                wallpaperStore.saveFromUri(uri, horizontalBias, verticalBias, fit)
                wallpaperStore.currentInfo()
            }.onSuccess { info ->
                    invalidateWallpaperFrame()
                    publishWallpaper(info, saving = false, error = null)
                }
                .onFailure { err ->
                    val msg = err.message ?: "Unable to save wallpaper"
                    _ui.update {
                        it.copy(
                            wallpaperSaving = false,
                            wallpaperError = msg,
                            errorMessage = msg,
                        )
                    }
                }
        }
    }

    fun addWallpapersFromUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(wallpaperSaving = true, wallpaperError = null) }
            runCatching {
                wallpaperStore.saveManyFromUris(uris)
                wallpaperStore.currentInfo()
            }.onSuccess { info ->
                invalidateWallpaperFrame()
                publishWallpaper(info, saving = false, error = null)
            }.onFailure { err ->
                val msg = err.message ?: "Unable to save wallpapers"
                _ui.update {
                    it.copy(
                        wallpaperSaving = false,
                        wallpaperError = msg,
                        errorMessage = msg,
                    )
                }
            }
        }
    }

    fun updateCurrentWallpaperOptions(
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(wallpaperSaving = true, wallpaperError = null) }
            runCatching {
                wallpaperStore.updateCurrentOptions(horizontalBias, verticalBias, fit)
                    ?: error("No wallpaper selected")
            }.onSuccess { info ->
                invalidateWallpaperFrame()
                publishWallpaper(info, saving = false, error = null)
            }.onFailure { err ->
                val msg = err.message ?: "Unable to update wallpaper"
                _ui.update {
                    it.copy(
                        wallpaperSaving = false,
                        wallpaperError = msg,
                        errorMessage = msg,
                    )
                }
            }
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = wallpaperStore.clearCurrent()
            invalidateWallpaperFrame()
            publishWallpaper(next, saving = false, error = null)
        }
    }

    fun cycleWallpaperFromSettings(delta: Int) {
        cycleWallpaper(delta)
    }

    fun getWallpaperSlideshowInterval(): Int =
        wallpaperStore.slideshowIntervalSec

    fun setWallpaperSlideshowInterval(seconds: Int) {
        wallpaperStore.slideshowIntervalSec = seconds
    }

    private fun cycleWallpaper(delta: Int) {
        val next = wallpaperStore.cycle(delta)
        invalidateWallpaperFrame()
        publishWallpaper(next)
    }

    private fun invalidateWallpaperFrame() {
        wallpaperFrameRevision++
        lastSignature = ""
        lastRedrawAt = 0L
        camMoving = true
    }

    private fun publishWallpaper(
        info: DashWallpaperInfo?,
        saving: Boolean = _ui.value.wallpaperSaving,
        error: String? = _ui.value.wallpaperError,
    ) {
        val gallery = wallpaperStore.allInfos()
        val index = info?.let { current -> gallery.indexOfFirst { it.slot == current.slot } } ?: -1
        _ui.update {
            it.copy(
                wallpaperPath = info?.path,
                wallpaperKind = info?.kind,
                wallpaperFit = info?.fit ?: DashWallpaperFit.CROP,
                wallpaperCropX = info?.horizontalBias ?: 0f,
                wallpaperCropY = info?.verticalBias ?: 0f,
                wallpaperGalleryCount = gallery.size,
                wallpaperGalleryIndex = if (index >= 0) index else 0,
                wallpaperSaving = saving,
                wallpaperError = error,
                errorMessage = error ?: it.errorMessage,
            )
        }
    }

    private fun isIdleWallpaperMode(): Boolean =
        destLat == null && destLng == null && route == null

    // ── Destination + routing ───────────────────────────────────────────────

    fun prefetchTiles(lat: Double, lng: Double) {
        val loc = location.location.value
        tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
    }

    fun setDestination(
        name: String,
        lat: Double?,
        lng: Double?,
        initialRoute: Route? = null,
        initialAlternates: List<Route> = emptyList(),
        isCustomTrail: Boolean = false,
        trailStart: Pair<Double, Double>? = null,
    ) {
        val alternates = initialAlternates.filter { it != initialRoute }
        // Fresh trip → fresh camera: nav default zoom, auto-zoom active immediately.
        zoom = DEFAULT_ZOOM
        lastManualZoomAt = 0L
        _ui.value = _ui.value.copy(
            destinationName = name,
            hasRoute = initialRoute != null,
            destLatLng = if (lat != null && lng != null) lat to lng else null,
            routePoints = initialRoute?.geometry ?: emptyList(),
            routeCongestion = initialRoute?.congestion ?: emptyList(),
            mapZoom = zoom,
            isCustomTrail = isCustomTrail,
            trailStart = trailStart,
        )
        destLat = lat
        destLng = lng
        route = initialRoute
        alternateRoutes = alternates.map { it.geometry }
        progressM = 0.0
        smoothEtaSec = 0.0; etaArrivalMs = 0L   // fresh ETA for the new route
        voice.resetTrip()   // fresh announcements for the new route
        session.updateRouteCard(name)
        if (initialRoute == null && lat != null && lng != null) {
            val loc = location.lastKnown()
            tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
            fetchRoute(lat, lng)
        } else if (initialRoute != null) {
            tiles.prefetchRoute(initialRoute.geometry)
        }
    }

    /** Drop the destination/route → free roam. The map keeps streaming and follows the rider. */
    fun exitNavigation() {
        destLat = null
        destLng = null
        route = null
        alternateRoutes = emptyList()
        progressM = 0.0
        offRouteSince = 0L
        zoom = DEFAULT_ZOOM
        smoothEtaSec = 0.0; etaArrivalMs = 0L
        voice.resetTrip()
        lastSignature = ""   // force a redraw with no route line
        _ui.value = _ui.value.copy(
            destinationName = null,
            hasRoute = false,
            destLatLng = null,
            routePoints = emptyList(),
            remainingKm = null,
            etaMinutes = null,
            maneuver = null,
            maneuverType = null,
            secondManeuverType = null,
            maneuverLat = null,
            maneuverLng = null,
            maneuverRoad = null,
            offRoute = false,
            mapZoom = zoom,
        )
        session.updateRouteCard("OpenDash")   // dash card → name + 0.0 km, nav off
    }

    /** Compute the road route now (while internet is reachable) and cache it. */
    private fun fetchRoute(destLatV: Double, destLngV: Double) {
        val loc = location.lastKnown()
        if (loc == null) {
            DebugLog.w("DashViewModel") { "fetchRoute: no origin location yet" }
            return
        }
        viewModelScope.launch {
            val list = Router.routes(GeoPoint(loc.latitude, loc.longitude), GeoPoint(destLatV, destLngV), alternatives = true)
            val r = list.firstOrNull()
            if (r != null) {
                route = r
                alternateRoutes = list.drop(1).map { it.geometry }
                tiles.prefetchRoute(r.geometry)
                _ui.value = _ui.value.copy(hasRoute = true, routePoints = r.geometry, routeCongestion = r.congestion)
                DebugLog.i("DashViewModel") { "Route ready: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m, ${alternateRoutes.size} alt" }
            } else {
                DebugLog.w("DashViewModel") { "Router returned no routes" }
            }
        }
    }

    // ── Map controls ────────────────────────────────────────────────────────

    fun zoomIn()  { zoom = (zoom + 1).coerceAtMost(20); lastManualZoomAt = System.currentTimeMillis(); _ui.value = _ui.value.copy(mapZoom = zoom) }
    fun zoomOut() { zoom = (zoom - 1).coerceAtLeast(11); lastManualZoomAt = System.currentTimeMillis(); _ui.value = _ui.value.copy(mapZoom = zoom) }
    fun recenter() {
        // Map always follows the rider — recenter just resets zoom to the nav default.
        zoom = DEFAULT_ZOOM
        _ui.value = _ui.value.copy(mapZoom = zoom)
    }

    // ── Video + nav loop ────────────────────────────────────────────────────

    private fun startStream() {
        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt) }
        val nalProc    = NalProcessor { nal, _ ->
            packetizer.packetize(nal, endOfAU = true, wallClockMs = System.currentTimeMillis())
        }
        val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, _ ->
            nalProc.process(annexB)
            // Atomic update: this runs on the encoder's callback thread, concurrent with the
            // frame loop's _ui writes — a plain copy() read-modify-write would drop updates.
            _ui.update { it.copy(frameCount = it.frameCount + 1) }
        }
        encoder?.release()
        encoder = DashEncoder(onEncoded).also { it.prepare() }

        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        // Fresh camera so it snaps to the first fix instead of gliding from a stale spot.
        camInit = false; lastTickNs = 0L; lastFixTime = 0L

        session.startStreaming()
        startMediaForwarding()
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPrefetch = 0L
            var lastWallpaperCycle = System.currentTimeMillis()
            var failures = 0
            // The loop must NEVER die silently: the session's heartbeats keep the dash
            // connected, so a dead frame loop = frozen map with the connection "up".
            while (isActive && session.state.value == DashState.STREAMING) {
                try {
                    val now = System.currentTimeMillis()
                    if (isIdleWallpaperMode()) {
                        val intervalSec = wallpaperStore.slideshowIntervalSec
                        if (intervalSec > 0 && now - lastWallpaperCycle >= intervalSec * 1000L) {
                            viewModelScope.launch(Dispatchers.Main) {
                                cycleWallpaper(1)
                            }
                            lastWallpaperCycle = now
                        }
                    } else {
                        lastWallpaperCycle = now
                    }

                    tick()
                    // Push the (possibly cached) frame to the encoder at a steady 4 fps.
                    val bmp = frameBitmap
                    val enc = encoder
                    if (bmp != null && enc != null) {
                        enc.renderFrame { canvas -> canvas.drawBitmap(bmp, 0f, 0f, null) }
                        enc.drain()
                    }
                    failures = 0
                    // Warm the tile cache ahead of the rider every ~20 s.
                    if (now - lastPrefetch > 20_000) {
                        lastPrefetch = now
                        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    DebugLog.e("DashViewModel", { "Frame loop error #$failures" }, e)
                    if (failures >= 3) {
                        // MediaCodec is in an error state — rebuild the encoder so the
                        // stream recovers. The fresh encoder re-emits SPS/PPS, which the
                        // NAL processor bundles into the next IDR for the dash decoder.
                        runCatching { encoder?.release() }
                        encoder = runCatching { DashEncoder(onEncoded).also { it.prepare() } }
                            .onFailure { DebugLog.e("DashViewModel", { "Encoder rebuild failed" }, it) }
                            .getOrNull()
                        lastSignature = "" // force a full redraw on the next tick
                        failures = 0
                    }
                }
                // Dynamic pacing: buttery while moving, throttled when stopped (power).
                delay(1000L / (if (camMoving) FPS_MOVING else FPS_IDLE))
            }
        }
    }

    /** Compute nav state, push nav-info to the dash, and redraw the frame only if it changed. */
    private fun tick() {
        // Auto-exit MUSIC mode after the rider stops using it, back to NORMAL (map) control.
        if (joyMusicMode && System.currentTimeMillis() - lastJoyInputAt > MUSIC_MODE_TIMEOUT_MS) {
            exitMusicMode()
        }

        val loc = location.location.value
        val r = route
        val dLat = destLat; val dLng = destLng

        // Default to raw GPS; map-matching below snaps it onto the route when on it.
        matchedLat = loc?.latitude
        matchedLng = loc?.longitude

        var remainingM: Double? = null
        var etaSec: Double? = null
        var nextManeuverForUi: com.example.opendash.dash.nav.Maneuver? = null
        var secondManeuverForUi: com.example.opendash.dash.nav.Maneuver? = null
        var nextTurnM: Double? = null
        // Travel direction only, like Google Maps nav: trust the GPS bearing solely while
        // actually moving. When stationary the fused provider feeds the COMPASS into
        // loc.bearing, which would spin the map/marker as the phone is turned in hand —
        // so below the speed gate we hold the last heading (and on GPS dropout too).
        var heading = if (loc != null && loc.hasBearing() && loc.speed >= 1.5f) loc.bearing
                      else (if (camInit) camHdg else 0f)
        var offRoute = false

        if (r != null && loc != null) {
            val ns = trackProgress(r, GeoPoint(loc.latitude, loc.longitude))
            remainingM = ns.remainingM
            nextTurnM = ns.nextTurnM
            nextManeuverForUi = ns.nextManeuver
            secondManeuverForUi = ns.nextManeuver2
            val headingKnown = loc.hasBearing() && loc.speed >= 1.5f
            val headingOff = headingKnown && angleDelta(loc.bearing, ns.heading) > 50f
            offRoute = when {
                ns.snapDist > 150.0 -> true
                ns.snapDist > 70.0 -> headingOff
                else -> false
            }
            // NOTE: no marker snapping. Raw GPS is accurate; snapping to the route
            // polyline pinned the rider onto a parallel road in dense areas (showed
            // "Indira Enclave" when actually at "Isha"). trackProgress is still used
            // for nav distances + off-route/reroute detection (ns.offRoute), just not
            // to move the displayed marker.
            // Parked/crawling on a route → face along the route, never the compass.
            if (loc.speed < 1.5f) heading = ns.heading
            val speed = if (loc.speed > 0.5f) loc.speed.toDouble() else 11.0
            // Smooth the ETA so it doesn't flicker every second with raw speed; recompute the
            // absolute arrival clock only every 5 s so "arrives 1:32 PM" stays steady.
            val rawEta = ns.remainingM / speed
            smoothEtaSec = if (smoothEtaSec <= 0.0) rawEta else smoothEtaSec + (rawEta - smoothEtaSec) * 0.08
            etaSec = smoothEtaSec
            val nowMs = System.currentTimeMillis()
            if (etaArrivalMs == 0L || nowMs - lastArrivalCalcMs > 5_000) {
                etaArrivalMs = nowMs + (smoothEtaSec * 1000).toLong()
                lastArrivalCalcMs = nowMs
            }
            // Feed the dash's own turn-by-turn widget with CORRECT distances (next-turn
            // + total remaining) and real arrival time. Glyph stays CONTINUE until
            // other codes are verified.
            val (pv, pu) = toDashDistance(ns.nextTurnM)
            val (tv, tu) = toDashDistance(ns.remainingM)
            val arrival = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.SECOND, etaSec!!.toInt())
            }
            val etaHHMM = "%02d%02d".format(
                arrival.get(java.util.Calendar.HOUR_OF_DAY), arrival.get(java.util.Calendar.MINUTE)
            )
            session.updateNavInfo(DashCommands.NAV_MANEUVER_CONTINUE, pv, pu, tv, tu, etaHHMM)
            // Olive banner under the video band: per-step guidance text (like the RE
            // app's "towards Bypass Rd"), falling back to the destination between steps.
            val guidance = ns.nextManeuver?.instruction?.takeIf { it.isNotBlank() }
                ?: _ui.value.destinationName
            guidance?.let { session.updateGuidanceText(it) }
            // Spoken/chime turn guidance (no-op when voice mode is OFF).
            voice.maybeAnnounce(ns.nextManeuver, ns.nextTurnM, ns.remainingM)
        } else if (loc != null && dLat != null && dLng != null) {
            remainingM = GeoPoint.distMeters(
                GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng)
            )
        }

        // Recompute the route if the rider has clearly left it for a few seconds.
        maybeReroute(offRoute, loc)
        // Periodically refresh live traffic on the route (frugal; setting-gated).
        maybeRefreshTraffic(loc)

        val fixAgeMs = loc?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        gpsStatus = when {
            loc == null || fixAgeMs > 4_000L -> GpsStatus.LOST
            loc.accuracy > 25f -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }

        // Publish nav figures to the phone UI.
        _ui.value = _ui.value.copy(
            hasGps = loc != null,
            gpsStatus = gpsStatus,
            speedKmh = loc?.let { if (it.hasSpeed()) (it.speed * 3.6f).toInt() else 0 },
            riderLat = matchedLat,
            riderLng = matchedLng,
            riderBearing = heading,
            remainingKm = remainingM?.let { it / 1000.0 },
            etaMinutes = etaSec?.let { (it / 60.0).toInt() },
            maneuver = nextManeuverForUi?.instruction,
            maneuverType = nextManeuverForUi?.type,
            nextTurnM = nextTurnM,
            secondManeuverType = secondManeuverForUi?.type,
            maneuverLat = nextManeuverForUi?.location?.lat,
            maneuverLng = nextManeuverForUi?.location?.lng,
            maneuverRoad = nextManeuverForUi?.let { navRoadFromInstruction(it.instruction) },
            offRoute = offRoute,
        )

        updateThermal()

        // Speed-based auto-zoom, like the RE app's Google Nav camera: z15 in town, out to
        // z13 at highway speed. Hysteresis gaps prevent flapping at the boundaries, and a
        // manual joystick/button zoom pauses this for AUTO_ZOOM_HOLD_MS.
        if (loc != null && System.currentTimeMillis() - lastManualZoomAt > AUTO_ZOOM_HOLD_MS) {
            val vKmh = loc.speed * 3.6f
            val nz = when {
                zoom >= 15 && vKmh >= 25f -> 14
                zoom == 14 && vKmh >= 55f -> 13
                zoom == 13 && vKmh <= 45f -> 14
                zoom == 14 && vKmh <= 18f -> 15
                else -> zoom
            }
            if (nz != zoom) {
                zoom = nz
                _ui.value = _ui.value.copy(mapZoom = nz)
            }
        }

        // ── Predictive, framerate-independent camera (CarPlay-smooth) ──
        // Capture each fresh GPS fix + its velocity for dead-reckoning.
        if (loc != null && loc.time != lastFixTime) {
            lastFixTime = loc.time
            fixLat = loc.latitude; fixLng = loc.longitude
            fixBearing = loc.bearing; fixSpeed = loc.speed
            fixWallMs = System.currentTimeMillis()
        }
        // Extrapolate where the rider IS NOW (last fix + velocity·elapsed), so the camera
        // doesn't trail the bike between 1 Hz fixes. Only predict while genuinely moving.
        val rider: Pair<Double, Double>? = when {
            loc == null -> null
            fixSpeed > 1.0f -> {
                val elapsed = ((System.currentTimeMillis() - fixWallMs) / 1000.0).coerceAtMost(1.5)
                project(fixLat, fixLng, fixBearing.toDouble(), fixSpeed * elapsed)
            }
            else -> matchedLat!! to matchedLng!!
        }

        val haveTarget = rider != null || (dLat != null && dLng != null)
        val targetLat = rider?.first ?: dLat ?: camLat
        val targetLng = rider?.second ?: dLng ?: camLng

        // Time-based smoothing: alpha derived from the real frame interval + a time
        // constant, so motion is equally smooth at any (dynamic) frame rate.
        val nowNs = System.nanoTime()
        val dt = if (lastTickNs == 0L) 0.042 else ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        val a = if (camInit) (1.0 - Math.exp(-dt / SMOOTH_TAU)) else 1.0

        val prevLat = camLat; val prevLng = camLng
        if (haveTarget) {
            if (!camInit) { camLat = targetLat; camLng = targetLng; camHdg = heading; camInit = true }
            else {
                camLat += (targetLat - camLat) * a
                camLng += (targetLng - camLng) * a
                val dh = (((heading - camHdg) % 360f) + 540f) % 360f - 180f  // shortest arc
                camHdg += dh * a.toFloat()
            }
        }
        // Lock the dash marker to the smoothed centre (map slides under it).
        frameRiderLat = if (rider != null) camLat else null
        frameRiderLng = if (rider != null) camLng else null
        // Drive the dynamic frame rate: full speed while moving/turning, throttle when idle.
        val movedM = if (camInit) GeoPoint.distMeters(GeoPoint(prevLat, prevLng), GeoPoint(camLat, camLng)) else 0.0
        camMoving = movedM > 0.25 || (loc?.speed ?: 0f) > 0.8f

        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        val sig = buildString {
            if (r == null && dLat == null && dLng == null) {
                append("idle:${_ui.value.wallpaperPath}")
                append(_ui.value.wallpaperKind)
                append(_ui.value.wallpaperFit)
                append(_ui.value.wallpaperCropX)
                append(_ui.value.wallpaperCropY)
                append(_ui.value.wallpaperGalleryIndex)
                append(wallpaperFrameRevision)
            } else {
                append("nav")
            }
            // High resolution (6 dp ≈ 0.1 m, 0.1° heading) so every smoothed step redraws
            // for buttery motion. Safe from standstill jitter because the camera is fed the
            // SMOOTHED position (which settles and stops), not raw GPS.
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(zoom)
            append(if (headingUp) (camHeading * 10).toInt() else 0)
            append(remainingM?.let { (it / 100).toInt() } ?: -1) // 100 m resolution to avoid jitter
            append(if (r != null) r.geometry.size else 0)
            // Overlay state that changes the frame: call card (incoming or active) + music-mode badge.
            CallInfoProvider.incomingCall.value?.let {
                append(it.caller); append(it.incoming)
                // Active-call duration ticks each second → redraw at 1 s granularity.
                if (!it.incoming && callActiveSinceMs > 0L)
                    append((System.currentTimeMillis() - callActiveSinceMs) / 1000)
            }
            append(joyMusicMode)
        }
        val now = System.currentTimeMillis()
        if (sig != lastSignature || now - lastRedrawAt > FORCE_REDRAW_MS) {
            lastSignature = sig
            lastRedrawAt = now
            redrawFrame(centerLat, centerLng, camHeading, remainingM)
        }
    }

    /**
     * Reroute when off the line for >5 s (12 s cooldown between attempts). Routes from
     * the live GPS position to the saved destination and swaps the polyline in. Needs
     * internet — available now because only the dash sockets are bound to the dash WiFi.
     */
    /**
     * Refresh the route's live traffic while riding — frugal by design: only while actually
     * moving, and at most once every [TRAFFIC_REFRESH_MS]. Re-routes from the live position
     * to the destination (so it's also traffic-aware rerouting) and swaps the congestion in.
     * A failed/offline call still advances the throttle so it won't spam.
     */
    private fun maybeRefreshTraffic(loc: android.location.Location?) {
        val dLat = destLat; val dLng = destLng
        if (loc == null || dLat == null || dLng == null || route == null) return
        if (rerouting || refreshingTraffic) return
        if (loc.speed < 1.0f) return   // < ~3.6 km/h → parked; don't burn a call
        val now = System.currentTimeMillis()
        if (now - lastTrafficRefreshAt < TRAFFIC_REFRESH_MS) return
        lastTrafficRefreshAt = now
        refreshingTraffic = true
        viewModelScope.launch {
            val list = Router.routes(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng), alternatives = true)
            val r = list.firstOrNull()
            if (r != null) {
                route = r
                alternateRoutes = list.drop(1).map { it.geometry }
                tiles.prefetchRoute(r.geometry)
                _ui.value = _ui.value.copy(hasRoute = true, routePoints = r.geometry, routeCongestion = r.congestion)
                DebugLog.i("DashViewModel") { "Traffic refresh: ${r.congestion.count { it > 0 }} slow/jam segs" }
            }
            refreshingTraffic = false
        }
    }

    private fun maybeReroute(offRoute: Boolean, loc: android.location.Location?) {
        val dLat = destLat; val dLng = destLng
        if (!offRoute || loc == null || dLat == null || dLng == null) { offRouteSince = 0L; return }
        val now = System.currentTimeMillis()
        if (offRouteSince == 0L) offRouteSince = now
        if (now - offRouteSince < 4_000 || now - lastRerouteAt < 12_000 || rerouting) return
        lastRerouteAt = now
        rerouting = true
        DebugLog.i("DashViewModel") { "Off-route ${(now - offRouteSince) / 1000}s → rerouting" }
        viewModelScope.launch {
            val list = Router.routes(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng), alternatives = true)
            val r = list.firstOrNull()
            if (r != null) {
                route = r
                alternateRoutes = list.drop(1).map { it.geometry }
                progressM = 0.0
                offRouteSince = 0L
                tiles.prefetchRoute(r.geometry)
                _ui.value = _ui.value.copy(hasRoute = true, routePoints = r.geometry, routeCongestion = r.congestion)
                DebugLog.i("DashViewModel") { "Reroute ok: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m, ${alternateRoutes.size} alt" }
            } else {
                DebugLog.w("DashViewModel") { "Reroute failed (no internet?)" }
            }
            rerouting = false
        }
    }

    private fun redrawFrame(
        centerLat: Double, centerLng: Double, heading: Float, remainingM: Double?,
    ) {
        val bmp = frameBitmap ?: return
        val loc = location.location.value
        if (route == null && destLat == null && destLng == null) {
            val canvas = Canvas(bmp)
            idleRenderer.draw(
                canvas,
                _ui.value.wallpaperPath,
                _ui.value.wallpaperKind,
                _ui.value.wallpaperCropX,
                _ui.value.wallpaperCropY,
                _ui.value.wallpaperFit,
            )
            drawCallOverlay(canvas, bmp.width, bmp.height)
            if (_ui.value.musicMode) drawMusicModeBadge(canvas, bmp.width, bmp.height)
            return
        }
        // Glanceable ETA — minutes remaining + a stable 12-hour arrival clock. Both come
        // from the smoothed estimate so they don't flicker every second.
        val mins = _ui.value.etaMinutes
        val arriving = mins != null && mins <= 0
        val etaPrimary = if (mins != null && etaArrivalMs > 0L) when {
            arriving   -> "Arriving"
            mins >= 60 -> "${mins / 60}h ${mins % 60}m"
            else       -> "$mins min"
        } else null
        // 12-hour arrival clock; hidden once arriving.
        val etaSecondary = if (etaPrimary != null && !arriving)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(etaArrivalMs))
        else null
        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            headingUp = headingUp && (loc != null),
            heading = heading,
            riderLat = frameRiderLat,
            riderLng = frameRiderLng,
            destLat = destLat,
            destLng = destLng,
            destName = _ui.value.destinationName,
            route = route?.geometry ?: emptyList(),
            // Ridden part of the route (drawn grey): geometry index from nav progress —
            // cumulative distances are precomputed, so this is a cheap scan, no search.
            travelledIdx = route?.let { rt ->
                val cum = rt.cumulative
                var i = 0
                while (i < cum.size - 1 && cum[i + 1] <= progressM) i++
                i
            } ?: 0,
            alternates = alternateRoutes,
            maneuverText = null, // turn-by-turn maneuver banner removed
            remainingText = remainingM?.let { fmtDist(it) },
            // Top-down (heading-up) nav view. The 3D perspective tilt is DISABLED: warping
            // flat raster tiles via setPolyToPoly stretches the baked-in map labels and
            // skews the angle (you can't get true Google-Maps 3D without vector tiles).
            tilt3d = false,
            etaPrimary = etaPrimary,
            etaSecondary = etaSecondary,
            gpsWeak = gpsStatus == GpsStatus.WEAK,
            gpsLost = gpsStatus == GpsStatus.LOST,
            showMediaOverlay = _ui.value.showMediaOverlay,
            gpsTopOffset = run {
                val hasMusic = _ui.value.showMediaOverlay && mediaInfo.nowPlaying.value != null
                val hasNav = _ui.value.maneuver != null
                if (hasMusic && hasNav) 58f else if (hasMusic || hasNav) 48f else 14f
            },
            // Day/night follows the Settings mode + clock; tick() redraws at 1 Hz, so the
            // 7 pm / 6 am auto flip reaches the dash within a second.
            night = com.example.opendash.data.NavSettings.nightActive(),
        )
        val canvas = Canvas(bmp)
        mapRenderer.draw(canvas, frame)
        drawCallOverlay(canvas, bmp.width, bmp.height)
        drawMediaOverlay(canvas, bmp.width, bmp.height)
        if (_ui.value.musicMode) drawMusicModeBadge(canvas, bmp.width, bmp.height)
    }

    /** Small pill at the bottom of the round dash telling the rider the joystick is in
     *  MUSIC control mode (so up/down = volume, left/right = track, not zoom/pan). */
    private fun drawMusicModeBadge(canvas: Canvas, width: Int, height: Int) {
        val cx = width / 2f
        val label = "♪ MUSIC"
        badgeText.textAlign = android.graphics.Paint.Align.CENTER
        val tw = badgeText.measureText(label)
        val padH = 12f; val padV = 6f
        val boxW = tw + padH * 2f; val boxH = badgeText.textSize + padV * 2f
        val top = height * 0.80f
        val rect = android.graphics.RectF(cx - boxW / 2f, top, cx + boxW / 2f, top + boxH)
        canvas.drawRoundRect(rect, boxH / 2f, boxH / 2f, badgeBg)
        canvas.drawText(label, cx, top + padV + badgeText.textSize * 0.82f, badgeText)
    }

    private val badgeBg by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6000000.toInt()
        }
    }
    private val badgeText by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 11f
            isFakeBoldText = true
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
    }

    private val mediaOverlayBackground by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3FFFFFF.toInt()
        }
    }
    private val mediaOverlayBorder by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80FFFFFF.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.0f
        }
    }
    private val mediaOverlayTitle by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E2022.toInt()
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    private val mediaOverlayArtist by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF687076.toInt()
            textSize = 9f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    private fun drawManeuverArrow(canvas: Canvas, x: Float, y: Float, size: Float, type: com.example.opendash.dash.nav.ManeuverType?, color: Int, strokeWidth: Float) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        when (type) {
            com.example.opendash.dash.nav.ManeuverType.TURN_LEFT,
            com.example.opendash.dash.nav.ManeuverType.SHARP_LEFT,
            com.example.opendash.dash.nav.ManeuverType.SLIGHT_LEFT -> {
                path.moveTo(x + size * 0.75f, y + size * 0.75f)
                path.lineTo(x + size * 0.75f, y + size * 0.45f)
                path.lineTo(x + size * 0.25f, y + size * 0.45f)
                path.moveTo(x + size * 0.4f, y + size * 0.3f)
                path.lineTo(x + size * 0.25f, y + size * 0.45f)
                path.lineTo(x + size * 0.4f, y + size * 0.6f)
            }
            com.example.opendash.dash.nav.ManeuverType.TURN_RIGHT,
            com.example.opendash.dash.nav.ManeuverType.SHARP_RIGHT,
            com.example.opendash.dash.nav.ManeuverType.SLIGHT_RIGHT -> {
                path.moveTo(x + size * 0.25f, y + size * 0.75f)
                path.lineTo(x + size * 0.25f, y + size * 0.45f)
                path.lineTo(x + size * 0.75f, y + size * 0.45f)
                path.moveTo(x + size * 0.6f, y + size * 0.3f)
                path.lineTo(x + size * 0.75f, y + size * 0.45f)
                path.lineTo(x + size * 0.6f, y + size * 0.6f)
            }
            com.example.opendash.dash.nav.ManeuverType.UTURN -> {
                path.moveTo(x + size * 0.75f, y + size * 0.75f)
                path.lineTo(x + size * 0.75f, y + size * 0.45f)
                val r = android.graphics.RectF(x + size * 0.25f, y + size * 0.25f, x + size * 0.75f, y + size * 0.65f)
                path.arcTo(r, 0f, -180f, false)
                path.lineTo(x + size * 0.25f, y + size * 0.75f)
                path.moveTo(x + size * 0.1f, y + size * 0.6f)
                path.lineTo(x + size * 0.25f, y + size * 0.75f)
                path.lineTo(x + size * 0.4f, y + size * 0.6f)
            }
            else -> {
                path.moveTo(x + size * 0.5f, y + size * 0.75f)
                path.lineTo(x + size * 0.5f, y + size * 0.25f)
                path.moveTo(x + size * 0.35f, y + size * 0.4f)
                path.lineTo(x + size * 0.5f, y + size * 0.25f)
                path.lineTo(x + size * 0.65f, y + size * 0.4f)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMediaOverlay(canvas: Canvas, width: Int, height: Int) {
        val hasMusic = _ui.value.showMediaOverlay && mediaInfo.nowPlaying.value != null
        val hasNav = _ui.value.maneuver != null
        if (!hasMusic && !hasNav) return
        
        val night = com.example.opendash.data.NavSettings.nightActive()
        val centerX = width / 2f
        val centerY = height / 2f
        
        if (hasMusic) {
            val track = mediaInfo.nowPlaying.value!!
            val title = if (track.title.length > 32) track.title.take(31) + "..." else track.title
            val Rc = 134f
            val artSize = 18f
            val spacing = 6f
            val edgePadding = 14f
            
            mediaOverlayTitle.apply {
                textSize = 10f
                isFakeBoldText = true
            }
            val textWidth = mediaOverlayTitle.measureText(title)
            val totalLength = edgePadding * 2f + artSize + spacing + textWidth
            val sweepAngle = ((totalLength / Rc) * (180f / Math.PI.toFloat())).coerceIn(45f, 130f)
            val startAngle = 270f - sweepAngle / 2f
            val endAngle = 270f + sweepAngle / 2f
            
            val arcPath = android.graphics.Path().apply {
                val rect = android.graphics.RectF(centerX - Rc, centerY - Rc, centerX + Rc, centerY + Rc)
                addArc(rect, startAngle, sweepAngle)
            }
            
            val fStart = startAngle / 360f
            val fCenter = 270f / 360f
            val fEnd = endAngle / 360f
            val positions = floatArrayOf(0.0f, maxOf(0.0f, fStart), fCenter, fEnd, 1.0f)
            
            val borderColors = if (night) {
                intArrayOf(0x00000000, 0x00000000, 0x4D000000, 0x00000000, 0x00000000)
            } else {
                intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
            }
            val borderShader = android.graphics.SweepGradient(centerX, centerY, borderColors, positions)
            val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 27.5f
                strokeCap = android.graphics.Paint.Cap.BUTT
                setShader(borderShader)
            }
            canvas.drawPath(arcPath, borderPaint)
            
            val bgColors = if (night) {
                intArrayOf(0x00000000, 0x00000000, 0xB3000000.toInt(), 0x00000000, 0x00000000)
            } else {
                intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
            }
            val bgShader = android.graphics.SweepGradient(centerX, centerY, bgColors, positions)
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 26f
                strokeCap = android.graphics.Paint.Cap.BUTT
                setShader(bgShader)
            }
            canvas.drawPath(arcPath, bgPaint)
            
            val arcLength = Rc * (sweepAngle * Math.PI / 180.0).toFloat()
            val contentWidth = artSize + spacing + textWidth
            val startOffset = (arcLength - contentWidth) / 2f
            
            val artOffset = startOffset + artSize / 2f
            val artAngle = startAngle + (artOffset / Rc) * (180f / Math.PI.toFloat())
            val thetaRad = artAngle * (Math.PI / 180.0)
            val artX = (centerX + Rc * Math.cos(thetaRad)).toFloat()
            val artY = (centerY + Rc * Math.sin(thetaRad)).toFloat()
            
            val baseCirclePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (night) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawCircle(artX, artY, 8.5f, baseCirclePaint)
            
            if (track.art != null) {
                canvas.save()
                val clipPath = android.graphics.Path().apply {
                    addCircle(artX, artY, 8f, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
                canvas.drawBitmap(track.art!!, null, android.graphics.RectF(artX - 8f, artY - 8f, artX + 8f, artY + 8f), null)
                canvas.restore()
            }
            
            val textStart = startOffset + artSize + spacing
            mediaOverlayTitle.textAlign = android.graphics.Paint.Align.LEFT
            mediaOverlayTitle.color = if (night) android.graphics.Color.WHITE else 0xFF1E2022.toInt()
            canvas.drawTextOnPath(title, arcPath, textStart, 3.5f, mediaOverlayTitle)
            
            if (hasNav) {
                val dist = _ui.value.nextTurnM
                val distText = dist?.let { if (it < 1000.0) "${it.toInt()} m" else "%.1f km".format(it / 1000.0) } ?: ""
                val RcTbt = 110f
                val iconSize = 15f
                val tbtSpacing = 5f
                val tbtEdgePadding = 12f
                
                val tbtTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (night) android.graphics.Color.WHITE else 0xFF1E2022.toInt()
                    textSize = 8.5f
                    isFakeBoldText = true
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                }
                val tbtTextWidth = tbtTextPaint.measureText(distText)
                val tbtTotalLength = tbtEdgePadding * 2f + iconSize + tbtSpacing + tbtTextWidth
                val tbtSweepAngle = ((tbtTotalLength / RcTbt) * (180f / Math.PI.toFloat())).coerceIn(40f, 130f)
                val tbtStartAngle = 270f - tbtSweepAngle / 2f
                val tbtEndAngle = 270f + tbtSweepAngle / 2f
                
                val tbtArcPath = android.graphics.Path().apply {
                    val rect = android.graphics.RectF(centerX - RcTbt, centerY - RcTbt, centerX + RcTbt, centerY + RcTbt)
                    addArc(rect, tbtStartAngle, tbtSweepAngle)
                }
                
                val tbtFStart = tbtStartAngle / 360f
                val tbtFCenter = 270f / 360f
                val tbtFEnd = tbtEndAngle / 360f
                val tbtPositions = floatArrayOf(0.0f, maxOf(0.0f, tbtFStart), tbtFCenter, tbtFEnd, 1.0f)
                
                val tbtBorderColors = if (night) {
                    intArrayOf(0x00000000, 0x00000000, 0x4D000000, 0x00000000, 0x00000000)
                } else {
                    intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                }
                val tbtBorderShader = android.graphics.SweepGradient(centerX, centerY, tbtBorderColors, tbtPositions)
                val tbtBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 23.5f
                    strokeCap = android.graphics.Paint.Cap.BUTT
                    setShader(tbtBorderShader)
                }
                canvas.drawPath(tbtArcPath, tbtBorderPaint)
                
                val tbtBgColors = if (night) {
                    intArrayOf(0x00000000, 0x00000000, 0xB3000000.toInt(), 0x00000000, 0x00000000)
                } else {
                    intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
                }
                val tbtBgShader = android.graphics.SweepGradient(centerX, centerY, tbtBgColors, tbtPositions)
                val tbtBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 22f
                    strokeCap = android.graphics.Paint.Cap.BUTT
                    setShader(tbtBgShader)
                }
                canvas.drawPath(tbtArcPath, tbtBgPaint)
                
                val tbtArcLength = RcTbt * (tbtSweepAngle * Math.PI / 180.0).toFloat()
                val tbtContentWidth = iconSize + tbtSpacing + tbtTextWidth
                val tbtStartOffset = (tbtArcLength - tbtContentWidth) / 2f
                
                val iconOffset = tbtStartOffset + iconSize / 2f
                val iconAngle = tbtStartAngle + (iconOffset / RcTbt) * (180f / Math.PI.toFloat())
                val tbtThetaRad = iconAngle * (Math.PI / 180.0)
                val iconX = (centerX + RcTbt * Math.cos(tbtThetaRad)).toFloat()
                val iconY = (centerY + RcTbt * Math.sin(tbtThetaRad)).toFloat()
                
                drawManeuverArrow(canvas, iconX - iconSize/2f, iconY - iconSize/2f, iconSize, _ui.value.maneuverType, if (night) android.graphics.Color.WHITE else 0xFF1E2022.toInt(), 2.5f)
                
                val tbtTextStart = tbtStartOffset + iconSize + tbtSpacing
                tbtTextPaint.textAlign = android.graphics.Paint.Align.LEFT
                canvas.drawTextOnPath(distText, tbtArcPath, tbtTextStart, 3.0f, tbtTextPaint)
            }
        } else {
            val dist = _ui.value.nextTurnM
            val distText = dist?.let { if (it < 1000.0) "${it.toInt()} m" else "%.1f km".format(it / 1000.0) } ?: ""
            val Rc = 134f
            val iconSize = 18f
            val spacing = 6f
            val edgePadding = 14f
            
            val tbtTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (night) android.graphics.Color.WHITE else 0xFF1E2022.toInt()
                textSize = 10f
                isFakeBoldText = true
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            }
            val textWidth = tbtTextPaint.measureText(distText)
            val totalLength = edgePadding * 2f + iconSize + spacing + textWidth
            val sweepAngle = ((totalLength / Rc) * (180f / Math.PI.toFloat())).coerceIn(45f, 130f)
            val startAngle = 270f - sweepAngle / 2f
            val endAngle = 270f + sweepAngle / 2f
            
            val arcPath = android.graphics.Path().apply {
                val rect = android.graphics.RectF(centerX - Rc, centerY - Rc, centerX + Rc, centerY + Rc)
                addArc(rect, startAngle, sweepAngle)
            }
            
            val fStart = startAngle / 360f
            val fCenter = 270f / 360f
            val fEnd = endAngle / 360f
            val positions = floatArrayOf(0.0f, maxOf(0.0f, fStart), fCenter, fEnd, 1.0f)
            
            val borderColors = if (night) {
                intArrayOf(0x00000000, 0x00000000, 0x4D000000, 0x00000000, 0x00000000)
            } else {
                intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
            }
            val borderShader = android.graphics.SweepGradient(centerX, centerY, borderColors, positions)
            val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 27.5f
                strokeCap = android.graphics.Paint.Cap.BUTT
                setShader(borderShader)
            }
            canvas.drawPath(arcPath, borderPaint)
            
            val bgColors = if (night) {
                intArrayOf(0x00000000, 0x00000000, 0xB3000000.toInt(), 0x00000000, 0x00000000)
            } else {
                intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
            }
            val bgShader = android.graphics.SweepGradient(centerX, centerY, bgColors, positions)
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 26f
                strokeCap = android.graphics.Paint.Cap.BUTT
                setShader(bgShader)
            }
            canvas.drawPath(arcPath, bgPaint)
            
            val arcLength = Rc * (sweepAngle * Math.PI / 180.0).toFloat()
            val contentWidth = iconSize + spacing + textWidth
            val startOffset = (arcLength - contentWidth) / 2f
            
            val iconOffset = startOffset + iconSize / 2f
            val iconAngle = startAngle + (iconOffset / Rc) * (180f / Math.PI.toFloat())
            val thetaRad = iconAngle * (Math.PI / 180.0)
            val iconX = (centerX + Rc * Math.cos(thetaRad)).toFloat()
            val iconY = (centerY + Rc * Math.sin(thetaRad)).toFloat()
            
            drawManeuverArrow(canvas, iconX - iconSize/2f, iconY - iconSize/2f, iconSize, _ui.value.maneuverType, if (night) android.graphics.Color.WHITE else 0xFF1E2022.toInt(), 3.0f)
            
            val textStart = startOffset + iconSize + spacing
            tbtTextPaint.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawTextOnPath(distText, arcPath, textStart, 3.5f, tbtTextPaint)
        }
    }

    private fun triggerMediaOverlay() {
        showMediaOverlayUntilMs = System.currentTimeMillis() + 6000L
        _ui.update { it.copy(showMediaOverlay = true) }
        viewModelScope.launch {
            delay(6050L)
            val stillActive = System.currentTimeMillis() < showMediaOverlayUntilMs
            if (!stillActive) {
                _ui.update { it.copy(showMediaOverlay = false) }
            }
        }
    }

    private val callArcName by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E2022.toInt()   // dark text on the frosted arc, matching the music title
            textSize = 11f
            isFakeBoldText = true
        }
    }
    // Material phone glyphs (white) rasterized once for drawing inside the colored circles.
    private fun rasterizeIcon(resId: Int, sizePx: Int): Bitmap {
        val d = androidx.core.content.ContextCompat.getDrawable(getApplication(), resId)!!
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(Canvas(bmp))
        return bmp
    }
    private val callIconAccept by lazy { rasterizeIcon(com.example.opendash.R.drawable.ic_call, 28) }
    private val callIconEnd by lazy { rasterizeIcon(com.example.opendash.R.drawable.ic_call_end, 28) }

    private fun fmtCallDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Incoming/active-call overlay on the dash frame — the SAME top-arc treatment as the
     *  music overlay, laid over the live map (no dim). Incoming: red decline (left) + caller
     *  + green accept (right). Active: red end (left) + "caller • duration".
     *  Joystick mirrors the button sides: LEFT = decline/end, RIGHT = accept. */
    private fun drawCallOverlay(canvas: Canvas, width: Int, height: Int) {
        val call = CallInfoProvider.incomingCall.value ?: return
        val centerX = width / 2f
        val centerY = height / 2f
        val Rc = 134f
        val iconD = 22f
        val spacing = 7f
        val edgePadding = 14f

        val duration = if (!call.incoming && callActiveSinceMs > 0L)
            "  •  " + fmtCallDuration(System.currentTimeMillis() - callActiveSinceMs) else ""
        val label = (call.caller + duration).let { if (it.length > 20) it.take(19) + "…" else it }
        val textWidth = callArcName.measureText(label)

        // Content laid along the arc: leftIcon + gap + text (+ gap + rightIcon when incoming).
        val contentWidth = iconD + spacing + textWidth + (if (call.incoming) spacing + iconD else 0f)
        val totalLength = edgePadding * 2f + contentWidth
        val sweepAngle = ((totalLength / Rc) * (180f / Math.PI.toFloat())).coerceIn(45f, 150f)
        val startAngle = 270f - sweepAngle / 2f
        val endAngle = 270f + sweepAngle / 2f

        val arcPath = android.graphics.Path().apply {
            addArc(android.graphics.RectF(centerX - Rc, centerY - Rc, centerX + Rc, centerY + Rc), startAngle, sweepAngle)
        }
        // Frosted-white gradient arc, identical treatment to the music overlay so it reads over the map.
        val fStart = startAngle / 360f; val fCenter = 270f / 360f; val fEnd = endAngle / 360f
        val positions = floatArrayOf(0f, maxOf(0f, fStart), fCenter, fEnd, 1f)
        val borderColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
        canvas.drawPath(arcPath, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 30f; strokeCap = android.graphics.Paint.Cap.BUTT
            shader = android.graphics.SweepGradient(centerX, centerY, borderColors, positions)
        })
        val bgColors = intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xE6FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
        canvas.drawPath(arcPath, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 28f; strokeCap = android.graphics.Paint.Cap.BUTT
            shader = android.graphics.SweepGradient(centerX, centerY, bgColors, positions)
        })

        val arcLength = Rc * (sweepAngle * Math.PI / 180.0).toFloat()
        val startOffset = (arcLength - contentWidth) / 2f

        fun placeIcon(offset: Float, circleColor: Int, icon: Bitmap) {
            val ang = startAngle + (offset / Rc) * (180f / Math.PI.toFloat())
            val t = ang * (Math.PI / 180.0)
            val x = (centerX + Rc * Math.cos(t)).toFloat()
            val y = (centerY + Rc * Math.sin(t)).toFloat()
            canvas.drawCircle(x, y, iconD / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = circleColor })
            val s = iconD * 0.6f
            canvas.drawBitmap(icon, null, android.graphics.RectF(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f), null)
        }

        // Left = decline (incoming) / end (active), red.
        placeIcon(startOffset + iconD / 2f, 0xFFFF3B30.toInt(), callIconEnd)
        // Caller (+ duration) text, curved along the arc after the left icon.
        callArcName.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawTextOnPath(label, arcPath, startOffset + iconD + spacing, 4f, callArcName)
        // Right = accept, green — only while ringing.
        if (call.incoming) placeIcon(startOffset + contentWidth - iconD / 2f, 0xFF34C759.toInt(), callIconAccept)
    }

    // ── Monotonic route-progress tracker ────────────────────────────────────
    // Snapping to the GLOBALLY nearest segment makes the remaining distance flicker
    // (a winding route can pass near the start again, so it jumps to ~straight-line).
    // Instead we advance progress along the route, searching a window AHEAD of where
    // we already are, only re-acquiring globally if we're clearly off-route.
    @Volatile private var progressM = 0.0

    private data class NavState(
        val remainingM: Double, val nextTurnM: Double, val heading: Float, val offRoute: Boolean,
        val snapped: GeoPoint, val snapDist: Double,
        val nextManeuver: com.example.opendash.dash.nav.Maneuver?,
        val nextManeuver2: com.example.opendash.dash.nav.Maneuver?,   // the one AFTER next ("Then …")
    )

    private data class Match(val cum: Double, val dist: Double, val bearing: Float, val proj: GeoPoint)

    private fun trackProgress(r: Route, pos: GeoPoint): NavState {
        val geom = r.geometry
        val cum = r.cumulative

        fun search(lo: Double, hi: Double): Match {
            var bestDist = Double.MAX_VALUE; var bestCum = progressM; var bestBearing = 0f; var bestProj = pos
            for (i in 0 until geom.size - 1) {
                if (cum[i + 1] < lo || cum[i] > hi) continue
                val (proj, t) = GeoPoint.projectOnSegment(pos, geom[i], geom[i + 1])
                val d = GeoPoint.distMeters(pos, proj)
                if (d < bestDist) {
                    bestDist = d
                    bestCum = cum[i] + GeoPoint.distMeters(geom[i], geom[i + 1]) * t
                    bestBearing = GeoPoint.bearing(geom[i], geom[i + 1]).toFloat()
                    bestProj = proj
                }
            }
            return Match(bestCum, bestDist, bestBearing, bestProj)
        }

        var m = search(progressM - 60.0, progressM + 1000.0)
        if (m.dist > 80.0) {
            val g = search(0.0, r.totalMeters) // off-window → re-acquire globally
            if (g.dist < m.dist) m = g
        }
        progressM = maxOf(progressM - 25.0, m.cum) // mostly forward, tolerate small GPS slide

        val remaining = (r.totalMeters - progressM).coerceAtLeast(0.0)
        val upcoming = r.maneuvers.filter {
            it.cumulativeMeters > progressM + 1.0 && it.type != com.example.opendash.dash.nav.ManeuverType.DEPART
        }
        val nextMan = upcoming.getOrNull(0)
        val nextMan2 = upcoming.getOrNull(1)
        val nextTurn = nextMan?.let { (it.cumulativeMeters - progressM).coerceAtLeast(0.0) } ?: remaining
        return NavState(remaining, nextTurn, m.bearing, m.dist > 70.0, m.proj, m.dist, nextMan, nextMan2)
    }

    private fun updateThermal() {
        val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "OK"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> "Hot"
            else -> "Throttling"
        }
        if (label != _ui.value.thermal) _ui.value = _ui.value.copy(thermal = label)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** metres → (value, dash unit). <1 km → metres (0x30); else km×10 (0x10). */
    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters < 1000.0) meters.toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_METERS
        else (meters / 100.0).toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_KM_TENTHS

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    /** Road name out of a maneuver instruction ("Turn right onto Baif Rd" → "Baif Rd"). */
    private fun navRoadFromInstruction(instruction: String): String {
        for (sep in listOf(" onto ", " on ", " toward ", " towards ")) {
            val i = instruction.indexOf(sep, ignoreCase = true)
            if (i >= 0) return instruction.substring(i + sep.length).trim().ifBlank { instruction }
        }
        return instruction
    }

    private fun angleDelta(first: Float, second: Float): Float {
        val delta = (((first - second) % 360f) + 540f) % 360f - 180f
        return kotlin.math.abs(delta)
    }

    private fun teardown() {
        streamJob?.cancel(); streamJob = null
        stopMediaForwarding()
        encoder?.release(); encoder = null
        idleRenderer.release()
        frameBitmap?.recycle(); frameBitmap = null
    }

    private fun startMediaForwarding() {
        mediaObserveJob?.cancel()
        mediaObserveJob = viewModelScope.launch {
            launch {
                mediaInfo.nowPlaying.collect { media ->
                    session.updateNowPlaying(
                        media?.title,
                        media?.album.orEmpty(),
                        media?.artist.orEmpty(),
                    )
                }
            }
            launch {
                CallInfoProvider.incomingCall.collect { call ->
                    // Stamp the moment a call goes active (answered), for the on-dash duration.
                    callActiveSinceMs = when {
                        call == null || call.incoming -> 0L
                        callActiveSinceMs == 0L -> System.currentTimeMillis()
                        else -> callActiveSinceMs
                    }
                    session.updateCall(call?.caller)
                    if (call != null) {
                        runCatching { mediaInfo.pause() }
                    }
                }
            }
        }
    }

    private fun stopMediaForwarding() {
        mediaObserveJob?.cancel()
        mediaObserveJob = null
        session.updateNowPlaying(null, "", "")
        session.updateCall(null)
    }

    fun answerCall(call: IncomingCall) {
        val handled = call.answerIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!handled) callController.answer()
    }

    fun endCall(call: IncomingCall) {
        val handled = call.declineIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!handled) callController.hangup()
    }

    fun skipNext() {
        triggerMediaOverlay()
        mediaInfo.skipNext()
    }

    fun skipPrevious() {
        triggerMediaOverlay()
        mediaInfo.skipPrevious()
    }

    fun playPause() {
        val track = mediaInfo.nowPlaying.value
        if (track != null) {
            triggerMediaOverlay()
            if (track.isPlaying) {
                mediaInfo.pause()
            } else {
                mediaInfo.play()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaInfo.stop()
        stopRecording()        // save the in-progress ride if the app is closed mid-session
        teardown()
        session.disconnect()
        wifiManager.disconnect()
        location.stop()
        voice.shutdown()
    }
}
