package com.jarvis.piper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.Result
import kotlin.runCatching
import android.os.Process

/**
 * Kitten TTS - Ultra-lightweight (~25-45MB) high-quality neural TTS with male voices.
 *
 * Models available (all ~25-45MB each):
 * - en_GB-alan-medium (male, British, JARVIS-like) - RECOMMENDED
 * - en_US-john (male, US)
 * - en_GB-graham (male, British)
 * - en_US-david (male, US)
 *
 * Setup:
 *   1. Add Kitten TTS model files to assets/tts/kitten/:
 *        - model.onnx (the Kitten TTS model)
 *        - voices.bin (speaker embeddings)
 *        - tokens.txt (phoneme vocabulary)
 *   2. Uses sherpa-onnx's OfflineTtsKittenModelConfig (no espeak-ng-data needed!)
 *
 * Advantages over Piper:
 * - ~40% smaller model size
 * - No espeak-ng-data dependency (~20MB saved)
 * - Lower latency (streaming generation)
 * - Excellent male voice quality (alan-medium sounds like JARVIS)
 * - Single model file + voices.bin + tokens.txt only
 */
class KittenTts private constructor(
    private val context: Context,
    private val tts: OfflineTts,
    private val listener: Listener?,
    private val config: Config,
) {

    interface Listener {
        /** Called once, right before audio starts generating for an utterance. */
        fun onSpeakingStarted() {}

        /**
         * Called repeatedly as audio chunks are synthesized, roughly in
         * real time. [amplitude] is 0f..1f, handy for driving a voice-orb /
         * waveform visualizer's SPEAKING state.
         */
        fun onAudioChunk(amplitude: Float) {}

        /** Called when playback finishes naturally or is stopped early. */
        fun onSpeakingFinished() {}
    }

    data class Config(
        /**
         * Voice model directory name. Resolved as an assets/ subfolder
         * unless [modelDirIsAbsolutePath] is true, in which case it's an
         * absolute filesystem path.
         */
        val assetModelDir: String = "tts/kitten",
        val modelDirIsAbsolutePath: Boolean = false,
        /** Filename of the .onnx model inside [assetModelDir]. Default: model.onnx */
        val onnxFileName: String = "model.onnx",
        /** Filename of the voices.bin file inside [assetModelDir]. */
        val voicesFileName: String = "voices.bin",
        /** Filename of the tokens.txt file inside [assetModelDir]. */
        val tokensFileName: String = "tokens.txt",
        val numThreads: Int = 2,
        /** Speech speed (0.5 - 2.0). Default 1.0 */
        val speed: Float = 1.0f,
        /** Speaker ID for multi-speaker models. Default 0 (alan-medium). */
        val speakerId: Int = 0,
        /** Kitten TTS always uses "kitten" engine type. */
        val engineType: String = "kitten",
    )

    companion object {
        private const val TAG = "KittenTts"

        /**
         * Creates a KittenTts instance.
         *
         * @param context Android context
         * @param config Configuration for the TTS engine
         * @param listener Optional listener for speaking events
         * @return KittenTts instance or null if initialization fails
         */
        fun create(
            context: Context,
            config: Config = Config(),
            listener: Listener? = null
        ): KittenTts? {
            return try {
                val modelDir = resolveModelDir(context, config)
                if (modelDir == null) {
                    Log.e(TAG, "Kitten TTS model directory not found: ${config.assetModelDir}")
                    return null
                }

                val onnxPath = File(modelDir, config.onnxFileName).absolutePath
                val voicesPath = File(modelDir, config.voicesFileName).absolutePath
                val tokensPath = File(modelDir, config.tokensFileName).absolutePath

                if (!File(onnxPath).exists()) {
                    Log.e(TAG, "Kitten TTS model not found: $onnxPath")
                    return null
                }
                if (!File(voicesPath).exists()) {
                    Log.e(TAG, "Kitten TTS voices.bin not found: $voicesPath")
                    return null
                }
                if (!File(tokensPath).exists()) {
                    Log.e(TAG, "Kitten TTS tokens.txt not found: $tokensPath")
                    return null
                }

                // Kitten engine phonemizes text via espeak-ng: it needs an espeak-ng-data
                // directory as dataDir. Extract the shared copy bundled under jarvis-voice
                // assets (which may be double-nested: espeak-ng-data/espeak-ng-data/...).
                val espeakDir = ensureEspeakData(context)
                if (espeakDir == null) {
                    Log.e(TAG, "espeak-ng-data missing - Kitten TTS cannot phonemize text")
                    return null
                }

                val kittenConfig = OfflineTtsKittenModelConfig(
                    model = onnxPath,
                    voices = voicesPath,
                    tokens = tokensPath,
                    dataDir = espeakDir.absolutePath,
                    lengthScale = 1.0f / config.speed.coerceIn(0.5f, 2.0f),
                )

                val offlineConfig = OfflineTtsConfig(model = OfflineTtsModelConfig(kitten = kittenConfig, numThreads = config.numThreads.coerceAtLeast(1)))
                // Use assetManager = null when loading from absolute file paths (not from assets)
                val tts = OfflineTts(assetManager = null, config = offlineConfig)
                Log.i(TAG, "Kitten TTS initialized successfully: ${config.assetModelDir}")
                KittenTts(context, tts, listener, config)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Kitten TTS", e)
                null
            }
        }

        private fun resolveModelDir(context: Context, config: Config): File? {
            return if (config.modelDirIsAbsolutePath) {
                File(config.assetModelDir)
            } else {
                val targetDir = File(context.filesDir, "tts_models/kitten")
                if (!targetDir.exists()) targetDir.mkdirs()

                // Copy from assets if not already present
                val onnxFile = File(targetDir, config.onnxFileName)
                val voicesFile = File(targetDir, config.voicesFileName)
                val tokensFile = File(targetDir, config.tokensFileName)

                if (!onnxFile.exists() || !voicesFile.exists() || !tokensFile.exists()) {
                    copyAssetsToDir(context, config.assetModelDir, targetDir)
                }

                if (onnxFile.exists() && voicesFile.exists() && tokensFile.exists()) {
                    targetDir
                } else {
                    null
                }
            }
        }

        /**
         * Locates and (once) extracts the shared espeak-ng-data the kitten engine
         * needs for phonemization. Handles the double-nested asset layout
         * "espeak-ng-data/espeak-ng-data/..." and the flat one, plus the
         * jarvis-voice fallback location.
         */
        private fun ensureEspeakData(context: Context): File? {
            val outDir = File(context.filesDir, "kitten_espeak-ng-data")
            val marker = File(context.filesDir, "kitten_espeak.ready")
            if (marker.exists() && File(outDir, "lang").exists()) return outDir

            // Candidate asset roots, in order
            val assetRoots = listOf(
                "tts/kitten/espeak-ng-data",
                "tts/espeak-ng-data",
                "jarvis-voice/espeak-ng-data",
                "jarvis-voice/espeak-ng-data/espeak-ng-data",
            )

            // A valid root has a "lang/" child (with entries) - the phonemizer data root
            for (root in assetRoots) {
                val langEntries = try {
                    context.assets.list("$root/lang") ?: emptyArray()
                } catch (e: Exception) {
                    emptyArray()
                }
                if (langEntries.isNotEmpty()) {
                    try {
                        outDir.deleteRecursively()
                        copyAssetDir(context, root, outDir)
                        marker.writeText("ok")
                        Log.i(TAG, "espeak-ng-data extracted to ${outDir.absolutePath} (from $root)")
                        return outDir
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to extract espeak-ng-data from $root", e)
                    }
                }
            }
            Log.e(TAG, "No espeak-ng-data found in any known asset location")
            return null
        }

        private fun copyAssetDir(context: Context, assetDir: String, outDir: File) {
            val children = context.assets.list(assetDir) ?: return
            if (children.isEmpty()) {
                // Leaf: outDir is the destination file itself
                outDir.parentFile?.mkdirs()
                outDir.outputStream().use { out ->
                    context.assets.open(assetDir).use { it.copyTo(out) }
                }
                return
            }
            outDir.mkdirs()
            for (name in children) {
                copyAssetDir(context, "$assetDir/$name", File(outDir, name))
            }
        }

        private fun copyAssetsToDir(context: Context, assetDir: String, targetDir: File) {
            try {
                val files = context.assets.list(assetDir) ?: return
                for (name in files) {
                    val outFile = File(targetDir, name)
                    if (!outFile.exists()) {
                        context.assets.open("$assetDir/$name").use { input ->
                            java.io.FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
                Log.i(TAG, "Copied Kitten TTS assets to ${targetDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy Kitten TTS assets", e)
            }
        }
    }

    private val playbackExecutor = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null

    private val sampleRate: Int by lazy { tts.sampleRate() }

    private var lastUtterance = 0.0

    /** Seconds of audio the device has actually played for the current utterance, or null while idle. */
    fun playbackSeconds(): Double? {
        val track = audioTrack ?: return null
        return try {
            track.playbackHeadPosition.toDouble() / sampleRate
        } catch (e: Exception) {
            null
        }
    }

    /** Total seconds of the utterance that finished last (real playback length). */
    fun lastUtteranceSeconds(): Double = lastUtterance

    // Set the moment speak() is called (before the executor even starts the
    // task). Relying on audioTrack != null alone leaves a window of several
    // hundred ms where speak() has returned but the executor hasn't created
    // the AudioTrack yet — MainActivity's post-TTS mic restart (300 ms delay)
    // would then open the microphone while the voice is still speaking,
    // producing a feedback loop of SpeechRecognizer ERROR_CLIENT errors
    // ("crashing the mic").
    private val speakingNow = AtomicBoolean(false)

    val isSpeaking: Boolean get() = speakingNow.get() || audioTrack != null

    /**
     * Synthesizes [text] and plays it immediately through the device
     * speaker/earpiece, streaming audio as it's generated (low latency,
     * doesn't wait for the whole utterance to finish synthesizing first).
     * Safe to call from any thread; playback happens on a background thread.
     */
    fun speak(text: String, speed: Float = 1.0f, speakerId: Int = -1): Boolean {
        stopRequested.set(false)
        val effectiveSpeakerId = if (speakerId >= 0) speakerId else config.speakerId ?: 0
        val effectiveSpeed = speed.coerceIn(0.5f, 2.0f)

        return try {
            speakingNow.set(true)
            playbackExecutor.execute {
                var track: AudioTrack? = null
                runCatching {
                    // stop() may have been called while this task sat queued —
                    // bail out instead of playing stale speech over a fresh mic.
                    if (stopRequested.get()) {
                        speakingNow.set(false)
                        return@runCatching
                    }
                    // Synthesis + streaming writes must not be preempted by UI
                    // threads or the audio pops/glitches mid-utterance.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    track = AudioTrack(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                        // ~1 second of headroom: Kitten generates
                        // sentence-by-sentence and the ONNX model can stall
                        // briefly between sentences — a small buffer runs dry
                        // there and the voice audibly stutters.
                        (sampleRate * 2).coerceAtLeast(
                            AudioTrack.getMinBufferSize(
                                sampleRate,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                        ),
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    ).apply {
                        play()
                        audioTrack = this
                        listener?.onSpeakingStarted()
                    }

                    val genConfig = GenerationConfig(
                        sid = effectiveSpeakerId,
                        speed = effectiveSpeed
                    )

                    // IMPORTANT: pass an explicit object expression, NOT a lambda.
                    // Kotlin 2.1 compiles lambdas to invokedynamic, which D8
                    // desugars into a synthetic class exposing ONLY the erased
                    // invoke(Object)Object. The native sherpa-onnx JNI looks up
                    // "invoke([F)Ljava/lang/Integer;" via GetMethodID and the
                    // process aborts when that method is missing (NoSuchMethodError
                    // -> "JNI DETECTED ERROR"). A real class overriding invoke
                    // with a nullable Int? return keeps that exact boxed-Integer
                    // method on the class.
                    var writtenFrames = 0
                    tts.generateWithConfigAndCallback(
                        text,
                        genConfig,
                        object : Function1<FloatArray, Int?> {
                            override fun invoke(samples: FloatArray): Int? {
                                if (stopRequested.get()) {
                                    return 0 // tell sherpa-onnx to stop generating further audio
                                }
                                listener?.onAudioChunk(peakAmplitude(samples))
                                val shorts = ShortArray(samples.size)
                                for (i in samples.indices) {
                                    shorts[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                                val written = track?.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING) ?: 0
                                writtenFrames += written
                                return 1 // continue
                            }
                        }
                    )

                    // Let the last buffered audio finish playing instead of
                    // chopping the tail of the final word. stop()+release()
                    // back-to-back discards whatever is still queued in the
                    // native mixer (~300ms), making every reply end mid-word.
                    drainAndRelease(track, stopRequested.get(), writtenFrames)
                    lastUtterance = writtenFrames.toDouble() / sampleRate
                    audioTrack = null
                    speakingNow.set(false)
                    listener?.onSpeakingFinished()
                }.onFailure { e ->
                    Log.e(TAG, "speak() failed", e)
                    try {
                        track?.stop()
                        track?.release()
                    } catch (t: Throwable) {
                        // ignore
                    }
                    audioTrack = null
                    speakingNow.set(false)
                    listener?.onSpeakingFinished()
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed submitting speak task to executor", t)
            speakingNow.set(false)
            false
        }
    }

    /**
     * Stops current playback immediately.
     */
    fun stop() {
        stopRequested.set(true)
        try {
            audioTrack?.let { track ->
                track.stop()
                track.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
        audioTrack = null
        speakingNow.set(false)
        listener?.onSpeakingFinished()
    }

    /**
     * Releases all resources. Call when done with TTS.
     */
    fun release() {
        stop()
        playbackExecutor.shutdown()
        try {
            tts.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TTS engine", e)
        }
    }

    /**
     * Calculates peak amplitude of audio samples (0.0 - 1.0) for visualization.
     */
    private fun peakAmplitude(samples: FloatArray): Float {
        var max = 0f
        for (s in samples) {
            val absS = abs(s)
            if (absS > max) max = absS
        }
        return max.coerceIn(0f, 1f)
    }

    /**
     * Waits for whatever is already written to the AudioTrack to finish
     * playing, then stops and releases. When [aborted] is true (user pressed
     * stop), skips the wait and kills playback immediately. [writtenFrames]
     * is the number of mono 16-bit frames the callback wrote (1 short = 1
     * frame), which AudioTrack does not track for us.
     */
    private fun drainAndRelease(track: AudioTrack?, aborted: Boolean, writtenFrames: Int) {
        if (track == null) return
        try {
            if (!aborted) {
                // Poll at 20ms until the mixer has played everything written.
                val deadline = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < deadline &&
                    track.playbackHeadPosition < writtenFrames
                ) {
                    if (stopRequested.get()) break
                    Thread.sleep(20)
                }
            }
            track.stop()
            track.release()
        } catch (t: Throwable) {
            Log.e(TAG, "drainAndRelease failed", t)
            runCatching { track.release() }
        }
    }
}