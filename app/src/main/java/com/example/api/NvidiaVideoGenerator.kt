package com.example.api

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GeneratedVideoResult(
    val videoUri: String,
    val thumbnailBase64: String?,
    val fileName: String,
    val modelUsed: String
)

object NvidiaVideoGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun generateVideo(prompt: String, apiKey: String, context: Context): GeneratedVideoResult? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

        // 1. Try NVIDIA Stable Video Diffusion / Cosmos endpoints if key provided
        if (cleanKey.isNotBlank()) {
            try {
                // First get an initial frame
                val base64Img = NvidiaImageGenerator.generateImageBase64FromNvidia(prompt, cleanKey)
                if (base64Img != null) {
                    val url = "https://ai.api.nvidia.com/v1/genai/stabilityai/stable-video-diffusion"
                    val jsonPayload = JSONObject().apply {
                        put("image", "data:image/jpeg;base64,$base64Img")
                        put("seed", System.currentTimeMillis() % 10000)
                        put("cfg_scale", 2.5)
                        put("motion_bucket_id", 127)
                    }

                    val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Authorization", "Bearer $cleanKey")
                        .addHeader("Accept", "application/json")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string()
                            if (!responseBody.isNullOrBlank()) {
                                val jsonResponse = JSONObject(responseBody)
                                val base64Video = jsonResponse.optString("video")
                                    .ifEmpty { jsonResponse.optJSONArray("artifacts")?.optJSONObject(0)?.optString("base64") ?: "" }
                                
                                if (base64Video.isNotBlank()) {
                                    val videoFile = File(context.cacheDir, "nvidia_video_${System.currentTimeMillis()}.mp4")
                                    val videoBytes = Base64.decode(base64Video, Base64.DEFAULT)
                                    FileOutputStream(videoFile).use { fos -> fos.write(videoBytes) }

                                    return@withContext GeneratedVideoResult(
                                        videoUri = videoFile.absolutePath,
                                        thumbnailBase64 = base64Img,
                                        fileName = videoFile.name,
                                        modelUsed = "NVIDIA Stable Video Diffusion 1.1"
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Honest failure: without a working video endpoint we return null
        // instead of fabricating an unplayable file. The caller shows a clear error.
        Log.w("NvidiaVideoGenerator", "Video generation unavailable - configure an NVIDIA API key (Stable Video Diffusion) or use Pollinations video models")
        return@withContext null
    }
}
