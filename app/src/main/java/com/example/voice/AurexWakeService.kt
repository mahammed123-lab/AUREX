package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.MainActivity
import java.util.Locale

/**
 * Always-on hotword service. Runs a continuous recognizer and watches partial
 * transcripts for "hey aurex", "hey jarvis", etc. On a hit it pops the overlay
 * voice HUD or opens the app in voice mode.
 *
 * Every step fails SOFT (stopSelf) so an un-permitted/unsupported device never
 * crash-loops the host app.
 */
class AurexWakeService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val TRIGGERS = listOf("hey aurex", "hey jarvis", "aurex", "jarvis", "hey aerex", "aurix")

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(3, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "Foreground denied: ${t.message}")
            stopSelf()
            return
        }
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "Speech recognizer unavailable")
                stopSelf()
                return
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    listening = false
                    restartDelayed()
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    handle(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                    restartDelayed()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!p.isNullOrBlank()) handle(p)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            listenOnce()
        } catch (t: Throwable) {
            Log.w(TAG, "Recognizer setup failed: ${t.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        listening = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val ch = NotificationChannel("aurex_wake", "AUREX Hotword", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        return Notification.Builder(this, "aurex_wake")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AUREX is always listening")
            .setContentText("Say \"Hey AUREX\" anywhere")
            .setOngoing(true)
            .build()
    }

    private fun handle(text: String?) {
        val t = text ?: return
        val lower = t.lowercase(Locale.US)
        if (TRIGGERS.any { lower.contains(it) }) {
            Log.i(TAG, "Hotword hit: $t")
            if (android.provider.Settings.canDrawOverlays(this)) {
                AurexOverlayService.show(this)
            } else {
                try {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("auto_voice", true)
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Could not launch activity: ${e.message}")
                }
            }
        }
    }

    private fun listenOnce() {
        if (listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }
        listening = true
        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            listening = false
            Log.w(TAG, "startListening failed: ${t.message}")
            restartDelayed()
        }
    }

    private fun restartDelayed() {
        Thread {
            Thread.sleep(1500)
            if (recognizer != null) listenOnce()
        }.start()
    }

    companion object {
        private const val TAG = "AurexWakeService"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AurexWakeService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "start() failed: ${t.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AurexWakeService::class.java))
        }
    }
}