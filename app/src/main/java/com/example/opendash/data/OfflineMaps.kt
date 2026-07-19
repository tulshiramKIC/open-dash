package com.example.opendash.data

import android.content.Context
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline map downloads — pick an area, download only its tiles.
 *
 * Wraps MapLibre's [OfflineManager]. A downloaded region is a tile pyramid for a
 * [LatLngBounds] over a zoom range of the same [MAP_STYLE_URL] the live map uses, so
 * once downloaded the map serves those tiles from disk with no network. Single user,
 * single style — no multi-style bookkeeping.
 */
object OfflineMaps {

    /** Must match the style [com.example.opendash.ui.components.OpenDashMap] loads, or downloads won't be reused. */
    const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /**
     * Esri imagery tile template — must stay byte-identical to the live map's satellite
     * source ([com.example.opendash.ui.components.OpenDashMap]), so tiles downloaded for a
     * region are served to satellite mode from the shared offline cache with no extra wiring.
     */
    private const val ESRI_TILE_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** Zoom detail of a downloaded area: overview → street level. Higher max = far more tiles. */
    const val MIN_ZOOM = 6.0
    const val MAX_ZOOM = 16.0

    data class Area(
        val id: Long,
        val name: String,
        val sizeBytes: Long,
        val complete: Boolean,
        val placesCount: Int = 0,
        val satellite: Boolean = false,
        val bounds: LatLngBounds? = null,
    )

    data class Download(
        val name: String,
        val percent: Int,
        val sizeBytes: Long,
        val done: Boolean = false,
        val error: String? = null,
    )

    private val _areas = MutableStateFlow<List<Area>>(emptyList())
    val areas = _areas.asStateFlow()

    private val _download = MutableStateFlow<Download?>(null)
    val download = _download.asStateFlow()

    private var manager: OfflineManager? = null
    // Live region handles keyed by id, so delete/cancel can act on them.
    private val regions = mutableMapOf<Long, OfflineRegion>()
    private var activeRegion: OfflineRegion? = null
    // Kept so the indexer can open the DB and clean up on delete.
    private var appContext: android.content.Context? = null
    // Bounds of the region currently being downloaded — handed to the indexer on completion.
    private var downloadBounds: LatLngBounds? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (manager != null) return
        appContext = context.applicationContext
        MapLibre.getInstance(context)
        manager = OfflineManager.getInstance(context.applicationContext)
        refresh()
    }

    /** Reload the list of downloaded areas (each region's size comes from an async status query). */
    fun refresh() {
        val mgr = manager ?: return
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions ?: emptyArray()
                regions.clear()
                if (list.isEmpty()) {
                    _areas.value = emptyList()
                    return
                }
                val acc = mutableListOf<Area>()
                var pending = list.size
                list.forEach { region ->
                    regions[region.id] = region
                    val name = nameOf(region)
                    val sat = isSatellite(region)
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status == null) { if (--pending == 0) publish(acc); return }
                            val count = appContext?.let { OpenDashDb.get(it).countOfflinePlaces(name) } ?: 0
                            val bounds = boundsOf(region)
                            acc += Area(region.id, name, status.completedResourceSize, status.isComplete, count, sat, bounds)
                            if (--pending == 0) publish(acc)
                        }

                        override fun onError(error: String?) {
                            val bounds = boundsOf(region)
                            acc += Area(region.id, name, 0L, false, 0, sat, bounds)
                            if (--pending == 0) publish(acc)
                        }
                    })
                }
            }

            override fun onError(error: String) {
                _areas.value = emptyList()
            }
        })
    }

    private fun publish(acc: List<Area>) {
        _areas.value = acc.sortedBy { it.name.lowercase() }
        // Back-fill any region that was downloaded before offline search existed.
        indexExistingRegions(acc)
    }

    /**
     * For each already-downloaded region that has no entries in [offline_place], kick off
     * an Overpass fetch in the background. Runs silently — no UI update needed.
     * Safe to call multiple times; the DB check prevents re-indexing already-indexed regions.
     */
    private fun indexExistingRegions(acc: List<Area>) {
        val ctx = appContext ?: return
        val db = OpenDashDb.get(ctx)
        acc.filter { it.complete }.forEach { area ->
            // Only index if this region has no places yet.
            if (db.countOfflinePlaces(area.name) == 0) {
                val region = regions[area.id] ?: return@forEach
                val bounds = boundsOf(region) ?: return@forEach
                scope.launch {
                    OfflinePlaceIndexer.index(db, area.name, bounds)
                    refresh()
                }
            }
        }
    }

    /** Extract the geographic bounds stored inside an [OfflineRegion]'s definition. */
    private fun boundsOf(region: OfflineRegion): LatLngBounds? =
        (region.definition as? OfflineTilePyramidRegionDefinition)?.bounds

    /**
     * Merge Esri imagery into the live vector style, so a single offline region downloads
     * both tile pyramids together (like Google offline maps). Returns the style JSON bytes,
     * or null if the base style couldn't be fetched.
     */
    private fun buildCombinedStyle(): ByteArray? = runCatching {
        val base = java.net.URL(MAP_STYLE_URL).openStream().use { String(it.readBytes(), Charsets.UTF_8) }
        val style = JSONObject(base)
        style.getJSONObject("sources").put(
            "esri-imagery",
            JSONObject()
                .put("type", "raster")
                .put("tiles", org.json.JSONArray().put(ESRI_TILE_URL))
                .put("tileSize", 256)
                .put("maxzoom", 19),
        )
        // The downloader only fetches tiles for sources a layer references — tuck the
        // raster layer at the very bottom, where the vector background fully covers it.
        val old = style.getJSONArray("layers")
        val layers = org.json.JSONArray().put(
            JSONObject().put("id", "esri-imagery-dl").put("type", "raster").put("source", "esri-imagery"),
        )
        for (i in 0 until old.length()) layers.put(old.get(i))
        style.put("layers", layers)
        style.toString().toByteArray(Charsets.UTF_8)
    }.getOrNull()

    // ── Loopback style server ────────────────────────────────────────────
    // MapLibre's offline engine only fetches http(s) style URLs (file:// and asset://
    // stall the download at 0%), so the combined style is served from a short-lived
    // localhost socket that lives for the duration of the download.
    private var styleServer: java.net.ServerSocket? = null

    private fun serveStyle(json: ByteArray): String? = runCatching {
        stopStyleServer()
        // Bind the IPv4 loopback explicitly — getLoopbackAddress() can resolve to ::1,
        // and MapLibre connects to 127.0.0.1, which then gets refused.
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        styleServer = server
        scope.launch {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                scope.launch {
                    runCatching {
                        sock.use { s ->
                            // Drain the request headers before answering.
                            val reader = s.getInputStream().bufferedReader()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) break
                            }
                            val head = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${json.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            s.getOutputStream().apply {
                                write(head.toByteArray(Charsets.US_ASCII)); write(json); flush()
                            }
                        }
                    }
                }
            }
        }
        "http://127.0.0.1:${server.localPort}/style.json"
    }.getOrNull()

    private fun stopStyleServer() {
        runCatching { styleServer?.close() }
        styleServer = null
    }

    /**
     * Start downloading tiles for [bounds]. Progress is reported through [download].
     * With [includeSatellite], Esri imagery for the same bounds is bundled into the SAME
     * region — one download, one area row, both map styles offline.
     */
    fun startDownload(context: Context, name: String, bounds: LatLngBounds, includeSatellite: Boolean = false) {
        if (_download.value?.done == false) return  // one download at a time
        val cleanName = name.trim().ifBlank { "Offline area" }
        val pixelRatio = context.resources.displayMetrics.density
        if (!includeSatellite) {
            createRegion(cleanName, bounds, pixelRatio, MAP_STYLE_URL, withSatellite = false)
            return
        }
        // Building the combined style needs a network fetch — off the main thread, with
        // the banner already showing so the tap feels instant.
        _download.value = Download(cleanName, 0, 0L)
        scope.launch {
            val styleUrl = buildCombinedStyle()?.let { serveStyle(it) }
            withContext(Dispatchers.Main) {
                if (styleUrl == null) {
                    _download.value = Download(
                        cleanName, 0, 0L,
                        error = "Couldn't prepare the satellite style — check your connection.",
                    )
                } else {
                    createRegion(cleanName, bounds, pixelRatio, styleUrl, withSatellite = true)
                }
            }
        }
    }

    private fun createRegion(
        cleanName: String,
        bounds: LatLngBounds,
        pixelRatio: Float,
        styleUrl: String,
        withSatellite: Boolean,
    ) {
        val mgr = manager ?: return
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio,
        )
        val metadata = JSONObject()
            .put(KEY_NAME, cleanName)
            .put(KEY_KIND, if (withSatellite) KIND_MAP_SAT else KIND_MAP)
            .toString().toByteArray(Charsets.UTF_8)
        _download.value = Download(cleanName, 0, 0L)
        downloadBounds = bounds

        mgr.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                activeRegion = offlineRegion
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val required = status.requiredResourceCount
                        val percent = if (status.isRequiredResourceCountPrecise && required > 0) {
                            ((status.completedResourceCount * 100.0) / required).toInt().coerceIn(0, 100)
                        } else 0
                        if (status.isComplete) {
                            _download.value = Download(cleanName, 100, status.completedResourceSize, done = true)
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            activeRegion = null
                            stopStyleServer()
                            // Index POIs for offline search while the phone is still online.
                            val ctx = appContext
                            val bbox = downloadBounds
                            if (ctx != null && bbox != null) {
                                scope.launch {
                                    OfflinePlaceIndexer.index(
                                        OpenDashDb.get(ctx), cleanName, bbox,
                                    )
                                    refresh()
                                }
                            }
                            downloadBounds = null
                            refresh()
                        } else {
                            _download.value = Download(cleanName, percent, status.completedResourceSize)
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        _download.value = Download(cleanName, 0, 0L, error = error.message ?: error.reason)
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        activeRegion = null
                        stopStyleServer()
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        _download.value = Download(
                            cleanName, 0, 0L,
                            error = "Area too large. Zoom in or pick a smaller area (limit $limit tiles).",
                        )
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        // Roll back the partial region so it doesn't linger.
                        offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {}
                            override fun onError(error: String) {}
                        })
                        activeRegion = null
                        stopStyleServer()
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                _download.value = Download(cleanName, 0, 0L, error = error ?: "Could not start download")
                stopStyleServer()
            }
        })
    }

    /** Stop an in-progress download and discard the partial region. */
    fun cancelDownload() {
        val region = activeRegion
        _download.value = null
        stopStyleServer()
        if (region == null) return
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { refresh() }
            override fun onError(error: String) {}
        })
        activeRegion = null
    }

    /** Dismiss a finished/failed download banner. */
    fun clearDownloadStatus() {
        if (_download.value?.done != false) _download.value = null
    }

    fun delete(area: Area) {
        val region = regions[area.id] ?: return
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                regions.remove(area.id)
                _areas.value = _areas.value.filterNot { it.id == area.id }
                // Clean up any place-index rows for this region.
                val ctx = appContext ?: return
                scope.launch { OpenDashDb.get(ctx).deleteOfflinePlacesByRegion(area.name) }
            }

            override fun onError(error: String) {}
        })
    }

    private const val KEY_NAME = "name"
    private const val KEY_KIND = "kind"
    private const val KIND_MAP = "map"
    private const val KIND_MAP_SAT = "map+sat"

    private fun nameOf(region: OfflineRegion): String = runCatching {
        JSONObject(String(region.metadata, Charsets.UTF_8)).optString(KEY_NAME, "Offline area")
    }.getOrDefault("Offline area")

    private fun isSatellite(region: OfflineRegion): Boolean = runCatching {
        JSONObject(String(region.metadata, Charsets.UTF_8)).optString(KEY_KIND, KIND_MAP) != KIND_MAP
    }.getOrDefault(false)

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
