package com.example.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class N8nWebhookResponse(
    val success: Boolean,
    val statusCode: Int,
    val responseBody: String,
    val errorMessage: String? = null
)

object N8nClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(TraceparentInterceptor())
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun triggerWebhook(
        webhookUrl: String,
        eventName: String,
        payloadData: Map<String, Any>,
        apiKey: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): N8nWebhookResponse = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            return@withContext N8nWebhookResponse(
                success = false,
                statusCode = 0,
                responseBody = "",
                errorMessage = "n8n Webhook URL is not configured. Please enter your n8n URL in Automations."
            )
        }

        val json = JSONObject().apply {
            put("event", eventName)
            put("source", "JARVIS_ANDROID_ASSISTANT")
            put("timestamp", System.currentTimeMillis())
            val dataObj = JSONObject()
            payloadData.forEach { (key, value) ->
                dataObj.put(key, value)
            }
            put("data", dataObj)
        }

        val requestBuilder = Request.Builder()
            .url(webhookUrl.trim())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", "JARVIS-Android-n8n-Client/1.0")

        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("X-N8N-API-KEY", apiKey.trim())
            if (!apiKey.trim().startsWith("Bearer ", ignoreCase = true)) {
                requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            } else {
                requestBuilder.header("Authorization", apiKey.trim())
            }
        }

        customHeaders.forEach { (hName, hValue) ->
            if (hName.isNotBlank() && hValue.isNotBlank()) {
                requestBuilder.header(hName.trim(), hValue.trim())
            }
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val bodyStr = response.body?.string() ?: ""
            val isOk = response.isSuccessful
            N8nWebhookResponse(
                success = isOk,
                statusCode = response.code,
                responseBody = bodyStr,
                errorMessage = if (isOk) null else "n8n returned HTTP ${response.code}: $bodyStr"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            N8nWebhookResponse(
                success = false,
                statusCode = 0,
                responseBody = "",
                errorMessage = "Failed to connect to n8n webhook: ${e.localizedMessage}"
            )
        }
    }
}
