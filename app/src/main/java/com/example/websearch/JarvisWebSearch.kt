package com.example.websearch

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class WebSearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SearchResultItem(
    val title: String,
    val url: String,
    val content: String,
    val score: Double = 0.0
)

data class WebSearchResult(
    val query: String,
    val answer: String?,
    val results: List<SearchResultItem>
) {
    /** Formats results as plain text ready to feed back into a model prompt. */
    fun asContextBlock(maxResults: Int = 5): String {
        val lines = mutableListOf<String>()
        answer?.let { if (it.isNotBlank()) lines.add("Quick answer: $it\n") }
        results.take(maxResults).forEachIndexed { i, r ->
            lines.add("[${i + 1}] ${r.title} — ${r.url}\n${r.content}\n")
        }
        return if (lines.isEmpty()) "No results found." else lines.joinToString("\n")
    }
}

enum class SearchDepth(val apiValue: String) { BASIC("basic"), ADVANCED("advanced") }
enum class SearchTopic(val apiValue: String) { GENERAL("general"), NEWS("news"), FINANCE("finance") }

class TavilyClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun search(
        query: String,
        maxResults: Int = 5,
        searchDepth: SearchDepth = SearchDepth.BASIC,
        topic: SearchTopic = SearchTopic.GENERAL,
        timeRange: String? = null,
        includeAnswer: Boolean = true,
        includeDomains: List<String>? = null,
        excludeDomains: List<String>? = null
    ): WebSearchResult {
        val body = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("search_depth", searchDepth.apiValue)
            put("topic", topic.apiValue)
            put("max_results", maxResults)
            put("include_answer", includeAnswer)
            timeRange?.let { put("time_range", it) }
            includeDomains?.let { put("include_domains", JSONArray(it)) }
            excludeDomains?.let { put("exclude_domains", JSONArray(it)) }
        }

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val responseJson = executeAsync(request)
        return parseResponse(query, responseJson)
    }

    private suspend fun executeAsync(request: Request): JSONObject = suspendCoroutine { cont ->
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                cont.resumeWithException(WebSearchException("Tavily request failed: ${e.message}", e))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val bodyStr = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        cont.resumeWithException(
                            WebSearchException("Tavily API error ${it.code}: $bodyStr")
                        )
                        return
                    }
                    try {
                        cont.resume(JSONObject(bodyStr))
                    } catch (e: Exception) {
                        cont.resumeWithException(WebSearchException("Failed to parse Tavily response", e))
                    }
                }
            }
        })
    }

    private fun parseResponse(query: String, json: JSONObject): WebSearchResult {
        val resultsArray = json.optJSONArray("results") ?: JSONArray()
        val items = (0 until resultsArray.length()).map { i ->
            val r = resultsArray.getJSONObject(i)
            SearchResultItem(
                title = r.optString("title", ""),
                url = r.optString("url", ""),
                content = r.optString("content", ""),
                score = r.optDouble("score", 0.0)
            )
        }
        return WebSearchResult(
            query = query,
            answer = if (json.isNull("answer")) null else json.optString("answer", null),
            results = items
        )
    }
}

/** Android Keystore-backed storage for Tavily & NVIDIA NIM API keys */
class ApiKeyStore(context: Context) {
    private val prefs by lazy {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "jarvis_secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("jarvis_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getTavilyKey(): String? = prefs.getString("tavily_api_key", null)
    fun setTavilyKey(key: String) = prefs.edit().putString("tavily_api_key", key).apply()

    fun getNvidiaKey(): String? = prefs.getString("nvidia_api_key", null)
    fun setNvidiaKey(key: String) = prefs.edit().putString("nvidia_api_key", key).apply()
}

private const val NEEDS_SEARCH_PREFIX = "NEEDS_SEARCH:"

fun needsSearch(llmResponse: String): String? {
    val trimmed = llmResponse.trim()
    return if (trimmed.startsWith(NEEDS_SEARCH_PREFIX, ignoreCase = true)) {
        trimmed.substring(NEEDS_SEARCH_PREFIX.length).trim()
    } else null
}

suspend fun answerWithSearchFallback(
    tavilyClient: TavilyClient,
    query: String,
    llmAnswer: suspend (String) -> String
): String {
    val firstPass = llmAnswer(query)
    val searchQuery = needsSearch(firstPass) ?: return firstPass

    val result = try {
        tavilyClient.search(searchQuery)
    } catch (e: WebSearchException) {
        return "I don't have current information on this, and the web search failed: ${e.message}"
    }

    val followupPrompt = buildString {
        append("Original question: $query\n\n")
        append("Web search results:\n${result.asContextBlock()}\n\n")
        append("Using the above, answer the original question.")
    }
    return llmAnswer(followupPrompt)
}
