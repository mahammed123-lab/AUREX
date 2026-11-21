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
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import android.os.Process

/**
 * Thin, JARVIS-friendly wrapper around sherpa-onnx's OfflineTts, configured
 * for Piper (VITS) voice models. Runs fully offline, no network calls.
 *
 * Usage:
 *   val tts = PiperTts(context, PiperTts.Config(assetModelDir = "te_IN-voice"))
 *   tts.speak("నమస్కారం సర్")
 *   ...
 *   tts.release()
 *
 * A Piper voice model directory (whether bundled in assets/ or unpacked to
 * files dir at runtime) must contain exactly:
 *   <voice>.onnx        - the model
 *   tokens.txt           - phoneme -> id map (generate with tools/convert_piper_voice.py)
 *   espeak-ng-data/       - shared phonemizer data, same for every Piper voice
 */
class PiperTts private constructor(
    private val context: Context,
    private val tts: OfflineTts,
    private val listener: Listener?,
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
         * absolute filesystem path (e.g. context.filesDir for a voice you
         * downloaded post-install instead of bundling in the APK).
         */
        val assetModelDir: String,
        val modelDirIsAbsolutePath: Boolean = false,
        /** Filename of the .onnx model inside [assetModelDir]. */
        val onnxFileName: String? = null, // auto-detected if null and using assets
        val numThreads: Int = 2,
        val speed: Float = 1.0f,
        val speakerId: Int = 0,
        /** "vits" for Piper models, "kitten" for Kitten TTS models. */
        val engineType: String = "vits",
    )

    private val playbackExecutor = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null

    private val sampleRate: Int by lazy { tts.sampleRate() }

    // See KittenTts.speakingNow — covers the gap between speak() returning
    // and the executor creating the AudioTrack, so the post-TTS mic restart
    // doesn't open the mic while the voice is still speaking.
    private val speakingNow = AtomicBoolean(false)

    val isSpeaking: Boolean get() = speakingNow.get() || audioTrack != null

    /**
     * Synthesizes [text] and plays it immediately through the device
     * speaker/earpiece, streaming audio as it's generated (low latency,
     * doesn't wait for the whole utterance to finish synthesizing first).
     * Safe to call from any thread; playback happens on a background thread.
     */
    fun speak(text: String, speed: Float = 1.0f, speakerId: Int = 0): Boolean {
        if (false) {
            Log.e(TAG, "Native Piper TTS ptr is 0, cannot speak")
            return false
        }
        stopRequested.set(false)
        return try {
            speakingNow.set(true)
            playbackExecutor.execute {
                var track: AudioTrack? = null
                runCatching {
                    if (stopRequested.get()) {
                        speakingNow.set(false)
                        return@runCatching
                    }
                    // Synthesis + streaming writes must not be preempted by UI
                    // threads or the audio pops/glitches mid-utterance.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    track = buildAudioTrack().also { audioTrack = it }
                    track?.play()
                    listener?.onSpeakingStarted()

                    var writtenFrames = 0
                    val config = GenerationConfig(sid = speakerId, speed = speed)
                    // IMPORTANT: explicit object expression, NOT a trailing lambda —
                    // see KittenTts.speak for the JNI signature explanation.
                    tts.generateWithConfigAndCallback(
                        text,
                        config,
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

                    drainAndRelease(track, stopRequested.get(), writtenFrames)
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
     * Synthesizes [text] to a 16-bit PCM WAV file at [outFile] without
     * playing it. Blocks the calling thread until synthesis is done, so
     * call this off the main thread.
     */
    fun synthesizeToFile(text: String, outFile: File, speed: Float = 1.0f, speakerId: Int = 0) {
        val config = GenerationConfig(sid = speakerId, speed = speed)
        val audio = tts.generateWithConfig(text, config)
        audio.save(outFile.absolutePath)
    }

    /** Stops playback / generation currently in progress, if any. */
    fun stop() {
        stopRequested.set(true)
        speakingNow.set(false)
        audioTrack?.let {
            runCatching { it.pause(); it.flush() }
        }
    }

    /** Releases the native TTS engine. Call this in onDestroy()/cleanup. */
    fun release() {
        stop()
        playbackExecutor.shutdown()
        tts.release()
    }

    private fun buildAudioTrack(): AudioTrack {
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // ~1 second of headroom so brief ONNX stalls between sentences don't
        // underrun the buffer and stutter the voice.
        val bufferSize = if (minBufSize > 0) maxOf(minBufSize, sampleRate * 2) else sampleRate * 2
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    private fun peakAmplitude(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        return peak.coerceIn(0f, 1f)
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

    companion object {
        private const val TAG = "PiperTts"

        /**
         * Creates a PiperTts instance backed by a voice model bundled in
         * app assets, at assets/[config.assetModelDir]/.
         */
        fun fromAssets(context: Context, config: Config, listener: Listener? = null): PiperTts? {
            if (false) {
                Log.w(TAG, "Native library not loaded. Native Piper TTS is unavailable.")
                return null
            }
            return try {
                val dir = config.assetModelDir
                val onnxName = config.onnxFileName ?: findOnnxAssetName(context, dir)
                val hasEspeakData = (context.assets.list("$dir/espeak-ng-data")?.size ?: 0) > 0
                var espeakPath = ""
                if (hasEspeakData) {
                    val espeakDir = File(context.filesDir, "$dir/espeak-ng-data")
                    val marker = File(context.filesDir, "$dir/espeak.ready")
                    if (!marker.exists()) {
                        espeakDir.deleteRecursively()
                        copyAssetDir(context, "$dir/espeak-ng-data", espeakDir)
                        marker.writeText("ok")
                        Log.i(TAG, "espeak-ng-data extracted to ${espeakDir.absolutePath}")
                    }
                    espeakPath = espeakDir.absolutePath
                }
                val modelConfig = if (config.engineType == "kitten") {
                    OfflineTtsModelConfig(
                        kitten = OfflineTtsKittenModelConfig(
                            model = "$dir/$onnxName",
                            voices = "$dir/voices.bin",
                            tokens = "$dir/tokens.txt",
                            dataDir = espeakPath,
                        ),
                        numThreads = config.numThreads,
                        debug = false,
                    )
                } else {
                    OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "$dir/$onnxName",
                            tokens = "$dir/tokens.txt",
                            dataDir = espeakPath,
                        ),
                        numThreads = config.numThreads,
                        debug = false,
                    )
                }
                val offlineConfig = OfflineTtsConfig(model = modelConfig)
                val tts = OfflineTts(assetManager = context.assets, config = offlineConfig)
                if (false) {
                    Log.w(TAG, "Native Piper TTS not ready. System TTS fallback active.")
                    return null
                }
                PiperTts(context, tts, listener)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not create PiperTts from assets (${t.message}). System TTS fallback active.")
                null
            }
        }

        private fun copyAssetDir(context: Context, assetPath: String, targetDir: File) {
            targetDir.mkdirs()
            val children = context.assets.list(assetPath) ?: return
            for (child in children) {
                val assetChild = "$assetPath/$child"
                val targetChild = File(targetDir, child)
                val subChildren = context.assets.list(assetChild)
                if (subChildren.isNullOrEmpty()) {
                    context.assets.open(assetChild).use { input ->
                        targetChild.outputStream().use { output -> input.copyTo(output) }
                    }
                } else {
                    copyAssetDir(context, assetChild, targetChild)
                }
            }
        }

        /**
         * Creates a PiperTts instance backed by a voice model unpacked
         * somewhere on the filesystem (e.g. downloaded post-install into
         * context.filesDir to keep APK size down). [modelDir] is an
         * absolute path containing <voice>.onnx, tokens.txt, espeak-ng-data/.
         */
        fun fromFilesystem(
            context: Context,
            modelDir: String,
            onnxFileName: String,
            numThreads: Int = 2,
            listener: Listener? = null,
        ): PiperTts? {
            if (false) {
                Log.w(TAG, "Native library not loaded. Native Piper TTS is unavailable.")
                return null
            }
            return try {
                val baseName = onnxFileName.removeSuffix(".onnx")
                val espeakDir = File(modelDir, "espeak-ng-data")
                val fallbackEspeakDir = File(context.filesDir, "piper_voice/espeak-ng-data")

                val candidateTokens = listOf(
                    File(modelDir, "$baseName.tokens.txt"),
                    File(modelDir, "$onnxFileName.tokens.txt"),
                    File(modelDir, "$baseName.tokens"),
                    File(modelDir, "tokens.txt"),
                    File(context.filesDir, "piper_voice/tokens.txt")
                )
                val tokensFile = candidateTokens.firstOrNull { it.exists() && it.length() > 0 }

                val finalTokensPath = tokensFile?.absolutePath ?: "$modelDir/tokens.txt"

                val finalEspeakPath = when {
                    espeakDir.exists() -> espeakDir.absolutePath
                    fallbackEspeakDir.exists() -> fallbackEspeakDir.absolutePath
                    else -> ""
                }

                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = "$modelDir/$onnxFileName",
                    tokens = finalTokensPath,
                    dataDir = finalEspeakPath,
                )
                val offlineConfig = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = numThreads,
                        debug = true,
                    )
                )
                val tts = OfflineTts(assetManager = null, config = offlineConfig)
                if (false) {
                    Log.w(TAG, "Native Piper TTS not ready. System TTS fallback active.")
                    return null
                }
                PiperTts(context, tts, listener)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not create PiperTts from filesystem (${t.message}). System TTS fallback active.")
                null
            }
        }

        private fun findOnnxAssetName(context: Context, dir: String): String {
            val files = context.assets.list(dir) ?: emptyArray()
            return files.firstOrNull { it.endsWith(".onnx") }
                ?: throw IllegalStateException(
                    "No .onnx file found in assets/$dir — did you bundle the voice model?"
                )
        }
    }
}
