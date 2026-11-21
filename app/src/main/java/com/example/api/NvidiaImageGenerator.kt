package com.example.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object NvidiaImageGenerator {

    private const val TAG = "NvidiaImageGen"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val endpoints = listOf(
        "https://ai.api.nvidia.com/v1/genai/stabilityai/stable-diffusion-3.5-large",
        "https://ai.api.nvidia.com/v1/genai/stabilityai/stable-diffusion-3-medium"
    )

    suspend fun generateImageBase64FromNvidia(prompt: String, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            val cleanKey = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
            if (cleanKey.isBlank()) {
                Log.w(TAG, "NVIDIA API key is blank - skipping NVIDIA NIM image generation")
                return@withContext null
            }

            for (endpoint in endpoints) {
                try {
                    Log.d(TAG, "Requesting NVIDIA NIM image generation @ $endpoint")
                    val payload = JSONObject().apply {
                        put("prompt", prompt)
                        put("negative_prompt", "")
                        put("cfg_scale", 4.5)
                        put("aspect_ratio", "1:1")
                        put("seed", System.currentTimeMillis() % Int.MAX_VALUE)
                        put("steps", 28)
                        put("mode", "base")
                    }
                    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(body)
                        .addHeader("Authorization", "Bearer $cleanKey")
                        .addHeader("Accept", "application/json")
                        .build()

                    client.newCall(request).execute().use { response ->
                        val raw = response.body?.string()
                        if (!response.isSuccessful) {
                            Log.w(TAG, "NVIDIA NIM HTTP ${response.code} @ $endpoint: ${raw?.take(300)}")
                            return@use
                        }
                        if (raw.isNullOrBlank()) return@use
                        val data = JSONObject(raw)
                        val b64 = data.optJSONArray("artifacts")
                            ?.optJSONObject(0)
                            ?.optString("base64")
                            .orEmpty()
                        if (b64.isBlank()) {
                            Log.w(TAG, "NVIDIA NIM response had no artifacts.image @ $endpoint")
                            return@use
                        }
                        Log.d(TAG, "NVIDIA NIM image generated successfully (${b64.length} b64 chars)")
                        return@withContext b64.removePrefix("data:image/png;base64,").removePrefix("data:image/jpeg;base64,")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NVIDIA NIM generation failed @ $endpoint: ${e.message}")
                }
            }
            null
        }

    suspend fun generateImageFromNvidia(prompt: String, apiKey: String): Bitmap? {
        val b64 = generateImageBase64FromNvidia(prompt, apiKey) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode NVIDIA image bytes: ${e.message}")
                null
            }
        }
    }
}
