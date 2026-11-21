package com.example.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Android thin client for the JARVIS BrowserAgentService (browser-use backend).
 *
 * browser-use runs Python + Playwright/Chromium on the backend host - it can
 * never run on-device. The app only submits a task string and reads back
 * streaming status/result. Requires the same bearer token the app already uses
 * against the JARVIS backend.
 */
object BrowserAgentClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(TraceparentInterceptor())
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class SubmitResponse(val task_id: String? = null, val error: String? = null)

    @Serializable
    data class TaskStatus(
        val task_id: String? = null,
        val status: String? = null,
        val result: String? = null,
        val error: String? = null,
        val steps: List<StepEvent> = emptyList()
    )

    @Serializable
    data class StepEvent(
        val type: String? = null,
        val summary: String? = null,
        val screenshot: String? = null,
        val action: String? = null,
        val payload: String? = null
    )

    suspend fun submitTask(baseUrl: String, task: String, bearerToken: String?): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            if (cleanUrl.isBlank()) return@withContext Pair(null, "Browser backend URL not configured.")
            return@withContext try {
                val body = json.encodeToString(SubmitPayload.serializer(), SubmitPayload(task = task))
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$cleanUrl/browser/tasks")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .apply { if (!bearerToken.isNullOrBlank()) addHeader("Authorization", "Bearer $bearerToken") }
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        val parsed = json.decodeFromString(SubmitResponse.serializer(), raw)
                        Pair(parsed.task_id, parsed.error)
                    } else {
                        Pair(null, "HTTP ${resp.code}: ${raw.take(300)}")
                    }
                }
            } catch (e: Exception) {
                Pair(null, e.localizedMessage ?: e.message ?: "connection failed")
            }
        }

    suspend fun getStatus(baseUrl: String, taskId: String, bearerToken: String?): TaskStatus? =
        withContext(Dispatchers.IO) {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            return@withContext try {
                val req = Request.Builder()
                    .url("$cleanUrl/browser/tasks/$taskId")
                    .get()
                    .apply { if (!bearerToken.isNullOrBlank()) addHeader("Authorization", "Bearer $bearerToken") }
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val raw = resp.body?.string() ?: ""
                        if (raw.isBlank()) null else json.decodeFromString(TaskStatus.serializer(), raw)
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }

    suspend fun cancel(baseUrl: String, taskId: String, bearerToken: String?): Boolean =
        withContext(Dispatchers.IO) {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            return@withContext try {
                val req = Request.Builder()
                    .url("$cleanUrl/browser/tasks/$taskId/cancel")
                    .post(ByteArray(0).toRequestBody(null))
                    .apply { if (!bearerToken.isNullOrBlank()) addHeader("Authorization", "Bearer $bearerToken") }
                    .build()
                okHttpClient.newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }

    @Serializable
    private data class SubmitPayload(val task: String)
}