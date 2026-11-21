package com.example.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object PollinationsImageGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateImageBase64(prompt: String, apiKey: String = ""): String? = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim().ifEmpty { "a beautiful dog" }
        val encodedPrompt = java.net.URLEncoder.encode(cleanPrompt, "UTF-8").replace("+", "%20")
        val cleanKey = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        val seed = System.currentTimeMillis()

        val pollinationsUrls = mutableListOf<String>()

        if (cleanKey.startsWith("pk_")) {
            pollinationsUrls.add("https://gen.pollinations.ai/image/$encodedPrompt?model=flux&width=1024&height=1024&key=$cleanKey")
            pollinationsUrls.add("https://gen.pollinations.ai/image/$encodedPrompt?model=zimage&width=1024&height=1024&key=$cleanKey")
        }

        pollinationsUrls.add("https://image.pollinations.ai/prompt/$encodedPrompt?width=768&height=768&seed=$seed&nologo=true")
        pollinationsUrls.add("https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&seed=$seed&nologo=true&model=flux")
        pollinationsUrls.add("https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&seed=$seed&nologo=true&model=turbo")
        pollinationsUrls.add("https://image.pollinations.ai/prompt/$encodedPrompt?nologo=true")

        for (pUrl in pollinationsUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(pUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .get()

                if (cleanKey.startsWith("pk_")) {
                    reqBuilder.addHeader("Authorization", "Bearer $cleanKey")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val imageBytes = response.body?.bytes()
                        if (imageBytes != null && imageBytes.size > 1000) {
                            return@withContext Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return@withContext null
    }

    suspend fun generateImage(prompt: String, apiKey: String = ""): Bitmap? = withContext(Dispatchers.IO) {
        val base64Str = generateImageBase64(prompt, apiKey)
        if (base64Str != null) {
            try {
                val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
                return@withContext BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        null
    }
}
