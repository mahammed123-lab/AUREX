package com.example.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Payment Mode solves the UPI-vs-AUREX conflict.
 *
 * Indian UPI/bank apps follow NPCI anti-fraud rules and block payments when
 * ANY third-party accessibility service is enabled - they cannot tell a
 * screen-automator like AUREX from fraud malware. So the accessibility
 * service always gets out of the way: the moment a payment app comes to the
 * foreground it disables itself (see AurexAccessibilityService), your
 * payment goes through, and this manager takes over the recovery side.
 *
 * Android never lets an app silently re-enable its own accessibility
 * service - that is the whole point of the permission model - so recovery
 * is a one-tap prompt shown when the user returns to AUREX (unless
 * WRITE_SECURE_SETTINGS was adb-granted, in which case the app can
 * re-enable it by itself the moment a non-payment app is in front).
 */
object PaymentModeManager {

    const val TAG = "PaymentMode"

    /** True from the moment the service self-disabled for a payment app. */
    @Volatile
    var serviceYieldedForPayment: Boolean = false
        private set

    /** Called by the accessibility service just before it disables itself. */
    fun onServiceYielded(context: Context) {
        serviceYieldedForPayment = true
        Log.i(TAG, "Accessibility yielded for payment app - will prompt re-enable on return")
    }

    /** Clear the flag once the service is back (user re-enabled it). */
    fun onServiceRestored() {
        serviceYieldedForPayment = false
        Log.i(TAG, "Accessibility service restored")
    }

    /** True if AUREX's accessibility service is currently enabled. */
    fun isServiceEnabled(context: Context): Boolean {
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(context, AurexAccessibilityService::class.java).flattenToString()
        return setting.split(':').any { it.trim() == expected }
    }

    /**
     * Try to restore the service silently (only possible with the
     * adb-granted WRITE_SECURE_SETTINGS). Returns true when it worked.
     */
    fun trySilentRestore(context: Context): Boolean {
        val expected = ComponentName(context, AurexAccessibilityService::class.java).flattenToString()
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        if (current.split(':').any { it.trim() == expected }) {
            onServiceRestored()
            return true
        }
        return try {
            val merged = if (current.isBlank()) expected else "$current:$expected"
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged
            ) && Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            ).also {
                if (it) {
                    onServiceRestored()
                    Log.i(TAG, "Service silently restored via WRITE_SECURE_SETTINGS")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent restore failed (no WRITE_SECURE_SETTINGS?)", e)
            false
        }
    }

    /** Intent that opens this service's toggle in Accessibility settings. */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open accessibility settings", e)
        }
    }
}
