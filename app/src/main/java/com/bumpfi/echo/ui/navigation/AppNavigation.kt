package com.bumpfi.echo.ui.navigation

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bumpfi.echo.ui.screens.AppDetailScreen
import com.bumpfi.echo.ui.screens.DashboardScreen
import com.bumpfi.echo.ui.screens.SettingsScreen
import com.bumpfi.echo.ui.viewmodel.TrafficViewModel

/**
 * Navigation routes.
 */
sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object AppDetail : Screen("app_detail/{packageName}") {
        fun createRoute(packageName: String) = "app_detail/$packageName"
    }
    object Settings : Screen("settings")
}

/**
 * Main navigation component.
 */
@Composable
fun AppNavigation(
    viewModel: TrafficViewModel,
    onVpnPermissionNeeded: (Intent) -> Unit
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val selectedApp by viewModel.selectedApp.collectAsState()
    val autoStopEnabled by viewModel.autoStopEnabled.collectAsState()
    val context = LocalContext.current

    // Check permission when returning from settings
    LaunchedEffect(Unit) {
        viewModel.checkUsageStatsPermission()
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        composable(Screen.Dashboard.route) {
            // Re-check permission when this screen is shown
            LaunchedEffect(Unit) {
                viewModel.checkUsageStatsPermission()
            }

            DashboardScreen(
                uiState = uiState,
                onToggleRecording = {
                    val intent = viewModel.toggleRecording()
                    if (intent != null) {
                        onVpnPermissionNeeded(intent)
                    }
                },
                onAppClick = { app ->
                    viewModel.selectApp(app)
                    navController.navigate(Screen.AppDetail.createRoute(app.packageName))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onRequestUsageStatsPermission = {
                    context.startActivity(viewModel.getUsageStatsSettingsIntent())
                },
                formatBytes = viewModel::formatBytes,
                formatDuration = viewModel::formatDuration,
                autoStopEnabled = autoStopEnabled,
                onToggleAutoStop = viewModel::toggleAutoStop
            )
        }

        composable(Screen.AppDetail.route) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName")
            val app = selectedApp ?: viewModel.trafficRepository.getAppTraffic(packageName ?: "")

            if (app != null) {
                val connections = viewModel.getConnectionsForApp(app.packageName)

                AppDetailScreen(
                    app = app,
                    connections = connections,
                    onBackClick = {
                        viewModel.clearSelectedApp()
                        navController.popBackStack()
                    },
                    formatBytes = viewModel::formatBytes
                )
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onClearData = {
                    viewModel.clearData()
                },
                viewModel = viewModel
            )
        }
    }
}
