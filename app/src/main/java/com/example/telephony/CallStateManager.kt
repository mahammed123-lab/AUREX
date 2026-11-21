package com.example.telephony

import android.content.Context
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

class CallStateManager(
    private val context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit
) {
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var legacyListener: android.telephony.PhoneStateListener? = null

    @RequiresApi(Build.VERSION_CODES.S)
    private var modernCallback: TelephonyCallback? = null

    fun start() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, modernCallback as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                legacyListener = object : android.telephony.PhoneStateListener() {
                    @Suppress("DEPRECATION")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(legacyListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            android.util.Log.e("CallStateManager", "Failed to start call state listener", e)
        }
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(legacyListener, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            android.util.Log.e("CallStateManager", "Failed to stop call state listener", e)
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> onCallStarted()
            TelephonyManager.CALL_STATE_IDLE -> onCallEnded()
        }
    }
}
