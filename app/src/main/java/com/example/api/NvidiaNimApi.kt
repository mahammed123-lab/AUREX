package com.example.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

@Serializable
data class NimMessage(
    val role: String,
    val content: JsonElement
)

@Serializable
data class NimChoiceMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class NimChoice(
    val message: NimChoiceMessage? = null
)

@Serializable
data class NimChatCompletionRequest(
    val model: String,
    val messages: List<NimMessage>,
    val temperature: Float? = null,
    val max_tokens: Int? = null
)

@Serializable
data class NimChatCompletionResponse(
    val choices: List<NimChoice>? = null
)

interface NvidiaNimApiService {
    @Headers("Content-Type: application/json", "Accept: application/json")
    @POST
    suspend fun createChatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: NimChatCompletionRequest
    ): NimChatCompletionResponse
}

object NvidiaNimClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val service: NvidiaNimApiService by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl("https://integrate.api.nvidia.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(NvidiaNimApiService::class.java)
    }
}

fun compressAndDownscaleBase64Image(base64Str: String, maxDim: Int = 1280, maxQuality: Int = 80): String {
    return try {
        val bytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return base64Str
        if (bitmap.width <= maxDim && bitmap.height <= maxDim && bytes.size < 1_500_000) {
            return base64Str
        }
        val scale = Math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        val targetWidth = (bitmap.width * Math.min(1.0f, scale)).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * Math.min(1.0f, scale)).toInt().coerceAtLeast(1)
        val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val outputStream = java.io.ByteArrayOutputStream()
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, maxQuality, outputStream)
        android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        base64Str
    }
}

suspend fun callNvidiaNimApi(
    apiKey: String,
    baseUrl: String,
    modelName: String,
    history: List<com.example.data.ChatMessage>,
    currentPrompt: String,
    systemPrompt: String? = null,
    imageBase64Data: String? = null
): String {
    val cleanKey = apiKey.trim()
    val cleanBaseUrl = baseUrl.trim().ifEmpty { "https://integrate.api.nvidia.com/v1/" }
    val fullUrl = when {
        cleanBaseUrl.contains("/chat/completions") -> cleanBaseUrl
        cleanBaseUrl.endsWith("/") -> "${cleanBaseUrl}chat/completions"
        else -> "$cleanBaseUrl/chat/completions"
    }

    val cleanModel = modelName.trim().ifEmpty { "meta/llama-3.1-8b-instruct" }

    val messages = mutableListOf<NimMessage>()
    if (!systemPrompt.isNullOrBlank()) {
        messages.add(NimMessage(role = "system", content = JsonPrimitive(systemPrompt.trim())))
    }

    // Filter and sanitize recent history (last 24 messages for deep chat memory)
    for (msg in history.takeLast(24)) {
        val rawText = msg.text.trim()
        val cleanText = rawText
            .replace(Regex("\\[APP_LAUNCH:.*?\\]"), "")
            .replace(Regex("\\[SYSTEM_ACTION:.*?\\]"), "")
            .replace(Regex("\\[WEB_SEARCH:.*?\\]"), "")
            .trim()

        if (cleanText.isNotBlank() && !cleanText.startsWith("Error calling") && !cleanText.startsWith("NVIDIA NIM Error") && !cleanText.startsWith("HTTP ")) {
            val role = if (msg.sender == "user") "user" else "assistant"
            val textWithContext = if (!msg.fileUri.isNullOrBlank()) {
                "[System Note: AI generated and displayed image '${msg.fileName ?: "Image"}' to user] $cleanText"
            } else {
                cleanText
            }
            messages.add(NimMessage(role = role, content = JsonPrimitive(textWithContext)))
        }
    }

    val promptText = currentPrompt.trim().ifEmpty { "Analyze this image" }

    if (!imageBase64Data.isNullOrBlank()) {
        val processedBase64 = compressAndDownscaleBase64Image(imageBase64Data)
        val imageUrl = if (processedBase64.startsWith("data:")) processedBase64 else "data:image/jpeg;base64,$processedBase64"
        val multimodalContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", promptText)
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", imageUrl)
                })
            })
        }
        messages.add(NimMessage(role = "user", content = multimodalContent))
    } else {
        messages.add(NimMessage(role = "user", content = JsonPrimitive(promptText)))
    }

    val request = NimChatCompletionRequest(
        model = cleanModel,
        messages = messages,
        temperature = 0.7f,
        max_tokens = 1024
    )

    return try {
        val authHeader = if (cleanKey.startsWith("Bearer ", ignoreCase = true)) cleanKey else "Bearer $cleanKey"
        val response = NvidiaNimClient.service.createChatCompletion(
            url = fullUrl,
            authorization = authHeader,
            request = request
        )
        response.choices?.firstOrNull()?.message?.content
            ?: "Empty response from NVIDIA NIM."
    } catch (e: retrofit2.HttpException) {
        val code = e.code()
        val errorBodyStr = try {
            e.response()?.errorBody()?.string() ?: ""
        } catch (ex: Exception) {
            ""
        }
        val detailMsg = if (errorBodyStr.isNotBlank()) errorBodyStr else e.message()
        "NVIDIA NIM Error (HTTP $code): $detailMsg"
    } catch (e: Exception) {
        "Error calling NVIDIA NIM: ${e.localizedMessage ?: e.message}"
    }
}
