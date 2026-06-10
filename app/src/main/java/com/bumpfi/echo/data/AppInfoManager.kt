package com.bumpfi.echo.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bumpfi.echo.data.model.AppTrafficInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for retrieving information about installed apps.
 */
class AppInfoManager(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val appCache = mutableMapOf<String, AppTrafficInfo>()

    /**
     * Get all installed apps that can use the network.
     */
    suspend fun getInstalledApps(): List<AppTrafficInfo> = withContext(Dispatchers.IO) {
        val apps = mutableListOf<AppTrafficInfo>()

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        for (appInfo in installedApps) {
            // Skip system apps without internet permission (optional filter)
            if (!hasInternetPermission(appInfo.packageName)) continue

            val appName = appInfo.loadLabel(packageManager).toString()
            val appIcon = try {
                appInfo.loadIcon(packageManager)
            } catch (e: Exception) {
                null
            }

            apps.add(
                AppTrafficInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    appIcon = appIcon,
                    uid = appInfo.uid
                )
            )
        }

        apps.sortedBy { it.appName.lowercase() }
    }

    /**
     * Check if an app has internet permission.
     */
    private fun hasInternetPermission(packageName: String): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            packageInfo.requestedPermissions?.contains("android.permission.INTERNET") == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get app info by UID.
     */
    fun getAppByUid(uid: Int): AppTrafficInfo? {
        // Check cache first
        appCache.values.find { it.uid == uid }?.let { return it }

        // Look up by UID
        val packages = packageManager.getPackagesForUid(uid) ?: return null
        val packageName = packages.firstOrNull() ?: return null

        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }

            val trafficInfo = AppTrafficInfo(
                packageName = packageName,
                appName = appInfo.loadLabel(packageManager).toString(),
                appIcon = try { appInfo.loadIcon(packageManager) } catch (e: Exception) { null },
                uid = uid
            )

            appCache[packageName] = trafficInfo
            trafficInfo
        } catch (e: Exception) {
            null
        }
    }

}
