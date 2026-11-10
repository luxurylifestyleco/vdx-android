package com.vdx.capability

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log

/**
 * Reads device facts the OS will actually return. A failed read stays null.
 * GPU is left null: this process has no honest GPU query.
 * Installed applications are launcher-visible packages only. No broad package
 * query permission is requested, and the list is not padded.
 */
class AndroidDeviceProbe(private val context: Context) {
    fun snapshot(): ProbeSnapshot {
        val pm = context.packageManager
        return ProbeSnapshot(
            deviceId = readAndroidId(),
            platform = "android",
            platformVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            architecture = Build.SUPPORTED_ABIS.firstOrNull(),
            ramBytes = readRam(),
            storageBytes = readStorage(),
            cpu = null,
            gpu = null,
            sensors = mapOf(
                "camera" to hasFeature(pm, PackageManager.FEATURE_CAMERA_ANY),
                "microphone" to hasFeature(pm, PackageManager.FEATURE_MICROPHONE),
                "gps" to hasFeature(pm, PackageManager.FEATURE_LOCATION_GPS),
                "bluetooth" to hasFeature(pm, PackageManager.FEATURE_BLUETOOTH),
                "nfc" to hasFeature(pm, PackageManager.FEATURE_NFC),
            ),
            osCapabilities = mapOf(
                "accessibility" to accessibilityEnabled(),
            ),
            installedApplications = launchablePackages(pm),
            runtime = RuntimeState(
                networkAvailable = readNetwork(),
                batteryLow = readBatteryLow(),
                memoryPressure = null,
                executionRestricted = false,
            ),
        )
    }

    private fun readAndroidId(): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Exception) {
        Log.w(TAG, "android id unreadable", e)
        null
    }

    private fun readRam(): Long? = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem.takeIf { it > 0L }
    } catch (e: Exception) {
        Log.w(TAG, "ram unreadable", e)
        null
    }

    private fun readStorage(): Long? = try {
        val path = Environment.getDataDirectory() ?: return null
        StatFs(path.path).totalBytes.takeIf { it > 0L }
    } catch (e: Exception) {
        Log.w(TAG, "storage unreadable", e)
        null
    }

    private fun hasFeature(pm: PackageManager, feature: String): Boolean? = try {
        pm.hasSystemFeature(feature)
    } catch (e: Exception) {
        Log.w(TAG, "feature unreadable: $feature", e)
        null
    }

    private fun accessibilityEnabled(): Boolean? = try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        enabled?.contains("com.vdx") == true
    } catch (e: Exception) {
        Log.w(TAG, "accessibility unreadable", e)
        null
    }

    private fun launchablePackages(pm: PackageManager): List<String>? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
    } catch (e: Exception) {
        Log.w(TAG, "launcher packages unreadable", e)
        null
    }

    private fun readNetwork(): Boolean? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        Log.w(TAG, "network unreadable", e)
        null
    }

    private fun readBatteryLow(): Boolean? = try {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) null else (level * 100 / scale) <= 15
    } catch (e: Exception) {
        Log.w(TAG, "battery unreadable", e)
        null
    }

    private companion object {
        const val TAG = "DeviceProbe"
    }
}
