package com.example.opendash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.activity.SystemBarStyle
import com.example.opendash.data.SyncRepository
import com.example.opendash.ui.navigation.AppNavigation
import com.example.opendash.ui.theme.OpenDashTheme
import com.example.opendash.viewmodel.RouteViewModel

class MainActivity : ComponentActivity() {
    private val routeViewModel: RouteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )


        // The app runs fully local: on-device SQLite is the source of truth and there is no
        // sign-in. SyncRepository is used here only to read maintenance state for reminders.
        val sync = SyncRepository.get(applicationContext)

        // Maintenance reminders on app open (fires even if the Garage screen is never opened).
        Thread {
            com.example.opendash.data.MaintenanceNotifier.check(
                applicationContext, sync.maintenanceItems(), sync.odometer()
            )
        }.start()

        handleIntent(intent)
        setContent {
            OpenDashTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(routeViewModel = routeViewModel)
                    // Crash-SOS countdown renders over everything, whatever screen is up.
                    com.example.opendash.ui.components.CrashAlertOverlay()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A location-only group ride has no foreground service, so Android freezes it in
        // the background and the Realtime socket dies. Coming back to the app is the
        // moment to notice and silently rejoin, so the map shows live peers again.
        com.example.opendash.data.GroupRide.onAppForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            // Share-from-Google-Maps: text lives in EXTRA_TEXT, intent.data is null.
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "geo") {
                    routeViewModel.handleSharedText(uri.toString())
                } else {
                    routeViewModel.importGpxFile(this, uri)
                }
            }
        }
    }
}
