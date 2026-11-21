package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.RelativeLayout
import com.example.MainActivity

/**
 * Persistent floating orb "hotkey". Sits at the screen edge above any app.
 * Tap -> open AUREX in voice mode (or the overlay HUD). Long-drag to move.
 */
class AurexHotspotService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: RelativeLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var startX = 0
    private var startY = 0
    private var rawX = 0f
    private var rawY = 0f
    private var dragging = false

    override fun onCreate() {
        super.onCreate()
        // Run as a regular service (not foreground) to avoid FGS permission issues.
        // The overlay is maintained by SYSTEM_ALERT_WINDOW permission.
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        buildBubble()
    }

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val ch = NotificationChannel(
            "aurex_hotspot", "AUREX Hotkey", NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        return Notification.Builder(this, "aurex_hotspot")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AUREX hotkey bubble")
            .setContentText("Tap the orb anywhere to summon AUREX")
            .setOngoing(true)
            .build()
    }

    private fun buildBubble() {
        val root = RelativeLayout(this)

        // Glowing orb button
        val orb = ImageView(this)
        orb.setImageResource(android.R.drawable.ic_btn_speak_now)
        orb.setBackgroundColor(Color.TRANSPARENT)
        orb.setColorFilter(Color.parseColor("#34D399"))
        val orbLp = RelativeLayout.LayoutParams(dp(52), dp(52))
        orbLp.addRule(RelativeLayout.CENTER_IN_PARENT)
        orb.layoutParams = orbLp
        root.addView(orb)

        val params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    startX = params.x
                    startY = params.y
                    rawX = event.rawX
                    rawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dX = event.rawX - rawX
                    val dY = event.rawY - rawY
                    if (Math.abs(dX) > 8 || Math.abs(dY) > 8) dragging = true
                    params.x = (startX + dX).toInt()
                    params.y = (startY + dY).toInt()
                    windowManager?.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        openAurex()
                    }
                    true
                }
                else -> false
            }
        }

        lp = params
        bubble = root
        windowManager?.addView(root, params)
    }

    private fun openAurex() {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("auto_voice", true)
            }
            startActivity(i)
        } catch (e: Exception) {
            // fall back to overlay HUD
            AurexOverlayService.show(this)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val i = Intent(context, AurexHotspotService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AurexHotspotService::class.java))
        }
    }
}