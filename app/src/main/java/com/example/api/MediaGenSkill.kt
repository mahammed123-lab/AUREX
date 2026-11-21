package com.example.api

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MediaGenSkill - Pollinations.ai Image & Video Generation Engine for JARVIS
 *
 * Supports Pollinations AI image generation (flux, zimage, turbo, dreamshaper)
 * and video generation (veo, wan, seedance-pro) with automatic fallback.
 */
class MediaGenSkill(private val context: Context) {

    companion object {
        private const val TAG = "MediaGenSkill"
        private const val BASE_GEN_URL = "https://gen.pollinations.ai"
        private const val BASE_IMAGE_URL = "https://image.pollinations.ai"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val imageSaveDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JARVIS_Media")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val videoSaveDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "JARVIS_Video")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private fun newImageFile(): File {
        val name = "jarvis_img_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return File(imageSaveDir, name)
    }

    private fun newVideoFile(): File {
        val name = "jarvis_vid_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        return File(videoSaveDir, name)
    }

    /**
     * Generate an image using Pollinations AI models (flux, zimage, turbo, etc.)
     */
    suspend fun generateImage(
        prompt: String,
        model: String = "flux",
        width: Int = 1024,
        height: Int = 1024,
        seed: Long = System.currentTimeMillis(),
        apiKey: String = ""
    ): File? = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim().ifEmpty { "a futuristic AI portrait" }
        val encodedPrompt = URLEncoder.encode(cleanPrompt, "UTF-8").replace("+", "%20")
        val cleanKey = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

        val candidateUrls = mutableListOf<String>()

        // Paid / Authenticated endpoint if Pollinations key provided
        if (cleanKey.startsWith("pk_")) {
            candidateUrls.add("$BASE_GEN_URL/image/$encodedPrompt?model=$model&width=$width&height=$height&key=$cleanKey")
            candidateUrls.add("$BASE_GEN_URL/image/$encodedPrompt?model=zimage&width=$width&height=$height&key=$cleanKey")
        }

        // Free endpoints with high availability
        candidateUrls.add("$BASE_IMAGE_URL/prompt/$encodedPrompt?width=$width&height=$height&seed=$seed&nologo=true&model=$model")
        candidateUrls.add("$BASE_IMAGE_URL/prompt/$encodedPrompt?width=$width&height=$height&seed=$seed&nologo=true&model=flux")
        candidateUrls.add("$BASE_IMAGE_URL/prompt/$encodedPrompt?width=$width&height=$height&seed=$seed&nologo=true&model=turbo")
        candidateUrls.add("$BASE_IMAGE_URL/prompt/$encodedPrompt?nologo=true")

        for (url in candidateUrls) {
            try {
                Log.d(TAG, "Fetching image from Pollinations URL: $url")
                val reqBuilder = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Android; JARVIS MediaGenSkill)")
                    .get()

                if (cleanKey.startsWith("pk_")) {
                    reqBuilder.addHeader("Authorization", "Bearer $cleanKey")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.size > 2000) {
                            val destFile = newImageFile()
                            FileOutputStream(destFile).use { it.write(bytes) }
                            Log.d(TAG, "Image successfully saved to ${destFile.absolutePath} (${bytes.size} bytes)")
                            return@withContext destFile
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pollinations image URL $url failed: ${e.message}")
            }
        }

        Log.e(TAG, "All Pollinations image generation endpoints failed")
        null
    }

    /**
     * Generate a video clip using Pollinations AI models (veo, wan, seedance-pro, etc.)
     */
    suspend fun generateVideo(
        prompt: String,
        model: String = "veo",
        duration: Int = 4,
        apiKey: String = ""
    ): File? = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim().ifEmpty { "a futuristic AI city in motion" }
        val encodedPrompt = URLEncoder.encode(cleanPrompt, "UTF-8").replace("+", "%20")
        val cleanKey = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

        val candidateUrls = mutableListOf<String>()

        if (cleanKey.isNotBlank()) {
            candidateUrls.add("$BASE_GEN_URL/video/$encodedPrompt?model=$model&duration=$duration&key=$cleanKey")
            candidateUrls.add("$BASE_GEN_URL/video/$encodedPrompt?model=wan&duration=$duration&key=$cleanKey")
        }

        candidateUrls.add("$BASE_IMAGE_URL/prompt/$encodedPrompt?model=$model&duration=$duration&nologo=true")
        candidateUrls.add("$BASE_IMAGE_URL/prompt/$encodedPrompt?model=veo&duration=$duration&nologo=true")

        for (url in candidateUrls) {
            try {
                Log.d(TAG, "Fetching video from Pollinations URL: $url")
                val reqBuilder = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Android; JARVIS MediaGenSkill)")
                    .get()

                if (cleanKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $cleanKey")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.size > 5000) {
                            val destFile = newVideoFile()
                            FileOutputStream(destFile).use { it.write(bytes) }
                            Log.d(TAG, "Video successfully saved to ${destFile.absolutePath} (${bytes.size} bytes)")
                            return@withContext destFile
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pollinations video URL $url failed: ${e.message}")
            }
        }

        Log.e(TAG, "All Pollinations video generation endpoints failed")
        null
    }
}
