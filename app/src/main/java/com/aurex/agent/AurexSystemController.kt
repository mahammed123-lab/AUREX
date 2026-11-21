package com.aurex.agent

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/**
 * Core Automation Subsystem for AUREX.
 * Employs direct global database writes to bypass modern Android (API 29+) restrictions.
 * Requires the one-time ADB grant:
 *   adb shell pm grant com.aistudio.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS
 */
class AurexSystemController(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** True when the app holds WRITE_SECURE_SETTINGS (silent toggles are possible). */
    fun hasElevatedAccess(): Boolean = try {
        Settings.Global.putInt(appContext.contentResolver, "aurex_access_probe", 0)
        true
    } catch (e: SecurityException) {
        false
    }

    fun setWifiState(enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(
                appContext.contentResolver,
                Settings.Global.WIFI_ON,
                if (enable) 1 else 0
            )
            val intent = Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
            appContext.sendBroadcast(intent)
            true
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                true
            } else {
                false
            }
        }
    }

    fun setBluetoothState(enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(
                appContext.contentResolver,
                "bluetooth_on",
                if (enable) 1 else 0
            )
            val intent = Intent("android.bluetooth.adapter.action.STATE_CHANGED")
            appContext.sendBroadcast(intent)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun setAirplaneMode(enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(
                appContext.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (enable) 1 else 0
            )
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                putExtra("state", enable)
            }
            appContext.sendBroadcast(intent)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun setMobileDataState(enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(
                appContext.contentResolver,
                "mobile_data",
                if (enable) 1 else 0
            )
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun setFlashlightState(enable: Boolean): Boolean {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                true
            } else {
                false
            }
        } catch (e: CameraAccessException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** Maps assistant intent keys straight to physical system execution. */
    fun executeAssistantCommand(commandIntent: String): Boolean {
        return when (commandIntent.uppercase().trim()) {
            "WIFI_ON" -> setWifiState(true)
            "WIFI_OFF" -> setWifiState(false)
            "BLUETOOTH_ON" -> setBluetoothState(true)
            "BLUETOOTH_OFF" -> setBluetoothState(false)
            "AIRPLANE_ON" -> setAirplaneMode(true)
            "AIRPLANE_OFF" -> setAirplaneMode(false)
            "DATA_ON" -> setMobileDataState(true)
            "DATA_OFF" -> setMobileDataState(false)
            "FLASHLIGHT_ON", "NIGHTLIT_ON" -> setFlashlightState(true)
            "FLASHLIGHT_OFF", "NIGHTLIT_OFF" -> setFlashlightState(false)
            else -> false
        }
    }
}
