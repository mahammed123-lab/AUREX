package com.example.api

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jarvis.piper.KittenTts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * KittenTtsHelper - JARVIS Voice using Kitten TTS (~25-45MB, male British voice like JARVIS)
 *
 * Advantages over Piper TTS:
 * - ~40% smaller model size (no espeak-ng-data needed)
 * - Lower latency streaming generation
 * - Excellent male voice quality (alan-medium sounds like JARVIS)
 * - Single model file + voices.bin + tokens.txt only
 * - No phonemizer dependency
 *
 * Setup:
 *   1. Add Kitten TTS model files to app/src/main/assets/tts/kitten/:
 *        - model.onnx (the Kitten TTS model, ~25-45MB)
 *        - voices.bin (speaker embeddings)
 *        - tokens.txt (phoneme vocabulary)
 *   2. Uses sherpa-onnx's OfflineTtsKittenModelConfig via KittenTts wrapper
 */
class KittenTtsHelper(private val context: Context) {

    private val TAG = "KittenTtsHelper"
    private val ttsRef = AtomicReference<KittenTts?>(null)
    private var isInitialized = false

    /**
     * Invoked (on a playback worker thread) every time an utterance finishes,
     * including when stopped early. Voice mode relies on this to re-open the
     * microphone after the assistant finishes speaking — without it the app
     * goes silent after the first reply.
     */
    var onSpeakingFinished: (() -> Unit)? = null

    /**
     * Invoked (on the playback worker thread) as each synthesized audio chunk
     * goes out — 0f..1f peak amplitude. Drives the voice orb's Speaking state.
     */
    var onAudioChunk: ((Float) -> Unit)? = null

    /** Real playback clock of the utterance currently playing (seconds), or null while idle. */
    fun playbackSeconds(): Double? = ttsRef.get()?.playbackSeconds()

    /** Real playback length of the utterance that finished last, in seconds. */
    fun lastUtteranceSeconds(): Double = ttsRef.get()?.lastUtteranceSeconds() ?: 0.0

    /** Call once before using speak(). Safe to call from a background thread. */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        try {
            val tts = KittenTts.create(
                context = context,
                config = KittenTts.Config(
                    assetModelDir = "tts/kitten",
                    onnxFileName = "model.onnx",
                    voicesFileName = "voices.bin",
                    tokensFileName = "tokens.txt",
                    numThreads = 4,
                    speed = 1.0f,
                    speakerId = 0, // alan-medium (male British, JARVIS-like)
                ),
                listener = object : KittenTts.Listener {
                    override fun onSpeakingStarted() {
                        Log.d(TAG, "Kitten TTS: Speaking started")
                    }

                    override fun onAudioChunk(amplitude: Float) {
                        onAudioChunk?.invoke(amplitude)
                    }

                    override fun onSpeakingFinished() {
                        Log.d(TAG, "Kitten TTS: Speaking finished")
                        onSpeakingFinished?.invoke()
                    }
                }
            )

            if (tts != null) {
                ttsRef.set(tts)
                isInitialized = true
                Log.i(TAG, "Kitten TTS initialized successfully with JARVIS-like voice")
                return@withContext true
            } else {
                Log.w(TAG, "Kitten TTS model assets missing in assets/tts/kitten/")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Kitten TTS", e)
            return@withContext false
        }
    }

    /**
     * Speaks the given text using Kitten TTS (JARVIS-like male voice).
     * Non-blocking, streams audio as it's generated for low latency.
     */
    fun speak(text: String, speed: Float = 1.0f): Boolean {
        val tts = ttsRef.get()
        if (tts == null || !isInitialized) {
            Log.w(TAG, "Kitten TTS not initialized, call init() first")
            return false
        }

        val clampedSpeed = speed.coerceIn(0.7f, 1.5f)
        return tts.speak(text, speed = clampedSpeed)
    }

    /**
     * Stops current playback immediately.
     */
    fun stop() {
        ttsRef.get()?.stop()
    }

    /**
     * Releases all resources.
     */
    fun release() {
        ttsRef.getAndSet(null)?.release()
        isInitialized = false
    }

    val isSpeaking: Boolean get() = ttsRef.get()?.isSpeaking == true
    val isReady: Boolean get() = isInitialized

    fun setSpeechRateMultiplier(rate: Float) {
        // KittenTts generates at fixed speed, but we can pass speed parameter on each speak call
        // This is handled by the caller passing speed parameter
    }

    fun rescanModels() {
        // No models to rescan for Kitten TTS
    }

    fun destroy() {
        release()
    }

    fun importPiperModelFromUri(uri: android.net.Uri): String? {
        return "Kitten TTS is active - no model import needed"
    }

    fun getPiperModelSummary(): String = "Kitten TTS (JARVIS-like voice, ~25-45MB)"

    fun getDiscoveredModels(): List<com.example.api.PiperModelInfo> = emptyList()
}