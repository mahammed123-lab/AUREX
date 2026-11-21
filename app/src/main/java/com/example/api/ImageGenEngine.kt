package com.example.api

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ImageGenEngine(private val context: Context) {

    data class GeneratedImage(val path: String, val provider: String)

    companion object {
        private const val TAG = "JarvisImageGen"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Saves to app-scoped external storage — no runtime permission needed on Android 10+
    private val saveDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JARVIS_Images")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private fun newFile(): File {
        val name = "jarvis_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
        return File(saveDir, name)
    }

    private val mediaGenSkill = MediaGenSkill(context)

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PROVIDER 1 — POLLINATIONS (free, no key needed, instant) via MediaGenSkill
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private suspend fun genPollinations(prompt: String, apiKey: String = "", width: Int = 768, height: Int = 768): String {
        val file = mediaGenSkill.generateImage(
            prompt = prompt,
            model = "flux",
            width = width,
            height = height,
            apiKey = apiKey
        )
        if (file != null && file.exists()) {
            return file.absolutePath
        }
        throw Exception("Pollinations AI MediaGenSkill generation failed")
    }

    suspend fun generateVideo(prompt: String, apiKey: String = ""): String? {
        val file = mediaGenSkill.generateVideo(
            prompt = prompt,
            model = "veo",
            duration = 4,
            apiKey = apiKey
        )
        return file?.absolutePath
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PROVIDER 2 — STABILITY AI
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private fun genStability(prompt: String, apiKey: String, width: Int = 1024, height: Int = 1024): String {
        if (apiKey.isBlank()) throw Exception("Stability API key not configured")

        val json = JSONObject().apply {
            put("text_prompts", JSONArray().put(JSONObject().apply {
                put("text", prompt)
                put("weight", 1)
            }))
            put("cfg_scale", 7)
            put("height", height)
            put("width", width)
            put("steps", 30)
            put("samples", 1)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.stability.ai/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Stability HTTP ${response.code}")
            val raw = response.body?.string() ?: throw Exception("Empty response")
            val data = JSONObject(raw)
            val b64 = data.getJSONArray("artifacts").getJSONObject(0).getString("base64")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val file = newFile()
            FileOutputStream(file).use { it.write(bytes) }
            return file.absolutePath
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PROVIDER 2 — NVIDIA NIM (real SD 3.5 / SD Medium via ai.api.nvidia.com)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private suspend fun genNvidia(prompt: String, apiKey: String): String {
        if (apiKey.isBlank()) throw Exception("NVIDIA API key not configured")
        val b64 = NvidiaImageGenerator.generateImageBase64FromNvidia(prompt, apiKey)
            ?: throw Exception("NVIDIA NIM generation failed")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val file = newFile()
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PROVIDER 3 — GEMINI IMAGEN
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private fun genGemini(prompt: String, apiKey: String): String {
        if (apiKey.isBlank()) throw Exception("Gemini API key not configured")

        val url = "https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-001:predict?key=$apiKey"
        val json = JSONObject().apply {
            put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
            put("parameters", JSONObject().apply {
                put("sampleCount", 1)
                put("aspectRatio", "1:1")
            })
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Gemini HTTP ${response.code}")
            val raw = response.body?.string() ?: throw Exception("Empty response")
            val data = JSONObject(raw)
            val b64 = data.getJSONArray("predictions").getJSONObject(0).getString("bytesBase64Encoded")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val file = newFile()
            FileOutputStream(file).use { it.write(bytes) }
            return file.absolutePath
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // OPEN IMAGE WITH DEFAULT VIEWER
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    fun openImage(path: String) {
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open image viewer: ${e.message}")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // MAIN GENERATE FUNCTION — Pollinations first, NVIDIA NIM fallback,
    // then cloud providers. Reports which provider actually succeeded.
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    suspend fun generateImage(
        prompt: String,
        apiKey: String = "",
        nvidiaApiKey: String = "",
        openAfter: Boolean = false
    ): GeneratedImage? =
        withContext(Dispatchers.IO) {
            val providers = listOf<Pair<String, suspend (String) -> String>>(
                "Pollinations AI" to { p -> genPollinations(p, apiKey) },
                "NVIDIA SD 3.5 (NIM)" to { p -> genNvidia(p, nvidiaApiKey) },
                "Gemini Imagen" to { p -> genGemini(p, apiKey) },
                "Stability AI" to { p -> genStability(p, apiKey) }
            )

            for ((name, fn) in providers) {
                try {
                    Log.d(TAG, "Generating image via $name for prompt: '$prompt'...")
                    val path = fn(prompt)
                    if (File(path).exists()) {
                        Log.d(TAG, "Successfully generated and saved image via $name: $path")
                        if (openAfter) openImage(path)
                        return@withContext GeneratedImage(path, name)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$name failed: ${e.message}")
                    continue
                }
            }

            null
        }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PARSE IMAGE COMMAND FROM NATURAL LANGUAGE
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    fun parseImageCommand(text: String): String {
        val t = text.lowercase(Locale.US).trim()
        val patterns = listOf(
            Regex("generate (?:an? )?image (?:of\\s+)?(.+)"),
            Regex("create (?:an? )?image (?:of\\s+)?(.+)"),
            Regex("make (?:an? )?image (?:of\\s+)?(.+)"),
            Regex("draw (?:an? )?(?:image of\\s+)?(.+)"),
            Regex("generate (?:an? )?picture (?:of\\s+)?(.+)"),
            Regex("create (?:an? )?picture (?:of\\s+)?(.+)"),
            Regex("show me (?:an? )?(?:image|picture) (?:of\\s+)?(.+)"),
            Regex("(?:image|picture|draw|generate|create|make)\\s+(.+)")
        )
        for (regex in patterns) {
            val match = regex.find(t)
            if (match != null) return match.groupValues[1].trim()
        }
        return t
    }
}
