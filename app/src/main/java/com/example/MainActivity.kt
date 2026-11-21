package com.example

import android.app.Activity
import android.content.ContextWrapper
import android.content.Context
import android.util.Log
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.data.AiPersonalities
import com.example.data.AiPersonality
import com.example.api.KittenTtsHelper
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.data.ChatSession
import com.example.ui.ChatUiState
import com.example.ui.JarvisViewModel
import com.example.ui.AttachedFile
import com.example.ui.JarvisThread
import com.example.ui.JarvisChatMessage
import com.example.ui.JarvisEvent
import com.example.ui.theme.AurexColors
import com.example.ui.theme.AurexMotion
import com.example.ui.components.JarvisMessagesTab
import com.example.ui.components.JarvisCallsTab
import com.example.ui.components.OrbState
import com.example.ui.components.ThinkingOrb
import com.aurex.sphere.AurexSphere
import com.aurex.sphere.AurexSpeakingSphere
import com.aurex.sphere.SphereState
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.widget.Toast
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import java.util.Locale
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object VoiceLevelBus {
    val level = androidx.compose.runtime.mutableStateOf(0f)
    /** Real playback position of the current TTS sentence, in seconds. */
    var playbackSec: Double? = null

    // reply timeline: per-sentence durations (estimates refined with the real
    // playback length of every finished sentence) so the orb's clock tracks
    // exactly where the voice is through the whole reply
    private var durations = mutableListOf<Double>()
    private var offsets = DoubleArray(0)
    private var idx = 0

    fun beginReply(estimates: List<Double>) {
        durations = estimates.toMutableList()
        idx = 0
        rebuild()
    }

    fun chunkFinished(actualSeconds: Double?) {
        if (actualSeconds != null && actualSeconds > 0.0 && idx < durations.size) {
            durations[idx] = actualSeconds
        }
        idx++
        rebuild()
    }

    private fun rebuild() {
        offsets = DoubleArray(durations.size + 1)
        for (i in durations.indices) offsets[i + 1] = offsets[i] + durations[i]
    }

    /** Position within the whole reply, in seconds — the orb beats with the voice. */
    fun speechClock(): Double {
        val off = if (idx < offsets.size) offsets[idx] else 0.0
        return off + (playbackSec ?: 0.0)
    }

    fun push(v: Float) {
        level.value = v.coerceIn(0f, 1f)
    }
    fun reset() {
        level.value = 0f
        playbackSec = null
    }
    fun resetReply() {
        durations.clear()
        offsets = DoubleArray(0)
        idx = 0
    }
}

object VoiceSoundEffects {
    fun playStartChime() {
        Thread {
            try {
                val sampleRate = 44100
                val durationMs = 300
                val numSamples = sampleRate * durationMs / 1000
                val samples = FloatArray(numSamples)

                val tone1End = (numSamples * 0.35).toInt()
                val tone2End = (numSamples * 0.70).toInt()

                for (i in 0 until numSamples) {
                    val freq = when {
                        i < tone1End -> 523.25 // C5
                        i < tone2End -> 659.25 // E5
                        else -> 784.88        // G5
                    }
                    val t = i.toDouble() / sampleRate
                    var envelope = 1.0
                    if (i > numSamples - 2500) {
                        envelope = (numSamples - i).toDouble() / 2500.0
                    }
                    samples[i] = (Math.sin(2.0 * Math.PI * freq * t) * envelope * 0.35).toFloat()
                }

                val pcm16 = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    pcm16[i] = (samples[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm16.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm16, 0, pcm16.size)
                track.play()
                Thread.sleep(320)
                track.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun playEndChime() {
        Thread {
            try {
                val sampleRate = 44100
                val durationMs = 300
                val numSamples = sampleRate * durationMs / 1000
                val samples = FloatArray(numSamples)

                val tone1End = (numSamples * 0.35).toInt()
                val tone2End = (numSamples * 0.70).toInt()

                for (i in 0 until numSamples) {
                    val freq = when {
                        i < tone1End -> 784.88 // G5
                        i < tone2End -> 659.25 // E5
                        else -> 523.25        // C5
                    }
                    val t = i.toDouble() / sampleRate
                    var envelope = 1.0
                    if (i > numSamples - 2500) {
                        envelope = (numSamples - i).toDouble() / 2500.0
                    }
                    samples[i] = (Math.sin(2.0 * Math.PI * freq * t) * envelope * 0.35).toFloat()
                }

                val pcm16 = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    pcm16[i] = (samples[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm16.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm16, 0, pcm16.size)
                track.play()
                Thread.sleep(320)
                track.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}

class MainActivity : ComponentActivity() {
    private var mainViewModelRef: JarvisViewModel? = null
    var ttsManager: KittenTtsHelper? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningActive = false
    private var consecutiveSpeechErrors = 0

    // ── One-shot dictation mode ──
    // The chat-bar mic starts recognition without entering full voice
    // mode: one utterance goes into the input field (or sends directly
    // when it looks like a command) and recognition ends. Voice mode
    // keeps its continuous loop instead.
    private var dictationMode = false
    private var dictationAutoSend = false

    // ── End-of-speech watchdog ──
    // Google's engine on this Realme build never fires onResults by itself
    // once it has partial text: it keeps "Listening" indefinitely. The
    // watchdog watches partial results; when they stop changing for
    // WATCHDOG_SILENCE_MS, it calls stopListening() which forces the engine
    // to finalize and deliver.
    private var lastPartialAt = 0L
    private var lastPartialText = ""
    private var partialStabilized = false
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private val WATCHDOG_SILENCE_MS = 700L
    private var pendingCallTarget: String? = null
    private var pendingWhatsAppAction: Pair<String, String>? = null
    private var spotifyController: com.example.spotify.SpotifyController? = null
    private var musicCommandHandler: com.example.spotify.MusicCommandHandler? = null
    private var callStateManager: com.example.telephony.CallStateManager? = null
    private var conversationManager: com.example.voice.ConversationManager? = null
    private var ttsPlaybackManager: com.example.voice.TTSPlaybackManager? = null

    // ── VoicePipeline port (OpenAI Agents SDK SingleAgentVoiceWorkflow) ──
    // Audio in (mic) stays LIVE through playback; the reply is spoken as
    // sentence chunks so the first audio event lands as early as possible
    // and the rest streams behind it. Barge-in interrupts both.
    private val ttsQueue = java.util.ArrayDeque<String>()
    private var ttsQueueActive = false

    lateinit var requestPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>

    fun startListeningFromUi(viewModel: JarvisViewModel) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // Tapping the mic = one-shot dictation, not the continuous
            // voice-mode loop. If a dictation is already running, stop it
            // so the tap also acts as a cancel button.
            if (dictationMode && isListeningActive) {
                stopDictation(viewModel)
                return
            }
            dictationMode = true
            startListening(viewModel)
        } else if (::requestPermissionLauncher.isInitialized) {
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun stopDictation(viewModel: JarvisViewModel) {
        try { speechRecognizer?.stopListening() } catch (e: Exception) { e.printStackTrace() }
        dictationMode = false
        dictationAutoSend = false
        isListeningActive = false
        viewModel.setVoiceStatus("")
    }

    /**
     * True when a dictated phrase is a device command ("wifi off", "call dad",
     * "open whatsapp", "set alarm 7 am", ...) — those execute immediately;
     * everything else lands in the input bar for review.
     */
    private fun looksLikeCommand(text: String): Boolean {
        val t = text.lowercase().trim().removePrefix("hey aurex").removePrefix("hey jarvis").trim()
        val keywords = listOf(
            "wifi", "wi-fi", "bluetooth", "torch", "flashlight", "location", "gps",
            "open ", "launch ", "start app", "call ", "phone ", "dial ",
            "set alarm", "set a timer", "timer for", "remind me", "reminder",
            "volume up", "volume down", "mute", "brightness", "screenshot",
            "screen record", "play ", "pause", "next song", "previous song",
            "battery", "turn on", "turn off", "switch on", "switch off",
            "send whatsapp", "whatsapp to", "message to", "compose email",
            "send email", "check email", "check my email", "calendar", "schedule",
            "lock screen", "lock the phone", "go home", "go back", "recents",
            "what's my battery", "battery percentage", "scan qr", "scan a qr"
        )
        return keywords.any { t.startsWith(it) } || t in listOf(
            "screenshot", "what time is it", "what's the time", "play music",
            "stop music", "pause music", "next song", "check my battery"
        )
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val target = pendingCallTarget
        pendingCallTarget = null
        if (target != null) {
            makeDirectPhoneCall(target)
        }

        val waAction = pendingWhatsAppAction
        pendingWhatsAppAction = null
        if (waAction != null) {
            sendWhatsAppMessage(waAction.first, waAction.second)
        }
    }

    data class ContactMatch(
        val displayName: String,
        val phoneNumber: String,
        val score: Int
    )

    private fun normalizePhonetic(str: String): String {
        return str.lowercase(Locale.ROOT)
            .replace("y", "i")
            .replace("ee", "i")
            .replace("ea", "i")
            .replace("oo", "u")
            .replace("ck", "k")
            .replace("th", "t")
            .replace("ph", "f")
            .replace("v", "w")
            .replace(Regex("(.)\\1+"), "$1")
            .trim()
    }

    private fun resolveMatchingContactsList(query: String): List<ContactMatch> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")
        if (trimmed.matches(Regex("^[+0-9\\s\\-\\(\\)]+$")) && digitsOnly.length >= 3) {
            return listOf(ContactMatch(displayName = digitsOnly, phoneNumber = digitsOnly, score = 1000))
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val matches = mutableListOf<ContactMatch>()
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val cursor = contentResolver.query(uri, projection, null, null, null)

            val queryLower = trimmed.lowercase(Locale.ROOT)
            val queryNorm = normalizePhonetic(queryLower)
            val queryWords = queryLower.split(Regex("[\\s\\-_/(),.#@]+")).filter { it.isNotEmpty() }

            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)

                val seenNumbers = mutableSetOf<String>()

                while (it.moveToNext()) {
                    if (nameIndex < 0 || numIndex < 0) continue
                    val name = it.getString(nameIndex) ?: continue
                    val rawNum = it.getString(numIndex) ?: continue
                    val cleanNum = rawNum.replace(Regex("[^0-9+]"), "")
                    if (cleanNum.isEmpty() || seenNumbers.contains(cleanNum)) continue
                    seenNumbers.add(cleanNum)

                    val nameLower = name.lowercase(Locale.ROOT)
                    val nameNorm = normalizePhonetic(nameLower)
                    val nameWords = nameLower.split(Regex("[\\s\\-_/(),.#@]+")).filter { it.isNotEmpty() }

                    var score = 0

                    if (nameLower == queryLower) {
                        score = 1000
                    } else if (nameNorm == queryNorm) {
                        score = 980
                    } else if (nameLower.startsWith(queryLower) || nameNorm.startsWith(queryNorm)) {
                        score = 900
                    } else if (nameLower.contains(queryLower) || nameNorm.contains(queryNorm)) {
                        score = 800
                    } else {
                        for (qWord in queryWords) {
                            val qNorm = normalizePhonetic(qWord)
                            for (nWord in nameWords) {
                                val nNorm = normalizePhonetic(nWord)
                                when {
                                    nWord == qWord || nNorm == qNorm -> score += 350
                                    nWord.startsWith(qWord) || nNorm.startsWith(qNorm) -> score += 250
                                    qWord.startsWith(nWord) || qNorm.startsWith(nNorm) -> score += 180
                                    nWord.contains(qWord) || nNorm.contains(qNorm) -> score += 150
                                    levenshteinDistance(nWord, qWord) <= 1 || levenshteinDistance(nNorm, qNorm) <= 1 -> score += 140
                                    levenshteinDistance(nWord, qWord) <= 2 -> score += 80
                                }
                            }
                        }
                    }

                    if (score >= 120) {
                        matches.add(ContactMatch(displayName = name, phoneNumber = cleanNum, score = score))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return matches.sortedByDescending { it.score }
    }

    private fun resolveContactDetails(query: String): ContactMatch? {
        val list = resolveMatchingContactsList(query)
        return list.firstOrNull()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }

    private fun resolveContactToPhoneNumber(query: String): String? {
        return resolveContactDetails(query)?.phoneNumber
    }

    private fun makeDirectPhoneCall(target: String) {
        val hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CALL_PHONE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val hasContactsPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasCallPermission || !hasContactsPermission) {
            pendingCallTarget = target
            try {
                callPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.CALL_PHONE,
                        android.Manifest.permission.READ_CONTACTS
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        val handler = com.example.telephony.ContactCallHandler(this)
        val placed = handler.callByName(target)
        if (!placed) {
            val allMatches = resolveMatchingContactsList(target)
            val contactMatch = allMatches.firstOrNull()
            val resolvedNumber = contactMatch?.phoneNumber ?: target.replace(Regex("[^0-9+]"), "")
            val displayName = contactMatch?.displayName ?: target

            if (resolvedNumber.isNotEmpty() && resolvedNumber.any { it.isDigit() }) {
                Toast.makeText(this, "Calling $displayName ($resolvedNumber)...", Toast.LENGTH_LONG).show()
                try {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$resolvedNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(callIntent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolvedNumber")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            } else {
                Toast.makeText(this, "No confident contact match for '$target'", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun evaluateMathExpression(expr: String): String {
        try {
            val clean = expr.replace("x", "*").replace("X", "*").replace("times", "*").replace("plus", "+").replace("minus", "-").replace("divided by", "/").replace("percent of", "%").trim()
            if (clean.contains("%")) {
                val parts = clean.split("%")
                if (parts.size == 2) {
                    val pct = parts[0].trim().toDoubleOrNull()
                    val total = parts[1].replace("of", "").trim().toDoubleOrNull()
                    if (pct != null && total != null) {
                        val result = (pct / 100.0) * total
                        return "Result: $result"
                    }
                }
            }
            val tokens = clean.split(Regex("(?<=[-+*/])|(?=[-+*/])")).map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.size >= 3) {
                val a = tokens[0].toDoubleOrNull()
                val op = tokens[1]
                val b = tokens[2].toDoubleOrNull()
                if (a != null && b != null) {
                    val res = when (op) {
                        "+" -> a + b
                        "-" -> a - b
                        "*" -> a * b
                        "/" -> if (b != 0.0) a / b else null
                        else -> null
                    }
                    if (res != null) return "Result: $res"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Calculation requested for: $expr"
    }

    private fun sendWhatsAppMessage(target: String, message: String) {
        val hasContactsPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            pendingWhatsAppAction = Pair(target, message)
            try {
                callPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_CONTACTS,
                        android.Manifest.permission.CALL_PHONE
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        val contactMatch = resolveContactDetails(target)
        val rawNum = contactMatch?.phoneNumber ?: target.replace(Regex("[^0-9+]"), "")
        val displayName = contactMatch?.displayName ?: target
        var cleanDigits = rawNum.replace(Regex("[^0-9]"), "")

        if (cleanDigits.length == 10) {
            cleanDigits = "91$cleanDigits"
        }

        if (cleanDigits.length >= 7) {
            if (message.isNotBlank()) {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("WhatsApp Message", message)
                    clipboard?.setPrimaryClip(clip)
                } catch (e: Exception) { e.printStackTrace() }
            }

            val waUriStr = if (message.isNotBlank()) {
                "https://api.whatsapp.com/send?phone=$cleanDigits&text=" + Uri.encode(message)
            } else {
                "https://api.whatsapp.com/send?phone=$cleanDigits"
            }
            val uri = Uri.parse(waUriStr)

            val statusMsg = if (message.isNotBlank()) {
                "WhatsApp opened for $displayName! Message typed. Tap the Send button in WhatsApp."
            } else {
                "Opening WhatsApp chat with $displayName"
            }
            Toast.makeText(this, statusMsg, Toast.LENGTH_LONG).show()

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                intent.setPackage("com.whatsapp")
                startActivity(intent)
                if (message.isNotBlank()) {
                    Thread {
                        Thread.sleep(2200)
                        val acc = com.example.voice.AurexAccessibilityService.instance
                        if (acc != null) {
                            acc.clickByText("Send")
                            acc.clickByContentDescription("Send")
                            runOnUiThread {
                                Toast.makeText(this, "Message sent to $displayName", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this, "Tip: enable AUREX in Accessibility settings to auto-send", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            } catch (e: Exception) {
                try {
                    intent.setPackage("com.whatsapp.w4b")
                    startActivity(intent)
                } catch (ex: Exception) {
                    try {
                        intent.setPackage(null)
                        startActivity(intent)
                    } catch (ex2: Exception) {
                        Toast.makeText(this, "WhatsApp app is not installed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Could not resolve contact '$target' to a valid WhatsApp phone number", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAnySpeaking(): Boolean {
        return ttsManager?.isSpeaking == true
    }

    private fun startListening(viewModel: JarvisViewModel) {
        if (!viewModel.isVoiceMode.value && !dictationMode) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (isListeningActive) return
        if (viewModel.isGenerating.value) { Log.d("VoiceLoop", "re-arm blocked: isGenerating"); return }
        if (isAnySpeaking()) { Log.d("VoiceLoop", "re-arm blocked: TTS speaking"); return }
        Log.d("VoiceLoop", "mic re-armed")
        try {
            isListeningActive = true
            val currentAppLang = viewModel.appLanguage.value
            val primaryLang = if (currentAppLang == "Telugu") "te-IN" else "en-US"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLang)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN", "te-IN", "hi-IN"))
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // NO custom silence/minimum-length extras — on this Realme
                // build Google's engine mishandles them and holds the
                // session open forever (app stuck "Listening"). Default
                // endpointing works; end-of-speech snappiness comes from the
                // partial-results watchdog declared near ttsPlaybackManager.
            }
            if (speechRecognizer == null) {
                createAndSetupSpeechRecognizer(viewModel)
            }
            val recognizer = speechRecognizer
            if (recognizer != null) {
                recognizer.startListening(intent)
                lastPartialAt = 0L
                partialStabilized = false
                armEndOfSpeechWatchdog(viewModel)
                viewModel.setVoiceStatus("Listening...")
            } else {
                isListeningActive = false
                viewModel.setVoiceStatus("Tap microphone to speak")
            }
        } catch (e: Exception) {
            isListeningActive = false
            e.printStackTrace()
            consecutiveSpeechErrors++
            viewModel.setVoiceStatus("Tap microphone to speak")
        }
    }

    /**
     * Force end-of-speech on devices where Google's engine holds the
     * session open indefinitely. Runs while listening; when a partial
     * transcript exists and hasn't changed for WATCHDOG_SILENCE_MS,
     * stopListening() finalizes the utterance and onResults fires.
     */
    private fun armEndOfSpeechWatchdog(viewModel: JarvisViewModel) {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && isListeningActive) {
                delay(200)
                val last = lastPartialAt
                if (last > 0 && System.currentTimeMillis() - last >= WATCHDOG_SILENCE_MS && !partialStabilized) {
                    partialStabilized = true
                    Log.d("MainActivity", "Watchdog: speech stable, forcing finalize")
                    try {
                        speechRecognizer?.stopListening()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun notePartialResult(text: String?) {
        if (text.isNullOrBlank()) return
        if (text == lastPartialText) return
        lastPartialText = text
        lastPartialAt = System.currentTimeMillis()
        partialStabilized = false
    }

    /** Submits a watchdog-stabilized partial transcript as the user's turn. */
    private fun submitStabilizedUtterance(raw: String, viewModel: JarvisViewModel) {
        var text = raw.trim()
        if (viewModel.isDeveloperMode.value) {
            val lower = text.lowercase()
            when {
                lower.startsWith("hey aurex") -> text = text.substring(9).trim().removePrefix(",").trim()
                lower.startsWith("aurex") -> text = text.substring(5).trim().removePrefix(",").trim()
                lower.startsWith("hey jarvis") -> text = text.substring(10).trim().removePrefix(",").trim()
                lower.startsWith("jarvis") -> text = text.substring(6).trim().removePrefix(",").trim()
                lower.startsWith("hey google") -> text = text.substring(10).trim().removePrefix(",").trim()
            }
            if (text.isEmpty()) text = "Hello A.U.R.E.X."
        }
        watchdogJob?.cancel()
        if (dictationMode) {
            // One-shot dictation: transcript goes into the input bar (or
            // fires directly when it's a device command), then stops.
            dictationMode = false
            viewModel.setVoicePartialText("")
            if (text.isNotBlank()) {
                if (looksLikeCommand(text)) {
                    viewModel.setVoiceStatus("Processing...")
                    viewModel.handleSend(text)
                } else {
                    viewModel.setInputText(text)
                    viewModel.setVoiceStatus("")
                }
            } else {
                viewModel.setVoiceStatus("")
            }
            return
        }
        if (text.isNotBlank()) {
            viewModel.setVoicePartialText("You: $text")
            viewModel.setVoiceStatus("Processing...")
            viewModel.handleSend(text)
        }
    }

    private fun createAndSetupSpeechRecognizer(viewModel: JarvisViewModel) {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        consecutiveSpeechErrors = 0
                        viewModel.setVoiceStatus("Listening...")
                    }
                    override fun onBeginningOfSpeech() {
                        consecutiveSpeechErrors = 0
                        viewModel.stopSpeaking()
                        viewModel.setVoiceStatus("Listening...")
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        val normRms = if (rmsdB < 0f) 0f else rmsdB
                        viewModel.setVoiceRmsDb(normRms)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        if (viewModel.isVoiceMode.value && !viewModel.isGenerating.value && !isAnySpeaking()) {
                            viewModel.setVoiceStatus("Processing...")
                        }
                    }
                    override fun onError(error: Int) {
                        isListeningActive = false
                        // Silence timeouts are normal in a conversation —
                        // the user just paused. They must NOT count toward the
                        // error kill switch, or the mic goes permanently dead
                        // ("stuck") after a few quiet turns.
                        val isBenignSilence = error == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                            error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        if (!isBenignSilence) {
                            consecutiveSpeechErrors++
                        } else {
                            consecutiveSpeechErrors = 0
                        }
                        Log.w("MainActivity", "SpeechRecognizer onError: $error (consecutive: $consecutiveSpeechErrors)")

                        if (error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                            error == android.speech.SpeechRecognizer.ERROR_CLIENT ||
                            consecutiveSpeechErrors >= 3) {
                            // Watchdog-fallback: some Realme/ColorOS builds
                            // deliver ERROR_NO_MATCH instead of onResults
                            // after a forced stopListening(). If we had a
                            // stabilized partial, submit THAT as the user's
                            // utterance so the turn isn't lost.
                            if (partialStabilized && lastPartialText.isNotBlank()) {
                                Log.d("MainActivity", "Watchdog: submitting stabilized partial after error $error")
                                val stabilized = lastPartialText
                                lastPartialText = ""
                                partialStabilized = false
                                isListeningActive = false
                                submitStabilizedUtterance(stabilized, viewModel)
                                return
                            }
                            viewModel.setVoiceStatus("Tap microphone to speak")
                            return
                        }

                        if (dictationMode) {
                            // Dictation is one-shot: on error just exit — never
                            // auto-restart the mic outside voice mode.
                            val stabilizedPartial = lastPartialText
                            dictationMode = false
                            viewModel.setVoiceStatus("")
                            if (partialStabilized && stabilizedPartial.isNotBlank()) {
                                lastPartialText = ""
                                partialStabilized = false
                                if (looksLikeCommand(stabilizedPartial)) {
                                    viewModel.handleSend(stabilizedPartial)
                                } else {
                                    viewModel.setInputText(stabilizedPartial)
                                }
                            }
                            return
                        }

                        if (viewModel.isVoiceMode.value && !viewModel.isGenerating.value && !isAnySpeaking()) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                // Snappy re-arm on benign silence; longer backoff
                                // on genuine errors so a failing recognizer
                                // doesn't spin-loop.
                                delay(if (isBenignSilence) 300 else 1000)
                                if (viewModel.isVoiceMode.value && !viewModel.isGenerating.value && !isAnySpeaking() && consecutiveSpeechErrors < 3) {
                                    viewModel.setVoiceStatus("Listening...")
                                    startListening(viewModel)
                                }
                            }
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        isListeningActive = false
                        consecutiveSpeechErrors = 0
                        watchdogJob?.cancel()
                        lastPartialText = ""
                        lastPartialAt = 0L
                        partialStabilized = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        var querySubmitted = false
                        if (!matches.isNullOrEmpty()) {
                            var text = matches[0]
                            if (viewModel.isDeveloperMode.value) {
                                val lower = text.lowercase()
                                if (lower.startsWith("hey aurex")) {
                                    text = text.substring(9).trim().removePrefix(",").trim()
                                } else if (lower.startsWith("aurex")) {
                                    text = text.substring(5).trim().removePrefix(",").trim()
                                } else if (lower.startsWith("hey jarvis")) {
                                    text = text.substring(10).trim().removePrefix(",").trim()
                                } else if (lower.startsWith("jarvis")) {
                                    text = text.substring(6).trim().removePrefix(",").trim()
                                } else if (lower.startsWith("hey google")) {
                                    text = text.substring(10).trim().removePrefix(",").trim()
                                }
                                if (text.isEmpty()) {
                                    text = "Hello A.U.R.E.X."
                                }
                            }
                            if (dictationMode) {
                                // One-shot dictation: put the transcript in the
                                // chat input. If it sounds like a device command,
                                // fire it straight away instead of making the
                                // user hit send.
                                dictationMode = false
                                viewModel.setVoicePartialText("")
                                if (text.isNotBlank() && !isAnySpeaking()) {
                                    if (looksLikeCommand(text)) {
                                        dictationAutoSend = false
                                        viewModel.setVoiceStatus("Processing...")
                                        viewModel.handleSend(text)
                                    } else {
                                        viewModel.setInputText(text)
                                        viewModel.setVoiceStatus("")
                                    }
                                } else {
                                    viewModel.setVoiceStatus("")
                                }
                                return
                            }
                            if (text.isNotBlank() && !isAnySpeaking()) {
                                querySubmitted = true
                                viewModel.setVoicePartialText("You: $text")
                                viewModel.setVoiceStatus("Thinking...")
                                viewModel.handleSend(text)
                            }
                        }
                        if (dictationMode) {
                            dictationMode = false
                            viewModel.setVoiceStatus("")
                            return
                        }
                        if (!querySubmitted && viewModel.isVoiceMode.value && !viewModel.isGenerating.value && !isAnySpeaking()) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                delay(600)
                                if (viewModel.isVoiceMode.value && !viewModel.isGenerating.value && !isAnySpeaking()) {
                                    startListening(viewModel)
                                }
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0]
                            if (partial.isNotBlank()) {
                                notePartialResult(partial)
                                viewModel.stopSpeaking()
                                if (dictationMode) {
                                    // Live transcription straight into the chat
                                    // input while the user speaks.
                                    viewModel.setInputText(partial)
                                } else {
                                    viewModel.setVoicePartialText("You: $partial")
                                }
                                if (viewModel.isDeveloperMode.value && !viewModel.isVoiceMode.value && !dictationMode) {
                                    val lower = partial.lowercase()
                                    if (lower.contains("aurex") || lower.contains("hey aurex") || lower.contains("hey jarvis") || lower.contains("hey google") || lower.contains("jarvis")) {
                                        viewModel.setVoiceMode(true)
                                    }
                                }
                            }
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── VoicePipeline: sentence chunking so audio starts streaming early ──
    private fun splitSpeechChunks(text: String): List<String> {
        val raw = text.split(Regex("(?<=[.!?])\\s+"))
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (s in raw) {
            if (s.isBlank()) continue
            if (buf.isEmpty()) buf.append(s.trim()) else buf.append(" ").append(s.trim())
            if (buf.length >= 24) {
                out.add(buf.toString())
                buf.setLength(0)
            }
        }
        if (buf.isNotBlank()) out.add(buf.toString())
        return out.filter { it.isNotBlank() }
    }

    private fun pipelineSpeakAll(text: String, speed: Float) {
        val chunks = splitSpeechChunks(text)
        if (chunks.isEmpty()) return
        ttsQueue.clear()
        // the first chunk plays NOW; only the rest wait in the queue —
        // re-queuing it made the pipeline speak the opening sentence twice
        for (i in 1 until chunks.size) ttsQueue.add(chunks[i])
        ttsQueueActive = true
        // map every sentence onto the reply timeline: the orb follows the voice
        val wpm = (165 * speed).toInt().coerceIn(60, 300)
        VoiceLevelBus.beginReply(chunks.map { com.aurex.sphere.estimateSpeechSeconds(it, wpm) })
        ttsManager?.speak(chunks.first(), speed = speed)
    }

    private fun stopTtsQueue() {
        ttsQueue.clear()
        ttsQueueActive = false
    }

    private fun stopListening() {
        try {
            isListeningActive = false
            watchdogJob?.cancel()
            lastPartialText = ""
            lastPartialAt = 0L
            partialStabilized = false
            speechRecognizer?.cancel()
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatNaturalSpeechText(text: String): String {
        var result = text
        // Convert action tags into spoken emotional interjections
        result = result.replace(Regex("(?i)\\*(sighs?|sighing)\\*"), "Sigh... ")
        result = result.replace(Regex("(?i)\\*(laughs?|laughing|chuckles?|giggles?)\\*"), "Haha! ")
        result = result.replace(Regex("(?i)\\*(gasps?|surprised?)\\*"), "Oh! ")
        result = result.replace(Regex("(?i)\\*(thinks?|pondering?)\\*"), "Hmm... ")
        result = result.replace(Regex("(?i)\\*(smiles?|smiling)\\*"), "Ah, ")

        // Replace code blocks
        result = result.replace(Regex("```[\\s\\S]*?```"), " I've provided a code snippet in the chat. ")
        result = result.replace(Regex("`([^`]+)`"), "$1")
        // Strip markdown headings, bold, italics, strikethroughs, and list symbols
        result = result.replace(Regex("[#_~>]"), " ")
        result = result.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
        result = result.replace(Regex("(?m)^\\s*[-*•]\\s+"), " ")
        result = result.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), " ")
        
        // Convert isolated 4-digit years like 2026 into "twenty twenty-six"
        result = result.replace(Regex("\\b202([0-9])\\b")) { match ->
            val digitWord = when(match.groupValues[1]) {
                "0" -> "zero"
                "1" -> "one"
                "2" -> "two"
                "3" -> "three"
                "4" -> "four"
                "5" -> "five"
                "6" -> "six"
                "7" -> "seven"
                "8" -> "eight"
                "9" -> "nine"
                else -> match.groupValues[1]
            }
            "twenty twenty-$digitWord"
        }

        // Standard natural symbol and jargon pronunciation
        result = result.replace(Regex("&"), " and ")
            .replace(Regex("%"), " percent ")
            .replace(Regex("\\+"), " plus ")
            .replace(Regex("="), " equals ")
            .replace(Regex("\\be\\.g\\.\\b", RegexOption.IGNORE_CASE), "for example")
            .replace(Regex("\\bi\\.e\\.\\b", RegexOption.IGNORE_CASE), "that is")
            .replace(Regex("\\betc\\.\\b", RegexOption.IGNORE_CASE), "and so forth")
            .replace(Regex("\\bAI\\b"), "A I")
            .replace(Regex("\\bAPI\\b"), "A P I")
            .replace(Regex("\\bUI\\b"), "U I")
            .replace(Regex("\\bURL\\b"), "U R L")
            .replace(Regex("\\bTTS\\b"), "T T S")
        
        // Convert double newlines into natural pauses
        result = result.replace(Regex("\\n+"), ", ")
        result = result.replace(Regex("\\s+"), " ").trim()
        return result
    }


    private var isTorchOn = false

    private fun executeSystemControl(action: String, viewModel: JarvisViewModel) {
        val cleanAction = action.lowercase().trim()
        when (cleanAction) {
            "flashlight_on", "torch_on" -> {
                try {
                    val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                    val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                    if (cameraId != null) {
                        cameraManager.setTorchMode(cameraId, true)
                        isTorchOn = true
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            "flashlight_off", "torch_off" -> {
                try {
                    val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                    val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                    if (cameraId != null) {
                        cameraManager.setTorchMode(cameraId, false)
                        isTorchOn = false
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            "flashlight_toggle", "torch_toggle" -> {
                try {
                    val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                    val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                    if (cameraId != null) {
                        isTorchOn = !isTorchOn
                        cameraManager.setTorchMode(cameraId, isTorchOn)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            "flashlight_sos" -> {
                try {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                        val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return@launch
                        for (i in 0..5) {
                            cameraManager.setTorchMode(cameraId, true)
                            delay(200)
                            cameraManager.setTorchMode(cameraId, false)
                            delay(200)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            "mode_silent" -> {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                    Toast.makeText(this, "Ringer mode set to SILENT", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
            "mode_vibrate" -> {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                    Toast.makeText(this, "Ringer mode set to VIBRATE", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
            "mode_normal" -> {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    Toast.makeText(this, "Ringer mode set to NORMAL", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
            "wifi_settings" -> {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (e: Exception) { e.printStackTrace() }
            }
            "bluetooth_settings" -> {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (e: Exception) { e.printStackTrace() }
            }
            "hotspot_settings" -> {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (e: Exception) { e.printStackTrace() }
            }
            "take_screenshot" -> {
                Toast.makeText(this, "Use Power + Vol Down for instant screenshot", Toast.LENGTH_LONG).show()
            }
            "screen_record" -> {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (e: Exception) { e.printStackTrace() }
            }
            "battery_diag" -> {
                try {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                    val rawLevel = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val msg = if (rawLevel in 0..100) {
                        val isCharging = bm?.isCharging ?: false
                        val stateStr = if (isCharging) "Charging" else "Discharging"
                        "Battery Health: Good | Level: $rawLevel% | Status: $stateStr"
                    } else {
                        "Could not read battery level from device system service."
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
            "volume_mute" -> {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, android.media.AudioManager.FLAG_SHOW_UI)
                } catch (e: Exception) { e.printStackTrace() }
            }
            "volume_max" -> {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    if (audioManager != null) {
                        val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVol, android.media.AudioManager.FLAG_SHOW_UI)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            "volume_up" -> {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                } catch (e: Exception) { e.printStackTrace() }
            }
            "volume_down" -> {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                } catch (e: Exception) { e.printStackTrace() }
            }
            "battery_check" -> {
                try {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                    val rawLevel = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val activePersonality = viewModel.selectedPersonality.value
                    val title = if (activePersonality == "friday") "Boss" else ""
                    val msg = if (rawLevel in 0..100) {
                        val isCharging = bm?.isCharging ?: false
                        val stateStr = if (isCharging) "charging" else "discharging"
                        "Battery is at $rawLevel% ($stateStr), $title."
                    } else {
                        "Unable to read battery status from device, $title."
                    }
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
            "open_voice_mode", "open_voice" -> {
                viewModel.setVoiceMode(true)
            }
            "open_settings_modal", "open_api_settings" -> {
                viewModel.setApiModalOpen(true)
            }
            "toggle_theme" -> {
                viewModel.setDarkMode(!viewModel.isDarkMode.value)
            }
            "clear_chat" -> {
                viewModel.startNewChat()
            }
            // Network/Wireless controls - direct Settings.Global write via
            // AurexSystemController (silent, needs the one-time adb
            // WRITE_SECURE_SETTINGS grant), with the verified accessibility
            // tile automation as the fallback.
            "wifi_on" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("WIFI_ON")) {
                    Toast.makeText(this, "Wi-Fi on", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleWifi(this, enable = true)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "wifi_off" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("WIFI_OFF")) {
                    Toast.makeText(this, "Wi-Fi off", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleWifi(this, enable = false)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "wifi_toggle", "toggle_wifi" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("WIFI_ON") || ctrl.executeAssistantCommand("WIFI_OFF")) {
                    Toast.makeText(this, "Wi-Fi toggled", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleWifi(this)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "bluetooth_on" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("BLUETOOTH_ON")) {
                    Toast.makeText(this, "Bluetooth on", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleBluetooth(this, enable = true)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "bluetooth_off" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("BLUETOOTH_OFF")) {
                    Toast.makeText(this, "Bluetooth off", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleBluetooth(this, enable = false)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "bluetooth_toggle", "toggle_bluetooth" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("BLUETOOTH_ON") || ctrl.executeAssistantCommand("BLUETOOTH_OFF")) {
                    Toast.makeText(this, "Bluetooth toggled", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleBluetooth(this)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "airplane_on" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("AIRPLANE_ON")) {
                    Toast.makeText(this, "Airplane mode on", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleAirplaneMode(this, enable = true)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "airplane_off" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("AIRPLANE_OFF")) {
                    Toast.makeText(this, "Airplane mode off", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleAirplaneMode(this, enable = false)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "airplane_toggle", "toggle_airplane" -> {
                val ctrl = com.aurex.agent.AurexSystemController(this)
                if (ctrl.executeAssistantCommand("AIRPLANE_ON") || ctrl.executeAssistantCommand("AIRPLANE_OFF")) {
                    Toast.makeText(this, "Airplane mode toggled", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = com.example.connectors.DeviceControlBridge.toggleAirplaneMode(this)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
            "mobile_data_on" -> {
                val msg = com.example.connectors.DeviceControlBridge.toggleMobileData(this, enable = true)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            "mobile_data_off" -> {
                val msg = com.example.connectors.DeviceControlBridge.toggleMobileData(this, enable = false)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            "mobile_data_toggle" -> {
                val msg = com.example.connectors.DeviceControlBridge.toggleMobileData(this)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            "hotspot_on", "hotspot_off", "hotspot_toggle" -> {
                val acc = com.example.voice.AurexAccessibilityService.instance
                if (acc != null) {
                    val enable = when (action) { "hotspot_on" -> true; "hotspot_off" -> false; else -> null }
                    acc.toggleHotspotTile(enable) { ok, _ ->
                        runOnUiThread {
                            if (ok) Toast.makeText(this, "Hotspot toggled", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(this, "Hotspot tile not found — opening Hotspot settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (enable == null) Toast.makeText(this, "Toggling Hotspot", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this, if (enable == true) "Turning Hotspot on" else "Turning Hotspot off", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        com.example.voice.AurexHotspotService.start(this)
                        Toast.makeText(this, "Opening Hotspot settings", Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        Toast.makeText(this, "Enable the AUREX Accessibility service for hotspot control", Toast.LENGTH_LONG).show()
                    }
                }
            }
            "night_light_on", "night_light_off", "night_light_toggle" -> {
                val acc = com.example.voice.AurexAccessibilityService.instance
                if (acc != null) {
                    val enable = when (action) { "night_light_on" -> true; "night_light_off" -> false; else -> null }
                    acc.toggleNightLightTile(enable) { ok, _ ->
                        runOnUiThread {
                            if (ok) Toast.makeText(this, "Night light toggled", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(this, "Night light tile not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Enable the AUREX Accessibility service for night light control", Toast.LENGTH_LONG).show()
                }
            }
            "brightness_up" -> {
                try {
                    val current = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
                    val max = 255
                    val next = (current + 40).coerceAtMost(max)
                    android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, next)
                    Toast.makeText(this, "Brightness up", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
            "brightness_down" -> {
                try {
                    val current = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
                    val next = (current - 40).coerceAtLeast(10)
                    android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, next)
                    Toast.makeText(this, "Brightness down", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { e.printStackTrace() }
            }
            "dnd_on" -> {
                val notif = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notif.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Toast.makeText(this, "Do Not Disturb on", Toast.LENGTH_SHORT).show()
            }
            "dnd_off" -> {
                val notif = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notif.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                Toast.makeText(this, "Do Not Disturb off", Toast.LENGTH_SHORT).show()
            }
            "battery_saver_on" -> {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (pm.isPowerSaveMode) {
                        Toast.makeText(this, "Battery saver already on", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            "open_accessibility_settings", "enable_accessibility", "accessibility_settings" -> {
                // Deep-links to the page where the AUREX service gets enabled
                // — Realme UI 7 buries this and users can't find it.
                val msg = com.example.connectors.DeviceControlBridge.openAccessibilitySettings(this)
                Toast.makeText(this, if (com.example.voice.AurexAccessibilityService.isEnabled)
                    "$msg — AUREX service already ON" else
                    "$msg — find AUREX in the list and toggle it ON", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun executeAppLaunch(target: String) {
        try {
            val cleanTarget = target.lowercase().trim()
            if (cleanTarget.startsWith("playstore_install:") || cleanTarget.startsWith("playstore:")) {
                val appName = cleanTarget.substringAfter(":").trim()
                val pkgMap = mapOf(
                    "instagram" to "com.instagram.android",
                    "whatsapp" to "com.whatsapp",
                    "eenadu" to "com.eenadu.digital",
                    "telegram" to "org.telegram.messenger",
                    "facebook" to "com.facebook.katana",
                    "twitter" to "com.twitter.android",
                    "x" to "com.twitter.android",
                    "snapchat" to "com.snapchat.android",
                    "spotify" to "com.spotify.music",
                    "youtube" to "com.google.android.youtube",
                    "freefire" to "com.dts.freefireth",
                    "pubg" to "com.pubg.imobile",
                    "bgmi" to "com.pubg.imobile",
                    "flipkart" to "com.flipkart.android",
                    "amazon" to "com.amazon.mShop.android.shopping",
                    "phonepe" to "com.phonepe.app",
                    "gpay" to "com.google.android.apps.nbu.paisa.user",
                    "paytm" to "net.one97.paytm"
                )
                val targetPkg = pkgMap[appName.lowercase()]
                if (targetPkg != null) {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$targetPkg")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    try {
                        startActivity(marketIntent)
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$targetPkg")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                } else {
                    val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(appName)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    try {
                        startActivity(searchIntent)
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(appName)}&c=apps")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                }
                return
            }

            if (cleanTarget == "eenadu" || cleanTarget == "eenadu news") {
                val eenaduIntent = packageManager.getLaunchIntentForPackage("com.eenadu.digital")
                    ?: packageManager.getLaunchIntentForPackage("com.eenadu.epaper")
                if (eenaduIntent != null) {
                    eenaduIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(eenaduIntent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.eenadu.net")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                return
            }

            val intent = when (cleanTarget) {
                "playstore", "play store" -> packageManager.getLaunchIntentForPackage("com.android.vending") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"))
                "camera" -> Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
                "youtube" -> packageManager.getLaunchIntentForPackage("com.google.android.youtube") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com"))
                "maps" -> packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                "chrome", "browser" -> packageManager.getLaunchIntentForPackage("com.android.chrome") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"))
                "gallery", "photos" -> Intent(Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                "phone", "dialer" -> Intent(Intent.ACTION_DIAL)
                "messages", "sms" -> Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
                "calculator" -> packageManager.getLaunchIntentForPackage("com.google.android.calculator") ?: packageManager.getLaunchIntentForPackage("com.android.calculator2") ?: Intent(android.provider.Settings.ACTION_SETTINGS)
                "music", "spotify" -> packageManager.getLaunchIntentForPackage("com.spotify.music") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com"))
                "mail", "gmail" -> packageManager.getLaunchIntentForPackage("com.google.android.gm") ?: Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"))
                "calendar" -> Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/").buildUpon().appendPath(System.currentTimeMillis().toString()).build())
                "contacts" -> Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI)
                "notes", "keep" -> packageManager.getLaunchIntentForPackage("com.google.android.keep") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://keep.google.com"))
                "alarm", "clock" -> Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                else -> packageManager.getLaunchIntentForPackage(target)
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handlePickedFile(uri: Uri, viewModel: JarvisViewModel) {
        val contentResolver = contentResolver
        var name = "device_file"
        var size = "Unknown"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) {
                    val rawSize = cursor.getLong(sizeIndex)
                    size = "${rawSize / 1024} KB"
                }
            }
        }

        val lowerName = name.lowercase()
        val isPdf = mimeType.contains("pdf") || lowerName.endsWith(".pdf")
        val isImage = mimeType.startsWith("image/") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp")
        val isText = mimeType.startsWith("text/") || 
                     lowerName.endsWith(".txt") || lowerName.endsWith(".json") || 
                     lowerName.endsWith(".csv") || lowerName.endsWith(".kt") || 
                     lowerName.endsWith(".java") || lowerName.endsWith(".py") || 
                     lowerName.endsWith(".xml") || lowerName.endsWith(".html") || 
                     lowerName.endsWith(".md") || lowerName.endsWith(".doc") || 
                     lowerName.endsWith(".docx") || lowerName.endsWith(".log")

        var fileContent: String? = null
        var fileType = "text"

        if (isPdf) {
            fileType = "pdf"
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val pdfRenderer = android.graphics.pdf.PdfRenderer(pfd)
                    val pageCount = pdfRenderer.pageCount
                    if (pageCount > 0) {
                        val page = pdfRenderer.openPage(0)
                        val renderWidth = 1080
                        val renderHeight = (renderWidth * (page.height.toFloat() / page.width.toFloat())).toInt().coerceAtLeast(300)
                        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()

                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        val bytes = baos.toByteArray()
                        val rawBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        fileContent = com.example.api.compressAndDownscaleBase64Image(rawBase64, maxDim = 1280, maxQuality = 80)
                    }
                    pdfRenderer.close()
                }
            } catch (e: Exception) {
                fileContent = null
            }
        } else if (isImage) {
            fileType = "image"
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val rawBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    fileContent = com.example.api.compressAndDownscaleBase64Image(rawBase64, maxDim = 1280, maxQuality = 80)
                }
            } catch (e: Exception) {
                fileContent = null
            }
        } else {
            fileType = "text"
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    fileContent = stream.bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                fileContent = "Error reading document content: ${e.localizedMessage}"
            }
        }

        viewModel.setAttachedFile(
            AttachedFile(
                name = name,
                uri = uri.toString(),
                type = fileType,
                size = size,
                content = fileContent
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        enableEdgeToEdge()

        val app = application as JarvisApplication
        val viewModel: JarvisViewModel by viewModels {
            android.util.Log.d("JarvisViewModel", "Factory lambda called")
            JarvisViewModel.Factory(app.repository, app)
        }
        android.util.Log.d("JarvisViewModel", "ViewModel obtained: $viewModel")
        mainViewModelRef = viewModel

        // Overlay voice HUD -> route recognized commands back into the app.
        val overlayCommandReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val text = intent?.getStringExtra("text") ?: return
                if (text.isNotBlank()) {
                    val vm = mainViewModelRef ?: return
                    lifecycleScope.launch {
                        vm.setVoiceMode(true)
                        vm.handleSend(text)
                    }
                }
            }
        }
        registerReceiver(overlayCommandReceiver, IntentFilter(com.example.voice.AurexOverlayService.ACTION_COMMAND), Context.RECEIVER_NOT_EXPORTED)

        // Diagnostic: trigger a spoken test sentence from adb.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                ttsManager?.speak("Hello sir, this is AUREX. My Kitten TTS voice engine is working now.")
            }
        }, IntentFilter("com.aistudio.jarvis.TEST_TTS"), Context.RECEIVER_NOT_EXPORTED)

        // Background services are opt-in (started from Settings) to avoid
        // ColorOS foreground-service restrictions crashing the app on open.
        // Un-comment to auto-start once the user grants everything.
        /*
        lifecycleScope.launch {
            try {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    com.example.voice.AurexWakeService.start(this@MainActivity)
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "Wake service start skipped", t)
            }
            try {
                if (android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    com.example.voice.AurexHotspotService.start(this@MainActivity)
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "Hotspot service start skipped", t)
            }
        }
        */

        // Launch straight into voice mode if opened from the hotword.
        if (intent.getBooleanExtra("auto_voice", false)) {
            mainViewModelRef?.setVoiceMode(true)
        }

        // Calendar permissions launcher - triggered when a connector action needs calendar access
        val calendarPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (grants.values.all { it }) {
                android.widget.Toast.makeText(
                    this, "Calendar connected. Ask for your agenda again.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                android.widget.Toast.makeText(
                    this, "Calendar permission denied - agenda features unavailable",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        lifecycleScope.launch {
            viewModel.requestCalendarPermissions.collect {
                calendarPermissionLauncher.launch(com.example.data.DeviceCalendarEngine.requiredPermissions())
            }
        }

        lifecycleScope.launch {
            viewModel.connectorTestLog.collect { log ->
                if (log.isNotBlank()) {
                    val parts = log.split("|")
                    if (parts.size >= 2) {
                        val name = parts[0].replaceFirstChar { it.uppercase() }
                            .replace("_", " ")
                            .let { if (it == "N8n") "n8n" else it }
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "$name: ${parts[1]}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Initialize Spotify, Voice, and Telephony managers
        spotifyController = com.example.spotify.SpotifyController(this)
        musicCommandHandler = com.example.spotify.MusicCommandHandler(this, spotifyController!!)
        ttsPlaybackManager = com.example.voice.TTSPlaybackManager(this)

        conversationManager = com.example.voice.ConversationManager(
            context = this,
            onUtterance = { text ->
                mainViewModelRef?.handleSend(text)
            },
            onBargeIn = {
                ttsPlaybackManager?.stop()
                ttsManager?.stop()
            }
        )

        callStateManager = com.example.telephony.CallStateManager(
            context = this,
            onCallStarted = {
                stopListening()
                conversationManager?.stop()
                mainViewModelRef?.setVoiceStatus("Call Active (Paused)")
            },
            onCallEnded = {
                if (mainViewModelRef?.isVoiceMode?.value == true) {
                    mainViewModelRef?.setVoiceStatus("Listening...")
                    conversationManager?.start()
                    startListening(mainViewModelRef!!)
                }
            }
        )

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            callStateManager?.start()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_PHONE_STATE),
                1024
            )
        }

        // Initialize Kitten TTS - lightweight male JARVIS-like voice (~25-45MB, low latency)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ttsManager = KittenTtsHelper(this@MainActivity)
                // When the voice finishes, re-open the mic in voice mode. This is
                // the authoritative end-of-speech signal (the fixed 300ms guess
                // missed late-starting playback and left the app deaf, or fired
                // early and collided with still-speaking audio).
                ttsManager?.onAudioChunk = { amp ->
                    runOnUiThread { VoiceLevelBus.push(amp) }
                }
                ttsManager?.onSpeakingFinished = {
                    runOnUiThread {
                        val vm = mainViewModelRef ?: return@runOnUiThread
                        ttsQueueActive = false
                        VoiceLevelBus.chunkFinished(ttsManager?.lastUtteranceSeconds())
                        if (!vm.isVoiceMode.value) {
                            stopTtsQueue()
                            return@runOnUiThread
                        }
                        // VoicePipeline: next sentence streams behind the last
                        if (ttsQueue.isNotEmpty()) {
                            ttsQueueActive = true
                            ttsManager?.speak(ttsQueue.poll(), speed = vm.ttsSpeechRate.value)
                        } else if (!vm.isGenerating.value && !isAnySpeaking()) {
                            vm.setVoiceStatus("Listening...")
                            startListening(vm)
                        }
                    }
                }
                val initialized = ttsManager?.init() ?: false
                if (initialized) {
                    Log.i("MainActivity", "Kitten TTS initialized successfully with JARVIS-like voice")
                } else {
                    Log.w("MainActivity", "Kitten TTS model assets missing - will use fallback")
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "Failed to initialize KittenTtsHelper", t)
            }
        }

        // Initialize SpeechRecognizer
        createAndSetupSpeechRecognizer(viewModel)

        // Register audio permission launcher
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                if (viewModel.isVoiceMode.value) {
                    startListening(viewModel)
                }
            } else {
                viewModel.setVoiceMode(false)
            }
        }

        // Register file picker launcher
        val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handlePickedFile(it, viewModel) }
        }

        // Register camera launcher
        val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            bitmap?.let { bmp ->
                val byteArrayOutputStream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
                val byteArray = byteArrayOutputStream.toByteArray()
                val base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                viewModel.setAttachedFile(
                    AttachedFile(
                        name = "Camera_${System.currentTimeMillis()}.jpg",
                        uri = "camera://${System.currentTimeMillis()}",
                        type = "image",
                        size = "${byteArray.size / 1024} KB",
                        content = base64Image
                    )
                )
            }
        }

        // Connect flows to lifecycle
        lifecycleScope.launch {
            viewModel.ttsSpeechRate.collect { rate ->
                ttsManager?.setSpeechRateMultiplier(rate)
            }
        }

        lifecycleScope.launch {
            viewModel.stopTtsEvent.collect {
                try {
                    stopTtsQueue()
                    ttsManager?.stop()
                    VoiceLevelBus.reset()
                    VoiceLevelBus.resetReply()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // VoicePipeline: feed the orb the real playback position of the voice
        lifecycleScope.launch {
            while (isActive) {
                VoiceLevelBus.playbackSec = ttsManager?.playbackSeconds()
                kotlinx.coroutines.delay(40)
            }
        }

        lifecycleScope.launch {
            viewModel.speakTextEvent.collect { text ->
                try {
                    if (viewModel.isTtsMuted.value) {
                        // TTS muted: onSpeakingFinished will never fire, so the
                        // mic must re-arm here or voice mode goes deaf after
                        // one muted turn.
                        if (viewModel.isVoiceMode.value && !viewModel.isGenerating.value && !isAnySpeaking()) {
                            viewModel.setVoiceStatus("Listening...")
                            startListening(viewModel)
                        }
                        return@collect
                    }
                    // VoicePipeline: the mic stays live during playback so the
                    // user can barge in — onBeginningOfSpeech stops playback.
                    val activePersonality = viewModel.selectedPersonality.value
                    val cleanText = formatNaturalSpeechText(text)
                    if (cleanText.isNotBlank()) {
                        viewModel.setVoicePartialText(cleanText)
                        viewModel.setVoiceStatus("Speaking...")

                        pipelineSpeakAll(cleanText, viewModel.ttsSpeechRate.value)
                        // Each sentence hands off to the next inside
                        // onSpeakingFinished; the mic re-arms when the queue
                        // drains. No fixed-delay guessing, no mic cycling.
                    } else {
                        // Nothing speakable (empty after cleanup): no TTS will
                        // start, so onSpeakingFinished never fires. Re-arm the
                        // mic ourselves.
                        if (viewModel.isVoiceMode.value && !viewModel.isGenerating.value && !isAnySpeaking()) {
                            viewModel.setVoiceStatus("Listening...")
                            startListening(viewModel)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (viewModel.isVoiceMode.value) {
                        viewModel.setVoiceStatus("Listening...")
                        startListening(viewModel)
                    }
                }
            }
        }

        var lastVoiceModeState = false
        lifecycleScope.launch {
            viewModel.isVoiceMode.collect { enabled ->
                try {
                    if (enabled != lastVoiceModeState) {
                        lastVoiceModeState = enabled
                        try {
                            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            if (enabled) am.unloadSoundEffects() else am.loadSoundEffects()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (enabled) {
                        ttsManager?.stop()
                        viewModel.setVoiceStatus("Listening...")
                        viewModel.setVoicePartialText("")
                        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startListening(viewModel)
                        } else {
                            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        stopListening()
                        try {
                            ttsManager?.stop()
                        } catch (e: Exception) { e.printStackTrace() }
                        VoiceLevelBus.reset()
                        VoiceLevelBus.resetReply()
                        viewModel.setVoiceStatus("Ready")
                        viewModel.setVoicePartialText("")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.appIntentEvent.collect { appAction ->
                try {
                    when {
                        appAction.startsWith("sys:") || appAction.startsWith("SYSTEM_ACTION:") -> {
                            val action = appAction.substringAfter("sys:").substringAfter("SYSTEM_ACTION:").trim()
                            executeSystemControl(action, viewModel)
                        }
                        appAction.startsWith("app_launch:") || appAction.startsWith("APP_LAUNCH:") -> {
                            val target = appAction.substringAfter("app_launch:").substringAfter("APP_LAUNCH:").trim()
                            executeAppLaunch(target)
                        }
                        appAction.startsWith("music:") -> {
                            val query = appAction.substringAfter("music:").trim()
                            musicCommandHandler?.handlePlayCommand(query)
                        }
                        appAction.startsWith("ytsearch:") -> {
                            val query = appAction.substringAfter("ytsearch:")
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        appAction.startsWith("websearch:") -> {
                            var query = appAction.substringAfter("websearch:").trim()
                            if (query.isBlank() || query.lowercase() == "query" || query.lowercase() == "search query") {
                                query = "latest news"
                            }
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        appAction.startsWith("set_alarm:") -> {
                            val params = appAction.substringAfter("set_alarm:")
                            var hour = 7
                            var minute = 0
                            var label = "Alarm"
                            params.split(",").forEach { kv ->
                                val parts = kv.split("=")
                                if (parts.size == 2) {
                                    when (parts[0].trim()) {
                                        "hour" -> hour = parts[1].trim().toIntOrNull() ?: 7
                                        "minute" -> minute = parts[1].trim().toIntOrNull() ?: 0
                                        "label" -> label = parts[1].trim()
                                    }
                                }
                            }
                            val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                                putExtra(android.provider.AlarmClock.EXTRA_VIBRATE, false)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                startActivity(alarmIntent)
                            } catch (e: Exception) {
                                try {
                                    startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                } catch (e2: Exception) {
                                    android.widget.Toast.makeText(this@MainActivity, "No clock app found to set the alarm", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        appAction.startsWith("whatsapp:") -> {
                            val msgData = appAction.substringAfter("whatsapp:")
                            val target = msgData.substringBefore("|").trim()
                            val message = msgData.substringAfter("|", "").trim()
                            sendWhatsAppMessage(target, message)
                        }
                        appAction.startsWith("sms:") -> {
                            val msgData = appAction.substringAfter("sms:")
                            val number = msgData.substringBefore("|").trim()
                            val message = msgData.substringAfter("|", "").trim()
                            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                                putExtra("sms_body", message)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(smsIntent)
                        }
                        appAction.startsWith("call:") -> {
                            val target = appAction.substringAfter("call:").trim()
                            makeDirectPhoneCall(target)
                        }
                        appAction.startsWith("alarm:") -> {
                            startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        appAction.startsWith("timer:") -> {
                            startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        appAction.startsWith("navigate:") -> {
                            val dest = appAction.substringAfter("navigate:").trim()
                            val gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(dest))
                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                                setPackage("com.google.android.apps.maps")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                startActivity(mapIntent)
                            } catch (e: Exception) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                            }
                        }
                        appAction.startsWith("calculate:") -> {
                            val expr = appAction.substringAfter("calculate:").trim()
                            val resStr = evaluateMathExpression(expr)
                            Toast.makeText(this@MainActivity, resStr, Toast.LENGTH_LONG).show()
                        }
                        appAction.startsWith("save_note:") -> {
                            val noteText = appAction.substringAfter("save_note:").trim()
                            if (noteText.isNotBlank()) {
                                viewModel.saveNote(noteText)
                                Toast.makeText(this@MainActivity, "Note saved: '$noteText'", Toast.LENGTH_LONG).show()
                            }
                        }
                        appAction == "read_notes" || appAction.startsWith("read_notes") -> {
                            val notes = viewModel.allNotes.value
                            if (notes.isEmpty()) {
                                Toast.makeText(this@MainActivity, "No saved notes found", Toast.LENGTH_SHORT).show()
                            } else {
                                val notesStr = notes.take(5).mapIndexed { idx, note -> "${idx + 1}. ${note.content}" }.joinToString("\n")
                                Toast.makeText(this@MainActivity, "Your Notes:\n$notesStr", Toast.LENGTH_LONG).show()
                            }
                        }
                        appAction.startsWith("translate:") -> {
                            val query = appAction.substringAfter("translate:").trim()
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=te&text=" + Uri.encode(query))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        appAction.startsWith("weather:") -> {
                            val query = appAction.substringAfter("weather:").trim()
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        else -> {
                            executeAppLaunch(appAction)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        setContent {
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            
            MaterialTheme(
                colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
            ) {
                CompositionLocalProvider(
                    LocalFilePicker provides filePickerLauncher,
                    LocalCameraLauncher provides cameraLauncher
                ) {
                    JarvisAppShell(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            ttsManager?.rescanModels()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error rescanning models on resume", e)
        }
        // After a payment the accessibility service self-disabled (UPI apps
        // refuse to pay while any 3rd-party accessibility service runs).
        // Coming back to AUREX: restore it silently if we hold
        // WRITE_SECURE_SETTINGS, otherwise leave the one-tap re-enable
        // prompt to the user via the existing service-off banner flow.
        if (com.example.voice.PaymentModeManager.serviceYieldedForPayment &&
            !com.example.voice.AurexAccessibilityService.isEnabled
        ) {
            val restored = com.example.voice.PaymentModeManager.trySilentRestore(this)
            android.util.Log.i(
                "PaymentMode",
                if (restored) "Accessibility restored after payment" else "Accessibility needs manual re-enable after payment"
            )
        }
    }

    fun checkAndRequestStoragePermissions() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            } else {
                val permissions = arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    androidx.core.app.ActivityCompat.requestPermissions(this, permissions, 2048)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to check or request storage permissions", e)
        }
    }

    override fun onDestroy() {
        try {
            conversationManager?.stop()
            ttsPlaybackManager?.stop()
            callStateManager?.stop()
            spotifyController?.disconnect()
            ttsManager?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }
}

// CompositionLocal for the file picker launcher
val LocalFilePicker = staticCompositionLocalOf<androidx.activity.result.ActivityResultLauncher<String>?> { null }
val LocalCameraLauncher = staticCompositionLocalOf<androidx.activity.result.ActivityResultLauncher<Void?>?> { null }

@Composable
fun JarvisAppShell(viewModel: JarvisViewModel) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val isSidebarOpen by viewModel.isSidebarOpen.collectAsStateWithLifecycle()
    val chatState by viewModel.chatState.collectAsStateWithLifecycle()
    val currentMessages by viewModel.currentMessages.collectAsStateWithLifecycle()
    val isVoiceMode by viewModel.isVoiceMode.collectAsStateWithLifecycle()
    val isApiModalOpen by viewModel.isApiModalOpen.collectAsStateWithLifecycle()
    val isPersonalityModalOpen by viewModel.isPersonalityModalOpen.collectAsStateWithLifecycle()
    val isConnectorsModalOpen by viewModel.isConnectorsModalOpen.collectAsStateWithLifecycle()
    val isSettingsModalOpen by viewModel.isSettingsModalOpen.collectAsStateWithLifecycle()
    val isLiveVisionOpen by viewModel.isLiveVisionOpen.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkMode) Color(0xFF000000) else Color(0xFFF0F4F9))
    ) {
        val width = maxWidth
        val isTablet = width >= 768.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // Responsive Sidebar
            if (isTablet) {
                AnimatedVisibility(
                    visible = isSidebarOpen,
                    enter = slideInHorizontally { -it } + fadeIn(),
                    exit = slideOutHorizontally { -it } + fadeOut()
                ) {
                    SidebarContent(
                        viewModel = viewModel,
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                    )
                }
            }

            // Main Content Container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .imePadding()
                ) {
                    // Glass Header
                    Header(
                        viewModel = viewModel,
                        isTablet = isTablet,
                        isSidebarOpen = isSidebarOpen
                    )

                    // Chat Screen or Home screen
                    Box(modifier = Modifier.weight(1f)) {
                        if (chatState == ChatUiState.HOME) {
                            HomeScreen(viewModel)
                        } else {
                            ChatScreen(viewModel, currentMessages)
                        }
                    }

                    // Voice Mode Overlay - Pop out directly from behind the bottom chat bar
                    this.AnimatedVisibility(
                        visible = isVoiceMode,
                        enter = slideInVertically(
                            initialOffsetY = { fullHeight -> fullHeight },
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)
                        ) + expandVertically(
                            expandFrom = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 350)
                        ) + scaleIn(
                            initialScale = 0.7f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.0f),
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)
                        ) + fadeIn(animationSpec = tween(durationMillis = 250)),
                        exit = slideOutVertically(
                            targetOffsetY = { fullHeight -> fullHeight },
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                        ) + shrinkVertically(
                            shrinkTowards = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 300)
                        ) + scaleOut(
                            targetScale = 0.7f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.0f),
                            animationSpec = tween(durationMillis = 300)
                        ) + fadeOut(animationSpec = tween(durationMillis = 200))
                    ) {
                        VoiceModeOverlay(viewModel)
                    }

                    // Input controls (Bottom chat bar sitting in front)
                    InputBar(viewModel, isVoiceMode)
                }
            }
        }

        // Sidebar drawer overlay for Mobile
        AnimatedVisibility(
            visible = !isTablet && isSidebarOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isDarkMode) 0.6f else 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { viewModel.setSidebarOpen(false) }
            )
        }

        // Animated slide-out drawer
        AnimatedVisibility(
            visible = !isTablet && isSidebarOpen,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f)
            ) + fadeIn(tween(180)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
            ) {
                SidebarContent(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Config Modal
    if (isApiModalOpen) {
        ApiConfigModal(viewModel)
    }

    // Personality Modal
    if (isPersonalityModalOpen) {
        PersonalityModal(viewModel)
    }

    // Connectors Modal
    if (isConnectorsModalOpen) {
        ConnectorsModal(viewModel)
    }

    // Settings Modal
    if (isSettingsModalOpen) {
        SettingsModal(viewModel)
    }

    // Gallery Modal
    val isGalleryModalOpen by viewModel.isGalleryModalOpen.collectAsStateWithLifecycle()
    if (isGalleryModalOpen) {
        GalleryModal(viewModel)
    }

    // NVIDIA NIM Live Vision Overlay / Modal
    if (isLiveVisionOpen) {
        LiveVisionModal(viewModel)
    }

    // JARVIS direct messaging window
    val isJarvisChatOpen by viewModel.isJarvisChatOpen.collectAsStateWithLifecycle()
    if (isJarvisChatOpen) {
        JarvisChatWindow(viewModel)
    }

    // Vision source chooser
    val isVisionChooserOpen by viewModel.isVisionChooserOpen.collectAsStateWithLifecycle()
    if (isVisionChooserOpen) {
        VisionSourceChooserDialog(viewModel)
    }
}

@Composable
fun RealtimeClockText(isDarkMode: Boolean) {
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.US)
        while (true) {
            currentTime = sdf.format(java.util.Date())
            kotlinx.coroutines.delay(1000)
        }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isDarkMode) 0.10f else 0.9f),
                        Color.White.copy(alpha = if (isDarkMode) 0.04f else 0.6f)
                    )
                )
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = if (isDarkMode) 0.14f else 0.95f)),
                RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PulsingStatusDot(AurexColors.Cyan, size = 5.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = currentTime.ifEmpty { "--:--:--" },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF334155)
            )
        }
    }
}

@Composable
private fun PulsingStatusDot(color: Color, size: Dp = 7.dp) {
    val pulse = rememberInfiniteTransition(label = "statusDot")
    val glow by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotGlow"
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size * 2.2f)
                .graphicsLayer { alpha = (1f - glow) * 0.6f }
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer { alpha = 0.5f + glow * 0.5f }
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
fun Header(
    viewModel: JarvisViewModel,
    isTablet: Boolean,
    isSidebarOpen: Boolean
) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isSidebarOpen || !isTablet) {
            val menuInteraction = remember { MutableInteractionSource() }
            val menuPressed by menuInteraction.collectIsPressedAsState()
            val menuScale by animateFloatAsState(
                targetValue = if (menuPressed) 0.90f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
                label = "menuScale"
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = menuScale
                        scaleY = menuScale
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (isDarkMode) 0.12f else 0.85f),
                                Color.White.copy(alpha = if (isDarkMode) 0.05f else 0.55f)
                            )
                        )
                    )
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = if (isDarkMode) 0.16f else 0.9f)),
                        CircleShape
                    )
                    .clickable(
                        interactionSource = menuInteraction,
                        indication = null
                    ) { viewModel.setSidebarOpen(true) }
                    .testTag("menu_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open Sidebar",
                    tint = if (isDarkMode) Color(0xFFE8F5EF) else Color(0xFF0F172A),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SidebarActionRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    isDarkMode: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AurexMotion.snappySpring(),
        label = "sideActionScale"
    )
    val rowBg by animateColorAsState(
        targetValue = if (pressed) tint.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "sideActionBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.15f))
                    .border(BorderStroke(1.dp, tint.copy(alpha = 0.32f)), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDarkMode) Color.LightGray else Color.DarkGray
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun SidebarContent(
    viewModel: JarvisViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val currentMessages by viewModel.currentMessages.collectAsStateWithLifecycle()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()
    val isStreamingEnabled by viewModel.isStreamingEnabled.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val gmailConnected by viewModel.gmailConnected.collectAsStateWithLifecycle()
    val driveConnected by viewModel.driveConnected.collectAsStateWithLifecycle()
    val calendarConnected by viewModel.calendarConnected.collectAsStateWithLifecycle()
    val webSearchConnected by viewModel.webSearchConnected.collectAsStateWithLifecycle()
    val osBridgeConnected by viewModel.osBridgeConnected.collectAsStateWithLifecycle()
    val callServerUrl by viewModel.callServerUrl.collectAsStateWithLifecycle()
    val messagingServerUrl by viewModel.messagingServerUrl.collectAsStateWithLifecycle()
    val activeConnectorCount = listOf(
        gmailConnected, driveConnected, calendarConnected, webSearchConnected, osBridgeConnected
    ).count { it }
    // JARVIS Inbox - collect threads/messages at top
    val jarvisThreads by viewModel.jarvisThreads.collectAsStateWithLifecycle()
    val activeThreadId by viewModel.activeThreadId.collectAsStateWithLifecycle()
    val threadMessages by viewModel.threadMessages.collectAsStateWithLifecycle()
    val jarvisEvents by viewModel.jarvisEvents.collectAsStateWithLifecycle()
    val showJarvisInbox by viewModel.isJarvisInboxOpen.collectAsStateWithLifecycle()
    val inboxTab by viewModel.jarvisInboxTab.collectAsStateWithLifecycle()

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val jsonString = viewModel.exportCurrentChatAsJson()
                val stream = context.contentResolver.openOutputStream(uri)
                stream?.use {
                    it.write(jsonString.toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(context, "Chat exported successfully!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            if (isDarkMode) Color(0xFF101010) else Color.White.copy(alpha = 0.94f),
                            if (isDarkMode) Color(0xFF000000) else Color(0xFFF3F7F5).copy(alpha = 0.90f)
                        )
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        listOf(
                            Color(0xFFEFEFEF).copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = Offset(0f, size.height * 0.10f),
                        radius = size.width * 1.7f
                    )
                )
            }
            .border(BorderStroke(1.dp, AurexColors.sheenBorder(isDarkMode)))
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // New Chat area
        Box(modifier = Modifier.padding(16.dp)) {
            val newChatInteraction = remember { MutableInteractionSource() }
            val newChatPressed by newChatInteraction.collectIsPressedAsState()
            val newChatScale by animateFloatAsState(
                targetValue = if (newChatPressed) 0.96f else 1f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 450f),
                label = "newChatScale"
            )
            Button(
                onClick = { viewModel.startNewChat() },
                interactionSource = newChatInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .graphicsLayer {
                        scaleX = newChatScale
                        scaleY = newChatScale
                    }
                    .testTag("new_chat_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDarkMode) Color(0xFF1C1C1C) else Color(0xFFEAEAEA),
                    contentColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF101010)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    Color(0xFFE4E4E4).copy(alpha = if (isDarkMode) 0.35f else 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFFE4E4E4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Chat",
                                tint = Color(0xFF0A0A0A),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("New chat", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "More",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // JARVIS Inbox Section (collapsible with tabs)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header row with toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { viewModel.setJarvisInboxOpen(!showJarvisInbox) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "JARVIS Inbox",
                        tint = Color(0xFFEFEFEF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "JARVIS Inbox",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        letterSpacing = 1.sp
                    )
                    val totalItems = jarvisThreads.size
                    if (totalItems > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val badgePulse = rememberInfiniteTransition(label = "inboxBadge")
                        val badgeScale by badgePulse.animateFloat(
                            initialValue = 0.92f,
                            targetValue = 1.08f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(900, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "badgeScale"
                        )
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(18.dp)
                                .graphicsLayer {
                                    scaleX = badgeScale
                                    scaleY = badgeScale
                                }
                                .background(Color(0xFFE4E4E4), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$totalItems",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                Icon(
                    imageVector = if (showJarvisInbox) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showJarvisInbox) "Collapse" else "Expand",
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expandable content with tabs
            AnimatedVisibility(
                visible = showJarvisInbox,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    // Tab pills (icon only)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val msgTabColor by animateColorAsState(
                            targetValue = if (inboxTab == 0) Color(0xFFEFEFEF).copy(alpha = 0.16f) else Color.Transparent,
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            label = "msgTabBg"
                        )
                        val callTabColor by animateColorAsState(
                            targetValue = if (inboxTab == 1) Color(0xFFE4E4E4).copy(alpha = 0.16f) else Color.Transparent,
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            label = "callTabBg"
                        )
                        val tabPulse = rememberInfiniteTransition(label = "tabPulse")
                        val msgTabScale by tabPulse.animateFloat(
                            initialValue = 1f,
                            targetValue = if (inboxTab == 0) 1.12f else 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1300, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "msgTabScale"
                        )
                        val callTabScale by tabPulse.animateFloat(
                            initialValue = 1f,
                            targetValue = if (inboxTab == 1) 1.12f else 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1300, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "callTabScale"
                        )
                        // Messages tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .graphicsLayer {
                                    scaleX = msgTabScale
                                    scaleY = msgTabScale
                                }
                                .background(msgTabColor, RoundedCornerShape(8.dp))
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (inboxTab == 0) Color(0xFFEFEFEF).copy(alpha = 0.4f) else Color.Transparent
                                    ),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { viewModel.setJarvisInboxOpen(true, 0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = "Messages",
                                tint = if (inboxTab == 0) Color(0xFFE4E4E4) else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Calls tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .graphicsLayer {
                                    scaleX = callTabScale
                                    scaleY = callTabScale
                                }
                                .background(callTabColor, RoundedCornerShape(8.dp))
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (inboxTab == 1) Color(0xFFE4E4E4).copy(alpha = 0.4f) else Color.Transparent
                                    ),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { viewModel.setJarvisInboxOpen(true, 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Calls",
                                tint = if (inboxTab == 1) Color(0xFFE4E4E4) else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Tab content
                    when (inboxTab) {
                        0 -> JarvisMessagesTab(
                            viewModel = viewModel,
                            threads = jarvisThreads,
                            activeThreadId = activeThreadId,
                            threadMessages = threadMessages,
                            isDarkMode = isDarkMode,
                            onThreadClick = { threadId -> viewModel.loadThreadMessages(threadId) }
                        )
                        1 -> JarvisCallsTab(
                            viewModel = viewModel,
                            events = jarvisEvents,
                            isDarkMode = isDarkMode
                        )
                    }
                }
            }
        }

        // Chat History List
        Text(
            text = "History",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            letterSpacing = 1.sp
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(sessions, key = { it.id }) { session ->
                val isSelected = session.id == currentSessionId
                val rowInteraction = remember { MutableInteractionSource() }
                val rowPressed by rowInteraction.collectIsPressedAsState()
                val rowScale by animateFloatAsState(
                    targetValue = if (rowPressed) 0.97f else 1f,
                    animationSpec = AurexMotion.snappySpring(),
                    label = "historyPress"
                )
                val rowBg by animateColorAsState(
                    targetValue = when {
                        isSelected && isDarkMode -> Color(0xFFEFEFEF).copy(alpha = 0.14f)
                        isSelected -> Color(0xFFEFEFEF).copy(alpha = 0.09f)
                        rowPressed -> Color.White.copy(alpha = if (isDarkMode) 0.06f else 0.45f)
                        else -> Color.Transparent
                    },
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    label = "historyBg"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (isSelected) Color(0xFFE4E4E4) else Color.Gray,
                    animationSpec = tween(240),
                    label = "historyIcon"
                )
                val titleColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> Color(0xFFEFEFEF)
                        isDarkMode -> Color.LightGray
                        else -> Color.DarkGray
                    },
                    animationSpec = tween(240),
                    label = "historyTitle"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = rowScale
                            scaleY = rowScale
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(rowBg)
                        .clickable(
                            interactionSource = rowInteraction,
                            indication = null
                        ) { viewModel.selectSession(session) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Message,
                            contentDescription = "Session",
                            tint = iconTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = session.title,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val deleteInteraction = remember { MutableInteractionSource() }
                    val deletePressed by deleteInteraction.collectIsPressedAsState()
                    val deleteScale by animateFloatAsState(
                        targetValue = if (deletePressed) 0.80f else 1f,
                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
                        label = "deleteScale"
                    )
                    IconButton(
                        onClick = { viewModel.deleteSession(session) },
                        interactionSource = deleteInteraction,
                        modifier = Modifier.size(24.dp).graphicsLayer {
                            scaleX = deleteScale
                            scaleY = deleteScale
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete conversation",
                            tint = if (deletePressed) Color(0xFFF87171) else Color.Gray.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Bottom Config Area - normal vertical rows, anchored to the bottom
        // of the drawer below the chat history list
        Divider(color = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Settings Button
            SidebarActionRow(
                icon = Icons.Default.Settings,
                tint = Color(0xFFEDEDED),
                label = "Settings",
                isDarkMode = isDarkMode
            ) { viewModel.setSettingsModalOpen(true) }

            SidebarActionRow(
                icon = Icons.Default.PhotoLibrary,
                tint = Color(0xFFEDEDED),
                label = "Gallery",
                isDarkMode = isDarkMode,
                trailing = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFE4E4E4).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "Images",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE4E4E4)
                        )
                    }
                }
            ) { viewModel.setGalleryModalOpen(true) }

            SidebarActionRow(
                icon = Icons.Default.Hub,
                tint = Color(0xFFEDEDED),
                label = "Connectors",
                isDarkMode = isDarkMode,
                trailing = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFE4E4E4).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$activeConnectorCount Active",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE4E4E4)
                        )
                    }
                }
            ) { viewModel.setConnectorsModalOpen(true) }

            SidebarActionRow(
                icon = Icons.Default.VpnKey,
                tint = Color(0xFFEDEDED),
                label = "API Configuration",
                isDarkMode = isDarkMode
            ) { viewModel.setApiModalOpen(true) }

            SidebarActionRow(
                icon = if (isDarkMode) Icons.Default.WbSunny else Icons.Default.NightsStay,
                tint = Color(0xFFEDEDED),
                label = if (isDarkMode) "Light Mode" else "Dark Mode",
                isDarkMode = isDarkMode
            ) { viewModel.setDarkMode(!isDarkMode) }

            SidebarActionRow(
                icon = Icons.Default.Download,
                tint = Color(0xFFEDEDED),
                label = "Download Chat",
                isDarkMode = isDarkMode
            ) {
                if (currentMessages.isEmpty()) {
                    android.widget.Toast.makeText(context, "No chat history to export", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val fileName = "jarvis_chat_${currentSessionId ?: System.currentTimeMillis()}.json"
                    exportLauncher.launch(fileName)
                }
            }
        }
    }
}

private fun buildGreeting(): String {
    val calendar = java.util.Calendar.getInstance()
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val day = calendar.get(java.util.Calendar.DAY_OF_WEEK)

    val pool = mutableListOf<String>()
    when (hour) {
        in 5..11 -> pool += listOf(
            "Good morning, ready.",
            "Hello, good morning.",
            "Good morning, Sir.",
            "A fine morning indeed.",
            "Morning looks good today."
        )
        in 12..16 -> pool += listOf(
            "Good afternoon, ready.",
            "Hello, good afternoon.",
            "A pleasant afternoon, Sir.",
            "Good afternoon to you."
        )
        in 17..21 -> pool += listOf(
            "Good evening, ready.",
            "Hello, good evening.",
            "A calm evening, Sir.",
            "Good evening to you."
        )
        else -> pool += listOf(
            "Good night, still up.",
            "Hello, late nighter.",
            "Working late tonight?",
            "A quiet night, Sir."
        )
    }
    when (day) {
        java.util.Calendar.MONDAY -> pool += "Monday kickoff, ready."
        java.util.Calendar.FRIDAY -> pool += "Happy Friday, Sir."
        java.util.Calendar.SATURDAY -> pool += "Saturday chill mode."
        java.util.Calendar.SUNDAY -> pool += "Sunday reset, ready."
    }
    return pool.random()
}

private object SessionGreeting {
    val text: String = buildGreeting()
}

@Composable
fun HomeScreen(viewModel: JarvisViewModel) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

    val shiftGreeting = SessionGreeting.text

    val entrance = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(
            1f,
            animationSpec = tween(900, delayMillis = 120, easing = FastOutSlowInEasing)
        )
    }

    val breathe = rememberInfiniteTransition(label = "homeBreathe")
    val shimmerX by breathe.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, delayMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "greetingShimmer"
    )

    val baseTextColor = if (isDarkMode) Color(0xFFF2F2F2) else Color(0xFF1F2937)
    val greetingBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to baseTextColor,
            (shimmerX * 0.5f).coerceIn(0f, 1f) to baseTextColor,
            (shimmerX * 0.75f).coerceIn(0f, 1f) to Color(0xFFE4E4E4),
            shimmerX.coerceIn(0f, 1f) to baseTextColor,
            1.0f to baseTextColor
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    alpha = entrance.value
                    translationY = (1f - entrance.value) * 46f
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = shiftGreeting,
                fontSize = 30.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                style = androidx.compose.ui.text.TextStyle(brush = greetingBrush),
                textAlign = TextAlign.Center,
                lineHeight = 40.sp
            )
        }
    }
}

@Composable
fun GlassFeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cardScale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.38f else 0.14f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "cardGlow"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.55f else 1f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "cardBorder"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isDarkMode) Color.White.copy(alpha = 0.05f)
                else Color.White.copy(alpha = 0.70f)
            )
            .drawBehind {
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(iconTint.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width * 0.82f, size.height * 0.12f),
                        radius = size.width * 0.95f
                    ),
                    cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx())
                )
            }
            .border(
                BorderStroke(
                    1.dp * borderAlpha.coerceAtLeast(0.4f),
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = (if (isDarkMode) 0.20f else 0.8f) * borderAlpha),
                            Color.White.copy(alpha = 0.05f * borderAlpha)
                        )
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            .padding(14.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = if (isDarkMode) Color.White.copy(0.6f) else Color.Black.copy(0.6f)
            )
        }
    }
}

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Code(val language: String, val code: String) : MessageSegment()
    data class GenerativeUI(val language: String, val code: String, val title: String) : MessageSegment()
}

fun prepareGenerativeHtml(rawHtml: String, isDarkMode: Boolean): String {
    val trimmed = rawHtml.trim()
    val hasHtmlStructure = trimmed.contains("<html", ignoreCase = true) || trimmed.contains("<!DOCTYPE", ignoreCase = true)

    if (hasHtmlStructure) {
        if (!trimmed.contains("viewport", ignoreCase = true)) {
            val headMatch = Regex("(?i)<head>").find(trimmed)
            if (headMatch != null) {
                return trimmed.replaceFirst(
                    headMatch.value,
                    "${headMatch.value}\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n"
                )
            }
        }
        return trimmed
    }

    val bg = if (isDarkMode) "#0f172a" else "#ffffff"
    val fg = if (isDarkMode) "#f8fafc" else "#0f172a"

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                body {
                    margin: 0;
                    padding: 12px;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                    background: $bg;
                    color: $fg;
                    min-height: 100vh;
                }
                button, input, select, textarea {
                    font-family: inherit;
                }
            </style>
        </head>
        <body>
            $trimmed
        </body>
        </html>
    """.trimIndent()
}

fun extractUiTitle(code: String, language: String): String {
    val titleMatch = Regex("(?i)<title>(.*?)</title>").find(code)
    if (titleMatch != null) {
        val t = titleMatch.groupValues[1].trim()
        if (t.isNotBlank()) return t
    }
    val h1Match = Regex("(?i)<h[1-3][^>]*>(.*?)</h[1-3]>").find(code)
    if (h1Match != null) {
        val h = h1Match.groupValues[1].replace(Regex("<.*?>"), "").trim()
        if (h.isNotBlank() && h.length <= 30) return h
    }
    val commentMatch = Regex("(<!--|//|#)\\s*([A-Za-z0-9 ]+UI|[A-Za-z0-9 ]+Widget|[A-Za-z0-9 ]+App|[A-Za-z0-9 ]+Calculator|[A-Za-z0-9 ]+Game)").find(code)
    if (commentMatch != null) {
        val c = commentMatch.groupValues[2].trim()
        if (c.isNotBlank() && c.length <= 30) return c
    }
    return when {
        code.contains("calc", ignoreCase = true) -> "Interactive Calculator"
        code.contains("game", ignoreCase = true) || code.contains("board", ignoreCase = true) -> "Interactive Game"
        code.contains("weather", ignoreCase = true) -> "Weather Card UI"
        code.contains("chart", ignoreCase = true) || code.contains("canvas", ignoreCase = true) -> "Visual Chart UI"
        code.contains("timer", ignoreCase = true) || code.contains("clock", ignoreCase = true) -> "Timer Widget"
        language.contains("SVG", ignoreCase = true) -> "Interactive SVG Graphic"
        else -> "Interactive UI Component"
    }
}

fun parseMessageSegments(text: String): List<MessageSegment> {
    if (!text.contains("```")) {
        return listOf(MessageSegment.Text(text))
    }
    val parts = text.split("```")
    val segments = mutableListOf<MessageSegment>()
    for (i in parts.indices) {
        val part = parts[i]
        if (i % 2 == 1) {
            val lines = part.split("\n", limit = 2)
            val langCandidate = lines.getOrNull(0)?.trim() ?: ""
            val isAlphaWord = langCandidate.isNotEmpty() && langCandidate.all { it.isLetterOrDigit() || it == '+' || it == '#' || it == '-' || it == '_' }
            val hasSecondLine = lines.size > 1
            val language = if (isAlphaWord && hasSecondLine) langCandidate.uppercase() else "CODE"
            val code = (if (isAlphaWord && hasSecondLine) lines.getOrNull(1) ?: "" else part).trimEnd()

            val isHtmlOrUI = language in listOf("HTML", "HTM", "GEN_UI", "GENERATIVE_UI", "GEN-UI", "GENERATIVE-UI", "GENUI", "UI", "WEBVIEW", "WIDGET", "COMPONENT", "SVG", "CANVAS", "APP", "MINIAPP") ||
                ((code.contains("<html", ignoreCase = true) || code.contains("<!DOCTYPE", ignoreCase = true) || code.contains("<div", ignoreCase = true) || code.contains("<canvas", ignoreCase = true) || code.contains("<style", ignoreCase = true) || code.contains("<script", ignoreCase = true) || code.contains("<svg", ignoreCase = true)) &&
                 (code.contains("</html>", ignoreCase = true) || code.contains("</div>", ignoreCase = true) || code.contains("</svg>", ignoreCase = true) || code.contains("</script>", ignoreCase = true) || code.contains("</style>", ignoreCase = true) || code.contains("</canvas>", ignoreCase = true) || code.contains("/>")))

            if (isHtmlOrUI && code.isNotBlank()) {
                segments.add(
                    MessageSegment.GenerativeUI(
                        language = language,
                        code = code,
                        title = extractUiTitle(code, language)
                    )
                )
            } else {
                segments.add(MessageSegment.Code(language = language, code = code))
            }
        } else {
            if (part.isNotEmpty()) {
                segments.add(MessageSegment.Text(part))
            }
        }
    }
    return segments
}

@Composable
fun GenerativeUIFullscreenDialog(
    title: String,
    htmlContent: String,
    rawCode: String,
    isDarkMode: Boolean,
    onDismiss: () -> Unit
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = if (isDarkMode) Color.White else Color.Black
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFFEFEFEF),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "LIVE GENERATIVE UI",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEFEFEF)
                                )
                            }
                            Text(
                                text = title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) Color.White else Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                tint = if (isDarkMode) Color.White else Color.Black
                            )
                        }
                        IconButton(onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(rawCode))
                            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Code",
                                tint = if (isDarkMode) Color.White else Color.Black
                            )
                        }
                    }
                }

                // Fullscreen WebView
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    key(reloadKey) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.databaseEnabled = true
                                    settings.allowFileAccess = true
                                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    webChromeClient = android.webkit.WebChromeClient()
                                    webViewClient = object : WebViewClient() {}
                                    loadDataWithBaseURL("https://app.local", htmlContent, "text/html", "UTF-8", null)
                                }
                            },
                            update = { webView ->
                                webView.loadDataWithBaseURL("https://app.local", htmlContent, "text/html", "UTF-8", null)
                            },
                            onRelease = { webView ->
                                try {
                                    webView.stopLoading()
                                    webView.onPause()
                                    webView.destroy()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GenerativeUICard(
    language: String,
    code: String,
    title: String,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Live UI, 1 = Code
    var showFullscreen by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var isCopied by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current

    val preparedHtml = remember(code, isDarkMode) {
        prepareGenerativeHtml(code, isDarkMode)
    }

    if (showFullscreen) {
        GenerativeUIFullscreenDialog(
            title = title,
            htmlContent = preparedHtml,
            rawCode = code,
            isDarkMode = isDarkMode,
            onDismiss = { showFullscreen = false }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isDarkMode) Color(0xFF11131A) else Color(0xFFF8FAFC))
            .border(
                BorderStroke(
                    1.5.dp,
                    Brush.linearGradient(
                        listOf(
                            Color(0xFFEFEFEF).copy(alpha = 0.60f),
                            Color(0xFF3B82F6).copy(alpha = 0.50f),
                            Color(0xFFA855F7).copy(alpha = 0.30f)
                        )
                    )
                ),
                RoundedCornerShape(18.dp)
            )
    ) {
        Column {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDarkMode) Color(0xFF1A1D27) else Color(0xFFEDF2F7))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Generative UI Badge & Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFFEFEFEF), Color(0xFF3B82F6))
                                )
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "GEN UI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp)
                    )
                }

                // Tabs & Controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Tab Switcher
                    val previewTabBg by animateColorAsState(
                        targetValue = if (selectedTab == 0) Color(0xFFEFEFEF) else Color.Transparent,
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        label = "previewTabBg"
                    )
                    val codeTabBg by animateColorAsState(
                        targetValue = if (selectedTab == 1) Color(0xFF3B82F6) else Color.Transparent,
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        label = "codeTabBg"
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDarkMode) Color(0xFF262A38) else Color(0xFFCBD5E1))
                            .padding(2.dp)
                    ) {
                        val previewTabInteraction = remember { MutableInteractionSource() }
                        val previewTabPressed by previewTabInteraction.collectIsPressedAsState()
                        val previewTabScale by animateFloatAsState(
                            targetValue = if (previewTabPressed) 0.94f else 1f,
                            animationSpec = AurexMotion.snappySpring(),
                            label = "previewTabScale"
                        )
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = previewTabScale
                                    scaleY = previewTabScale
                                }
                                .clip(RoundedCornerShape(6.dp))
                                .background(previewTabBg)
                                .clickable(interactionSource = previewTabInteraction, indication = null) { selectedTab = 0 }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Preview",
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 0) Color.White else (if (isDarkMode) Color.LightGray else Color.DarkGray)
                            )
                        }
                        val codeTabInteraction = remember { MutableInteractionSource() }
                        val codeTabPressed by codeTabInteraction.collectIsPressedAsState()
                        val codeTabScale by animateFloatAsState(
                            targetValue = if (codeTabPressed) 0.94f else 1f,
                            animationSpec = AurexMotion.snappySpring(),
                            label = "codeTabScale"
                        )
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = codeTabScale
                                    scaleY = codeTabScale
                                }
                                .clip(RoundedCornerShape(6.dp))
                                .background(codeTabBg)
                                .clickable(interactionSource = codeTabInteraction, indication = null) { selectedTab = 1 }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Code",
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 1) Color.White else (if (isDarkMode) Color.LightGray else Color.DarkGray)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (selectedTab == 0) {
                        IconButton(
                            onClick = { reloadKey++ },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                tint = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { showFullscreen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = Color(0xFFEFEFEF),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(code))
                            isCopied = true
                            Toast.makeText(context, "UI Code copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy Code",
                            tint = if (isCopied) Color(0xFFEFEFEF) else (if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Body
            if (selectedTab == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .background(if (isDarkMode) Color(0xFF0F172A) else Color(0xFFFFFFFF))
                ) {
                    key(reloadKey) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.databaseEnabled = true
                                    settings.allowFileAccess = true
                                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    webChromeClient = android.webkit.WebChromeClient()
                                    webViewClient = object : WebViewClient() {}
                                    tag = preparedHtml
                                    loadDataWithBaseURL("https://app.local", preparedHtml, "text/html", "UTF-8", null)
                                }
                            },
                            update = { webView ->
                                // Only reload when the prepared HTML actually
                                // changed — reloading on every recomposition
                                // wipes the page's live state (inputs, scroll,
                                // running timers).
                                if (webView.tag != preparedHtml) {
                                    webView.tag = preparedHtml
                                    webView.loadDataWithBaseURL("https://app.local", preparedHtml, "text/html", "UTF-8", null)
                                }
                            },
                            onRelease = { webView ->
                                try {
                                    webView.stopLoading()
                                    webView.onPause()
                                    webView.destroy()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 340.dp)
                        .background(if (isDarkMode) Color(0xFF13151D) else Color(0xFF1E222D))
                        .padding(14.dp)
                ) {
                    val scrollState = rememberScrollState()
                    val vertScrollState = rememberScrollState()
                    Text(
                        text = code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier
                            .verticalScroll(vertScrollState)
                            .horizontalScroll(scrollState)
                    )
                }
            }
        }
    }
}

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    var isCopied by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(RoundedCornerShape(18.dp))
            .background(AurexColors.codeBlockFill())
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.30f),
                            Color(0xFFEFEFEF).copy(alpha = 0.40f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                ),
                RoundedCornerShape(18.dp)
            )
    ) {
        Column {
            // Header Bar matching user's requested layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AurexColors.codeHeaderFill())
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language Label
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFEFEFEF).copy(alpha = 0.2f))
                            .border(BorderStroke(1.dp, Color(0xFFE4E4E4).copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = language.ifBlank { "C" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE4E4E4)
                        )
                    }
                }

                // Copy Code Icon Button
                val copyInteraction = remember { MutableInteractionSource() }
                val copyPressed by copyInteraction.collectIsPressedAsState()
                val copyScale by animateFloatAsState(
                    targetValue = if (copyPressed) 0.88f else 1f,
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
                    label = "codeCopyScale"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = copyScale
                            scaleY = copyScale
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable(
                            interactionSource = copyInteraction,
                            indication = null
                        ) {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(code))
                            isCopied = true
                            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    AnimatedContent(
                        targetState = isCopied,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.5f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f)) + fadeIn(tween(140))) togetherWith
                                    (scaleOut(targetScale = 0.5f, animationSpec = tween(120)) + fadeOut(tween(100)))
                        },
                        label = "codeCopyIcon"
                    ) { copied ->
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy Code",
                            tint = if (copied) Color(0xFFE4E4E4) else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (isCopied) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Copied",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE4E4E4)
                        )
                    }
                }
            }

            // Divider Line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )

            // Code Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                val scrollState = rememberScrollState()
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier.horizontalScroll(scrollState)
                )
            }
        }
    }
}

@Composable
fun FormattedMessageText(
    text: String,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val segments = remember(text) { parseMessageSegments(text) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Text -> {
                    if (segment.content.isNotBlank()) {
                        Text(
                            text = segment.content.trim(),
                            color = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF0F172A),
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
                is MessageSegment.GenerativeUI -> {
                    GenerativeUICard(
                        language = segment.language,
                        code = segment.code,
                        title = segment.title,
                        isDarkMode = isDarkMode
                    )
                }
                is MessageSegment.Code -> {
                    CodeBlockCard(
                        language = segment.language,
                        code = segment.code,
                        isDarkMode = isDarkMode
                    )
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    viewModel: JarvisViewModel,
    messages: List<com.example.data.ChatMessage>
) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val isNearBottom by remember(messages.size) {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf true
            val lastVisibleIndex = visibleItems.last().index
            lastVisibleIndex >= messages.size - 3
        }
    }

    LaunchedEffect(messages.size, isGenerating) {
        if (messages.isNotEmpty()) {
            val isLastMessageUser = messages.lastOrNull()?.sender == "user"
            if (isLastMessageUser || isNearBottom) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
    ) {
        items(messages, key = { it.id }) { msg ->
            val isUser = msg.sender == "user"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(320, easing = FastOutSlowInEasing),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        fadeOutSpec = tween(180)
                    ),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                if (isUser) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        if (!msg.fileUri.isNullOrBlank()) {
                                val context = LocalContext.current
                                val imageModel = remember(msg.fileUri) {
                                    resolveImageModel(context, msg.fileUri)
                                }
                                val imageBitmap = remember(msg.fileUri) {
                                    loadBitmapFromUriOrBase64(context, msg.fileUri)
                                }
                                if (imageBitmap != null) {
                                    Image(
                                        bitmap = imageBitmap,
                                        contentDescription = "Attached Image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                } else if (imageModel != null) {
                                    coil.compose.AsyncImage(
                                        model = imageModel,
                                        contentDescription = "Attached Image",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                            FormattedMessageText(
                                text = msg.text,
                                isDarkMode = isDarkMode
                            )
                    }
                } else {
                    Row(
                        modifier = Modifier.widthIn(max = 340.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 8.dp, bottom = 2.dp)
                        ) {
                                    if (!msg.fileUri.isNullOrBlank()) {
                                        val context = LocalContext.current
                                        val isVideo = msg.fileName?.endsWith(".mp4", ignoreCase = true) == true ||
                                                msg.fileSize?.contains("Video", ignoreCase = true) == true ||
                                                msg.fileUri.endsWith(".mp4", ignoreCase = true) ||
                                                msg.fileUri.endsWith(".mkv", ignoreCase = true) ||
                                                msg.fileUri.endsWith(".webm", ignoreCase = true)

                                        if (isVideo) {
                                            VideoMessagePlayer(
                                                videoUriStr = msg.fileUri,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        } else {
                                            val imageModel = remember(msg.fileUri) {
                                                resolveImageModel(context, msg.fileUri)
                                            }
                                            val imageBitmap = remember(msg.fileUri) {
                                                loadBitmapFromUriOrBase64(context, msg.fileUri)
                                            }
                                            if (imageBitmap != null) {
                                                Image(
                                                    bitmap = imageBitmap,
                                                    contentDescription = "Generated Image",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(220.dp)
                                                        .clip(RoundedCornerShape(12.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                            } else if (imageModel != null) {
                                                coil.compose.AsyncImage(
                                                    model = imageModel,
                                                    contentDescription = "Generated Image",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(220.dp)
                                                        .clip(RoundedCornerShape(12.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                    FormattedMessageText(
                                        text = msg.text,
                                        isDarkMode = isDarkMode
                                    )
                            }

                            // AI Message Action Bar: Speaker Read-Aloud & Copy
                            Row(
                                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                var isCopied by remember { mutableStateOf(false) }
                                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

                                // Speak Response Button
                                IconButton(
                                    onClick = { viewModel.speakText(msg.text) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Read Aloud",
                                        tint = Color(0xFFEFEFEF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Stop Speech Button
                                IconButton(
                                    onClick = { viewModel.stopSpeaking() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                        contentDescription = "Stop Speech",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Copy Response Button
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(msg.text))
                                        isCopied = true
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copy Text",
                                        tint = if (isCopied) Color(0xFFEFEFEF) else Color.Gray,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }

                                if (isCopied) {
                                    Text(
                                        text = "Copied",
                                        fontSize = 11.sp,
                                        color = Color(0xFFEFEFEF),
                                        modifier = Modifier.padding(start = 2.dp)
                                    )
                                }
                            }
                        }
                    }
            }
        }

        item {            val generatingStatus by viewModel.generatingStatus.collectAsStateWithLifecycle()
            if (isGenerating && generatingStatus.isNotBlank()) {
                val thinkPulse = rememberInfiniteTransition(label = "thinkPulse")
                val borderGlow by thinkPulse.animateFloat(
                    initialValue = 0.22f,
                    targetValue = 0.72f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1100, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "thinkGlow"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isDarkMode) Color(0xFFEFEFEF).copy(alpha = 0.15f) else Color(0xFFEFEFEF).copy(alpha = 0.10f),
                        border = BorderStroke(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFEFEFEF).copy(alpha = 0.25f + borderGlow * 0.45f),
                                    Color(0xFF22D3EE).copy(alpha = borderGlow),
                                    Color(0xFFEFEFEF).copy(alpha = 0.25f + borderGlow * 0.45f)
                                )
                            )
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                val infiniteTransition = rememberInfiniteTransition(label = "bouncing")
                                val delayMillis = listOf(0, 150, 300)
                                delayMillis.forEach { delay ->
                                    val translationY by infiniteTransition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = -6f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(durationMillis = 300, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse,
                                            initialStartOffset = StartOffset(delay)
                                        ),
                                        label = "dot"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .offset(y = translationY.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEFEFEF))
                                    )
                                }
                            }
                            Text(
                                text = generatingStatus,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkMode) Color(0xFFE4E4E4) else Color(0xFFB8B8B8)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PlusAction(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val run: () -> Unit
)

@Composable
fun InputBar(
    viewModel: JarvisViewModel,
    isVoiceMode: Boolean
) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val isAttachmentOpen by viewModel.isAttachmentMenuOpen.collectAsStateWithLifecycle()
    val attachedFile by viewModel.attachedFile.collectAsStateWithLifecycle()
    val isTtsMuted by viewModel.isTtsMuted.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generatingStatus by viewModel.generatingStatus.collectAsStateWithLifecycle()
    val geminiModel by viewModel.geminiModel.collectAsStateWithLifecycle()
    val nvidiaModel by viewModel.nvidiaModel.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProvider.collectAsStateWithLifecycle()
    val isModelSelectorOpen by viewModel.isModelSelectorOpen.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val filePicker = LocalFilePicker.current
    val cameraLauncher = LocalCameraLauncher.current
    val context = LocalContext.current

    val infiniteTransition = rememberInfiniteTransition(label = "InputBarPulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.012f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )

    // ── Send-bounce animation ──
    // When the user sends a message the input bar hops up a little, like
    // the message "jumped" out of the field. Tracked via the message list's
    // user-message count so it also fires from voice dictation.
    val chatMessages by viewModel.currentMessages.collectAsStateWithLifecycle()
    var lastUserMsgCount by remember { mutableIntStateOf(-1) }
    val sendBounce = remember { Animatable(0f) }
    LaunchedEffect(chatMessages.count { it.sender == "user" }) {
        val userCount = chatMessages.count { it.sender == "user" }
        if (lastUserMsgCount >= 0 && userCount > lastUserMsgCount) {
            sendBounce.snapTo(0f)
            sendBounce.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 420
                    0f at 0 using CubicBezierEasing(0.2f, 0f, 0.4f, 1f)
                    -22f at 140 using FastOutLinearInEasing
                    0f at 280 using LinearOutSlowInEasing
                    -5f at 360
                    0f at 420
                }
            )
        }
        lastUserMsgCount = userCount
    }
    // Dictation listening = subtle live "ripple" lift while the mic hears you.
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    val voiceRmsFloat by viewModel.voiceRmsDb.collectAsStateWithLifecycle()
    val isDictating = voiceStatus.equals("Listening...", ignoreCase = true) && !isVoiceMode

    var radialActive by remember { mutableStateOf(false) }
    var radialSelected by remember { mutableStateOf(-1) }
    var plusHeld by remember { mutableStateOf(false) }
    val plusCenterInRoot = remember { mutableStateOf(Offset.Zero) }
    val inputBarOrigin = remember { mutableStateOf(Offset.Zero) }
    val radialPop = remember { Animatable(0f) }
    LaunchedEffect(radialActive) {
        if (radialActive) {
            radialPop.snapTo(0f)
            radialPop.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    val radialAngles = remember { listOf(180f, 144f, 108f, 72f, 36f, 0f) }

    val plusActions = listOf(
        PlusAction(Icons.Default.Phone, "JARVIS Live Call", Color(0xFF00E5FF)) {
            viewModel.setAttachmentMenuOpen(false)
            try {
                context.startActivity(com.example.call.JarvisCallActivity.outgoing(context))
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context, "Call screen failed: ${t.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        PlusAction(Icons.Default.Message, "Message JARVIS", Color(0xFFEFEFEF)) {
            viewModel.setAttachmentMenuOpen(false)
            viewModel.setJarvisChatOpen(true)
        },
        PlusAction(Icons.Default.PhotoCamera, "Take Photo", Color(0xFFF59E0B)) {
            viewModel.setAttachmentMenuOpen(false)
            cameraLauncher?.launch(null)
        },
        PlusAction(Icons.Default.Visibility, "Vision", Color(0xFF60A5FA)) {
            viewModel.setAttachmentMenuOpen(false)
            viewModel.setVisionChooserOpen(true)
        },
        PlusAction(Icons.Default.Image, "Upload Images", Color(0xFF3B82F6)) {
            viewModel.setAttachmentMenuOpen(false)
            filePicker?.launch("image/*")
        },
        PlusAction(Icons.Default.Description, "Upload File", Color(0xFF8B5CF6)) {
            viewModel.setAttachmentMenuOpen(false)
            filePicker?.launch("*/*")
        }
    )

    if (isVoiceMode) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Send bounce + dictation lift, both in px so they stay
                // resolution-independent.
                translationY = sendBounce.value + if (isDictating) -6f else 0f
                val s = if (isDictating) 1.008f + (voiceRmsFloat * 0.004f) else 1f
                scaleX = s
                scaleY = s
            }
            .onGloballyPositioned { inputBarOrigin.value = it.positionInRoot() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Attachment Menu Overlay
            AnimatedVisibility(
                visible = isAttachmentOpen,
                enter = slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                ) + expandVertically(
                    expandFrom = Alignment.Bottom,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ) + shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(150))
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(
                            1.dp,
                            if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f),
                            RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkMode) Color(0xFF1E1F20) else Color.White
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        plusActions.forEachIndexed { idx, action ->
                            AttachmentItem(
                                icon = action.icon,
                                text = action.label,
                                iconColor = action.color,
                                isDarkMode = isDarkMode,
                                index = idx
                            ) { action.run() }
                        }
                    }
                }
            }

            // Attached File Pill
            attachedFile?.let { file ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .border(
                            1.dp,
                            if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f),
                            RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkMode) Color(0xFF1E1F20) else Color.White
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (file.type == "image") Icons.Default.Image else Icons.Default.Description,
                                contentDescription = "Attached File",
                                tint = if (file.type == "image") Color(0xFF3B82F6) else Color(0xFFEFEFEF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = file.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isDarkMode) Color.White else Color.Black
                                )
                                Text(
                                    text = "Size: ${file.size} • Type: ${file.type}",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.setAttachedFile(null) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove attachment",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Dynamic glow styling when AI is processing/thinking
            val rowBorderBrush = if (isGenerating) {
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFEFEFEF).copy(alpha = glowAlpha),
                        Color(0xFF3B82F6).copy(alpha = glowAlpha),
                        Color(0xFFEFEFEF).copy(alpha = glowAlpha)
                    )
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDarkMode) 0.35f else 0.85f),
                        Color(0xFFEFEFEF).copy(alpha = 0.3f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            }

            val rowBgColor = if (isGenerating) {
                if (isDarkMode) Color(0xFFEFEFEF).copy(alpha = 0.08f * glowAlpha)
                else Color(0xFFEFEFEF).copy(alpha = 0.05f * glowAlpha)
            } else {
                if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.75f)
            }

            // Input Row Container Card (Screenshot style)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(if (isGenerating) glowScale else 1f)
                    .shadow(
                        elevation = if (isGenerating) 18.dp else 12.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black.copy(alpha = if (isDarkMode) 0.45f else 0.12f),
                        spotColor = if (isGenerating) AurexColors.Emerald.copy(alpha = 0.35f)
                            else Color.Black.copy(alpha = if (isDarkMode) 0.45f else 0.14f)
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(AurexColors.inputBarFill(isDarkMode))
                    .border(
                        width = if (isGenerating) 1.8.dp else 1.dp,
                        brush = rowBorderBrush,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // Text Field
                TextField(
                    value = inputText,
                    onValueChange = { viewModel.setInputText(it) },
                    placeholder = {
                        Text(
                            text = if (isGenerating && generatingStatus.isNotBlank()) generatingStatus else "Chat or say a command...",
                            color = if (isGenerating && generatingStatus.isNotBlank()) Color(0xFFEFEFEF).copy(alpha = glowAlpha)
                                    else if (isDarkMode) Color.Gray else Color(0xFF6B7280),
                            fontSize = 16.sp,
                            fontWeight = if (isGenerating && generatingStatus.isNotBlank()) FontWeight.Medium else FontWeight.Normal
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_input"),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = if (isDarkMode) Color.White else Color.Black,
                        unfocusedTextColor = if (isDarkMode) Color.White else Color.Black
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() || attachedFile != null) {
                            viewModel.handleSend(inputText)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    }),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Control Bar inside Card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val attachScale by animateFloatAsState(
                            targetValue = if (plusHeld) 0.88f else 1f,
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
                            label = "attachScale"
                        )
                        val plusRotation by animateFloatAsState(
                            targetValue = if (isAttachmentOpen || radialActive) 45f else 0f,
                            animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
                            label = "plusRotation"
                        )

                        // Plus / Attachment Button - tap shows the menu, hold 1s+ pops the radial inventory
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .graphicsLayer {
                                    scaleX = attachScale
                                    scaleY = attachScale
                                }
                                .clip(CircleShape)
                                .background(
                                    when {
                                        radialActive -> Color(0xFFE4E4E4).copy(alpha = 0.25f)
                                        isDarkMode -> Color.White.copy(alpha = 0.10f)
                                        else -> Color.Black.copy(alpha = 0.06f)
                                    }
                                )
                                .onGloballyPositioned { coords ->
                                    plusCenterInRoot.value = coords.positionInRoot() +
                                            Offset(coords.size.width / 2f, coords.size.height / 2f)
                                }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        val downTime = System.currentTimeMillis()
                                        val center = Offset(size.width / 2f, size.height / 2f)
                                        val selectRadius = 55.dp.toPx()
                                        var radial = false
                                        plusHeld = true
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val anyPressed = event.changes.any { it.pressed }
                                            if (!anyPressed) break
                                            if (!radial && System.currentTimeMillis() - downTime >= 1000L) {
                                                radial = true
                                                radialSelected = -1
                                                viewModel.setAttachmentMenuOpen(false)
                                                radialActive = true
                                            }
                                            if (radial) {
                                                val change = event.changes.firstOrNull { it.pressed }
                                                if (change != null) {
                                                    val v = change.position - center
                                                    val dist = v.getDistance()
                                                    radialSelected = if (dist < selectRadius) -1 else {
                                                        val ang = Math.toDegrees(
                                                            atan2(-v.y.toDouble(), v.x.toDouble())
                                                        ).toFloat()
                                                        var bestIdx = -1
                                                        var bestDiff = 360f
                                                        radialAngles.forEachIndexed { idx, a ->
                                                            var diff = abs(ang - a)
                                                            if (diff > 180f) diff = 360f - diff
                                                            if (diff < bestDiff) {
                                                                bestDiff = diff
                                                                bestIdx = idx
                                                            }
                                                        }
                                                        bestIdx
                                                    }
                                                }
                                            }
                                        }
                                        plusHeld = false
                                        if (radial) {
                                            val sel = radialSelected
                                            radialActive = false
                                            radialSelected = -1
                                            if (sel in plusActions.indices) plusActions[sel].run()
                                        } else {
                                            viewModel.setAttachmentMenuOpen(!isAttachmentOpen)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Attachment Menu",
                                tint = if (isDarkMode) Color.White else Color(0xFF0F172A),
                                modifier = Modifier
                                    .size(21.dp)
                                    .graphicsLayer { rotationZ = plusRotation }
                            )
                        }

                        // Model Selector Pill Button with Dropdown Menu
                        Box {
                            val pillInteraction = remember { MutableInteractionSource() }
                            val pillPressed by pillInteraction.collectIsPressedAsState()
                            val pillScale by animateFloatAsState(
                                targetValue = if (pillPressed) 0.94f else 1f,
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
                                label = "pillScale"
                            )
                            val pillBgAlpha by animateFloatAsState(
                                targetValue = if (isModelSelectorOpen) 0.20f else 0.12f,
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                label = "pillBg"
                            )
                            Row(
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = pillScale
                                        scaleY = pillScale
                                    }
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (isDarkMode) Color.White.copy(alpha = pillBgAlpha)
                                        else Color.Black.copy(alpha = pillBgAlpha * 0.65f)
                                    )
                                    .clickable(
                                        interactionSource = pillInteraction,
                                        indication = null
                                    ) { viewModel.setModelSelectorOpen(!isModelSelectorOpen) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val modelLabel = when {
                                    activeProvider == "nvidia_nim" -> when {
                                        nvidiaModel.contains("70b", ignoreCase = true) -> "Llama 3.1 70B"
                                        nvidiaModel.contains("8b", ignoreCase = true) -> "Llama 3.1 8B"
                                        nvidiaModel.contains("claude", ignoreCase = true) -> "Claude 3.5 Sonnet"
                                        nvidiaModel.contains("mistral", ignoreCase = true) -> "Mistral Large"
                                        nvidiaModel.contains("deepseek", ignoreCase = true) -> "DeepSeek R1"
                                        nvidiaModel.contains("nemotron", ignoreCase = true) -> "Nemotron 340B"
                                        nvidiaModel.isNotBlank() -> {
                                            val simple = nvidiaModel.substringAfterLast("/").replace("-instruct", "", ignoreCase = true).trim()
                                            if (simple.length > 20) simple.take(18) + ".." else simple
                                        }
                                        else -> "NVIDIA NIM"
                                    }
                                    else -> when {
                                        geminiModel.contains("2.5") -> "Gemini 2.5 Flash"
                                        geminiModel.contains("1.5-pro") -> "Gemini 1.5 Pro"
                                        geminiModel.contains("2.0") -> "Gemini 2.0 Flash"
                                        geminiModel.contains("1.5-flash") -> "Gemini 1.5 Flash"
                                        else -> geminiModel.substringAfterLast("/")
                                    }
                                }
                                Text(
                                    text = modelLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select Model",
                                    tint = if (isDarkMode) Color.White.copy(alpha = 0.8f) else Color(0xFF0F172A),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            val visionModel by viewModel.visionModel.collectAsStateWithLifecycle()
                            val customModelsList by viewModel.customModelsList.collectAsStateWithLifecycle()

                            DropdownMenu(
                                expanded = isModelSelectorOpen,
                                onDismissRequest = { viewModel.setModelSelectorOpen(false) },
                                modifier = Modifier
                                    .widthIn(min = 300.dp)
                                    .background(if (isDarkMode) Color(0xFF141414) else Color.White)
                                    .border(
                                        BorderStroke(1.dp, Color(0xFFE4E4E4).copy(alpha = 0.22f)),
                                        RoundedCornerShape(16.dp)
                                    )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(Color(0xFFE4E4E4).copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Memory,
                                            contentDescription = null,
                                            tint = Color(0xFFE4E4E4),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "CONFIGURED MODELS",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp,
                                        color = Color(0xFFE4E4E4)
                                    )
                                }

                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFFE4E4E4).copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFFE4E4E4), modifier = Modifier.size(15.dp))
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    if (nvidiaModel.isNotBlank()) nvidiaModel.substringAfterLast("/") else "Default Model",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (isDarkMode) Color.White else Color.Black,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "Active primary model",
                                                    fontSize = 10.sp,
                                                    color = Color.Gray,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.selectModel("nvidia_nim", nvidiaModel)
                                        viewModel.setModelSelectorOpen(false)
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFF60A5FA).copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.RemoveRedEye, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(15.dp))
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    visionModel.substringAfterLast("/"),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (isDarkMode) Color.White else Color.Black,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "Vision • auto on image upload",
                                                    fontSize = 10.sp,
                                                    color = Color.Gray,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.selectModel("nvidia_nim", visionModel)
                                        viewModel.setModelSelectorOpen(false)
                                    }
                                )

                                customModelsList.forEach { cModel ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(30.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(Color(0xFFE4E4E4).copy(alpha = 0.10f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Memory, contentDescription = null, tint = Color(0xFFE4E4E4), modifier = Modifier.size(15.dp))
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column {
                                                    Text(
                                                        cModel.substringAfterLast("/"),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = if (isDarkMode) Color.White else Color.Black,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        "Custom model",
                                                        fontSize = 10.sp,
                                                        color = Color.Gray,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.selectModel("nvidia_nim", cModel)
                                            viewModel.setModelSelectorOpen(false)
                                        }
                                    )
                                }

                                HorizontalDivider(color = Color(0xFFE4E4E4).copy(alpha = 0.18f), modifier = Modifier.padding(vertical = 4.dp))

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "+ Add Custom Model...",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE4E4E4),
                                            maxLines = 1
                                        )
                                    },
                                    onClick = {
                                        viewModel.setModelSelectorOpen(false)
                                        viewModel.setApiModalOpen(true)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFFE4E4E4), modifier = Modifier.size(18.dp))
                                    }
                                )

                                HorizontalDivider(color = Color(0xFFE4E4E4).copy(alpha = 0.18f), modifier = Modifier.padding(vertical = 4.dp))

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Configure API Keys & Base URL...",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFE4E4E4),
                                            maxLines = 1
                                        )
                                    },
                                    onClick = {
                                        viewModel.setModelSelectorOpen(false)
                                        viewModel.setApiModalOpen(true)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFE4E4E4), modifier = Modifier.size(18.dp))
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val micContext = LocalContext.current

                        // Quick Dictation Mic - bare glyph, starts speech recognition.
                        // While dictating: emerald + pulsing, tap again to cancel.
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isDictating) Color(0xFFEFEFEF).copy(
                                        alpha = 0.14f + (voiceRmsFloat / 20f).coerceIn(0f, 0.30f)
                                    ) else Color.Transparent
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val micScale by animateFloatAsState(
                                targetValue = if (isDictating) 1f + (voiceRmsFloat / 30f).coerceIn(0f, 0.35f) else 1f,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                                label = "micScale"
                            )
                            IconButton(
                                onClick = { (micContext as? MainActivity)?.startListeningFromUi(viewModel) },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDictating) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (isDictating) "Stop dictation" else "Voice input",
                                    tint = when {
                                        isDictating -> Color(0xFFEFEFEF)
                                        isDarkMode -> Color.White.copy(alpha = 0.92f)
                                        else -> Color(0xFF0F172A)
                                    },
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            scaleX = micScale
                                            scaleY = micScale
                                        }
                                )
                            }
                        }

                        // Primary Action - voice mode at rest, morphs into send while typing, stop while generating
                        val canSend = inputText.isNotBlank() || attachedFile != null
                        val actionBg by animateColorAsState(
                            targetValue = when {
                                isGenerating -> Color(0xFFEFEFEF).copy(alpha = 0.18f)
                                canSend -> AurexColors.Emerald
                                else -> if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                            },
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            label = "actionBg"
                        )
                        val actionInteraction = remember { MutableInteractionSource() }
                        val actionPressed by actionInteraction.collectIsPressedAsState()
                        val actionScale by animateFloatAsState(
                            targetValue = if (actionPressed) 0.86f else 1f,
                            animationSpec = spring(dampingRatio = 0.68f, stiffness = 500f),
                            label = "actionScale"
                        )
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .graphicsLayer {
                                    scaleX = actionScale
                                    scaleY = actionScale
                                }
                                .shadow(
                                    elevation = if (canSend && !isGenerating) 10.dp else 0.dp,
                                    shape = CircleShape,
                                    ambientColor = Color.Black.copy(alpha = 0.30f),
                                    spotColor = AurexColors.Emerald.copy(alpha = 0.55f)
                                )
                                .clip(CircleShape)
                                .background(actionBg)
                                .clickable(
                                    interactionSource = actionInteraction,
                                    indication = null
                                ) {
                                    when {
                                        isGenerating -> viewModel.stopGenerating()
                                        canSend -> {
                                            viewModel.handleSend(inputText)
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                        }
                                        else -> viewModel.setVoiceMode(!isVoiceMode)
                                    }
                                }
                                .testTag(if (canSend) "send_button" else "voice_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = when {
                                    isGenerating -> "stop"
                                    canSend -> "send"
                                    else -> "voice"
                                },
                                transitionSpec = {
                                    (scaleIn(
                                        initialScale = 0.55f,
                                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f)
                                    ) + fadeIn(tween(140))) togetherWith
                                            (scaleOut(targetScale = 0.55f, animationSpec = tween(120)) + fadeOut(tween(100)))
                                },
                                label = "actionIcon"
                            ) { state ->
                                when (state) {
                                    "stop" -> Box(
                                        modifier = Modifier
                                            .size(13.dp)
                                            .background(Color(0xFF0A0A0A), RoundedCornerShape(2.5.dp))
                                    )
                                    "send" -> Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = Color(0xFF0A0A0A),
                                        modifier = Modifier.size(19.dp)
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Voice Mode",
                                        tint = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (radialActive) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { radialActive = false } }
            ) {
                val anchor = plusCenterInRoot.value - inputBarOrigin.value
                val density = LocalDensity.current
                val radiusPx = with(density) { 130.dp.toPx() }
                val itemHalf = with(density) { 29.dp.roundToPx() }
                val pop = radialPop.value
                plusActions.forEachIndexed { idx, action ->
                    val angleRad = Math.toRadians(radialAngles[idx].toDouble())
                    val eased = pop * pop * (3f - 2f * pop)
                    val r = radiusPx * eased
                    val cx = anchor.x + (cos(angleRad) * r).toFloat()
                    val cy = anchor.y - (sin(angleRad) * r).toFloat()
                    val selected = radialSelected == idx
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (cx - itemHalf).roundToInt(),
                                    (cy - itemHalf).roundToInt()
                                )
                            }
                            .graphicsLayer {
                                alpha = pop
                                val s = if (selected) 1.22f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .size(58.dp)
                            .shadow(
                                if (selected) 14.dp else 6.dp,
                                CircleShape,
                                ambientColor = Color.Black.copy(alpha = 0.40f),
                                spotColor = action.color.copy(alpha = if (selected) 0.70f else 0.25f)
                            )
                            .clip(CircleShape)
                            .background(
                                if (isDarkMode) Color(0xFF0B0F0D).copy(alpha = 0.96f)
                                else Color.White
                            )
                            .border(
                                BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) action.color
                                    else Color.White.copy(alpha = if (isDarkMode) 0.18f else 0.10f)
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            tint = action.color,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                if (radialSelected in plusActions.indices) {
                    val selectedAction = plusActions[radialSelected]
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    anchor.x.roundToInt() + 48.dp.roundToPx(),
                                    anchor.y.roundToInt() - 14.dp.roundToPx()
                                )
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0B0F0D).copy(alpha = if (isDarkMode) 0.92f else 0.85f))
                            .border(BorderStroke(1.dp, selectedAction.color.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            selectedAction.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = selectedAction.color
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AttachmentItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    iconColor: Color,
    isDarkMode: Boolean = true,
    index: Int = 0,
    onClick: () -> Unit
) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(
            1f,
            animationSpec = tween(340, delayMillis = AurexMotion.stagger(index), easing = FastOutSlowInEasing)
        )
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AurexMotion.snappySpring(),
        label = "attachPress"
    )
    val chipAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.22f else 0.14f,
        animationSpec = tween(200),
        label = "attachChip"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 18f
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (pressed) iconColor.copy(alpha = 0.10f)
                else Color.White.copy(alpha = if (isDarkMode) 0.03f else 0.55f)
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconColor.copy(alpha = chipAlpha))
                .border(BorderStroke(1.dp, iconColor.copy(alpha = 0.35f)), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = iconColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDarkMode) Color.White else Color(0xFF0F172A)
        )
    }
}

@Composable
private fun VisionSourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
        label = "visionCardScale"
    )
    Column(
        modifier = Modifier
            .width(150.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0C0C0C).copy(alpha = 0.95f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color(0xFFE4E4E4),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            subtitle,
            fontSize = 11.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun VisionSourceChooserDialog(viewModel: JarvisViewModel) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { viewModel.setVisionChooserOpen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { viewModel.setVisionChooserOpen(false) },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Vision",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Choose a source",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    VisionSourceCard(
                        icon = Icons.Default.PhotoCamera,
                        title = "Camera",
                        subtitle = "Watch and explain\nthe world live"
                    ) {
                        viewModel.setVisionSource("camera")
                        viewModel.setVisionChooserOpen(false)
                        viewModel.setLiveVisionOpen(true)
                    }
                    VisionSourceCard(
                        icon = Icons.Default.Cast,
                        title = "Screen",
                        subtitle = "Ask questions about\nyour screen"
                    ) {
                        viewModel.setVisionSource("screen")
                        viewModel.setVisionChooserOpen(false)
                        viewModel.setLiveVisionOpen(true)
                    }
                }
            }
        }
    }
}

@Composable
fun JarvisChatWindow(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val threads by viewModel.jarvisThreads.collectAsStateWithLifecycle()
    val activeThreadId by viewModel.activeThreadId.collectAsStateWithLifecycle()
    val threadMessages by viewModel.threadMessages.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val dialogView = androidx.compose.ui.platform.LocalView.current

    androidx.compose.runtime.SideEffect {
        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.apply {
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    val threadId = activeThreadId ?: threads.firstOrNull()?.threadId
    val messages = threadMessages[threadId].orEmpty()

    LaunchedEffect(threadId) {
        threadId?.let { viewModel.loadThreadMessages(it) }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { viewModel.setJarvisChatOpen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val backInteraction = remember { MutableInteractionSource() }
                    val backPressed by backInteraction.collectIsPressedAsState()
                    val backScale by animateFloatAsState(
                        targetValue = if (backPressed) 0.88f else 1f,
                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
                        label = "chatBackScale"
                    )
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .graphicsLayer {
                                scaleX = backScale
                                scaleY = backScale
                            }
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable(interactionSource = backInteraction, indication = null) { viewModel.setJarvisChatOpen(false) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE4E4E4).copy(alpha = 0.18f))
                            .border(BorderStroke(1.dp, Color(0xFFE4E4E4).copy(alpha = 0.40f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "JARVIS",
                            tint = Color(0xFFE4E4E4),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "JARVIS",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulsingStatusDot(Color(0xFFE4E4E4), size = 4.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "online",
                                fontSize = 11.sp,
                                color = Color(0xFFE4E4E4)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    val callInteraction = remember { MutableInteractionSource() }
                    val callPressed by callInteraction.collectIsPressedAsState()
                    val callScale by animateFloatAsState(
                        targetValue = if (callPressed) 0.88f else 1f,
                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
                        label = "chatCallScale"
                    )
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .graphicsLayer {
                                scaleX = callScale
                                scaleY = callScale
                            }
                            .clip(CircleShape)
                            .background(Color(0xFFEFEFEF).copy(alpha = 0.18f))
                            .border(BorderStroke(1.dp, Color(0xFFEFEFEF).copy(alpha = 0.45f)), CircleShape)
                            .clickable(interactionSource = callInteraction, indication = null) {
                                try {
                                    context.startActivity(com.example.call.JarvisCallActivity.outgoing(context))
                                } catch (t: Throwable) {
                                    android.widget.Toast.makeText(context, "Call screen failed: ${t.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call JARVIS",
                            tint = Color(0xFFE4E4E4),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFFE4E4E4).copy(alpha = 0.15f))
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Say hello to JARVIS.",
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                    items(messages, key = { it.id }) { msg ->
                        val fromJarvis = msg.role == "assistant"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = tween(260, easing = FastOutSlowInEasing),
                                    placementSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                                    fadeOutSpec = tween(160)
                                ),
                            horizontalArrangement = if (fromJarvis) Arrangement.Start else Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = if (fromJarvis) 4.dp else 16.dp,
                                            bottomEnd = if (fromJarvis) 16.dp else 4.dp
                                        )
                                    )
                                    .background(
                                        if (fromJarvis) Color(0xFF15181D)
                                        else Color(0xFFEFEFEF).copy(alpha = 0.24f)
                                    )
                                    .border(
                                        BorderStroke(1.dp, Color(0xFFE4E4E4).copy(alpha = if (fromJarvis) 0.14f else 0.30f)),
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = if (fromJarvis) 4.dp else 16.dp,
                                            bottomEnd = if (fromJarvis) 16.dp else 4.dp
                                        )
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = msg.content,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = if (fromJarvis) Color(0xFFE2E8F0) else Color.White
                                    )
                                    Text(
                                        text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(msg.timestamp)),
                                        fontSize = 9.sp,
                                        color = Color.Gray.copy(alpha = 0.8f),
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .padding(bottom = 8.dp),
                        placeholder = {
                            Text("Message JARVIS...", fontSize = 14.sp, color = Color.Gray)
                        },
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF15181D),
                            unfocusedContainerColor = Color(0xFF101216),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFFE4E4E4)
                        )
                    )
                    val canSend = inputText.isNotBlank() && threadId != null
                    val sendColor by animateColorAsState(
                        targetValue = if (canSend) Color(0xFFEFEFEF) else Color(0xFF1E2420),
                        animationSpec = tween(200),
                        label = "jarvisSendColor"
                    )
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(sendColor)
                            .clickable {
                                if (canSend) {
                                    viewModel.sendReply(threadId!!, inputText.trim())
                                    inputText = ""
                                    keyboardController?.hide()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) Color(0xFF0A0A0A) else Color.Gray,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceModeOverlay(viewModel: JarvisViewModel) {
    val voiceRmsDb by viewModel.voiceRmsDb.collectAsStateWithLifecycle()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    val voicePartialText by viewModel.voicePartialText.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generatingStatus by viewModel.generatingStatus.collectAsStateWithLifecycle()

    val volumeLevel = (voiceRmsDb.coerceIn(0f, 12f) / 12f)

    val sphereState = when {
        isGenerating -> when {
            generatingStatus.contains("search", ignoreCase = true) -> SphereState.Searching
            generatingStatus.contains("solv", ignoreCase = true) ||
                    generatingStatus.contains("calculat", ignoreCase = true) -> SphereState.Solving
            generatingStatus.contains("generat", ignoreCase = true) ||
                    generatingStatus.contains("creat", ignoreCase = true) ||
                    generatingStatus.contains("imag", ignoreCase = true) -> SphereState.Composing
            else -> SphereState.Working
        }
        voiceStatus.contains("Speaking", ignoreCase = true) -> SphereState.Speaking
        voiceStatus.contains("Listening", ignoreCase = true) -> SphereState.Listening
        voiceStatus.contains("Connect", ignoreCase = true) -> SphereState.Connecting
        else -> SphereState.Idle
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(brush = AurexColors.voiceBackdrop()) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { viewModel.setVoiceMode(false) },
        contentAlignment = Alignment.Center
    ) {
        val statusColor by animateColorAsState(
            targetValue = AurexColors.statusColor(voiceStatus),
            animationSpec = tween(450, easing = FastOutSlowInEasing),
            label = "voiceStatusColor"
        )
        Text(
            text = voiceStatus,
            color = statusColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 16.dp)
        )

        val closeInteraction = remember { MutableInteractionSource() }
        val closePressed by closeInteraction.collectIsPressedAsState()
        val closeScale by animateFloatAsState(
            targetValue = if (closePressed) 0.88f else 1f,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
            label = "closeScale"
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 16.dp, top = 10.dp)
                .size(38.dp)
                .graphicsLayer {
                    scaleX = closeScale
                    scaleY = closeScale
                }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), CircleShape)
                .clickable(
                    interactionSource = closeInteraction,
                    indication = null
                ) { viewModel.setVoiceMode(false) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Voice Mode",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            AnimatedContent(
                targetState = sphereState == SphereState.Speaking,
                transitionSpec = {
                    (fadeIn(tween(280, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(200)))
                },
                label = "orbSpeakingSwap"
            ) { isSpeakingNow ->
                if (isSpeakingNow) {
                    AurexSpeakingSphere(
                        modifier = Modifier.size(300.dp),
                        speaking = true,
                        text = voicePartialText,
                        clock = { VoiceLevelBus.speechClock() },
                        contentDescription = "AUREX voice orb"
                    )
                } else {
                    AurexSphere(
                        state = sphereState,
                        modifier = Modifier.size(300.dp),
                        volume = { VoiceLevelBus.level.value },
                        contentDescription = "AUREX voice orb"
                    )
                }
            }

            // Real-time Voice Transcript - printed directly, no container
            if (voicePartialText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = voicePartialText,
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
fun VoiceOrbWidget(voiceStatus: String, voiceRmsDb: Float, voiceText: String = "", size: Dp = 280.dp) {
    val orbState = when {
        voiceStatus.contains("Listening", ignoreCase = true) -> "listening"
        voiceStatus.contains("Thinking", ignoreCase = true) || voiceStatus.contains("Processing", ignoreCase = true) -> "thinking"
        voiceStatus.contains("Speaking", ignoreCase = true) -> "speaking"
        voiceStatus.contains("Connect", ignoreCase = true) -> "connecting"
        voiceStatus.contains("Error", ignoreCase = true) || voiceStatus.contains("Failed", ignoreCase = true) -> "error"
        else -> "listening"
    }

    val volNorm = (voiceRmsDb.coerceIn(0f, 12f) / 12f).coerceIn(0.1f, 1.0f)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val escapedText = voiceText.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")

    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    val orbHtml = remember(sizePx) { cloudOrbHtml(sizePx) }

    LaunchedEffect(orbState, volNorm, voiceText) {
        webViewRef?.evaluateJavascript(
            "if (window.setOrbState) { window.setOrbState('$orbState', $volNorm, '$escapedText'); }",
            null
        )
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                "if (window.setOrbState) { window.setOrbState('$orbState', $volNorm, '$escapedText'); }",
                                null
                            )
                        }
                    }
                    loadDataWithBaseURL("https://app.local", orbHtml, "text/html", "UTF-8", null)
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
                webView.evaluateJavascript(
                    "if (window.setOrbState) { window.setOrbState('$orbState', $volNorm, '$escapedText'); }",
                    null
                )
            },
            onRelease = { webView ->
                webViewRef = null
                try {
                    webView.stopLoading()
                    webView.onPause()
                    webView.destroy()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

internal fun cloudOrbHtml(sizePx: Int): String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<style>
  html, body {
    margin: 0; padding: 0; width: 100%; height: 100%;
    background: transparent;
    display: flex; align-items: center; justify-content: center;
    overflow: hidden;
  }
  #stage { width: ${sizePx}px; height: ${sizePx}px; position: relative; display: flex; align-items: center; justify-content: center; }
  #orbWrap { position: relative; width: 154px; height: 154px; border-radius: 50%; }
  #launchDot { position: absolute; inset: 0; border-radius: 50%; background: #5659dc; opacity: 0; transform: scale(0.063); will-change: opacity, transform; }
  #orbCanvas { position: absolute; inset: 0; width: 154px; height: 154px; border-radius: 50%; opacity: 0; transform: scale(0.063); will-change: opacity, transform; }
  #spinner { position: absolute; left: 50%; top: 50%; width: 16px; height: 16px; box-sizing: border-box; border: 2px solid rgba(113, 120, 245, 0.24); border-top-color: #777ff6; border-right-color: #777ff6; border-radius: 50%; opacity: 0; margin-left: -8px; margin-top: -8px; will-change: opacity, transform; }
</style>
</head>
<body>
<div id="stage">
  <div id="orbWrap">
    <span id="launchDot"></span>
    <canvas id="orbCanvas"></canvas>
    <span id="spinner"></span>
  </div>
</div>

<script>
(function () {
  var NEUTRAL_DIAMETER = 0.55;
  var LISTEN_SHRINK = 0.204;
  var SPEAK_GROW = 0.2145;
  var DOT_SCALE = 0.063;
  var ENTRANCE_OVERSHOOT = 1.178;
  var DOT_HOLD_MS = 180;
  var GROW_MS = 300;
  var SETTLE_MS = 1350;
  var SURFACE_FADE_START_MS = DOT_HOLD_MS + GROW_MS * 0.22;
  var SURFACE_FADE_END_MS = DOT_HOLD_MS + GROW_MS * 0.8;
  var DOT_FADE_START_MS = DOT_HOLD_MS + GROW_MS * 0.58;
  var DOT_FADE_END_MS = DOT_HOLD_MS + GROW_MS;

  var SIZE = ${sizePx};
  var diameter = SIZE * NEUTRAL_DIAMETER;

  var canvas = document.getElementById('orbCanvas');
  var launchDot = document.getElementById('launchDot');
  var spinner = document.getElementById('spinner');

  function clamp(value, min, max) {
    if (min === undefined) min = 0;
    if (max === undefined) max = 1;
    return Math.min(max, Math.max(min, value));
  }
  function damp(current, target, rate, deltaSeconds) {
    return current + (target - current) * (1 - Math.exp(-rate * deltaSeconds));
  }
  function easeOutCubic(value) { return 1 - Math.pow(1 - value, 3); }
  function mix(from, to, progress) { return from + (to - from) * progress; }
  function smoothstepRange(value, start, end) {
    var progress = clamp((value - start) / (end - start));
    return progress * progress * (3 - 2 * progress);
  }
  function isVisibleState(state) {
    return state === 'listening' || state === 'speaking' || state === 'thinking';
  }

  var VERTEX_SHADER = [
    'attribute vec2 a_position;',
    '',
    'void main() {',
    '  gl_Position = vec4(a_position, 0.0, 1.0);',
    '}'
  ].join('\n');

  var FRAGMENT_SHADER = [
    'precision highp float;',
    '',
    'uniform vec2 u_resolution;',
    'uniform float u_time;',
    'uniform float u_activity;',
    '',
    'float hash(vec2 p) {',
    '  p = fract(p * vec2(123.34, 456.21));',
    '  p += dot(p, p + 45.32);',
    '  return fract(p.x * p.y);',
    '}',
    '',
    'float noise(vec2 p) {',
    '  vec2 i = floor(p);',
    '  vec2 f = fract(p);',
    '  vec2 u = f * f * (3.0 - 2.0 * f);',
    '',
    '  return mix(',
    '    mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),',
    '    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),',
    '    u.y',
    '  );',
    '}',
    '',
    'float fbm(vec2 p) {',
    '  float value = 0.0;',
    '  float amplitude = 0.52;',
    '  mat2 rotation = mat2(0.80, 0.60, -0.60, 0.80);',
    '',
    '  for (int octave = 0; octave < 5; octave++) {',
    '    value += amplitude * noise(p);',
    '    p = rotation * p * 1.92 + vec2(9.7, 4.3);',
    '    amplitude *= 0.5;',
    '  }',
    '',
    '  return value;',
    '}',
    '',
    'void main() {',
    '  vec2 uv = gl_FragCoord.xy / u_resolution;',
    '  vec2 centered = uv - 0.5;',
    '  float radius = length(centered);',
    '  float edge = 1.0 - smoothstep(0.488, 0.5, radius);',
    '',
    '  if (edge <= 0.0) discard;',
    '',
    '  vec2 p = centered * 2.0;',
    '  float t = u_time;',
    '',
    '  vec2 warp = vec2(',
    '    fbm(p * 1.02 + vec2(t * 0.34, -t * 0.24)),',
    '    fbm(p * 1.08 + vec2(-t * 0.27, t * 0.32) + vec2(6.7, 2.9))',
    '  );',
    '  vec2 curl = vec2(',
    '    sin(p.y * 2.4 + t * 0.68 + warp.y * 3.2),',
    '    cos(p.x * 2.1 - t * 0.61 + warp.x * 3.0)',
    '  );',
    '  vec2 warped =',
    '    p +',
    '    (warp - 0.5) * (1.18 + u_activity * 0.38) +',
    '    curl * (0.035 + u_activity * 0.07);',
    '  float broad = fbm(warped * 0.92 + vec2(t * 0.14, -t * 0.18));',
    '  float folded = fbm(warped * 1.66 + vec2(-t * 0.23, t * 0.19) + 5.2);',
    '  float field = mix(broad, folded, 0.3 + u_activity * 0.14);',
    '',
    '  float horizon =',
    '    0.46 +',
    '    0.08 * sin((uv.x + warp.x * 0.2) * 5.4 + t * 0.42) +',
    '    0.16 * (broad - 0.5);',
    '  float upper = smoothstep(horizon - 0.12, horizon + 0.08, uv.y);',
    '  float band = exp(-pow((uv.y - horizon) * (5.2 + u_activity * 0.8), 2.0));',
    '  float cloud = smoothstep(0.24, 0.79, field);',
    '',
    '  vec3 deepPeriwinkle = vec3(0.36, 0.39, 0.985);',
    '  vec3 upperPeriwinkle = vec3(0.48, 0.56, 0.985);',
    '  vec3 lowerLavender = vec3(0.72, 0.78, 0.975);',
    '  vec3 milk = vec3(0.89, 0.92, 0.995);',
    '',
    '  vec3 color = mix(lowerLavender, upperPeriwinkle, upper);',
    '  float upperDepth = upper * (0.14 + smoothstep(0.42, 0.78, folded) * 0.5);',
    '  color = mix(color, deepPeriwinkle, upperDepth);',
    '',
    '  float milkAmount = clamp(band * (0.42 + cloud * 0.62), 0.0, 0.88);',
    '  color = mix(color, milk, milkAmount);',
    '',
    '  float lowerMist = (1.0 - upper) * smoothstep(0.58, 0.9, broad) * 0.18;',
    '  color = mix(color, milk, lowerMist);',
    '',
    '  float grain = (noise(gl_FragCoord.xy * 0.64) - 0.5) / 255.0;',
    '  color += grain;',
    '',
    '  gl_FragColor = vec4(color, edge);',
    '}'
  ].join('\n');

  function compileShader(gl, type, source) {
    var shader = gl.createShader(type);
    if (!shader) return null;
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      gl.deleteShader(shader);
      return null;
    }
    return shader;
  }

  function createCloudRenderer(cv, dia) {
    var gl = cv.getContext('webgl', {
      alpha: true,
      antialias: true,
      premultipliedAlpha: true
    });
    if (!gl) return null;

    var vertexShader = compileShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER);
    var fragmentShader = compileShader(gl, gl.FRAGMENT_SHADER, FRAGMENT_SHADER);
    if (!vertexShader || !fragmentShader) return null;

    var program = gl.createProgram();
    var buffer = gl.createBuffer();
    if (!program || !buffer) return null;

    gl.attachShader(program, vertexShader);
    gl.attachShader(program, fragmentShader);
    gl.linkProgram(program);

    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return null;

    var positionLocation = gl.getAttribLocation(program, 'a_position');
    var resolutionLocation = gl.getUniformLocation(program, 'u_resolution');
    var timeLocation = gl.getUniformLocation(program, 'u_time');
    var activityLocation = gl.getUniformLocation(program, 'u_activity');
    if (positionLocation < 0 || !resolutionLocation || !timeLocation || !activityLocation) return null;

    var pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    var pixelSize = Math.max(1, Math.round(dia * pixelRatio));
    cv.width = pixelSize;
    cv.height = pixelSize;

    gl.viewport(0, 0, pixelSize, pixelSize);
    gl.useProgram(program);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]),
      gl.STATIC_DRAW
    );
    gl.enableVertexAttribArray(positionLocation);
    gl.vertexAttribPointer(positionLocation, 2, gl.FLOAT, false, 0, 0);
    gl.uniform2f(resolutionLocation, pixelSize, pixelSize);

    return {
      draw: function (time, activity) {
        gl.uniform1f(timeLocation, time);
        gl.uniform1f(activityLocation, activity);
        gl.drawArrays(gl.TRIANGLES, 0, 6);
      },
      destroy: function () {
        gl.deleteProgram(program);
        gl.deleteBuffer(buffer);
        gl.deleteShader(vertexShader);
        gl.deleteShader(fragmentShader);
      }
    };
  }

  function entranceScale(elapsed) {
    if (elapsed <= DOT_HOLD_MS) return DOT_SCALE;
    if (elapsed <= DOT_HOLD_MS + GROW_MS) {
      var progress = easeOutCubic((elapsed - DOT_HOLD_MS) / GROW_MS);
      return mix(DOT_SCALE, ENTRANCE_OVERSHOOT, progress);
    }
    var settleElapsed = elapsed - DOT_HOLD_MS - GROW_MS;
    if (settleElapsed >= SETTLE_MS) return 1;
    var settleProgress = clamp(settleElapsed / SETTLE_MS);
    return 1 + (ENTRANCE_OVERSHOOT - 1) * Math.pow(1 - settleProgress, 2);
  }

  var reducedMotion = false;
  try {
    var motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    reducedMotion = motionQuery.matches;
  } catch (e) { reducedMotion = false; }

  var renderer = null;
  try {
    renderer = createCloudRenderer(canvas, diameter);
  } catch (e) { renderer = null; }
  if (!renderer) {
    canvas.style.background =
      'linear-gradient(180deg, #626afb 2%, #8f9dfb 32%, #dde6fd 52%, #c9d3fb 84%)';
  }

  var stateRef = { current: 'idle' };
  var volumeRef = { current: 0 };

  window.setOrbState = function (state, volume) {
    stateRef.current = state || 'idle';
    volumeRef.current = clamp(typeof volume === 'number' ? volume : 0);
  };

  var frame = 0;
  var previousTime = performance.now();
  var previousState = stateRef.current;
  var entranceStarted = isVisibleState(previousState) ? previousTime : undefined;
  var currentVolume = clamp(volumeRef.current);
  var currentAudioScale = 1;
  var currentOpacity = isVisibleState(previousState) ? 1 : 0;
  var currentExitScale = 1;
  var currentSpinnerOpacity = previousState === 'connecting' ? 1 : 0;
  var flowTime = 0;

  function render(now) {
    var deltaSeconds = Math.min((now - previousTime) / 1000, 0.05);
    previousTime = now;

    var nextState = stateRef.current;

    if (nextState !== previousState) {
      if (isVisibleState(nextState) && !isVisibleState(previousState)) entranceStarted = now;
      if (nextState === 'idle' || nextState === 'error') entranceStarted = undefined;
      previousState = nextState;
    }

    var rawVolume = clamp(volumeRef.current);
    var volumeRate = rawVolume > currentVolume ? 11 : 6;
    currentVolume = reducedMotion ? 0 : damp(currentVolume, rawVolume, volumeRate, deltaSeconds);

    var audioScaleTarget = 1;
    if (nextState === 'listening') audioScaleTarget = 1 - currentVolume * LISTEN_SHRINK;
    if (nextState === 'speaking') audioScaleTarget = 1 + currentVolume * SPEAK_GROW;

    var scaleRate = Math.abs(audioScaleTarget - 1) > Math.abs(currentAudioScale - 1) ? 12 : 6;
    currentAudioScale = reducedMotion
      ? 1
      : damp(currentAudioScale, audioScaleTarget, scaleRate, deltaSeconds);

    var visible = isVisibleState(nextState);
    var canvasOpacityTarget = visible ? 1 : 0;
    currentOpacity = reducedMotion
      ? canvasOpacityTarget
      : damp(currentOpacity, canvasOpacityTarget, 14, deltaSeconds);

    var exitScaleTarget = visible ? 1 : 0.92;
    currentExitScale = reducedMotion
      ? exitScaleTarget
      : damp(currentExitScale, exitScaleTarget, 12, deltaSeconds);

    var scale = currentAudioScale * currentExitScale;
    var surfaceMix = 1;
    var launchDotOpacity = 0;

    if (visible && entranceStarted !== undefined && !reducedMotion) {
      var elapsed = now - entranceStarted;
      var entrance = entranceScale(elapsed);
      var audioInfluence = clamp((elapsed - DOT_HOLD_MS - GROW_MS) / SETTLE_MS);
      scale = entrance * mix(1, currentAudioScale, audioInfluence);
      surfaceMix = smoothstepRange(elapsed, SURFACE_FADE_START_MS, SURFACE_FADE_END_MS);
      launchDotOpacity =
        currentOpacity * (1 - smoothstepRange(elapsed, DOT_FADE_START_MS, DOT_FADE_END_MS));
      if (elapsed >= DOT_HOLD_MS + GROW_MS + SETTLE_MS) entranceStarted = undefined;
    }

    var spinnerTarget = nextState === 'connecting' ? 1 : 0;
    currentSpinnerOpacity = reducedMotion
      ? spinnerTarget
      : damp(currentSpinnerOpacity, spinnerTarget, 18, deltaSeconds);

    canvas.style.opacity = String(currentOpacity * surfaceMix);
    canvas.style.transform = 'scale(' + scale + ')';
    launchDot.style.opacity = String(launchDotOpacity);
    launchDot.style.transform = 'scale(' + scale + ')';
    spinner.style.opacity = String(currentSpinnerOpacity);
    spinner.style.transform = 'rotate(' + (reducedMotion ? 45 : now * 0.34) + 'deg)';

    var speed = 0.24;
    var activity = 0.1;
    if (nextState === 'listening') {
      speed = 0.72 + currentVolume * 0.78;
      activity = 0.28 + currentVolume * 0.32;
    } else if (nextState === 'speaking') {
      speed = 1.65 + currentVolume * 1.55;
      activity = 0.66 + currentVolume * 0.34;
    }

    if (!reducedMotion) flowTime += deltaSeconds * speed;
    if (renderer) renderer.draw(flowTime, activity);

    frame = requestAnimationFrame(render);
  }

  frame = requestAnimationFrame(render);
})();
</script>
</body>
</html>"""



@Composable
fun ApiConfigModal(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val coreKey by viewModel.coreAiApiKey.collectAsStateWithLifecycle()
    val geminiModel by viewModel.geminiModel.collectAsStateWithLifecycle()
    val imageKey by viewModel.imageGenApiKey.collectAsStateWithLifecycle()
    val otherKey by viewModel.otherApiKey.collectAsStateWithLifecycle()
    val nvidiaKey by viewModel.nvidiaApiKey.collectAsStateWithLifecycle()
    val tavilyKey by viewModel.tavilyApiKey.collectAsStateWithLifecycle()
    val nvidiaBaseUrl by viewModel.nvidiaBaseUrl.collectAsStateWithLifecycle()
    val nvidiaModel by viewModel.nvidiaModel.collectAsStateWithLifecycle()
    val visionModel by viewModel.visionModel.collectAsStateWithLifecycle()
    val backendBaseUrl by viewModel.backendBaseUrl.collectAsStateWithLifecycle()
    val n8nWebhookUrl by viewModel.n8nWebhookUrl.collectAsStateWithLifecycle()
    val n8nApiKey by viewModel.n8nApiKey.collectAsStateWithLifecycle()
    val n8nTriggerKeywords by viewModel.n8nTriggerKeywords.collectAsStateWithLifecycle()
    val n8nAutoTriggerEnabled by viewModel.n8nAutoTriggerEnabled.collectAsStateWithLifecycle()
    val customProviders by viewModel.customProviders.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProvider.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }
    var tCore by remember(coreKey) { mutableStateOf(coreKey) }
    var tGemini by remember(geminiModel) { mutableStateOf(geminiModel) }
    var tImage by remember(imageKey) { mutableStateOf(imageKey) }
    var tOther by remember(otherKey) { mutableStateOf(otherKey) }
    var tNvidiaKey by remember(nvidiaKey) { mutableStateOf(nvidiaKey) }
    var tTavily by remember(tavilyKey) { mutableStateOf(tavilyKey) }
    var tNvidiaUrl by remember(nvidiaBaseUrl) { mutableStateOf(nvidiaBaseUrl) }
    var tNvidiaModel by remember(nvidiaModel) { mutableStateOf(nvidiaModel) }
    var tVision by remember(visionModel) { mutableStateOf(visionModel) }
    var tBackend by remember(backendBaseUrl) { mutableStateOf(backendBaseUrl) }
    var tWebhook by remember(n8nWebhookUrl) { mutableStateOf(n8nWebhookUrl) }
    var tN8nKey by remember(n8nApiKey) { mutableStateOf(n8nApiKey) }
    var tKeywords by remember(n8nTriggerKeywords) { mutableStateOf(n8nTriggerKeywords) }
    var tCpName by remember { mutableStateOf("") }
    var tCpUrl by remember { mutableStateOf("") }
    var tCpKey by remember { mutableStateOf("") }
    var tCpModel by remember { mutableStateOf("") }
    var tCpHeaders by remember { mutableStateOf("") }

    val fieldBorderColor = if (isDarkMode) Color.White.copy(alpha = 0.12f) else Color(0xFF0F172A).copy(alpha = 0.1f)
    val fieldBg = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White
    val textColor = if (isDarkMode) Color.White else Color(0xFF0F172A)

    val keyboardActions = androidx.compose.foundation.text.KeyboardActions()

    @Composable
    fun LabeledField(label: String, value: String, onValue: (String) -> Unit, placeholder: String = "", visual: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B))
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                placeholder = { Text(placeholder, fontSize = 13.sp, color = Color(0xFF8A9199)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                visualTransformation = visual,
                keyboardActions = keyboardActions,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFE4E4E4),
                    unfocusedBorderColor = fieldBorderColor,
                    focusedContainerColor = fieldBg,
                    unfocusedContainerColor = fieldBg,
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor
                )
            )
        }
    }

    Dialog(
        onDismissRequest = { viewModel.setApiModalOpen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkMode) Color(0xFF0A0F0D) else Color(0xFFF7FAF9))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.setApiModalOpen(false) },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = if (isDarkMode) Color.White else Color(0xFF0F172A), modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text("API Configuration", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color(0xFF0F172A))
                        Text("Providers, keys and automations", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { tab = 0 },
                        shape = RoundedCornerShape(99.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tab == 0) Color(0xFFEFEFEF) else Color.Transparent,
                            contentColor = if (tab == 0) Color.White else if (isDarkMode) Color.White else Color(0xFF0F172A)
                        ),
                        border = if (tab != 0) BorderStroke(1.dp, if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color(0xFF0F172A).copy(alpha = 0.12f)) else null
                    ) { Text("AI Models", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { tab = 1 },
                        shape = RoundedCornerShape(99.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tab == 1) Color(0xFFEFEFEF) else Color.Transparent,
                            contentColor = if (tab == 1) Color.White else if (isDarkMode) Color.White else Color(0xFF0F172A)
                        ),
                        border = if (tab != 1) BorderStroke(1.dp, if (isDarkMode) Color.White.copy(alpha = 0.15f) else Color(0xFF0F172A).copy(alpha = 0.12f)) else null
                    ) { Text("Automations (n8n)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (tab == 0) {
                        LabeledField("Core AI API Key", tCore, { tCore = it }, "sk-... or AIza...", androidx.compose.ui.text.input.PasswordVisualTransformation())
                        LabeledField("Gemini Model", tGemini, { tGemini = it }, "gemini-2.5-flash")
                        LabeledField("Image Generation Key", tImage, { tImage = it }, "Optional - Pollinations works free")

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White)
                                .border(1.dp, if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color(0xFF0F172A).copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF76B900).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                    Text("N", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF76B900))
                                }
                                Spacer(modifier = Modifier.width(9.dp))
                                Text("NVIDIA NIM", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color(0xFF0F172A))
                            }
                            LabeledField("NVIDIA API Key", tNvidiaKey, { tNvidiaKey = it }, "nvapi-...", androidx.compose.ui.text.input.PasswordVisualTransformation())
                            LabeledField("Chat Model", tNvidiaModel, { tNvidiaModel = it }, "meta/llama-3.1-8b-instruct")
                            LabeledField("Vision Model", tVision, { tVision = it }, "google/diffusiongemma-26b-a4b-it")
                            LabeledField("Base URL", tNvidiaUrl, { tNvidiaUrl = it }, "https://integrate.api.nvidia.com/v1/")
                        }

                        LabeledField("Tavily Search Key", tTavily, { tTavily = it }, "tvly-... (optional, free fallback exists)", androidx.compose.ui.text.input.PasswordVisualTransformation())
                        LabeledField("Custom Backend URL", tBackend, { tBackend = it }, "https://your-server.com/api")

                        Button(
                            onClick = {
                                viewModel.saveApiKeys(tCore, tGemini, tImage, tOther)
                                viewModel.saveNvidiaConfig(tNvidiaKey, tNvidiaUrl, tNvidiaModel)
                                viewModel.setVisionModel(tVision)
                                viewModel.saveTavilyKey(tTavily)
                                viewModel.setBackendBaseUrl(tBackend)
                                Toast.makeText(context, "Configuration saved", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFEFEF))
                        ) {
                            Text("Save Configuration", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    } else {
                        LabeledField("n8n Webhook URL", tWebhook, { tWebhook = it }, "https://your-n8n.cloud/webhook/aurex-action")
                        LabeledField("Auth (Bearer token or X-N8N-API-KEY)", tN8nKey, { tN8nKey = it }, "", androidx.compose.ui.text.input.PasswordVisualTransformation())
                        LabeledField("Trigger Keywords (comma separated)", tKeywords, { tKeywords = it }, "n8n, webhook, automate, deploy")

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-trigger on keywords", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF0F172A))
                                Text("Fire the webhook when a message contains a trigger word", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .size(width = 44.dp, height = 25.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(if (n8nAutoTriggerEnabled) Color(0xFFEFEFEF) else Color(0xFF64748B).copy(alpha = 0.4f))
                                    .clickable { viewModel.setN8nAutoTriggerEnabled(!n8nAutoTriggerEnabled) },
                                contentAlignment = if (n8nAutoTriggerEnabled) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(19.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.setN8nWebhookUrl(tWebhook)
                                    viewModel.setN8nApiKey(tN8nKey)
                                    viewModel.setN8nTriggerKeywords(tKeywords)
                                    Toast.makeText(context, "Automations saved", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(15.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFEFEF))
                            ) { Text("Save", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                            Button(
                                onClick = {
                                    viewModel.setN8nWebhookUrl(tWebhook)
                                    viewModel.setN8nApiKey(tN8nKey)
                                    viewModel.runConnectorSelfTest("n8n")
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(15.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                            ) { Text("Test Webhook", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectorsModal(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val gmailConnected by viewModel.gmailConnected.collectAsStateWithLifecycle()
    val driveConnected by viewModel.driveConnected.collectAsStateWithLifecycle()
    val calendarConnected by viewModel.calendarConnected.collectAsStateWithLifecycle()
    val webSearchConnected by viewModel.webSearchConnected.collectAsStateWithLifecycle()
    val osBridgeConnected by viewModel.osBridgeConnected.collectAsStateWithLifecycle()
    val n8nConnected by viewModel.n8nConnected.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = { viewModel.setConnectorsModalOpen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkMode) Color(0xFF0A0F0D) else Color(0xFFF7FAF9))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.setConnectorsModalOpen(false) },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = if (isDarkMode) Color.White else Color(0xFF0F172A), modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text("Connectors", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color(0xFF0F172A))
                        Text("System control · web · automations", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ConnectorSectionCard(isDarkMode = isDarkMode, title = "System Control", dotColor = Color(0xFFEFEFEF), badge = "ACTIVE", badgeColor = Color(0xFFEFEFEF)) {
                        Text(
                            "Calls, contacts, apps, flashlight, camera, alarms and volume - always available through voice commands.",
                            fontSize = 11.5.sp, lineHeight = 16.sp, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF475569)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            ConnectorChip("Flashlight", Color(0xFFEFEFEF)) { executeSystemControlStatic(context, "flashlight_toggle", viewModel) }
                            ConnectorChip("Battery", Color(0xFFEFEFEF)) { executeSystemControlStatic(context, "battery_check", viewModel) }
                            ConnectorChip("Max Volume", Color(0xFF3B82F6)) { executeSystemControlStatic(context, "volume_max", viewModel) }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            ConnectorChip("Wi-Fi On/Off", Color(0xFF3B82F6)) {
                                val msg = com.example.connectors.DeviceControlBridge.toggleWifi(context)
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            ConnectorChip("Bluetooth", Color(0xFFA855F7)) {
                                val msg = com.example.connectors.DeviceControlBridge.toggleBluetooth(context)
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            ConnectorChip("Airplane Mode", Color(0xFFEF4444)) {
                                val msg = com.example.connectors.DeviceControlBridge.toggleAirplaneMode(context)
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                            ConnectorChip("Internet", Color(0xFFEFEFEF)) {
                                val msg = com.example.connectors.DeviceControlBridge.toggleWifi(context)
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        SwitchToggleRow(isDarkMode = isDarkMode, label = "System bridge", checked = osBridgeConnected) { viewModel.toggleConnector("os_bridge") }
                    }

                    ConnectorSectionCard(isDarkMode = isDarkMode, title = "Calendar", dotColor = Color(0xFFEFEFEF), badge = "ON-DEVICE", badgeColor = Color(0xFFEFEFEF)) {
                        Text(
                            "Reads your real agenda and creates real events. Say 'what is on my agenda' or 'add event tomorrow at 9am'.",
                            fontSize = 11.5.sp, lineHeight = 16.sp, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF475569)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        ConnectorChip("Test Connection", Color(0xFFEFEFEF)) { viewModel.runConnectorSelfTest("calendar") }
                        Spacer(modifier = Modifier.height(10.dp))
                        SwitchToggleRow(isDarkMode = isDarkMode, label = "Calendar access", checked = calendarConnected) { viewModel.toggleConnector("calendar") }
                    }

                    ConnectorSectionCard(isDarkMode = isDarkMode, title = "Web Search", dotColor = Color(0xFFF59E0B), badge = if (webSearchConnected) "ON" else "OFF", badgeColor = if (webSearchConnected) Color(0xFFEFEFEF) else Color(0xFF64748B)) {
                        Text(
                            "Tavily search with free scraping fallback - works with or without a key.",
                            fontSize = 11.5.sp, lineHeight = 16.sp, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF475569)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        SwitchToggleRow(isDarkMode = isDarkMode, label = "Live web search", checked = webSearchConnected) { viewModel.toggleConnector("web_search") }
                    }

                    ConnectorSectionCard(isDarkMode = isDarkMode, title = "n8n Automations", dotColor = Color(0xFF3B82F6), badge = if (n8nConnected) "ON" else "OFF", badgeColor = if (n8nConnected) Color(0xFFEFEFEF) else Color(0xFF64748B)) {
                        Text(
                            "Trigger your own workflows by keyword. Configure the webhook in API Configuration.",
                            fontSize = 11.5.sp, lineHeight = 16.sp, color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF475569)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            ConnectorChip("Test Webhook", Color(0xFFFF6D5A)) { viewModel.runConnectorSelfTest("n8n") }
                            ConnectorChip("Configure", Color(0xFF3B82F6)) {
                                viewModel.setConnectorsModalOpen(false)
                                viewModel.setApiModalOpen(true)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        SwitchToggleRow(isDarkMode = isDarkMode, label = "n8n connected", checked = n8nConnected) { viewModel.toggleConnector("n8n") }
                    }

                    ConnectorSectionCard(isDarkMode = isDarkMode, title = "Cloud Connectors", dotColor = Color(0xFF8B5CF6), badge = null, badgeColor = Color(0xFF8B5CF6)) {
                        SwitchToggleRow(isDarkMode = isDarkMode, label = "Gmail", sub = "Compose and search via the Gmail app", checked = gmailConnected) { viewModel.toggleConnector("gmail") }
                        SwitchToggleRow(isDarkMode = isDarkMode, label = "Google Drive", sub = "Open Drive and upload files", checked = driveConnected) { viewModel.toggleConnector("drive") }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ConnectorSectionCard(
    isDarkMode: Boolean,
    title: String,
    dotColor: Color,
    badge: String?,
    badgeColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.White)
            .border(1.dp, if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color(0xFF0F172A).copy(alpha = 0.07f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(dotColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            }
            Spacer(modifier = Modifier.width(9.dp))
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = if (isDarkMode) Color.White else Color(0xFF0F172A))
            if (badge != null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    badge,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                    color = badgeColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SwitchToggleRow(
    isDarkMode: Boolean,
    label: String,
    sub: String? = null,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF0F172A))
            if (sub != null) Text(sub, fontSize = 11.sp, color = Color(0xFF64748B))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 25.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (checked) Color(0xFFEFEFEF) else Color(0xFF64748B).copy(alpha = 0.4f))
                .clickable { onToggle() },
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun ConnectorChip(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(99.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(7.dp))
            Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    activeTint: Color,
    isDarkMode: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val rowBg by animateColorAsState(
        targetValue = if (checked) activeTint.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "settingsRowBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(rowBg)
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                (if (checked) activeTint else Color.Gray).copy(alpha = 0.20f),
                                (if (checked) activeTint else Color.Gray).copy(alpha = 0.06f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (checked) activeTint else Color.Gray,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = AurexColors.textSecondary(isDarkMode)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0A0A0A),
                checkedTrackColor = activeTint
            ),
            modifier = Modifier.scale(0.85f)
        )
    }
}

@Composable
private fun SettingsEndpointCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    placeholder: String,
    iconTint: Color,
    isDarkMode: Boolean,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    if (isDarkMode) listOf(Color(0xFF1A1D22), Color(0xFF14161A))
                    else listOf(Color(0xFFF6F8FA), Color(0xFFF0F3F6))
                )
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = if (isDarkMode) 0.07f else 0.95f)),
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(iconTint.copy(alpha = 0.20f), iconTint.copy(alpha = 0.06f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = AurexColors.textSecondary(isDarkMode)
                )
            }
        }
        androidx.compose.material3.TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder, fontSize = 13.sp, color = Color.Gray.copy(alpha = 0.5f)) },
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = if (isDarkMode) Color.White.copy(alpha = 0.06f) else Color.White,
                unfocusedContainerColor = if (isDarkMode) Color.White.copy(alpha = 0.04f) else Color(0xFFF8FAFC),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = if (isDarkMode) Color.White else Color.Black,
                unfocusedTextColor = if (isDarkMode) Color.White else Color.Black,
                cursorColor = AurexColors.Emerald
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModal(viewModel: JarvisViewModel) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()
    val isStreamingEnabled by viewModel.isStreamingEnabled.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val ttsSpeechRate by viewModel.ttsSpeechRate.collectAsStateWithLifecycle()
    val callServerUrl by viewModel.callServerUrl.collectAsStateWithLifecycle()
    val messagingServerUrl by viewModel.messagingServerUrl.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { viewModel.setSettingsModalOpen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(16.dp)
                .shadow(
                    18.dp,
                    RoundedCornerShape(24.dp),
                    ambientColor = Color.Black.copy(alpha = 0.40f),
                    spotColor = Color.Black.copy(alpha = 0.30f)
                )
                .border(BorderStroke(1.dp, AurexColors.sheenBorder(isDarkMode)), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkMode) Color(0xFF101214) else Color.White
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(AurexColors.EmeraldGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color(0xFF0A0A0A),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Settings",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkMode) Color.White else Color.Black
                        )
                    }
                    val closeInteraction = remember { MutableInteractionSource() }
                    val closePressed by closeInteraction.collectIsPressedAsState()
                    val closeScale by animateFloatAsState(
                        targetValue = if (closePressed) 0.88f else 1f,
                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
                        label = "settingsCloseScale"
                    )
                    IconButton(
                        onClick = { viewModel.setSettingsModalOpen(false) },
                        interactionSource = closeInteraction,
                        modifier = Modifier
                            .size(34.dp)
                            .graphicsLayer {
                                scaleX = closeScale
                                scaleY = closeScale
                            }
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (isDarkMode) 0.08f else 0.7f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = if (isDarkMode) Color.White else Color.Black,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                SettingsToggleRow(
                    icon = Icons.Default.Code,
                    title = "Developer Mode",
                    subtitle = "Hotword & Automation",
                    checked = isDeveloperMode,
                    activeTint = AurexColors.Emerald,
                    isDarkMode = isDarkMode
                ) { viewModel.setDeveloperMode(it) }

                SettingsToggleRow(
                    icon = Icons.Default.Bolt,
                    title = "Token Streaming",
                    subtitle = "Real-time token rendering",
                    checked = isStreamingEnabled,
                    activeTint = AurexColors.Emerald,
                    isDarkMode = isDarkMode
                ) { viewModel.setStreamingEnabled(it) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(AurexColors.Emerald.copy(alpha = 0.20f), AurexColors.Emerald.copy(alpha = 0.06f))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Language",
                                tint = AurexColors.Emerald,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Language",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isDarkMode) Color(0xFF2D3748) else Color(0xFFE2E8F0))
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("English", "Telugu").forEach { lang ->
                            val isSelected = appLanguage == lang
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFFEFEFEF) else Color.Transparent)
                                    .clickable { viewModel.setAppLanguage(lang) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = lang,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color(0xFF0A0A0A) else (if (isDarkMode) Color.Gray else Color.DarkGray)
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.verticalGradient(
                                if (isDarkMode) listOf(Color(0xFF1A1D22), Color(0xFF14161A))
                                else listOf(Color(0xFFF6F8FA), Color(0xFFF0F3F6))
                            )
                        )
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = if (isDarkMode) 0.07f else 0.95f)),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(AurexColors.Emerald.copy(alpha = 0.20f), AurexColors.Emerald.copy(alpha = 0.06f))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "TTS Speed",
                                    tint = AurexColors.Emerald,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "TTS Speech Speed",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                                )
                                Text(
                                    "Accelerate voice response output",
                                    fontSize = 11.sp,
                                    color = AurexColors.textSecondary(isDarkMode)
                                )
                            }
                        }
                        Text(
                            text = String.format(java.util.Locale.US, "%.2fx", ttsSpeechRate),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEFEFEF)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1.0f to "1.0x Normal", 1.25f to "1.25x Fast", 1.5f to "1.5x Turbo", 1.8f to "1.8x Max").forEach { (rateVal, label) ->
                            val isSelected = Math.abs(ttsSpeechRate - rateVal) < 0.05f
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFFEFEFEF) else (if (isDarkMode) Color(0xFF33353A) else Color(0xFFE2E8F0)))
                                    .clickable { viewModel.setTtsSpeechRate(rateVal) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else (if (isDarkMode) Color.LightGray else Color.DarkGray)
                                )
                            }
                        }
                    }

                    Slider(
                        value = ttsSpeechRate,
                        onValueChange = { viewModel.setTtsSpeechRate(it) },
                        valueRange = 0.8f..2.2f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFEFEFEF),
                            activeTrackColor = Color(0xFFEFEFEF),
                            inactiveTrackColor = if (isDarkMode) Color.Gray.copy(alpha = 0.3f) else Color.LightGray
                        )
                    )

                    val context = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val manager = (context as? MainActivity)?.ttsManager
                            if (manager != null) {
                                manager.speak("Testing speed! AUREX voice response speed is now set to ${String.format(java.util.Locale.US, "%.2f", ttsSpeechRate)} times normal speed.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEFEFEF))
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Speech Speed", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Headset,
                        contentDescription = null,
                        tint = AurexColors.Blue,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "VOICE CALL & MESSAGING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = AurexColors.textSecondary(isDarkMode)
                    )
                }

                SettingsEndpointCard(
                    icon = Icons.Default.Call,
                    title = "Call Server (7860)",
                    subtitle = "Endpoint for voice call connections",
                    value = callServerUrl,
                    placeholder = "http://192.168.5.102:7860",
                    iconTint = AurexColors.Blue,
                    isDarkMode = isDarkMode
                ) { viewModel.setCallServerUrl(it) }

                SettingsEndpointCard(
                    icon = Icons.Default.Message,
                    title = "Messaging Server (8080)",
                    subtitle = "Endpoint for chat and event streaming",
                    value = messagingServerUrl,
                    placeholder = "http://192.168.5.102:8080",
                    iconTint = AurexColors.Emerald,
                    isDarkMode = isDarkMode
                ) { viewModel.setMessagingServerUrl(it) }
            }
        }
    }
}

@Composable
fun PersonalityModal(viewModel: JarvisViewModel) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val currentPersonalityId by viewModel.selectedPersonality.collectAsStateWithLifecycle()
    val savedCustomPrompt by viewModel.customSystemPrompt.collectAsStateWithLifecycle()

    var tempSelectedId by remember(currentPersonalityId) { mutableStateOf(currentPersonalityId) }
    var tempCustomPrompt by remember(savedCustomPrompt) { mutableStateOf(savedCustomPrompt) }

    androidx.compose.ui.window.Dialog(onDismissRequest = { viewModel.setPersonalityModalOpen(false) }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(
                    1.dp,
                    if (isDarkMode) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f),
                    RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkMode) Color(0xFF1E1F20) else Color.White
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "AI Personality",
                            tint = Color(0xFFEFEFEF),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI Persona & Voice",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkMode) Color.White else Color.Black
                        )
                    }
                    IconButton(onClick = { viewModel.setPersonalityModalOpen(false) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Gray
                        )
                    }
                }

                HorizontalDivider(color = if (isDarkMode) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f))

                Text(
                    text = "Select a personality style for your AI assistant:",
                    fontSize = 13.sp,
                    color = if (isDarkMode) Color.LightGray else Color.DarkGray
                )

                // Personas list
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(AiPersonalities.ALL) { persona ->
                        val isSelected = persona.id == tempSelectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    when {
                                        isSelected && isDarkMode -> Color(0xFFEFEFEF).copy(alpha = 0.20f)
                                        isSelected -> Color(0xFFEFEFEF).copy(alpha = 0.12f)
                                        isDarkMode -> Color.White.copy(alpha = 0.04f)
                                        else -> Color.Black.copy(alpha = 0.03f)
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) Color(0xFFEFEFEF) else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { tempSelectedId = persona.id }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(persona.badge, fontSize = 22.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = persona.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isSelected) Color(0xFFEFEFEF) else (if (isDarkMode) Color.White else Color.Black)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = persona.title,
                                        fontSize = 11.sp,
                                        color = Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = persona.tagline,
                                    fontSize = 12.sp,
                                    color = if (isDarkMode) Color.LightGray else Color.DarkGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color(0xFFEFEFEF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Custom prompt field if custom persona selected
                if (tempSelectedId == "custom") {
                    Text(
                        "Custom System Prompt",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDarkMode) Color.LightGray else Color.DarkGray
                    )
                    OutlinedTextField(
                        value = tempCustomPrompt,
                        onValueChange = { tempCustomPrompt = it },
                        placeholder = { Text("Enter custom system instructions...", color = Color.Gray) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = if (isDarkMode) Color.White else Color.Black,
                            unfocusedTextColor = if (isDarkMode) Color.White else Color.Black,
                            focusedBorderColor = Color(0xFFEFEFEF)
                        )
                    )
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { viewModel.setPersonalityModalOpen(false) }) {
                        Text("Cancel", color = Color.Gray, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            viewModel.savePersonality(tempSelectedId, tempCustomPrompt)
                            viewModel.setPersonalityModalOpen(false)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEFEFEF),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Apply Personality", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


data class ConnectorActionChip(
    val label: String,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
fun N8nConnectorCard(
    viewModel: JarvisViewModel,
    isDarkMode: Boolean,
    context: android.content.Context
) {
    val n8nWebhookUrl by viewModel.n8nWebhookUrl.collectAsStateWithLifecycle()
    val n8nApiKey by viewModel.n8nApiKey.collectAsStateWithLifecycle()
    val n8nConnected by viewModel.n8nConnected.collectAsStateWithLifecycle()
    val n8nLastLog by viewModel.n8nLastLog.collectAsStateWithLifecycle()

    var tempUrl by remember(n8nWebhookUrl) { mutableStateOf(n8nWebhookUrl) }
    var tempKey by remember(n8nApiKey) { mutableStateOf(n8nApiKey) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (n8nConnected) Color(0xFFFF6D5A).copy(alpha = 0.4f)
                else (if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)),
                RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF8FAFC)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF6D5A).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "n8n",
                            tint = Color(0xFFFF6D5A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "n8n Automation Engine",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFFF6D5A).copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "WORKFLOW LINK",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF6D5A)
                                )
                            }
                        }
                        Text(
                            "Connect webhooks & automate workflows via n8n pipelines",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
                Switch(
                    checked = n8nConnected,
                    onCheckedChange = { viewModel.toggleConnector("n8n") },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFFF6D5A)
                    )
                )
            }

            if (n8nConnected) {
                Spacer(modifier = Modifier.height(10.dp))
                Text("n8n Webhook Endpoint URL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color.LightGray else Color.DarkGray)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = tempUrl,
                    onValueChange = {
                        tempUrl = it
                        viewModel.setN8nWebhookUrl(it)
                    },
                    placeholder = { Text("https://primary.n8n.cloud/webhook/jarvis-action", fontSize = 12.sp, color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = if (isDarkMode) Color.White else Color.Black,
                        unfocusedTextColor = if (isDarkMode) Color.White else Color.Black,
                        focusedBorderColor = Color(0xFFFF6D5A)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("API Authorization Token / Header (Optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isDarkMode) Color.LightGray else Color.DarkGray)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = tempKey,
                    onValueChange = {
                        tempKey = it
                        viewModel.setN8nApiKey(it)
                    },
                    placeholder = { Text("Bearer token or X-N8N-API-KEY", fontSize = 12.sp, color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = if (isDarkMode) Color.White else Color.Black,
                        unfocusedTextColor = if (isDarkMode) Color.White else Color.Black,
                        focusedBorderColor = Color(0xFFFF6D5A)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Action Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConnectorActionChip("Test Webhook", Color(0xFFFF6D5A)) {
                        viewModel.triggerN8nWorkflow("test_ping", "Ping test from AUREX app") { success, msg ->
                            Toast.makeText(context, if (success) "n8n Webhook Success!" else "n8n Error: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                    ConnectorActionChip("Sync Workspace", Color(0xFF3B82F6)) {
                        viewModel.triggerN8nWorkflow("google_sync", "Sync all workspace documents and calendar") { success, msg ->
                            Toast.makeText(context, if (success) "Workspace sync triggered!" else "n8n Error: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                    ConnectorActionChip("Send Email via n8n", Color(0xFFEA4335)) {
                        viewModel.triggerN8nWorkflow("send_email", "Dispatch email digest via n8n") { success, msg ->
                            Toast.makeText(context, if (success) "Email workflow executed!" else "n8n Error: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                    ConnectorActionChip("AI Pipeline", Color(0xFFEFEFEF)) {
                        viewModel.triggerN8nWorkflow("ai_agent_delegate", "Delegate complex reasoning task to n8n AI node") { success, msg ->
                            Toast.makeText(context, if (success) "n8n AI agent executed!" else "n8n Error: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                if (n8nLastLog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDarkMode) Color(0xFF0F172A) else Color(0xFFE2E8F0))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = n8nLastLog,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (n8nLastLog.contains("Failed") || n8nLastLog.contains("Error")) Color(0xFFEF4444) else Color(0xFFEFEFEF)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedConnectorCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    iconTint: Color,
    isChecked: Boolean,
    isDarkMode: Boolean,
    onToggle: () -> Unit,
    howItWorks: String,
    actionChips: List<ConnectorActionChip>
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(
                1.dp,
                if (isChecked) iconTint.copy(alpha = 0.35f) else Color.Gray.copy(alpha = 0.15f),
                RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFF8FAFC)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isChecked) "ACTIVE" else "OFF",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isChecked) Color(0xFFEFEFEF) else Color.Gray,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isChecked) Color(0xFFEFEFEF).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = Color.Gray,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = isChecked,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = iconTint
                    ),
                    modifier = Modifier.scale(0.85f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expandable How-It-Works & Deep OS Action Shortcuts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isExpanded) "Hide Details & OS Controls" else "View How It Works & Deep OS Actions",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = iconTint
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = howItWorks,
                        fontSize = 11.sp,
                        color = if (isDarkMode) Color.LightGray else Color.DarkGray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDarkMode) Color.Black.copy(alpha = 0.25f) else Color.White)
                            .padding(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "DIRECT OS ACTION SHORTCUTS:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = iconTint
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        actionChips.forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(chip.color.copy(alpha = 0.15f))
                                    .border(1.dp, chip.color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .clickable { chip.onClick() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = chip.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = chip.color
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun executeSystemControlStatic(context: Context, action: String, viewModel: JarvisViewModel) {
    val cleanAction = action.lowercase().trim()
    when (cleanAction) {
        "flashlight_toggle" -> {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, true)
                    Toast.makeText(context, "Flashlight toggled ON", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Flashlight action triggered", Toast.LENGTH_SHORT).show()
            }
        }
        "battery_check" -> {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val rawLevel = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val msg = if (rawLevel in 0..100) {
                    val isCharging = bm?.isCharging ?: false
                    "Battery Health: Good | Level: $rawLevel% | Status: ${if (isCharging) "Charging" else "Discharging"}"
                } else {
                    "Could not read battery level from system service."
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) { e.printStackTrace() }
        }
        "volume_max" -> {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                Toast.makeText(context, "Volume set to MAX", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}

// ----------------------------------------------------------------------------
// NVIDIA NIM LIVE CAMERA VIDEO STREAM & VISION PERCEPTION OVERLAY / MODAL
// ----------------------------------------------------------------------------

@Composable
fun VideoMessagePlayer(
    videoUriStr: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    try {
                        val uri = if (videoUriStr.startsWith("http") || videoUriStr.startsWith("content://")) {
                            android.net.Uri.parse(videoUriStr)
                        } else {
                            android.net.Uri.fromFile(java.io.File(videoUriStr))
                        }
                        setVideoURI(uri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                        setOnErrorListener { _, _, _ -> true }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    onFrameCaptured: ((Bitmap) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            try {
                launcher.launch(android.Manifest.permission.CAMERA)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    var camera: android.hardware.Camera? = null
                    var lastCapture = 0L

                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        if (!hasCameraPermission) return
                        try {
                            val cam = android.hardware.Camera.open()
                            cam.setDisplayOrientation(90)
                            try {
                                val params = cam.parameters
                                var best: android.hardware.Camera.Size? = null
                                var bestScore = Int.MAX_VALUE
                                for (s in params.supportedPreviewSizes) {
                                    val score = abs(s.width - 1280) + abs(s.height - 720)
                                    if (score < bestScore) {
                                        bestScore = score
                                        best = s
                                    }
                                }
                                if (best != null) params.setPreviewSize(best.width, best.height)
                                try {
                                    val ranges = params.supportedPreviewFpsRange
                                    val target = ranges.firstOrNull { it[0] >= 24000 && it[1] <= 30000 } ?: ranges.lastOrNull()
                                    if (target != null) params.setPreviewFpsRange(target[0], target[1])
                                } catch (e: Exception) { e.printStackTrace() }
                                try {
                                    if (params.supportedFocusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                                        params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                                    }
                                } catch (e: Exception) { e.printStackTrace() }
                                try {
                                    if (params.supportedAntibanding.contains(android.hardware.Camera.Parameters.ANTIBANDING_AUTO)) {
                                        params.antibanding = android.hardware.Camera.Parameters.ANTIBANDING_AUTO
                                    }
                                } catch (e: Exception) { e.printStackTrace() }
                                cam.parameters = params
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            cam.setPreviewTexture(surface)
                            cam.startPreview()
                            camera = cam
                        } catch (e: Exception) {
                            e.printStackTrace()
                            camera = null
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                        try {
                            camera?.stopPreview()
                            camera?.release()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        camera = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                        if (camera == null) return
                        val now = System.currentTimeMillis()
                        if (now - lastCapture < 400) return
                        lastCapture = now
                        onFrameCaptured?.let { callback ->
                            try {
                                bitmap?.let { bmp -> callback(bmp) }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    )
}

@Composable
fun LiveVisionModal(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val isAnalyzing by viewModel.isLiveVisionAnalyzing.collectAsStateWithLifecycle(false)
    val autoScan by viewModel.liveVisionAutoScan.collectAsStateWithLifecycle(false)
    val visionResult by viewModel.liveVisionResult.collectAsStateWithLifecycle("")
    val capturedBitmap by viewModel.liveVisionCapturedBitmap.collectAsStateWithLifecycle(null)
    val visionSource by viewModel.visionSource.collectAsStateWithLifecycle("camera")

    var latestCameraFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var queryText by remember { mutableStateOf("") }
    var isCapturingFrame by remember { mutableStateOf(false) }
    var panelFraction by remember { androidx.compose.runtime.mutableFloatStateOf(0.46f) }
    val coroutineScope = rememberCoroutineScope()

    fun snapAndAnalyzeFrame(promptOverride: String? = null) {
        coroutineScope.launch {
            if (isCapturingFrame || isAnalyzing) return@launch
            val promptToSend = promptOverride ?: "Analyze this and summarize key visual details."
            if (visionSource == "camera") {
                val bmp = latestCameraFrameBitmap
                if (bmp == null) return@launch
                val byteArrayOutputStream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, byteArrayOutputStream)
                val frameBase64 = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
                viewModel.setLiveVisionCapturedBitmap(bmp)
                viewModel.analyzeLiveFrame(frameBase64, promptToSend)
            } else {
                isCapturingFrame = true
                delay(40)
                val (bmp, frameBase64) = captureActivityScreen(context)
                isCapturingFrame = false
                if (bmp != null) {
                    viewModel.setLiveVisionCapturedBitmap(bmp)
                }
                viewModel.analyzeLiveFrame(frameBase64, promptToSend)
            }
        }
    }

    // Auto-scan ticker
    LaunchedEffect(autoScan) {
        if (autoScan) {
            while (autoScan) {
                snapAndAnalyzeFrame()
                delay(5000)
            }
        }
    }

    // Capture initial snapshot when vision opens
    LaunchedEffect(Unit) {
        if (capturedBitmap == null) {
            kotlinx.coroutines.delay(300)
            snapAndAnalyzeFrame("Analyze what is visible and summarize key details with high precision.")
        }
    }

    Dialog(
        onDismissRequest = { viewModel.setLiveVisionOpen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            if (visionSource == "camera") {
                CameraPreviewView(
                    modifier = Modifier.fillMaxSize(),
                    onFrameCaptured = { bmp -> latestCameraFrameBitmap = bmp }
                )
            } else {
                capturedBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Screen share",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.30f)),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (visionSource == "screen") {
                            if (isAnalyzing) "Reading screen..." else "Screenshare"
                        } else {
                            if (isAnalyzing) "Analyzing..." else if (autoScan) "Watching" else "Paused"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAnalyzing) Color(0xFFF59E0B) else Color(0xFFE4E4E4)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.setLiveVisionOpen(false) },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val beam = rememberInfiniteTransition(label = "visionBeam")
            val beamX by beam.animateFloat(
                initialValue = -0.4f,
                targetValue = 1.4f,
                animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
                label = "visionBeamX"
            )

            if (visionSource == "camera") {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(panelFraction)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(Color(0xFF050505).copy(alpha = 0.97f))
                        .drawBehind {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        (beamX * 0.5f).coerceIn(0f, 1f) to Color(0xFFE4E4E4).copy(alpha = 0.4f),
                                        beamX.coerceIn(0f, 1f) to Color(0xFFE4E4E4),
                                        ((beamX + 0.5f).coerceAtMost(1f)) to Color(0xFFE4E4E4).copy(alpha = 0.3f),
                                        1.0f to Color.Transparent
                                    )
                                ),
                                topLeft = Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                            )
                        }
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(44.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3A3A3A))
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    panelFraction = (panelFraction - dragAmount / 800f).coerceIn(0.22f, 0.72f)
                                    change.consume()
                                }
                            }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val playInteraction = remember { MutableInteractionSource() }
                    val playPressed by playInteraction.collectIsPressedAsState()
                    val playScale by animateFloatAsState(
                        targetValue = if (playPressed) 0.90f else 1f,
                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 480f),
                        label = "visionPlayScale"
                    )
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .align(Alignment.CenterHorizontally)
                            .graphicsLayer {
                                scaleX = playScale
                                scaleY = playScale
                            }
                            .shadow(
                                12.dp,
                                CircleShape,
                                ambientColor = Color.Black.copy(alpha = 0.4f),
                                spotColor = Color(0xFFEFEFEF).copy(alpha = 0.5f)
                            )
                            .clip(CircleShape)
                            .background(if (autoScan) Color(0xFF161616) else Color(0xFFEFEFEF))
                            .border(
                                BorderStroke(1.dp, Color(0xFFE4E4E4).copy(alpha = if (autoScan) 0.40f else 0f)),
                                CircleShape
                            )
                            .clickable(interactionSource = playInteraction, indication = null) {
                                viewModel.setLiveVisionAutoScan(!autoScan)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (autoScan) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (autoScan) "Pause" else "Play",
                            tint = if (autoScan) Color(0xFFE4E4E4) else Color(0xFF0A0A0A),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0C0C0C).copy(alpha = 0.92f))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = when {
                                isAnalyzing && visionResult.isBlank() -> "Reading the scene..."
                                visionResult.isBlank() -> "Press play — I'll watch the camera and explain what I see."
                                else -> visionResult
                            },
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = Color(0xFFE2E2E2)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF060606).copy(alpha = 0.93f))
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(18.dp))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = when {
                                isAnalyzing && visionResult.isBlank() -> "Reading your screen..."
                                visionResult.isBlank() -> "I can see your screen. Ask me anything about it."
                                else -> visionResult
                            },
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = Color(0xFFE2E2E2)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ThinkingOrb(
                        state = if (isAnalyzing) OrbState.THINKING else OrbState.IDLE,
                        modifier = Modifier.size(48.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextField(
                            value = queryText,
                            onValueChange = { queryText = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            placeholder = {
                                Text("Ask about this screen...", fontSize = 14.sp, color = Color.Gray)
                            },
                            shape = RoundedCornerShape(26.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF151515),
                                unfocusedContainerColor = Color(0xFF101010),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFE4E4E4)
                            )
                        )
                        val canAsk = queryText.isNotBlank()
                        val askColor by animateColorAsState(
                            targetValue = if (canAsk) Color(0xFFEFEFEF) else Color(0xFF1E1E1E),
                            animationSpec = tween(200),
                            label = "visionAskColor"
                        )
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(askColor)
                                .clickable {
                                    if (canAsk) {
                                        snapAndAnalyzeFrame(queryText.trim())
                                        queryText = ""
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Ask",
                                tint = if (canAsk) Color(0xFF0A0A0A) else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Real-time Activity screen capture bitmap converted to high quality JPEG Base64 for vision model
private fun captureActivityScreen(context: Context): Pair<Bitmap?, String> {
    return try {
        val activity = context as? Activity
            ?: (context as? ContextWrapper)?.baseContext as? Activity
        val window = activity?.window
        if (window != null) {
            val view = window.decorView.rootView
            val w = view.width
            val h = view.height
            if (w > 0 && h > 0) {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                view.draw(canvas)

                // High clarity scaling: max dimension 1280px for optimal network speed & visual crispness
                val maxDim = 1280
                val scale = if (w > maxDim || h > maxDim) {
                    maxDim.toFloat() / Math.max(w, h).toFloat()
                } else 1.0f

                val finalBitmap = if (scale < 1.0f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (w * scale).toInt(),
                        (h * scale).toInt(),
                        true
                    )
                } else {
                    bitmap
                }

                val baos = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Pair(finalBitmap, base64)
            } else {
                Pair(null, generateSampleFrameBase64())
            }
        } else {
            Pair(null, generateSampleFrameBase64())
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Pair(null, generateSampleFrameBase64())
    }
}

private fun captureActivityScreenBase64(context: Context): String {
    return captureActivityScreen(context).second
}

// Generate sample frame fallback
private fun generateSampleFrameBase64(): String {
    return try {
        val width = 640
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.DKGRAY
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            textSize = 28f
            isAntiAlias = true
        }
        canvas.drawText("AUREX LIVE VISION FRAME", 40f, 200f, textPaint)
        canvas.drawText("STATUS: CAMERA/SCREEN VIEW ACTIVE", 40f, 260f, textPaint)

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        Base64.encodeToString(byteArray, Base64.NO_WRAP)
    } catch (e: Exception) {
        ""
    }
}

@Composable
fun GalleryModal(viewModel: JarvisViewModel) {
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val mediaMessages by viewModel.allMediaMessages.collectAsStateWithLifecycle()
    var selectedImageMessage by remember { mutableStateOf<com.example.data.ChatMessage?>(null) }

    Dialog(
        onDismissRequest = { viewModel.setGalleryModalOpen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .border(
                    1.dp,
                    AurexColors.BorderSheen,
                    RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkMode) Color(0xFF0A0A0A) else Color(0xFFF8FAFC)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Gallery",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Image Gallery",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                            )
                            Text(
                                text = "${mediaMessages.size} generated images",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.setGalleryModalOpen(false) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = if (isDarkMode) Color.White else Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (mediaMessages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = Color.Gray.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No generated images yet",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Type 'generate image of...' in chat to create pictures",
                                fontSize = 12.sp,
                                color = Color.Gray.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(mediaMessages, key = { it.id }) { msg ->
                            val context = LocalContext.current
                            val imageModel = remember(msg.fileUri) {
                                resolveImageModel(context, msg.fileUri)
                            }
                            val imageBitmap = remember(msg.fileUri) {
                                loadBitmapFromUriOrBase64(context, msg.fileUri)
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { selectedImageMessage = msg },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0)
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (imageBitmap != null) {
                                        Image(
                                            bitmap = imageBitmap,
                                            contentDescription = msg.text,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else if (imageModel != null) {
                                        coil.compose.AsyncImage(
                                            model = imageModel,
                                            contentDescription = msg.text,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PhotoLibrary,
                                                contentDescription = null,
                                                tint = Color.Gray.copy(alpha = 0.5f),
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                                )
                                            )
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = msg.text.removePrefix("Here is your generated image for prompt: ").replace("\"", ""),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedImageMessage != null) {
        val msg = selectedImageMessage!!
        Dialog(onDismissRequest = { selectedImageMessage = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val context = LocalContext.current
                    val fullModel = remember(msg.fileUri) {
                        resolveImageModel(context, msg.fileUri)
                    }
                    val fullBitmap = remember(msg.fileUri) {
                        loadBitmapFromUriOrBase64(context, msg.fileUri)
                    }

                    if (fullBitmap != null) {
                        Image(
                            bitmap = fullBitmap,
                            contentDescription = msg.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } else if (fullModel != null) {
                        coil.compose.AsyncImage(
                            model = fullModel,
                            contentDescription = msg.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = msg.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { selectedImageMessage = null }) {
                            Text("Close", color = Color(0xFFEC4899))
                        }
                    }
                }
            }
        }
    }
}

fun resolveImageModel(context: android.content.Context, rawUri: String?): Any? {
    if (rawUri.isNullOrBlank()) return null
    val raw = rawUri.trim()

    if (raw.startsWith("data:image") || (!raw.startsWith("/") && !raw.startsWith("file://") && !raw.startsWith("http") && !raw.startsWith("content://") && raw.length > 150)) {
        val cleanBase64 = if (raw.contains("base64,")) raw.substringAfter("base64,") else raw
        return try {
            val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: Exception) { null }
    }

    if (raw.startsWith("http://") || raw.startsWith("https://")) {
        return raw
    }

    if (raw.startsWith("content://")) {
        return android.net.Uri.parse(raw)
    }

    val cleanPath = if (raw.startsWith("file://")) raw.removePrefix("file://") else raw
    val candidateFiles = listOf(
        java.io.File(cleanPath),
        java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "JARVIS_Media/$cleanPath"),
        java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), cleanPath),
        java.io.File(context.filesDir, "jarvis_images/$cleanPath"),
        java.io.File(context.filesDir, cleanPath),
        java.io.File("/storage/emulated/0/Pictures/JARVIS_Media", cleanPath),
        java.io.File("/sdcard/Pictures/JARVIS_Media", cleanPath),
        java.io.File("/sdcard/Pictures", cleanPath),
        java.io.File("/sdcard/Download", cleanPath)
    )

    val foundFile = candidateFiles.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
    return foundFile ?: cleanPath
}

fun loadBitmapFromUriOrBase64(context: android.content.Context, rawUri: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (rawUri.isNullOrBlank()) return null
    return try {
        val raw = rawUri.trim()
        if (raw.startsWith("data:image") || (!raw.startsWith("/") && !raw.startsWith("file://") && !raw.startsWith("http") && !raw.startsWith("content://") && raw.length > 150)) {
            val cleanBase64 = if (raw.contains("base64,")) raw.substringAfter("base64,") else raw
            val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } else {
            val cleanPath = when {
                raw.startsWith("file://") -> raw.removePrefix("file://")
                else -> raw
            }
            if (cleanPath.startsWith("content://")) {
                val uri = android.net.Uri.parse(cleanPath)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } else {
                val candidateFiles = listOf(
                    java.io.File(cleanPath),
                    java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "JARVIS_Media/$cleanPath"),
                    java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), cleanPath),
                    java.io.File(context.filesDir, "jarvis_images/$cleanPath"),
                    java.io.File(context.filesDir, cleanPath),
                    java.io.File("/storage/emulated/0/Pictures/JARVIS_Media", cleanPath),
                    java.io.File("/sdcard/Pictures/JARVIS_Media", cleanPath),
                    java.io.File("/sdcard/Pictures", cleanPath),
                    java.io.File("/sdcard/Download", cleanPath)
                )
                val targetFile = candidateFiles.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
                if (targetFile != null) {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                    android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath, options)?.asImageBitmap()
                } else null
            }
        }
    } catch (e: Exception) {
        null
    }
}
