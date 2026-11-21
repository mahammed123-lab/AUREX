package com.example.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Generic OpenAI-compatible chat client. Point it at ANY provider AUREX user
 * configures: OpenAI, OpenRouter, Groq, NVIDIA NIM, Together, vLLM, Ollama,
 * LM Studio, or the user's own OpenAI-compatible server. No provider SDK needed.
 *
 * The Android app never stores credentials in code — the key comes from the
 * user's saved configuration at runtime.
 */
@Serializable
data class CustomProvider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val extraHeaders: String = ""   // comma separated "Name: Value, Name2: Value2"
)

object CustomProviderClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(TraceparentInterceptor())
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun complete(
        provider: CustomProvider,
        messages: List<Pair<String, String>>,   // (role, content)
        systemPrompt: String? = null,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.IO) {
        val cleanBase = provider.baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) return@withContext "No base URL configured for '${provider.name}'."
        if (provider.model.isBlank()) return@withContext "No model configured for '${provider.name}'."

        val arr = buildJsonObject {}.let { _ ->
            val list = mutableListOf<JsonObject>()
            if (!systemPrompt.isNullOrBlank()) {
                list.add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt.trim())
                })
            }
            messages.forEach { (role, content) ->
                list.add(buildJsonObject {
                    put("role", if (role == "ai" || role == "assistant" || role == "model") "assistant" else "user")
                    put("content", content)
                })
            }
            list
        }

        val body = buildJsonObject {
            put("model", provider.model)
            put("messages", kotlinx.serialization.json.JsonArray(arr))
            put("temperature", temperature)
            put("stream", false)
        }.toString()

        val endpoint = if (cleanBase.endsWith("/chat/completions")) cleanBase
        else if (cleanBase.endsWith("/v1")) "$cleanBase/chat/completions"
        else "$cleanBase/v1/chat/completions"

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "AUREX-CustomProvider")

        if (provider.apiKey.isNotBlank()) {
            val cleaned = provider.apiKey.trim()
            if (cleaned.startsWith("Bearer ", ignoreCase = true)) {
                requestBuilder.addHeader("Authorization", cleaned)
            } else {
                requestBuilder.addHeader("Authorization", "Bearer $cleaned")
            }
        }

        provider.extraHeaders.split(",").forEach { pair ->
            val parts = pair.split(":")
            if (parts.size >= 2) {
                requestBuilder.addHeader(parts[0].trim(), parts.drop(1).joinToString(":").trim())
            }
        }

        return@withContext try {
            client.newCall(requestBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    "Custom provider '${provider.name}' HTTP ${resp.code}: ${raw.take(300)}"
                } else {
                    parseChoices(raw)
                }
            }
        } catch (e: Exception) {
            "Error calling '${provider.name}': ${e.localizedMessage ?: e.message}"
        }
    }

    private fun parseChoices(raw: String): String {
        return try {
            val el = json.parseToJsonElement(raw)
            val obj = el as? JsonObject
            val choices = obj?.get("choices")?.jsonArray
            val first = choices?.firstOrNull()?.jsonObject
            val text = first?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: first?.get("text")?.jsonPrimitive?.content
                ?: obj?.get("output")?.jsonPrimitive?.content
                ?: obj?.get("response")?.jsonPrimitive?.content
                ?: raw
            text
        } catch (e: Exception) {
            raw
        }
    }
}