package com.example.api

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TtsManager - Uses Android's native TextToSpeech engine.
 * Reliable, no native libraries, works everywhere.
 */
class TtsManager(private val context: Context) {

    private val TAG = "TtsManager"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var userSpeechRate: Float = 1.15f
    private var currentOnStart: (() -> Unit)? = null
    private var currentOnDone: (() -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Language not supported")
                }
                Log.i(TAG, "System TTS ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun setSpeechRateMultiplier(rate: Float) {
        userSpeechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(rate)
    }

    fun getSpeechRateMultiplier(): Float = userSpeechRate

    fun speak(
        text: String,
        apiKeyOverride: String? = null,
        voiceType: String = "jarvis",
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        try {
            stop()
            val cleanText = formatTextForSpeech(text)
            if (cleanText.isBlank()) {
                onDone?.invoke()
                return
            }

            val engine = tts
            if (engine == null || !ttsReady) {
                Log.w(TAG, "TTS not ready")
                onDone?.invoke()
                return
            }

            currentOnStart = onStart
            currentOnDone = onDone

            val emotion = detectEmotion(cleanText)
            var rate = when (voiceType.lowercase()) {
                "friday" -> userSpeechRate * 1.04f
                "coder" -> userSpeechRate * 0.98f
                else -> userSpeechRate
            }
            rate = when (emotion) {
                "EXCITED" -> rate + 0.06f
                "SURPRISED" -> rate + 0.04f
                "EMPATHETIC" -> rate - 0.06f
                "THOUGHTFUL" -> rate - 0.05f
                "CURIOUS" -> rate + 0.02f
                else -> rate
            }

            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            engine.setPitch(0.95f)

            val utteranceId = "utterance_${System.currentTimeMillis()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == utteranceId) currentOnStart?.invoke()
                }
                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        val d = currentOnDone
                        currentOnDone = null
                        d?.invoke()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        Log.e(TAG, "TTS error")
                        val d = currentOnDone
                        currentOnDone = null
                        d?.invoke()
                    }
                }
            })

            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in TtsManager speak", t)
            onDone?.invoke()
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping TTS", t)
        }
    }

    fun isSpeaking(): Boolean {
        return try {
            tts?.isSpeaking == true
        } catch (e: Exception) {
            false
        }
    }

    fun rescanModels() {
        // No models to scan with native TTS
    }

    fun importPiperModelFromUri(uri: android.net.Uri): String? {
        return "Native TTS is active - no model import needed"
    }

    fun getPiperModelSummary(): String = "Native Android TTS (built-in voice)"

    fun getDiscoveredModels(): List<PiperModelInfo> = emptyList()

    fun destroy() {
        try {
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.e(TAG, "Error destroying TTS", t)
        }
        tts = null
    }

    fun getActiveEngineLabel(): String = "System TTS (Native)"

    fun getInstalledTtsEngines(): List<InstalledTtsEngine> {
        return try {
            tts?.engines?.map { engine ->
                InstalledTtsEngine(
                    label = engine.label,
                    packageName = engine.name,
                    isSherpa = false
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSelectedEnginePackage(): String? = "system"

    fun getDetectedSherpaPackage(): String? = null

    fun setTtsEnginePackage(packageName: String?) {
        Log.i(TAG, "Engine selection ignored - using system TTS")
    }

    private fun formatTextForSpeech(text: String): String {
        var res = text
        res = res.replace(Regex("(?i)\\*(sighs?|sighing)\\*"), "Sigh... ")
        res = res.replace(Regex("(?i)\\*(laughs?|laughing|chuckles?|giggles?)\\*"), "Haha! ")
        res = res.replace(Regex("(?i)\\*(gasps?|surprised?)\\*"), "Oh! ")
        res = res.replace(Regex("(?i)\\*(thinks?|pondering?)\\*"), "Hmm... ")
        res = res.replace(Regex("(?i)\\*(smiles?|smiling)\\*"), "Ah, ")

        res = res.replace(Regex("```[\\s\\S]*?```"), " Code block provided in chat. ")
        res = res.replace(Regex("`([^`]+)`"), "$1")
        res = res.replace(Regex("[#_~>]"), " ")
        res = res.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
        res = res.replace(Regex("(?m)^\\s*[-*•]\\s+"), " ")
        res = res.replace(Regex("&"), " and ")
            .replace(Regex("%"), " percent ")
            .replace(Regex("\\+"), " plus ")
            .replace(Regex("="), " equals ")
            .replace(Regex("\\bAI\\b"), "A I")
            .replace(Regex("\\bAPI\\b"), "A P I")
            .replace(Regex("\\bUI\\b"), "U I")
            .replace(Regex("\\bURL\\b"), "U R L")
            .replace(Regex("\\bTTS\\b"), "T T S")
        return res.replace(Regex("\\s+"), " ").trim()
    }

    private fun detectEmotion(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("!") || lower.contains("haha") || lower.contains("yay") || lower.contains("whoa") || lower.contains("awesome") || lower.contains("great news") -> "EXCITED"
            lower.contains("oh!") || lower.contains("gosh") || lower.contains("wow") || lower.contains("unbelievable") -> "SURPRISED"
            lower.contains("sorry") || lower.contains("unfortunately") || lower.contains("sad") || lower.contains("aww") || lower.contains("sigh") -> "EMPATHETIC"
            lower.contains("hmm") || lower.contains("um") || lower.contains("let's see") || lower.contains("...") -> "THOUGHTFUL"
            lower.contains("?") -> "CURIOUS"
            else -> "NEUTRAL"
        }
    }
}