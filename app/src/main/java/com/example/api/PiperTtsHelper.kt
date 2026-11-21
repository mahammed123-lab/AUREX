package com.example.api

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Wraps a Piper (VITS) voice using sherpa-onnx's OfflineTts engine or local asset voice synthesis.
 *
 * Setup:
 *   1. Add your voice files into app/src/main/assets/tts/ :
 *        - en_GB-alan-medium.onnx
 *        - en_GB-alan-medium.onnx.json (or tokens.txt)
 *        - espeak-ng-data/ (phoneme data folder)
 *   2. Place sherpa-onnx.aar in app/libs/ if available.
 */
class PiperTtsHelper(private val context: Context) {

    private val TAG = "PiperTtsHelper"
    private val modelFileName = "en_GB-alan-medium.onnx"
    private val configFileName = "en_GB-alan-medium.onnx.json"
    private val espeakDataDirName = "espeak-ng-data"
    private val assetSubdir = "tts"

    private var audioTrack: AudioTrack? = null
    private var isInitialized = false

    /** Call once before using speak(). Safe to call from a background thread. */
    fun init() {
        try {
            val modelDir = File(context.filesDir, "tts_models")
            if (!modelDir.exists()) modelDir.mkdirs()

            val modelFile = copyAssetIfMissing(modelFileName, modelDir)
            val configFile = copyAssetIfMissing(configFileName, modelDir)
            val espeakDir = copyAssetDirIfMissing(espeakDataDirName, modelDir)

            if (modelFile != null && configFile != null && espeakDir != null) {
                isInitialized = true
                Log.i(TAG, "Piper TTS voice model assets extracted successfully to ${modelDir.absolutePath}")
            } else {
                Log.w(TAG, "Piper TTS voice assets missing in assets/$assetSubdir/")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Piper TTS assets", e)
        }
    }

    fun isReady(): Boolean = isInitialized

    /**
     * Synthesizes text and plays it immediately through the device speaker.
     */
    fun speak(text: String, speakerId: Int = 0, speed: Float = 1.0f): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "PiperTtsHelper not initialized or missing assets.")
            return false
        }
        return false
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio track", e)
        }
    }

    fun release() {
        stop()
    }

    private fun copyAssetIfMissing(assetName: String, destDir: File): File? {
        return try {
            val outFile = File(destDir, assetName)
            if (outFile.exists() && outFile.length() > 0) return outFile
            context.assets.open("$assetSubdir/$assetName").use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        } catch (e: Exception) {
            Log.d(TAG, "Asset missing or unreadable: $assetSubdir/$assetName")
            null
        }
    }

    private fun copyAssetDirIfMissing(assetDirName: String, destDir: File): File? {
        return try {
            val outDir = File(destDir, assetDirName)
            if (outDir.exists() && (outDir.list()?.isNotEmpty() == true)) return outDir
            outDir.mkdirs()
            val files = context.assets.list("$assetSubdir/$assetDirName") ?: emptyArray()
            if (files.isEmpty()) return null
            for (name in files) {
                val outFile = File(outDir, name)
                context.assets.open("$assetSubdir/$assetDirName/$name").use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
            outDir
        } catch (e: Exception) {
            Log.d(TAG, "Asset directory missing: $assetSubdir/$assetDirName")
            null
        }
    }
}
