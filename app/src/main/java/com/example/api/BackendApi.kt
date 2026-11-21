package com.example.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Serializable
data class BackendChatRequest(
    val message: String,
    val session_id: String,
    val history: List<BackendMessageItem> = emptyList(),
    val personality: String? = null,
    val user_name: String? = null,
    val language: String? = null,
    val image_base64: String? = null
)

@Serializable
data class BackendMessageItem(
    val sender: String,
    val text: String
)

@Serializable
data class BackendChatResponse(
    val response: String? = null,
    val message: String? = null,
    val text: String? = null,
    val reply: String? = null,
    val content: String? = null,
    val output: String? = null,
    val status: String? = null
)

object BackendClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(TraceparentInterceptor())
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun testConnection(baseUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) return@withContext Pair(false, "Base URL is empty. Please enter your backend URL.")

        val startTime = System.currentTimeMillis()
        val testEndpoints = listOf("$cleanUrl/health", "$cleanUrl/ping", "$cleanUrl/", "$cleanUrl/api/health")

        var lastError = "Connection attempt failed."
        for (endpoint in testEndpoints) {
            try {
                val request = Request.Builder().url(endpoint).get().build()
                val response = okHttpClient.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    return@withContext Pair(true, "Connected! (${elapsed}ms, HTTP ${response.code})")
                } else {
                    lastError = "Backend responded with HTTP ${response.code} at $endpoint"
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: e.message ?: "Network unreachable"
            }
        }
        Pair(false, lastError)
    }

    suspend fun callJarvisBackend(
        baseUrl: String,
        sessionId: String,
        history: List<com.example.data.ChatMessage>,
        currentPrompt: String,
        personality: String? = null,
        userName: String? = null,
        language: String? = null,
        imageBase64: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) {
            return@withContext "JARVIS Backend Base URL is not configured. Please enter your server endpoint in Settings."
        }

        val historyItems = history.takeLast(12).map {
            BackendMessageItem(
                sender = if (it.sender == "user") "user" else "assistant",
                text = it.text.take(2000)
            )
        }

        val requestData = BackendChatRequest(
            message = currentPrompt,
            session_id = sessionId,
            history = historyItems,
            personality = personality,
            user_name = userName,
            language = language,
            image_base64 = imageBase64
        )

        val jsonString = json.encodeToString(BackendChatRequest.serializer(), requestData)
        val body = jsonString.toRequestBody("application/json".toMediaType())

        // Determine target URL endpoints to attempt (supports custom /chat, /api/chat, or /v1/chat/completions)
        val endpointsToTry = when {
            cleanUrl.endsWith("/chat") || cleanUrl.endsWith("/chat/completions") -> listOf(cleanUrl)
            else -> listOf(
                "$cleanUrl/chat",
                "$cleanUrl/api/chat",
                "$cleanUrl/v1/chat/completions",
                cleanUrl
            )
        }

        var lastResponseCode = 0
        var lastErrorMsg = ""

        for (targetUrl in endpointsToTry) {
            val request = Request.Builder()
                .url(targetUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "JARVIS-Android-Client/2.0")
                .build()

            try {
                val response = okHttpClient.newCall(request).execute()
                lastResponseCode = response.code
                val responseBodyStr = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val parsedResult = extractResponseText(responseBodyStr)
                    if (parsedResult.isNotBlank()) {
                        return@withContext parsedResult
                    }
                } else if (lastResponseCode != 404) {
                    // Non-404 error (e.g. 500, 401, 403, 422) means endpoint was found but threw error
                    return@withContext "JARVIS Backend Error (HTTP $lastResponseCode): ${responseBodyStr.ifBlank { response.message }}"
                } else {
                    lastErrorMsg = "HTTP 404 at $targetUrl"
                }
            } catch (e: Exception) {
                lastErrorMsg = e.localizedMessage ?: e.message ?: "Connection failed"
            }
        }

        "Error connecting to JARVIS Backend at $cleanUrl ($lastErrorMsg)"
    }

    private fun extractResponseText(rawJson: String): String {
        if (rawJson.isBlank()) return ""
        return try {
            val element = json.parseToJsonElement(rawJson)
            if (element is JsonObject) {
                // 1. Direct field match
                element["response"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: element["reply"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: element["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: element["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: element["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: element["output"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    // 2. OpenAI / Compatible choices format
                    ?: element["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: rawJson
            } else {
                rawJson
            }
        } catch (e: Exception) {
            rawJson
        }
    }
}
