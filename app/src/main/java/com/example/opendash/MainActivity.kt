package com.example.opendash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.opendash.data.SyncRepository
import com.example.opendash.ui.navigation.AppNavigation
import com.example.opendash.ui.theme.OpenDashTheme
import com.example.opendash.viewmodel.RouteViewModel

class MainActivity : ComponentActivity() {
    private val routeViewModel: RouteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: return
                routeViewModel.handleSharedText(uri)
            }
        }
    }
}
