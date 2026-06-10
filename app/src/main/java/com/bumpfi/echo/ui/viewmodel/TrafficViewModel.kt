package com.bumpfi.echo.ui.viewmodel

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bumpfi.echo.data.AppInfoManager
import com.bumpfi.echo.data.TrafficRepository
import com.bumpfi.echo.data.export.CsvExporter
import com.bumpfi.echo.data.model.AppTrafficInfo
import com.bumpfi.echo.data.model.ConnectionInfo
import com.bumpfi.echo.data.SelectedAppsStore
import com.bumpfi.echo.vpn.TrafficAnalyzerVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Main ViewModel for the Echo Traffic Analyzer app.
 */
class TrafficViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TrafficViewModel"
        private const val AUTO_STOP_DURATION_MS = 6 * 60 * 1000L // 6 minutes
    }

    // Repository for traffic data
    val trafficRepository = TrafficRepository()

    // App info manager
    val appInfoManager = AppInfoManager(application)

    // UI State
    private val _uiState = MutableStateFlow(TrafficUiState())
    val uiState: StateFlow<TrafficUiState> = _uiState.asStateFlow()

    // VPN running state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Selected app for detail view
    private val _selectedApp = MutableStateFlow<AppTrafficInfo?>(null)
    val selectedApp: StateFlow<AppTrafficInfo?> = _selectedApp.asStateFlow()

    // Usage stats permission state
    private val _hasUsageStatsPermission = MutableStateFlow(false)
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission.asStateFlow()

    // Auto-stop after 6 minutes (for consistent measurement sessions)
    private val _autoStopEnabled = MutableStateFlow(false)
    val autoStopEnabled: StateFlow<Boolean> = _autoStopEnabled.asStateFlow()

    // Selected apps store
    val selectedAppsStore = SelectedAppsStore()
    val selectedPackages = selectedAppsStore.selectedPackages

    // CSV Exporter for scientific data export
    val csvExporter = CsvExporter(application)

    init {
        // Initialize VPN service references
        TrafficAnalyzerVpnService.trafficRepository = trafficRepository
        TrafficAnalyzerVpnService.appInfoManager = appInfoManager
        TrafficAnalyzerVpnService.selectedAppsStore = selectedAppsStore

        // Start observing traffic data
        observeTrafficData()
        updateVpnState()
        checkUsageStatsPermission()
    }

    /**
     * Check if usage stats permission is granted.
     */
    fun checkUsageStatsPermission(): Boolean {
        val context = getApplication<Application>()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        val hasPermission = mode == AppOpsManager.MODE_ALLOWED
        _hasUsageStatsPermission.value = hasPermission
        _uiState.value = _uiState.value.copy(hasUsageStatsPermission = hasPermission)
        return hasPermission
    }

    /**
     * Get intent to open usage stats settings.
     */
    fun getUsageStatsSettingsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun observeTrafficData() {
        viewModelScope.launch {
            trafficRepository.appTrafficList.collect { apps ->
                _uiState.value = _uiState.value.copy(
                    appTrafficList = apps
                )
            }
        }

        viewModelScope.launch {
            trafficRepository.totalBytesSent.collect { bytes ->
                _uiState.value = _uiState.value.copy(
                    totalBytesSent = bytes
                )
            }
        }

        viewModelScope.launch {
            trafficRepository.totalBytesReceived.collect { bytes ->
                _uiState.value = _uiState.value.copy(
                    totalBytesReceived = bytes
                )
            }
        }

        viewModelScope.launch {
            trafficRepository.connectionCount.collect { count ->
                _uiState.value = _uiState.value.copy(
                    connectionCount = count
                )
            }
        }

        // Update session duration periodically + auto-stop check
        viewModelScope.launch {
            while (isActive) {
                if (TrafficAnalyzerVpnService.isRunning) {
                    val duration = trafficRepository.getSessionDuration()
                    _uiState.value = _uiState.value.copy(
                        sessionDuration = duration
                    )

                    // Auto-stop after 6 minutes if enabled
                    if (_autoStopEnabled.value && duration >= AUTO_STOP_DURATION_MS) {
                        Log.d(TAG, "Auto-stop triggered at ${duration}ms")
                        stopRecording()
                    }
                }
                delay(1000)
            }
        }
    }

    /**
     * Updates VPN running state.
     */
    fun updateVpnState() {
        val isRunning = TrafficAnalyzerVpnService.isRunning
        _isRecording.value = isRunning
        _uiState.value = _uiState.value.copy(
            isRecording = isRunning
        )
    }

    /**
     * Prepares VPN - returns Intent if permission needed.
     */
    fun prepareVpn(): Intent? {
        return VpnService.prepare(getApplication())
    }

    /**
     * Starts traffic recording.
     */
    fun startRecording() {
        val context = getApplication<Application>()
        try {
            val intent = Intent(context, TrafficAnalyzerVpnService::class.java).apply {
                action = TrafficAnalyzerVpnService.ACTION_START
            }
            context.startForegroundService(intent)

            _isRecording.value = true
            _uiState.value = _uiState.value.copy(
                isRecording = true,
                statusMessage = "Recording traffic..."
            )

            Log.d(TAG, "VPN service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}")
            _uiState.value = _uiState.value.copy(
                statusMessage = "Failed to start: ${e.message}"
            )
        }
    }

    /**
     * Stops traffic recording.
     */
    fun stopRecording() {
        val context = getApplication<Application>()
        try {
            val intent = Intent(context, TrafficAnalyzerVpnService::class.java).apply {
                action = TrafficAnalyzerVpnService.ACTION_STOP
            }
            context.startService(intent)

            _isRecording.value = false
            _uiState.value = _uiState.value.copy(
                isRecording = false,
                statusMessage = "Recording stopped"
            )

            Log.d(TAG, "VPN service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN: ${e.message}")
        }
    }

    /**
     * Toggles recording on/off.
     * @return Intent if VPN permission needed, null otherwise
     */
    fun toggleRecording(): Intent? {
        return if (TrafficAnalyzerVpnService.isRunning) {
            stopRecording()
            null
        } else {
            val permissionIntent = prepareVpn()
            if (permissionIntent == null) {
                startRecording()
            }
            permissionIntent
        }
    }

    /**
     * Toggle auto-stop after 6 minutes.
     * Can only be changed while not recording.
     */
    fun toggleAutoStop() {
        if (!TrafficAnalyzerVpnService.isRunning) {
            _autoStopEnabled.value = !_autoStopEnabled.value
        }
    }

    /**
     * Select an app to view details.
     */
    fun selectApp(app: AppTrafficInfo) {
        _selectedApp.value = app
    }

    /**
     * Clear selected app.
     */
    fun clearSelectedApp() {
        _selectedApp.value = null
    }

    /**
     * Get connections for selected app.
     */
    fun getConnectionsForApp(packageName: String): List<ConnectionInfo> {
        return trafficRepository.getConnectionsForApp(packageName)
    }

    /**
     * Clear all recorded data including cached CSV exports.
     */
    fun clearData() {
        trafficRepository.clearAll()
        _uiState.value = TrafficUiState()

        // Delete old CSV exports so no stale data remains
        try {
            val externalDir = getApplication<Application>().getExternalFilesDir(null)
            externalDir?.listFiles()?.filter { it.name.endsWith(".csv") }?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    /**
     * Format bytes to human-readable string.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Format duration to human-readable string.
     */
    fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%d:%02d", minutes, seconds % 60)
            else -> String.format("0:%02d", seconds)
        }
    }

    /**
     * Update selected packages.
     */
    fun setSelectedPackages(packages: Set<String>) {
        selectedAppsStore.setSelectedPackages(packages)
    }

    /**
     * Export all data including raw connection data, DNS queries, and destinations.
     * Creates multiple CSV files suitable for scientific analysis:
     * - metrics: Aggregated per-app statistics
     * - connections: Raw connection data with IP addresses, ports, hostnames
     * - dns: Raw DNS queries with domains and response IPs
     * - destinations: Aggregated destination summary per app
     *
     * @param scenario Measurement scenario name
     * @param run Run number
     * @param metadataProvider Optional function to provide (category, licenseType) for each app
     * @return Map of file type to file path
     */
    fun exportAllDataToCsv(
        scenario: String = "Default",
        run: Int = 1,
        metadataProvider: ((AppTrafficInfo) -> Pair<String, String>)? = null
    ): Map<String, String> {
        val apps = trafficRepository.appTrafficList.value
        val sessionDurationMs = trafficRepository.getSessionDuration()
        val sessionStartTime = trafficRepository.getSessionStartTime()

        return csvExporter.exportAllDataToCsv(
            apps = apps,
            sessionDurationMs = sessionDurationMs,
            sessionStartTime = sessionStartTime,
            metadataProvider = metadataProvider,
            scenario = scenario,
            run = run
        )
    }

}

/**
 * UI state for the traffic analyzer.
 */
data class TrafficUiState(
    val isRecording: Boolean = false,
    val appTrafficList: List<AppTrafficInfo> = emptyList(),
    val totalBytesSent: Long = 0,
    val totalBytesReceived: Long = 0,
    val connectionCount: Int = 0,
    val sessionDuration: Long = 0,
    val statusMessage: String = "Tap to start recording",
    val hasUsageStatsPermission: Boolean = false
) {
    val totalBytes: Long get() = totalBytesSent + totalBytesReceived
    val appCount: Int get() = appTrafficList.size
}
