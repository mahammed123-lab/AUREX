package com.example.connectors

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * DeviceControlBridge - Deep OS-level device control for AUREX/JARVIS.
 *
 * Provides actual toggle capabilities (not just opening settings panels) via:
 * 1. WRITE_SECURE_SETTINGS permission (via ADB grant) - direct system settings manipulation
 * 2. AccessibilityService - quick settings tile automation
 * 3. System APIs - for flashlight, volume, brightness, etc.
 *
 * For WiFi/Bluetooth/Airplane mode toggling without opening panels:
 * - Grant WRITE_SECURE_SETTINGS: adb shell pm grant com.aistudio.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS
 * - Enable AUREX Accessibility Service in Settings > Accessibility
 */
object DeviceControlBridge {

    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    fun hasWriteSecureSettings(context: Context): Boolean = try {
        ContextCompat.checkSelfPermission(context, WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    // ── QUICK SETTINGS TILE AUTOMATION (via AccessibilityService) ──

    /**
     * Post an honest corrective toast when the accessibility-verified toggle
     * did not actually change the tile state. Called on the main thread by
     * the service's onDone callback.
     */
    private fun reportVerified(context: Context, what: String, ok: Boolean, newState: Boolean?) {
        if (ok) return
        try {
            android.widget.Toast.makeText(
                context.applicationContext,
                if (newState != null) "$what is ${if (newState) "on" else "off"} - unchanged"
                else "$what toggle failed - toggle it in Quick Settings",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Throwable) {
            // ignore - never crash on a toast
        }
    }

    fun toggleWifi(context: Context, enable: Boolean? = null): String {
        val acc = com.example.voice.AurexAccessibilityService.instance
        val targetState = enable ?: !isWifiOn(context)
        if (acc != null) {
            acc.toggleWifiTile(targetState) { ok, newState ->
                reportVerified(context, "Wi-Fi", ok, newState)
            }
            return if (targetState) "Toggling Wi-Fi on" else "Toggling Wi-Fi off"
        }
        return if (hasWriteSecureSettings(context)) {
            setWifi(context, targetState)
        } else {
            openWifiPanel(context)
        }
    }

    fun toggleBluetooth(context: Context, enable: Boolean? = null): String {
        val acc = com.example.voice.AurexAccessibilityService.instance
        val targetState = enable ?: !isBluetoothOn(context)
        if (acc != null) {
            acc.toggleBluetoothTile(targetState) { ok, newState ->
                reportVerified(context, "Bluetooth", ok, newState)
            }
            return if (targetState) "Toggling Bluetooth on" else "Toggling Bluetooth off"
        }
        return if (hasWriteSecureSettings(context)) {
            setBluetooth(context, targetState)
        } else {
            openBluetoothPanel(context)
        }
    }

    fun toggleAirplaneMode(context: Context, enable: Boolean? = null): String {
        val acc = com.example.voice.AurexAccessibilityService.instance
        val targetState = enable ?: !isAirplaneModeOn(context)
        if (acc != null) {
            acc.toggleAirplaneTile(targetState) { ok, newState ->
                reportVerified(context, "Airplane mode", ok, newState)
            }
            return if (targetState) "Toggling Airplane Mode on" else "Toggling Airplane Mode off"
        }
        return if (hasWriteSecureSettings(context)) {
            toggleAirplaneDirect(context, targetState)
        } else {
            openAirplaneSettings(context)
        }
    }

    fun toggleMobileData(context: Context, enable: Boolean? = null): String {
        // Route 1: quick-settings tile via AccessibilityService - verified by
        // the tile's checked state, and not blocked by Realme's Permission
        // Monitoring the way silent Settings.Global writes are.
        val acc = com.example.voice.AurexAccessibilityService.instance
        if (acc != null) {
            val targetState = enable ?: !isMobileDataOn(context)
            acc.toggleDataTile(targetState) { ok, newState ->
                reportVerified(context, "Mobile data", ok, newState)
            }
            return if (targetState) "Toggling mobile data on" else "Toggling mobile data off"
        }
        // Route 2: WRITE_SECURE_SETTINGS (true silent toggle) - blocked on
        // Realme until the user disables Permission Monitoring.
        if (hasWriteSecureSettings(context)) {
            try {
                val targetState = enable ?: !isMobileDataOn(context)
                val ok = Settings.Global.putInt(
                    context.contentResolver,
                    "mobile_data",
                    if (targetState) 1 else 0
                )
                if (ok) return if (targetState) "Mobile data enabled" else "Mobile data disabled"
            } catch (e: Exception) {
                // fall through
            }
        }
        return "Mobile data toggle needs either the AUREX Accessibility service (enable it in Settings > Accessibility) or run: adb shell pm grant com.aistudio.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS"
    }

    // ── DIRECT SYSTEM SETINGS MANIPULATION (requires WRITE_SECURE_SETTINGS) ──

    private fun isWifiOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 1) == 1
    } catch (e: Exception) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.isWifiEnabled
    }

    private fun isBluetoothOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, 1) == 1
    } catch (e: Exception) {
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    }

    private fun isMobileDataOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
    } catch (e: Exception) {
        true
    }

    fun setWifi(context: Context, enable: Boolean): String = try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Method 1: WifiManager.setWifiEnabled (deprecated in API 29 but still functional
        // for apps holding WRITE_SECURE_SETTINGS on most OEM builds)
        var ok = try {
            wifiManager.setWifiEnabled(enable)
        } catch (e: Exception) {
            false
        }

        // Method 2: Settings.Global as backup (respected when the caller holds
        // WRITE_SECURE_SETTINGS)
        if (!ok) {
            try {
                ok = Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.WIFI_ON,
                    if (enable) 1 else 0
                )
            } catch (e: Exception) {
                // ignore
            }
        }

        if (ok) {
            if (enable) "Wi-Fi enabled" else "Wi-Fi disabled"
        } else {
            "Wi-Fi toggle attempted - check Quick Settings"
        }
    } catch (e: Exception) {
        "Failed to toggle Wi-Fi directly: ${e.message}"
    }

    fun setBluetooth(context: Context, enable: Boolean): String = try {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) {
            if (enable) adapter.enable() else adapter.disable()
        }

        // Also try Settings.Global as backup
        try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.BLUETOOTH_ON,
                if (enable) 1 else 0
            )
        } catch (e: Exception) {
            // ignore
        }

        if (enable) "Bluetooth enabled" else "Bluetooth disabled"
    } catch (e: Exception) {
        "Failed to toggle Bluetooth directly: ${e.message}"
    }

    fun toggleWifiDirect(context: Context): String =
        setWifi(context, !isWifiOn(context))

    fun toggleBluetoothDirect(context: Context): String =
        setBluetooth(context, !isBluetoothOn(context))

    fun toggleAirplaneDirect(context: Context, enable: Boolean? = null): String = try {
        val currentlyOn = isAirplaneModeOn(context)
        val targetState = enable ?: !currentlyOn

        Settings.Global.putInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            if (targetState) 1 else 0
        )
        context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))

        if (targetState) "Airplane mode enabled" else "Airplane mode disabled"
    } catch (e: Exception) {
        "Failed to toggle Airplane mode directly: ${e.message}"
    }

    fun isAirplaneModeOn(context: Context): Boolean = try {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) == 1
    } catch (e: Exception) {
        false
    }

    // ── FALLBACK: OPEN SETTINGS PANELS ──

    private fun openWifiPanel(context: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.startActivity(Intent("android.settings.panel.action.INTERNET").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Opened Wi-Fi panel - tap to toggle"
        } else {
            openWifiSettings(context)
        }
    } catch (e: Exception) {
        openWifiSettings(context)
    }

    fun openWifiSettings(context: Context): String = try {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        "Opened Wi-Fi settings"
    } catch (e: Exception) {
        "Could not open Wi-Fi settings"
    }

    private fun openBluetoothPanel(context: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.startActivity(Intent("android.settings.panel.action.BLUETOOTH").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Opened Bluetooth panel - tap to toggle"
        } else {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Opened Bluetooth settings"
        }
    } catch (e: Exception) {
        "Could not open Bluetooth settings"
    }

    private fun openAirplaneSettings(context: Context): String = try {
        context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        "Opened Airplane mode settings"
    } catch (e: Exception) {
        "Could not open Airplane mode settings"
    }

    /**
     * Deep-links to the system Accessibility settings page where the user
     * enables the AUREX accessibility service. Realme UI hides the list
     * entry under different names, so also expose the service checker.
     */
    fun openAccessibilitySettings(context: Context): String = try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        "Opened Accessibility settings"
    } catch (e: Exception) {
        "Could not open Accessibility settings"
    }

    /** True when the AUREX accessibility service is enabled and connected. */
    fun isAccessibilityReady(context: Context): Boolean =
        com.example.voice.AurexAccessibilityService.isEnabled

    // ── DEEP OS CONTROLS (Work without special permissions) ──

    /** Flashlight / Torch control */
    fun toggleFlashlight(context: Context): String {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                val chars = cameraManager.getCameraCharacteristics(it)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight available on this device"

            val currentlyOn = try {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } catch (e: Exception) { false }

            cameraManager.setTorchMode(cameraId, !currentlyOn)
            return if (currentlyOn) "Flashlight off" else "Flashlight on"
        } catch (e: Exception) {
            return "Failed to toggle flashlight: ${e.message}"
        }
    }

    fun setFlashlight(context: Context, enable: Boolean): String {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                val chars = cameraManager.getCameraCharacteristics(it)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight available"

            cameraManager.setTorchMode(cameraId, enable)
            return if (enable) "Flashlight on" else "Flashlight off"
        } catch (e: Exception) {
            return "Failed to set flashlight: ${e.message}"
        }
    }

    /** Volume control (0-100) */
    fun setVolume(context: Context, level: Int, stream: Int = AudioManager.STREAM_MUSIC): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(stream)
            val targetVolume = (level.coerceIn(0, 100) * maxVolume / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(stream, targetVolume, AudioManager.FLAG_SHOW_UI)
            return "Volume set to $level%"
        } catch (e: Exception) {
            return "Failed to set volume: ${e.message}"
        }
    }

    fun adjustVolume(context: Context, delta: Int, stream: Int = AudioManager.STREAM_MUSIC): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(stream)
            val maxVolume = audioManager.getStreamMaxVolume(stream)
            val target = (current + delta).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            return "Volume ${if (delta > 0) "increased" else "decreased"} to ${(target * 100 / maxVolume)}%"
        } catch (e: Exception) {
            return "Failed to adjust volume: ${e.message}"
        }
    }

    fun getVolume(context: Context, stream: Int = AudioManager.STREAM_MUSIC): Int {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            return (current * 100 / max).coerceIn(0, 100)
        } catch (e: Exception) {
            return 50
        }
    }

    /** Screen brightness (0-255 or 0-100%) */
    fun setBrightness(context: Context, level: Int): String {
        try {
            val level255 = level.coerceIn(0, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            return "Brightness set to ${(level255 * 100 / 255)}%"
        } catch (e: Exception) {
            return "Failed to set brightness: ${e.message}"
        }
    }

    fun getBrightness(context: Context): Int {
        try {
            return Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) {
            return 128
        }
    }

    /** Screen timeout */
    fun setScreenTimeout(context: Context, milliseconds: Long): String {
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, milliseconds.toInt())
            return "Screen timeout set to ${milliseconds / 1000}s"
        } catch (e: Exception) {
            return "Failed to set screen timeout: ${e.message}"
        }
    }

    /** Do Not Disturb */
    fun toggleDoNotDisturb(context: Context): String {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val currentPolicy = notificationManager.currentInterruptionFilter
            val isDnd = currentPolicy == android.app.NotificationManager.INTERRUPTION_FILTER_NONE

            // Requires WRITE_SECURE_SETTINGS or Do Not Disturb permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val policy = android.app.NotificationManager.Policy(
                    if (isDnd) android.app.NotificationManager.INTERRUPTION_FILTER_ALL else android.app.NotificationManager.INTERRUPTION_FILTER_NONE,
                    0, 0
                )
                notificationManager.setNotificationPolicy(policy)
            }
            return if (isDnd) "Do Not Disturb off" else "Do Not Disturb on"
        } catch (e: Exception) {
            return "Failed to toggle Do Not Disturb: ${e.message}"
        }
    }

    /** Battery info */
    fun getBatteryInfo(context: Context): String {
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val health = intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1
            val technology = intent?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
            val temperature = (intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10f

            val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val statusStr = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
            val healthStr = when (health) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                else -> "Unknown"
            }

            return "Battery: $percentage% | $statusStr | Health: $healthStr | Temp: ${temperature}°C | Tech: $technology"
        } catch (e: Exception) {
            return "Failed to get battery info: ${e.message}"
        }
    }

    /** Launch app by package name or label */
    fun launchApp(context: Context, query: String): String {
        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(query)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Launched $query"
            }

            // Search by label
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.contains(query.lowercase())) {
                    val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return "Launched $label"
                    }
                }
            }
            return "App not found: $query"
        } catch (e: Exception) {
            return "Failed to launch app: ${e.message}"
        }
    }

    /** Take screenshot (requires MediaProjection on Android 10+) */
    fun takeScreenshot(context: Context): String {
        return "Screenshot requires MediaProjection API - not implemented in bridge"
    }

    /** Get device info */
    fun getDeviceInfo(context: Context): String {
        return "Device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.DEVICE}"
    }

    /** Vibrate */
    fun vibrate(context: Context, durationMs: Long = 100): String {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
                return "Vibrated for ${durationMs}ms"
            }
            return "No vibrator on this device"
        } catch (e: Exception) {
            return "Failed to vibrate: ${e.message}"
        }
    }

    /** Check if accessibility service is enabled */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return com.example.voice.AurexAccessibilityService.isEnabled
    }

    /** Check if WRITE_SECURE_SETTINGS is granted */
    fun isWriteSecureSettingsGranted(context: Context): Boolean = hasWriteSecureSettings(context)

    /** Get permission instructions for user */
    fun getPermissionInstructions(): String {
        return """
            For deep OS control (toggle WiFi/Bluetooth/Airplane without opening panels), grant:

            1. WRITE_SECURE_SETTINGS (via ADB):
               adb shell pm grant com.aistudio.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS

            2. Enable AUREX Accessibility Service:
               Settings > Accessibility > AUREX Accessibility Service > ON

            With both granted, AUREX can toggle WiFi, Bluetooth, Airplane Mode, Mobile Data instantly.
        """.trimIndent()
    }
}