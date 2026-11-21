package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import com.example.cloudOrbHtml

/**
 * System overlay voice HUD. Shows the orb on the screen above whatever app is
 * open, listens, and prints the transcript + response beneath it - no window
 * background, just the orb and text floating on top of everything.
 */
class AurexOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var orbWeb: WebView? = null
    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ORB_STATE -> {
                    val state = intent.getStringExtra("state") ?: "listening"
                    val vol = intent.getFloatExtra("volume", 0f)
                    orbWeb?.evaluateJavascript(
                        "if (window.setOrbState) window.setOrbState('$state', $vol);", null
                    )
                }
                ACTION_SHOW_TEXT -> {
                    transcriptText?.text = intent.getStringExtra("text") ?: ""
                }
                ACTION_SHOW_STATUS -> {
                    statusText?.text = intent.getStringExtra("status") ?: ""
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(2, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(commandReceiver, IntentFilter().apply {
            addAction(ACTION_ORB_STATE)
            addAction(ACTION_SHOW_TEXT)
            addAction(ACTION_SHOW_STATUS)
        })
        buildOverlay()
        startListening()
    }

    override fun onDestroy() {
        stopListening()
        try { unregisterReceiver(commandReceiver) } catch (_: Throwable) {}
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val ch = NotificationChannel(
            "aurex_overlay", "AUREX Voice HUD", NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        return Notification.Builder(this, "aurex_overlay")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AUREX is listening")
            .setContentText("Say 'Hey AUREX' or a command")
            .setOngoing(true)
            .build()
    }

    private fun buildOverlay() {
        val web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.webViewClient = WebViewClient()
        web.loadDataWithBaseURL("https://app.local", cloudOrbHtml(280), "text/html", "UTF-8", null)
        orbWeb = web

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            text = "Listening..."
        }
        val transcript = TextView(this).apply {
            setTextColor(Color.parseColor("#34D399"))
            textSize = 13f
            gravity = Gravity.CENTER
            text = ""
        }
        statusText = status
        transcriptText = transcript

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)
        val lp = FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL)
        web.layoutParams = lp
        root.addView(web)
        val slp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP
        ).apply { topMargin = this@AurexOverlayService.dp(170) }
        status.layoutParams = slp
        root.addView(status)
        val tlp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP
        ).apply { topMargin = this@AurexOverlayService.dp(200) }
        transcript.layoutParams = tlp
        root.addView(transcript)

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wlp.gravity = Gravity.CENTER
        overlayView = root
        windowManager?.addView(root, wlp)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText?.text = "Speech recognition unavailable on this device"
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val vol = (rmsdB.coerceIn(0f, 12f) / 12f).coerceIn(0.1f, 1f)
                orbWeb?.evaluateJavascript("if (window.setOrbState) window.setOrbState('listening', $vol);", null)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
                // Restart on common transient errors
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart()
                    else -> restartDelayed()
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    transcriptText?.text = text
                    orbWeb?.evaluateJavascript("if (window.setOrbState) window.setOrbState('speaking', 0.5f);", null)
                    sendBroadcast(Intent(ACTION_COMMAND).putExtra("text", text).setPackage(packageName))
                }
                restartDelayed()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) transcriptText?.text = p
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listenOnce()
    }

    private fun listenOnce() {
        if (listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }
        listening = true
        recognizer?.startListening(intent)
    }

    private fun restart() {
        listenOnce()
    }

    private fun restartDelayed() {
        Thread {
            Thread.sleep(1200)
            runOnMainThread { listenOnce() }
        }.start()
    }

    private fun runOnMainThread(r: Runnable) {
        orbWeb?.post(r)
    }

    private fun stopListening() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        listening = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_COMMAND = "com.aistudio.jarvis.OVERLAY_COMMAND"
        const val ACTION_ORB_STATE = "com.aistudio.jarvis.OVERLAY_ORB_STATE"
        const val ACTION_SHOW_TEXT = "com.aistudio.jarvis.OVERLAY_SHOW_TEXT"
        const val ACTION_SHOW_STATUS = "com.aistudio.jarvis.OVERLAY_SHOW_STATUS"

        fun show(context: Context) {
            val i = Intent(context, AurexOverlayService::class.java)
            context.startForegroundService(i)
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, AurexOverlayService::class.java))
        }

        fun isShowing(context: Context): Boolean = ServiceStateHolder.overlayActive
    }

    private object ServiceStateHolder {
        @Volatile var overlayActive = false
    }
}