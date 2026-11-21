package com.example.api

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log

data class InstalledTtsEngine(
    val packageName: String,
    val label: String,
    val isSherpa: Boolean
)

object SherpaTtsEngineDetector {

    private const val TAG = "SherpaTtsDetector"
    private const val PREFS_NAME = "jarvis_tts_prefs"
    private const val KEY_PREFERRED_ENGINE = "preferred_tts_engine_package"

    // Known Sherpa / Sherpa-ONNX / VoxSherpa engine package identifiers
    private val KNOWN_SHERPA_PACKAGES = listOf(
        "com.k2fsa.sherpa.onnx.tts.engine",
        "com.k2fsa.sherpa.onnx",
        "org.sherpaonnx.tts",
        "com.k2fsa.sherpa.onnx.tts",
        "com.sherpa.tts",
        "com.voxsherpa.tts",
        "com.example.voxsherpatts"
    )

    /**
     * Finds all TTS engines installed on the device via PackageManager and TextToSpeech.
     */
    fun getInstalledTtsEngines(context: Context): List<InstalledTtsEngine> {
        val engines = LinkedHashMap<String, InstalledTtsEngine>()
        try {
            val pm = context.packageManager

            // 1. Query via Intent Action
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val resolveInfos = pm.queryIntentServices(intent, 0)

            for (info in resolveInfos) {
                val pkg = info.serviceInfo?.packageName ?: continue
                val label = info.serviceInfo?.loadLabel(pm)?.toString() ?: pkg
                val lowerPkg = pkg.lowercase()
                val lowerLabel = label.lowercase()

                val isSherpa = KNOWN_SHERPA_PACKAGES.contains(pkg) ||
                        lowerPkg.contains("sherpa") || lowerPkg.contains("k2fsa") ||
                        lowerPkg.contains("onnx") || lowerPkg.contains("voxsherpa") ||
                        lowerLabel.contains("sherpa") || lowerLabel.contains("k2fsa") ||
                        lowerLabel.contains("onnx") || lowerLabel.contains("voxsherpa")

                engines[pkg] = InstalledTtsEngine(pkg, label, isSherpa)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying TTS engines via PackageManager", e)
        }

        return engines.values.toList()
    }

    /**
     * Scans for an installed Sherpa / Sherpa-ONNX TTS engine package.
     */
    fun findSherpaEnginePackage(context: Context): String? {
        val installed = getInstalledTtsEngines(context)
        val sherpaEngine = installed.firstOrNull { it.isSherpa }
        if (sherpaEngine != null) {
            Log.i(TAG, "Discovered installed Sherpa TTS engine: ${sherpaEngine.packageName} (${sherpaEngine.label})")
            return sherpaEngine.packageName
        }
        return null
    }

    /**
     * Gets the preferred TTS engine package to use for TextToSpeech instantiation.
     * Auto-detects installed Sherpa TTS engine if user hasn't explicitly set another preference.
     */
    fun getPreferredOrSherpaEnginePackage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PREFERRED_ENGINE, null)

        if (!saved.isNullOrBlank()) {
            if (saved == "SYSTEM_DEFAULT") return null
            return saved
        }

        // Auto-detect Sherpa TTS engine installed on device
        val detectedSherpa = findSherpaEnginePackage(context)
        if (detectedSherpa != null) {
            Log.i(TAG, "Auto-detected Sherpa TTS engine on phone: $detectedSherpa")
            return detectedSherpa
        }

        return null
    }

    /**
     * Saves user's preferred engine package setting.
     */
    fun setPreferredEnginePackage(context: Context, packageName: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFERRED_ENGINE, packageName ?: "SYSTEM_DEFAULT").apply()
        Log.i(TAG, "Saved preferred TTS engine package: ${packageName ?: "SYSTEM_DEFAULT"}")
    }

    fun getPreferredEnginePackageSetting(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFERRED_ENGINE, null)
    }
}
