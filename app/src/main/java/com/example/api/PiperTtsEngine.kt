package com.example.api

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.piper.PiperTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class PiperModelInfo(
    val file: File,
    val language: String,
    val name: String
)

/**
 * PiperTtsEngine
 * Offline High-Quality Neural Text-To-Speech Engine for JARVIS using native Piper VITS sherpa-onnx wrapper.
 * Handles Piper VITS voice output with multi-language routing (English & Telugu) and audio amplitude reactivity.
 */
class PiperTtsEngine(
    private val context: Context,
    private val assetManager: AssetManager = context.assets
) : TextToSpeech.OnInitListener {

    private val TAG = "PiperTtsEngine"
    private val loadedPiperInstances = ConcurrentHashMap<String, PiperTts>()
    private val discoveredModels = mutableListOf<PiperModelInfo>()
    private var bundledAssetPiperInstance: PiperTts? = null

    private var fallbackTts: TextToSpeech? = null
    private var isFallbackReady = false
    private var speechRateMultiplier: Float = 1.0f

    fun setSpeechRateMultiplier(rate: Float) {
        speechRateMultiplier = rate.coerceIn(0.7f, 1.5f)
        try {
            fallbackTts?.setSpeechRate(speechRateMultiplier)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var currentOnStart: (() -> Unit)? = null
    private var currentOnDone: (() -> Unit)? = null
    private var currentOnAmplitude: ((Float) -> Unit)? = null

    init {
        // Initialize fallback Android system TTS immediately (prefer installed Sherpa TTS engine if available)
        try {
            val sherpaPkg = SherpaTtsEngineDetector.getPreferredOrSherpaEnginePackage(context)
            if (!sherpaPkg.isNullOrBlank()) {
                fallbackTts = TextToSpeech(context.applicationContext, this, sherpaPkg)
            } else {
                fallbackTts = TextToSpeech(context.applicationContext, this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize fallback TextToSpeech", e)
        }

        // Asynchronously initialize Native Piper TTS on IO thread with safety exception handler
        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in PiperTtsEngine initialization coroutine", throwable)
        }
        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            initNativePiperTts()
        }
    }

    private fun createPiperListener(): PiperTts.Listener {
        return object : PiperTts.Listener {
            override fun onSpeakingStarted() {
                currentOnStart?.invoke()
            }
            override fun onAudioChunk(amplitude: Float) {
                currentOnAmplitude?.invoke(amplitude)
            }
            override fun onSpeakingFinished() {
                currentOnDone?.invoke()
            }
        }
    }

    private val basePiperDir: File
        get() = File(context.filesDir, "piper_voice").also { if (!it.exists()) it.mkdirs() }

    private fun initNativePiperTts() {
        try {
            if (false) {
                Log.i(TAG, "Native Piper TTS JNI libraries not available. Defaulting to System TTS fallback.")
                return
            }

            val piperDir = basePiperDir

            val espeakDir = File(piperDir, "espeak-ng-data")
            val requiredFiles = listOf("phondata", "phontab", "phonindex", "en_dict")
            val isEspeakValid = espeakDir.exists() && requiredFiles.all { name ->
                val f = File(espeakDir, name)
                f.exists() && f.length() > 100L
            }

            val onnxFileInPiper = piperDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx") }
            val tokensFileInPiper = piperDir.walkTopDown().firstOrNull { it.isFile && it.name == "tokens.txt" }

            if (onnxFileInPiper == null || onnxFileInPiper.length() == 0L ||
                tokensFileInPiper == null || tokensFileInPiper.length() == 0L ||
                !isEspeakValid
            ) {
                Log.i(TAG, "Unpacking jarvis-voice assets to ${piperDir.absolutePath}...")
                copyAssetsDir("jarvis-voice", piperDir, forceOverwrite = true)
            }

            // Load bundled asset-backed Piper model directly if available
            try {
                bundledAssetPiperInstance = PiperTts.fromAssets(
                    context = context,
                    config = PiperTts.Config(assetModelDir = "jarvis-voice", onnxFileName = "jarvis.onnx"),
                    listener = createPiperListener()
                )
                if (bundledAssetPiperInstance != null) {
                    Log.i(TAG, "Successfully loaded bundled asset Piper ONNX model (jarvis.onnx - Ryan male voice)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not load bundled asset Piper model: ${t.message}")
            }

            // Search for ONNX voice models in candidate locations, prioritizing user custom path and phone storage
            val customPrefPath = try {
                com.example.data.JarvisDatabase.getDatabase(context).jarvisDao().getPreference("piper_onnx_path")?.value
            } catch (t: Throwable) {
                null
            }

            val searchDirs = mutableListOf<File>()
            if (!customPrefPath.isNullOrBlank()) {
                val customFile = File(customPrefPath)
                if (customFile.exists()) {
                    if (customFile.isDirectory) {
                        searchDirs.add(customFile)
                    } else if (customFile.isFile && customFile.name.endsWith(".onnx")) {
                        customFile.parentFile?.let { searchDirs.add(it) }
                    }
                }
            }

            searchDirs.addAll(
                listOf(
                    File("/storage/emulated/0/models"),
                    File("/storage/emulated/0/Models"),
                    File("/sdcard/models"),
                    File("/sdcard/Models"),
                    File("/storage/emulated/0/Download"),
                    File("/storage/emulated/0/Downloads"),
                    File("/storage/emulated/0/Download/models"),
                    File("/storage/emulated/0/Download/Models"),
                    File("/sdcard/Download"),
                    File("/sdcard/Download/models"),
                    File("/storage/emulated/0/piper"),
                    File("/storage/emulated/0/Piper"),
                    File("/storage/emulated/0/piper-models"),
                    File("/storage/emulated/0/sherpa-onnx"),
                    File("/storage/emulated/0/TTS"),
                    piperDir,
                    File(context.filesDir, "tts_models")
                )
            )

            // Also check top-level folders in /storage/emulated/0/ if readable
            val sdRoot = File("/storage/emulated/0")
            if (sdRoot.exists() && sdRoot.isDirectory) {
                searchDirs.add(sdRoot)
                sdRoot.listFiles()?.filter { it.isDirectory && !it.name.equals("Android", ignoreCase = true) }?.forEach { sub ->
                    searchDirs.add(sub)
                }
            }

            discoveredModels.clear()
            val seenPaths = mutableSetOf<String>()

            // 1. Explicitly register customPrefPath file if it exists directly as a .onnx file
            if (!customPrefPath.isNullOrBlank()) {
                val customFile = File(customPrefPath)
                if (customFile.exists() && customFile.isFile && customFile.name.endsWith(".onnx")) {
                    if (seenPaths.add(customFile.absolutePath)) {
                        val lowerName = customFile.name.lowercase()
                        val lang = when {
                            lowerName.contains("te_") || lowerName.contains("te-") || lowerName.contains("telugu") || lowerName.contains("venkatesh") -> "te"
                            lowerName.contains("en_") || lowerName.contains("en-") || lowerName.contains("english") || lowerName.contains("ryan") || lowerName.contains("jarvis") -> "en"
                            lowerName.contains("hi_") || lowerName.contains("hi-") || lowerName.contains("hindi") -> "hi"
                            else -> "en"
                        }
                        discoveredModels.add(PiperModelInfo(customFile, lang, customFile.name))
                        Log.i(TAG, "Registered explicit custom Piper ONNX model: ${customFile.name} at ${customFile.absolutePath}")
                    }
                }
            }

            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    try {
                        dir.walkTopDown()
                            .onEnter { folder ->
                                val name = folder.name
                                !name.startsWith(".") && !folder.absolutePath.contains("/Android/data") && !folder.absolutePath.contains("/Android/obb")
                            }
                            .filter { it.isFile && it.name.endsWith(".onnx") && it.length() > 1000L }
                            .forEach { modelFile ->
                                if (seenPaths.add(modelFile.absolutePath)) {
                                    val lowerName = modelFile.name.lowercase()
                                    val lang = when {
                                        lowerName.contains("te_") || lowerName.contains("te-") || lowerName.contains("telugu") || lowerName.contains("venkatesh") -> "te"
                                        lowerName.contains("en_") || lowerName.contains("en-") || lowerName.contains("english") || lowerName.contains("ryan") || lowerName.contains("jarvis") -> "en"
                                        lowerName.contains("hi_") || lowerName.contains("hi-") || lowerName.contains("hindi") -> "hi"
                                        else -> "en"
                                    }
                                    discoveredModels.add(PiperModelInfo(modelFile, lang, modelFile.name))
                                    Log.i(TAG, "Discovered Piper ONNX voice model: ${modelFile.name} (lang=$lang) at ${modelFile.absolutePath}")
                                }
                            }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error scanning directory ${dir.absolutePath}: ${e.message}")
                    }
                }
            }

            // Auto-save discovered model directory path to preferences if not set
            if (discoveredModels.isNotEmpty() && customPrefPath.isNullOrBlank()) {
                val firstModel = discoveredModels.first()
                val foundFolderPath = firstModel.file.parentFile?.absolutePath ?: firstModel.file.absolutePath
                try {
                    com.example.data.JarvisDatabase.getDatabase(context).jarvisDao().insertPreference(
                        com.example.data.AppPreference("piper_onnx_path", foundFolderPath)
                    )
                    Log.i(TAG, "Auto-saved discovered Piper ONNX model path: $foundFolderPath")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to auto-save discovered model path: ${e.message}")
                }
            }

            // Pre-load discovered models
            for (modelInfo in discoveredModels) {
                try {
                    val mDir = modelInfo.file.parentFile ?: piperDir
                    ensureSupportFiles(mDir)
                    val piperInstance = PiperTts.fromFilesystem(
                        context = context,
                        modelDir = mDir.absolutePath,
                        onnxFileName = modelInfo.file.name,
                        listener = createPiperListener()
                    )
                    if (piperInstance != null) {
                        loadedPiperInstances[modelInfo.file.absolutePath] = piperInstance
                        Log.i(TAG, "Successfully pre-loaded Piper TTS model: ${modelInfo.name} (${modelInfo.language})")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load Piper model ${modelInfo.name}: ${e.message}")
                }
            }

        } catch (t: Throwable) {
            Log.w(TAG, "Native Piper TTS initialization failed safely (${t.message}), using system fallback.")
        }
    }

    private fun getPiperForText(text: String): PiperTts? {
        val isTelugu = text.any { it in '\u0C00'..'\u0C7F' }
        val targetLang = if (isTelugu) "te" else "en"

        // 0. Priorities: Exact user specified custom path model first
        val customPrefPath = try {
            com.example.data.JarvisDatabase.getDatabase(context).jarvisDao().getPreference("piper_onnx_path")?.value
        } catch (t: Throwable) {
            null
        }

        if (!customPrefPath.isNullOrBlank()) {
            val customMatch = discoveredModels.firstOrNull {
                it.file.absolutePath.equals(customPrefPath, ignoreCase = true) ||
                        it.file.parentFile?.absolutePath.equals(customPrefPath, ignoreCase = true)
            }
            if (customMatch != null) {
                val cached = loadedPiperInstances[customMatch.file.absolutePath]
                if (cached != null) return cached

                val mDir = customMatch.file.parentFile ?: basePiperDir
                ensureSupportFiles(mDir)
                val loaded = PiperTts.fromFilesystem(
                    context = context,
                    modelDir = mDir.absolutePath,
                    onnxFileName = customMatch.file.name,
                    listener = createPiperListener()
                )
                if (loaded != null) {
                    loadedPiperInstances[customMatch.file.absolutePath] = loaded
                    return loaded
                }
            }
        }

        // 1. Match discovered model for language
        var targetModel = discoveredModels.firstOrNull { it.language == targetLang }
            ?: discoveredModels.firstOrNull()

        if (targetModel == null) {
            // Try rescanning storage on demand in case models were added or permission was granted after startup
            try { initNativePiperTts() } catch (t: Throwable) { Log.w(TAG, "On-demand model scan failed: ${t.message}") }
            targetModel = discoveredModels.firstOrNull { it.language == targetLang }
                ?: discoveredModels.firstOrNull()
        }

        if (targetModel != null) {
            val cached = loadedPiperInstances[targetModel.file.absolutePath]
            if (cached != null) return cached

            val mDir = targetModel.file.parentFile ?: basePiperDir
            ensureSupportFiles(mDir)
            val loaded = PiperTts.fromFilesystem(
                context = context,
                modelDir = mDir.absolutePath,
                onnxFileName = targetModel.file.name,
                listener = createPiperListener()
            )
            if (loaded != null) {
                loadedPiperInstances[targetModel.file.absolutePath] = loaded
                return loaded
            }
        }

        return loadedPiperInstances.values.firstOrNull() ?: bundledAssetPiperInstance
    }

    private fun ensureSupportFiles(targetDir: File) {
        try {
            if (!targetDir.exists()) targetDir.mkdirs()
            val tokensFile = File(targetDir, "tokens.txt")
            if (!tokensFile.exists() || tokensFile.length() == 0L) {
                val srcTokens = File(basePiperDir, "tokens.txt")
                if (srcTokens.exists() && srcTokens.length() > 0L) {
                    srcTokens.copyTo(tokensFile, overwrite = true)
                } else {
                    copyAssetsDir("jarvis-voice/tokens.txt", targetDir, forceOverwrite = true)
                }
            }
            val espeakDir = File(targetDir, "espeak-ng-data")
            if (!espeakDir.exists() || espeakDir.listFiles().isNullOrEmpty()) {
                val srcEspeak = File(basePiperDir, "espeak-ng-data")
                if (srcEspeak.exists() && srcEspeak.isDirectory) {
                    srcEspeak.copyRecursively(espeakDir, overwrite = true)
                } else {
                    copyAssetsDir("jarvis-voice/espeak-ng-data", espeakDir, forceOverwrite = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure support files in ${targetDir.absolutePath}: ${e.message}")
        }
    }

    private fun copyAssetsDir(assetPath: String, targetDir: File, forceOverwrite: Boolean = false) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val files = try { context.assets.list(assetPath) } catch (e: Exception) { null } ?: return
        for (file in files) {
            val subAssetPath = if (assetPath.isEmpty()) file else "$assetPath/$file"
            val targetFile = File(targetDir, file)
            val subFiles = try { context.assets.list(subAssetPath) } catch (e: Exception) { null }
            if (!subFiles.isNullOrEmpty()) {
                copyAssetsDir(subAssetPath, targetFile, forceOverwrite)
            } else {
                if (forceOverwrite || !targetFile.exists() || targetFile.length() == 0L) {
                    try {
                        targetFile.parentFile?.mkdirs()
                        context.assets.open(subAssetPath).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        // If context.assets.open failed because it's a directory
                        val children = try { context.assets.list(subAssetPath) } catch (t: Throwable) { null }
                        if (!children.isNullOrEmpty()) {
                            copyAssetsDir(subAssetPath, targetFile, forceOverwrite)
                        } else {
                            Log.e(TAG, "Error copying asset file: $subAssetPath", e)
                        }
                    }
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isFallbackReady = true
            try {
                fallbackTts?.language = Locale.US
                fallbackTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        currentOnStart?.invoke()
                    }

                    override fun onDone(utteranceId: String?) {
                        currentOnDone?.invoke()
                    }

                    override fun onError(utteranceId: String?) {
                        currentOnDone?.invoke()
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up utterance listener", e)
            }
        }
    }

    /**
     * Speaks text using Piper Voice pipeline and triggers amplitude callbacks for Voice Orb reactivity
     */
    suspend fun speak(
        text: String,
        voiceType: String = "jarvis",
        onAmplitude: (Float) -> Unit = {},
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) = withContext(Dispatchers.Default) {
        stop()
        currentOnStart = onStart
        currentOnDone = onDone
        currentOnAmplitude = onAmplitude

        val cleanText = formatTextForSpeech(text)
        if (cleanText.isBlank()) {
            withContext(Dispatchers.Main) { onDone?.invoke() }
            return@withContext
        }

        // Try using Native Piper TTS ONNX model first if available
        val piper = getPiperForText(cleanText)
        if (piper != null) {
            val submitted = try {
                piper.speak(cleanText)
            } catch (t: Throwable) {
                Log.e(TAG, "Native Piper TTS speak failed, falling back", t)
                false
            }
            if (submitted) {
                return@withContext
            }
        }

        withContext(Dispatchers.Main) {
            currentOnStart?.invoke()
        }

        // Fallback speech with system TTS
        playFallbackSpeech(cleanText, voiceType, onAmplitude)
    }

    private fun playFallbackSpeech(
        text: String,
        voiceType: String,
        onAmplitude: (Float) -> Unit
    ) {
        val tts = fallbackTts
        if (tts == null || !isFallbackReady) {
            currentOnDone?.invoke()
            return
        }

        try {
            val isTelugu = text.any { it in '\u0C00'..'\u0C7F' }
            val locale = if (isTelugu) Locale("te", "IN") else Locale.US
            try {
                val res = tts.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.language = Locale.US
                }
            } catch (e: Exception) {
                tts.language = Locale.US
            }

            val voices = try { tts.voices } catch (e: Exception) { null }
            if (!voices.isNullOrEmpty()) {
                val maleNames = listOf("male", "m0", "guy", "man", "en-us-x-sfg#male", "en-us-x-iom", "en-us-x-tpf", "en-us-x-rdb", "en-gb-x-rdb", "en-us-x-iol", "cmn", "george", "male_1", "male_2")
                val femaleNames = listOf("female", "woman", "girl", "lady", "f00", "f01", "f02", "female_1", "female_2", "fem")

                val matchingVoice = when (voiceType.lowercase()) {
                    "friday" -> voices.firstOrNull { v -> femaleNames.any { f -> v.name.lowercase().contains(f) } }
                    else -> voices.firstOrNull { v -> maleNames.any { m -> v.name.lowercase().contains(m) } && femaleNames.none { f -> v.name.lowercase().contains(f) } }
                        ?: voices.firstOrNull { v -> v.locale.language == "en" && femaleNames.none { f -> v.name.lowercase().contains(f) } }
                }
                if (matchingVoice != null) {
                    try { tts.voice = matchingVoice } catch (e: Exception) { e.printStackTrace() }
                }
            }

            var pitch = when (voiceType.lowercase()) {
                "friday" -> 1.02f
                else -> 0.94f // Warm natural male voice for AUREX
            }
            var rate = when (voiceType.lowercase()) {
                "friday" -> speechRateMultiplier * 1.02f
                else -> speechRateMultiplier
            }

            // Apply dynamic emotion modulation to fallback voice
            val emotion = detectEmotion(text)
            when (emotion) {
                "EXCITED" -> {
                    pitch += 0.08f
                    rate += 0.04f
                }
                "SURPRISED" -> {
                    pitch += 0.10f
                    rate += 0.02f
                }
                "EMPATHETIC" -> {
                    pitch -= 0.05f
                    rate -= 0.05f
                }
                "THOUGHTFUL" -> {
                    pitch -= 0.02f
                    rate -= 0.04f
                }
                "CURIOUS" -> {
                    pitch += 0.05f
                    rate += 0.01f
                }
            }

            tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
            tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))

            val params = Bundle()
            val utteranceId = "PIPER_JARVIS_TTS_${System.currentTimeMillis()}"
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

            // Generate periodic synthetic amplitude pulses while speaking for Voice Orb reactivity
            CoroutineScope(Dispatchers.Default).launch {
                kotlinx.coroutines.delay(100)
                var ticks = 0
                while ((tts.isSpeaking || ticks < 5) && ticks < 200) {
                    val sampleAmplitude = (0.25f + (Math.sin(ticks * 0.4).toFloat() * 0.35f + Math.cos(ticks * 0.7).toFloat() * 0.2f)).coerceIn(0.15f, 0.95f)
                    withContext(Dispatchers.Main) {
                        onAmplitude(sampleAmplitude)
                    }
                    kotlinx.coroutines.delay(60)
                    ticks++
                }
                withContext(Dispatchers.Main) {
                    onAmplitude(0f)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback speech", e)
            currentOnDone?.invoke()
        }
    }

    fun rescanModels() {
        CoroutineScope(Dispatchers.IO).launch {
            initNativePiperTts()
        }
    }

    fun importModelFromUri(uri: android.net.Uri): String? {
        return try {
            val fileName = getFileNameFromUri(context, uri) ?: "custom_piper_model.onnx"
            val customDir = File(context.filesDir, "custom_piper_models")
            if (!customDir.exists()) customDir.mkdirs()
            val targetFile = File(customDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            ensureSupportFiles(customDir)
            Log.i(TAG, "Imported custom Piper ONNX model to ${targetFile.absolutePath}")
            
            // Auto save preference
            com.example.data.JarvisDatabase.getDatabase(context).jarvisDao().insertPreference(
                com.example.data.AppPreference("piper_onnx_path", targetFile.absolutePath)
            )

            // Rescan
            initNativePiperTts()
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import Piper model from Uri: ${e.message}", e)
            null
        }
    }

    private fun getFileNameFromUri(context: Context, uri: android.net.Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) name = cursor.getString(idx)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve display name: ${e.message}")
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        return name
    }

    fun getDiscoveredModels(): List<PiperModelInfo> {
        return discoveredModels.toList()
    }

    fun getDiscoveredModelSummary(): String {
        if (discoveredModels.isEmpty()) return "No local Piper ONNX voice models discovered yet."
        return discoveredModels.joinToString("\n") { "• ${it.name} [${it.language.uppercase()}] (${it.file.parent})" }
    }

    fun stop() {
        try {
            for (p in loadedPiperInstances.values) {
                p.stop()
            }
            if (fallbackTts?.isSpeaking == true) {
                fallbackTts?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    fun isSpeaking(): Boolean {
        val nativeSpeaking = loadedPiperInstances.values.any { try { it.isSpeaking } catch (e: Exception) { false } }
        val fallbackSpeaking = try { fallbackTts?.isSpeaking == true } catch (e: Exception) { false }
        return nativeSpeaking || fallbackSpeaking
    }

    fun release() {
        stop()
        try {
            for (p in loadedPiperInstances.values) {
                p.release()
            }
            loadedPiperInstances.clear()
            fallbackTts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Piper TTS", e)
        }
    }

    private fun detectEmotion(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("!") || lower.contains("haha") || lower.contains("yay") || lower.contains("whoa") || lower.contains("awesome") || lower.contains("excited") || lower.contains("great news") -> "EXCITED"
            lower.contains("oh!") || lower.contains("gosh") || lower.contains("wow") || lower.contains("unbelievable") || lower.contains("ahh!") -> "SURPRISED"
            lower.contains("sorry") || lower.contains("unfortunately") || lower.contains("sad") || lower.contains("alas") || lower.contains("aww") || lower.contains("sigh") -> "EMPATHETIC"
            lower.contains("hmm") || lower.contains("ahh") || lower.contains("um") || lower.contains("let's see") || lower.contains("thinking") || lower.contains("...") -> "THOUGHTFUL"
            lower.contains("?") -> "CURIOUS"
            else -> "NEUTRAL"
        }
    }

    private fun formatTextForSpeech(text: String): String {
        var res = text
        // Convert action tags into spoken emotional interjections
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
}
