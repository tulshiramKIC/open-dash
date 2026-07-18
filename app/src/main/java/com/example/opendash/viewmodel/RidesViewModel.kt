package com.example.opendash.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.Ride
import com.example.opendash.data.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recorded rides for RidesScreen. Rides are written by [DashViewModel] on disconnect. */
class RidesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SyncRepository.get(app)

    private val _rides = MutableStateFlow<List<Ride>>(emptyList())
    val rides = _rides.asStateFlow()

    init {
        reload()
        // Reflect new rides + cross-device syncs.
        viewModelScope.launch { repo.revision.collect { reload() } }
    }

    private fun reload() = viewModelScope.launch {
        _rides.value = withContext(Dispatchers.IO) { repo.rides() }
    }

    fun deleteRide(r: Ride) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.deleteRide(r) }
    }

    fun exportGpx(context: android.content.Context, route: com.example.opendash.dash.nav.Route, name: String) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val cleanName = name.replace(Regex("[^a-zA-Z0-9]"), "_")
                val f = java.io.File(context.cacheDir, "${cleanName}.gpx")
                val sb = StringBuilder()
                sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                sb.append("<gpx version=\"1.1\" creator=\"OpenDash\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                sb.append("  <trk>\n")
                sb.append("    <name>").append(name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</name>\n")
                sb.append("    <trkseg>\n")
                route.geometry.forEach { gp ->
                    sb.append("      <trkpt lat=\"").append(gp.lat).append("\" lon=\"").append(gp.lng).append("\" />\n")
                }
                sb.append("    </trkseg>\n")
                sb.append("  </trk>\n")
                sb.append("</gpx>\n")
                f.writeText(sb.toString())
                f
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val sendIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                type = "application/gpx+xml"
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val shareIntent = android.content.Intent.createChooser(sendIntent, "Export GPX Route")
            shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
        }
    }
}
