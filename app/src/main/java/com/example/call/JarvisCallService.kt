package com.example.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone-side of JARVIS's nerve: a foreground service long-polling the
 * call server. When JARVIS escalates (autopilot needs permission / has a
 * suggestion / wants to talk), this produces the REAL incoming-call UI —
 * CallStyle notification + full-screen intent, lock-screen and all — and
 * Accept/Decline wire straight into [JarvisCallActivity].
 */
class JarvisCallService : Service() {

    // Any exception inside a coroutine would CRASH THE WHOLE APP by default.
    // This guard turns them into logs — the call link degrades, never kills.
    private val crashGuard = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "poll loop error (contained)", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + crashGuard + Dispatchers.Default)

    /** The relay event cursor, so a restart does not replay the whole log. */
    private val prefs by lazy { getSharedPreferences("jarvis_call_service", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called, flags=$flags, startId=$startId")
        // NEVER let the call link crash the app: if the foreground promotion
        // fails for any OS reason (missing type permission, restricted mode),
        // degrade to a background poller instead of dying.
        try {
            Log.d(TAG, "Starting foreground service...")
            startForeground(FOREGROUND_ID, persistentNotification())
            Log.d(TAG, "Foreground service started")
        } catch (t: Throwable) {
            Log.w(TAG, "foreground promotion failed — running as background poller", t)
        }
        if (!running) {
            running = true
            Log.d(TAG, "Launching pollLoop coroutine")
            scope.launch { pollLoop() }
        } else {
            Log.d(TAG, "pollLoop already running")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        Log.d(TAG, "pollLoop started")
        // The relay's event cursor. It must persist across polls: without it
        // every poll starts at seq 0 and re-notifies the same incoming call for
        // as long as the app lives.
        var cursor = prefs.getLong("events_cursor", 0L)
        while (scope.isActive) {
            Log.d(TAG, "pollLoop: polling events...")
            val page = try {
                CallBridgeApi.fetchEvents(applicationContext, cursor)
            } catch (t: Throwable) {
                Log.e(TAG, "poll failed (contained)", t)
                emptyList<CallBridgeApi.JarvisEvent>() to cursor
            }
            val events = page.first
            cursor = page.second
            Log.d(TAG, "pollLoop: got ${events.size} events (cursor=$cursor)")
            for (e in events) {
                Log.d(TAG, "pollLoop: handling event ${e.id} kind=${e.kind} call=${e.call}")
                try {
                    handleEvent(e)
                } catch (t: Throwable) {
                    Log.e(TAG, "event handling failed (contained)", t)
                }
            }
            try { prefs.edit().putLong("events_cursor", cursor).apply() } catch (_: Exception) {}
            try { delay(3000) } catch (_: Exception) {}
        }
        Log.d(TAG, "pollLoop exiting")
    }

    private fun handleEvent(e: CallBridgeApi.JarvisEvent) {
        Log.d(TAG, "handleEvent: kind=${e.kind}, call=${e.call}, id=${e.id}")
        when {
            // In-call captions: rendered live by the call screen itself —
            // never a notification (that would be constant buzzing mid-call).
            e.kind == "transcript" || e.kind == "reply" -> {}
            e.kind == "call" || e.call -> postIncomingCall(e)
            e.kind == "ask"  -> postHeadsUp(e, CHANNEL_ASKS)
            else             -> postHeadsUp(e, CHANNEL_INFO)
        }
    }

    // ── notifications ────────────────────────────────────────────────────────

    private fun person(): Person = Person.Builder().setName("JARVIS").setImportant(true).build()

    private fun postIncomingCall(e: CallBridgeApi.JarvisEvent) = try {
        Log.d(TAG, "postIncomingCall: building notification for event ${e.id}")
        val fullScreen = PendingIntent.getActivity(
            this, e.id.toInt(),
            JarvisCallActivity.ringingIntent(this, e),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answer = PendingIntent.getActivity(
            this, (e.id + 1).toInt(),
            JarvisCallActivity.answerIntent(this, e),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getActivity(
            this, (e.id + 2).toInt(),
            JarvisCallActivity.declineIntent(this, e),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("JARVIS is calling")
            .setContentText(if (e.body.isNotBlank()) e.body else e.title)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)   // ← the lock-screen ringing UI
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person(), decline, answer))
        }

        nm().notify(CALL_NOTIFICATION_ID, builder.build())
        Log.d(TAG, "postIncomingCall: notification posted successfully")
    } catch (t: Throwable) {
        Log.e(TAG, "incoming-call notification failed (contained)", t)
    }

    private fun postHeadsUp(e: CallBridgeApi.JarvisEvent, channel: String) = try {
        val tap = PendingIntent.getActivity(
            this, (e.id + 5000).toInt(),
            JarvisCallActivity.replyIntent(this, e),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(e.title.ifBlank { "JARVIS" })
            .setContentText(e.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm().notify((10000 + e.id).toInt(), n)
    } catch (t: Throwable) {
        Log.e(TAG, "heads-up notification failed (contained)", t)
    }

    private fun persistentNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("JARVIS link active")
            .setContentText("Listening for calls from your PC")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun nm(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_CALLS = "jarvis_call_channel"
        const val CHANNEL_ASKS  = "jarvis_ask_channel"
        const val CHANNEL_INFO  = "jarvis_info_channel"
        const val CALL_NOTIFICATION_ID = 4242
        const val FOREGROUND_ID = 4243
        const val TAG = "JarvisCallService"
        @Volatile private var running = false

        fun start(ctx: Context) {
            ensureChannels(ctx)
            val i = Intent(ctx, JarvisCallService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            fun chan(id: String, name: String, importance: Int) {
                if (nm.getNotificationChannel(id) == null)
                    nm.createNotificationChannel(NotificationChannel(id, name, importance))
            }
            chan(CHANNEL_CALLS, "JARVIS calls", NotificationManager.IMPORTANCE_HIGH)
            chan(CHANNEL_ASKS, "JARVIS questions", NotificationManager.IMPORTANCE_HIGH)
            chan(CHANNEL_INFO, "JARVIS updates", NotificationManager.IMPORTANCE_LOW)
        }
    }
}
