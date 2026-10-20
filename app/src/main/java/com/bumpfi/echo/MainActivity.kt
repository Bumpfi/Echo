package com.bumpfi.echo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.bumpfi.echo.ui.navigation.AppNavigation
import com.bumpfi.echo.ui.theme.EchoTheme
import com.bumpfi.echo.ui.viewmodel.TrafficViewModel

/**
 * MainActivity - Entry point for the Echo Traffic Analyzer app.
 *
 * This activity:
 * 1. Sets up Compose UI with Material 3 theming
 * 2. Handles VPN and notification permission requests
 * 3. Hosts the navigation graph
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: TrafficViewModel

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startRecording()
        }
        viewModel.updateVpnState()
    }

    // Recording notification is hidden on API 33+ unless granted; VPN works either way
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[TrafficViewModel::class.java]

        enableEdgeToEdge()

        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            EchoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        viewModel = viewModel,
                        onVpnPermissionNeeded = { intent ->
                            vpnPermissionLauncher.launch(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateVpnState()
    }
}