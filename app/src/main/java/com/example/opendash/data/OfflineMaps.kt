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

    /** Zoom detail of a downloaded area: overview → street level. Higher max = far more tiles. */
    const val MIN_ZOOM = 6.0
    const val MAX_ZOOM = 16.0

    data class Area(
        val id: Long,
        val name: String,
        val sizeBytes: Long,
        val complete: Boolean,
        val placesCount: Int = 0,
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
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status == null) { if (--pending == 0) publish(acc); return }
                            val count = appContext?.let { OpenDashDb.get(it).countOfflinePlaces(name) } ?: 0
                            acc += Area(region.id, name, status.completedResourceSize, status.isComplete, count)
                            if (--pending == 0) publish(acc)
                        }

                        override fun onError(error: String?) {
                            acc += Area(region.id, name, 0L, false, 0)
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

    /** Start downloading tiles for [bounds]. Progress is reported through [download]. */
    fun startDownload(context: Context, name: String, bounds: LatLngBounds) {
        val mgr = manager ?: return
        if (_download.value?.done == false) return  // one download at a time
        val cleanName = name.trim().ifBlank { "Offline area" }
        val pixelRatio = context.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(
            MAP_STYLE_URL, bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio,
        )
        val metadata = JSONObject().put(KEY_NAME, cleanName).toString().toByteArray(Charsets.UTF_8)
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
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                _download.value = Download(cleanName, 0, 0L, error = error ?: "Could not start download")
            }
        })
    }

    /** Stop an in-progress download and discard the partial region. */
    fun cancelDownload() {
        val region = activeRegion
        _download.value = null
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

    private fun nameOf(region: OfflineRegion): String = runCatching {
        JSONObject(String(region.metadata, Charsets.UTF_8)).optString(KEY_NAME, "Offline area")
    }.getOrDefault("Offline area")

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
