package com.example.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiSystemInstruction(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Float? = 0.7f,
    val maxOutputTokens: Int? = 2048
)

@Serializable
data class GeminiGenerateRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiSystemInstruction? = null,
    val generationConfig: GeminiGenerationConfig? = GeminiGenerationConfig()
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)

@Serializable
data class GeminiGenerateResponse(
    val candidates: List<GeminiCandidate>? = null
)

interface GeminiApiService {
    @POST
    suspend fun generateContent(
        @Url url: String,
        @Query("key") apiKey: String,
        @Body request: GeminiGenerateRequest
    ): GeminiGenerateResponse
}

object GeminiApiClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}

suspend fun callGeminiApi(
    apiKey: String,
    modelName: String,
    history: List<com.example.data.ChatMessage>,
    currentPrompt: String,
    systemPrompt: String? = null,
    imageDataBase64: String? = null
): String {
    val cleanKey = apiKey.trim()
    if (cleanKey.isBlank()) {
        return "Gemini API Key is missing. Please enter your Gemini API Key in Settings."
    }

    var cleanModel = modelName.trim().ifEmpty { "gemini-2.5-flash" }
    if (cleanModel == "gemini-1.5-flash" || cleanModel == "gemini-1.5-pro") {
        cleanModel = "gemini-2.5-flash" // Upgrade to newest high performance Gemini model
    }

    val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"

    val contents = mutableListOf<GeminiContent>()

    // Filter recent conversation history (last 24 messages for deep chat memory)
    for (msg in history.takeLast(24)) {
        val rawText = msg.text.trim()
        val cleanText = rawText
            .replace(Regex("\\[APP_LAUNCH:.*?\\]"), "")
            .replace(Regex("\\[SYSTEM_ACTION:.*?\\]"), "")
            .replace(Regex("\\[WEB_SEARCH:.*?\\]"), "")
            .trim()

        if (cleanText.isNotBlank() && !cleanText.startsWith("Error calling") && !cleanText.startsWith("Gemini Error") && !cleanText.startsWith("HTTP ")) {
            val role = if (msg.sender == "user") "user" else "model"
            val textWithContext = if (!msg.fileUri.isNullOrBlank()) {
                "[System Note: AI generated and displayed image '${msg.fileName ?: "Image"}' to user] $cleanText"
            } else {
                cleanText
            }
            contents.add(GeminiContent(role = role, parts = listOf(GeminiPart(text = textWithContext))))
        }
    }

    val promptText = currentPrompt.trim().ifEmpty { "Analyze this image and request." }
    val userParts = mutableListOf<GeminiPart>()
    if (!imageDataBase64.isNullOrBlank()) {
        userParts.add(
            GeminiPart(
                inlineData = GeminiInlineData(
                    mimeType = "image/jpeg",
                    data = imageDataBase64.trim()
                )
            )
        )
    }
    userParts.add(GeminiPart(text = promptText))
    contents.add(GeminiContent(role = "user", parts = userParts))

    val sysInstruction = if (!systemPrompt.isNullOrBlank()) {
        GeminiSystemInstruction(parts = listOf(GeminiPart(text = systemPrompt.trim())))
    } else null

    val request = GeminiGenerateRequest(
        contents = contents,
        systemInstruction = sysInstruction,
        generationConfig = GeminiGenerationConfig(temperature = 0.7f, maxOutputTokens = 2048)
    )

    return try {
        val response = GeminiApiClient.service.generateContent(
            url = url,
            apiKey = cleanKey,
            request = request
        )
        val reply = response.candidates?.firstOrNull()?.content?.parts?.mapNotNull { it.text }?.joinToString("\n")
        if (!reply.isNullOrBlank()) {
            reply
        } else {
            "Received empty response from Gemini AI."
        }
    } catch (e: retrofit2.HttpException) {
        val code = e.code()
        val errorBodyStr = try {
            e.response()?.errorBody()?.string() ?: ""
        } catch (ex: Exception) {
            ""
        }
        val detailMsg = if (errorBodyStr.isNotBlank()) errorBodyStr else e.message()
        "Gemini Error (HTTP $code): $detailMsg"
    } catch (e: Exception) {
        "Error calling Gemini AI: ${e.localizedMessage ?: e.message}"
    }
}
