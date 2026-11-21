package com.example.vision

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val NIM_BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
private const val VISION_MODEL = "google/diffusiongemma-26b-a4b-it"

private const val CAPTURE_INTERVAL_MS = 1000L
private const val CHANGE_THRESHOLD_PCT = 6.0
private const val MIN_MS_BETWEEN_CALLS = 3000L
private const val MAX_FRAME_WIDTH = 768
private const val JPEG_QUALITY = 65
private const val CONTEXT_HISTORY_LEN = 8

private const val SYSTEM_PROMPT = """
You are AUREX's live vision module. You watch the user's phone screen continuously
and report ONLY what is new or meaningfully changed since your last glance — like
a co-pilot glancing over his shoulder, not a narrator describing everything.

OUTPUT FORMAT — respond with exactly ONE line, plain text, no markdown, no preamble:
<TAG>: <one short sentence, max ~20 words>

TAGS — choose exactly one, the one that best matches the MOST important thing visible:
- ERROR   — a visible error, crash, failed action, or red/warning UI state. Always
            wins over other tags if present.
- CODE    — a code editor / terminal app is open, no error present.
- APP     — user is actively using an app (name it) — not code, chat, or media.
- BROWSER — reading/browsing a webpage. Name the site or topic, never the raw URL.
- CHAT    — a messaging/email app is open or active. Name the app only — never
            summarize or quote the actual message content.
- MEDIA   — video, music, game, or other entertainment content.
- IDLE    — nothing meaningfully different from the last glance.

HARD RULES:
1. If nothing meaningfully changed, respond with exactly: IDLE: no change
2. Never mention status bar, navigation bar, or wallpaper unless the user is
   actively interacting with them.
3. Never transcribe or quote on-screen text verbatim — always paraphrase.
4. If anything sensitive is visible (passwords, OTPs, card numbers, private
   messages, personal identifiers), respond with the relevant tag and exactly:
   "sensitive info visible, skipped."
5. One sentence only — this feeds a rolling short-term memory buffer, not a report.
6. If asked a direct question instead of doing routine narration, answer that
   question using what's visible instead — rules 3 and 4 still apply.
"""

enum class ScreenTag { ERROR, CODE, APP, BROWSER, CHAT, MEDIA, IDLE, UNKNOWN }

data class ScreenEvent(
    val timestampMs: Long,
    val tag: ScreenTag,
    val description: String
) {
    companion object {
        fun fromRaw(timestampMs: Long, raw: String): ScreenEvent {
            val trimmed = raw.trim()
            val idx = trimmed.indexOf(':')
            if (idx == -1) return ScreenEvent(timestampMs, ScreenTag.UNKNOWN, trimmed)
            val tagStr = trimmed.substring(0, idx).trim().uppercase()
            val rest = trimmed.substring(idx + 1).trim()
            val tag = try { ScreenTag.valueOf(tagStr) } catch (e: Exception) { ScreenTag.UNKNOWN }
            return ScreenEvent(timestampMs, tag, rest)
        }
    }
}

interface ScreenEventListener {
    fun onNewScreenEvent(event: ScreenEvent)
}

class ScreenVisionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var lastSentBitmap: Bitmap? = null
    private var lastCallTimeMs = 0L
    private val context = ArrayDeque<ScreenEvent>()
    private var captureLoopJob: Job? = null

    var listener: ScreenEventListener? = null
    private lateinit var nimApiKey: String

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        nimApiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""

        if (resultCode == Activity.RESULT_OK && data != null) {
            setupMediaProjection(resultCode, data)
        } else {
            Log.e(TAG, "Missing screen capture permission result — stopping service.")
            stopSelf()
        }
        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "JarvisScreenVision", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        captureLoopJob = serviceScope.launch {
            while (isActive) {
                delay(CAPTURE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (now - lastCallTimeMs < MIN_MS_BETWEEN_CALLS) continue

                val bitmap = grabLatestFrame() ?: continue
                val scaled = downscale(bitmap, MAX_FRAME_WIDTH)

                val changed = lastSentBitmap == null || percentChanged(lastSentBitmap!!, scaled) >= CHANGE_THRESHOLD_PCT
                if (!changed) continue

                try {
                    val raw = describeFrame(scaled, null)
                    lastSentBitmap = scaled
                    lastCallTimeMs = now

                    val event = ScreenEvent.fromRaw(now, raw)
                    if (event.tag == ScreenTag.IDLE) continue

                    synchronized(context) {
                        if (context.size >= CONTEXT_HISTORY_LEN) context.removeFirst()
                        context.addLast(event)
                    }
                    listener?.onNewScreenEvent(event)
                } catch (e: Exception) {
                    Log.w(TAG, "describeFrame failed: ${e.message}")
                }
            }
        }
    }

    private fun grabLatestFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } finally {
            image.close()
        }
    }

    private fun downscale(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    private fun percentChanged(prev: Bitmap, curr: Bitmap): Double {
        if (prev.width != curr.width || prev.height != curr.height) return 100.0
        var diffCount = 0
        var sampleCount = 0
        val stepX = maxOf(1, prev.width / 60)
        val stepY = maxOf(1, prev.height / 60)
        var x = 0
        while (x < prev.width) {
            var y = 0
            while (y < prev.height) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                sampleCount++
                if (abs((p1 shr 16 and 0xFF) - (p2 shr 16 and 0xFF)) > 25 ||
                    abs((p1 shr 8 and 0xFF) - (p2 shr 8 and 0xFF)) > 25 ||
                    abs((p1 and 0xFF) - (p2 and 0xFF)) > 25
                ) diffCount++
                y += stepY
            }
            x += stepX
        }
        return if (sampleCount == 0) 0.0 else (diffCount.toDouble() / sampleCount) * 100.0
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun describeFrame(bitmap: Bitmap, question: String?): String =
        withContext(Dispatchers.IO) {
            val b64 = bitmapToBase64Jpeg(bitmap)
            val promptText = question ?: "Describe what changed on screen since the last glance."

            val userContent = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", promptText)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$b64")
                    })
                })
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", SYSTEM_PROMPT) })
                put(JSONObject().apply { put("role", "user"); put("content", userContent) })
            }

            val body = JSONObject().apply {
                put("model", VISION_MODEL)
                put("messages", messages)
                put("max_tokens", 150)
                put("temperature", 0.4)
            }

            val request = Request.Builder()
                .url(NIM_BASE_URL)
                .addHeader("Authorization", "Bearer $nimApiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: throw Exception("Empty NIM response")
                if (!response.isSuccessful) throw Exception("NIM error ${response.code}: $respBody")
                val json = JSONObject(respBody)
                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }

    fun getRecentContext(maxEvents: Int = 3): String {
        val events = synchronized(context) { context.toList().takeLast(maxEvents) }
        if (events.isEmpty()) return ""
        return "Recent screen activity:\n" + events.joinToString("\n") { "- [${it.tag}] ${it.description}" }
    }

    suspend fun describeNow(question: String): String {
        val bitmap = grabLatestFrame()?.let { downscale(it, MAX_FRAME_WIDTH) }
            ?: return "Couldn't capture the screen right now."
        val raw = describeFrame(bitmap, question)
        lastSentBitmap = bitmap
        lastCallTimeMs = System.currentTimeMillis()
        val event = ScreenEvent.fromRaw(System.currentTimeMillis(), raw)
        synchronized(context) {
            if (context.size >= CONTEXT_HISTORY_LEN) context.removeFirst()
            context.addLast(event)
        }
        return event.description
    }

    private fun startForegroundWithNotification() {
        val channelId = "jarvis_screen_vision"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "JARVIS Screen Vision", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AUREX is watching your screen")
            .setContentText("Live screen understanding is active, sir.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        captureLoopJob?.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenVisionService"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_API_KEY = "extra_api_key"
    }
}
