package com.example.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Command queue dispatcher for browser automation tasks in JARVIS.
 * Receives JSON or Command objects from WebSocket JSON-RPC / Voice / Local pipeline,
 * executes them via JarvisBrowserController, and formats responses for JARVIS memory/NexusBrain.
 */
class JarvisBrowserCommandHandler(private val context: Context) {

    private val browserController = JarvisBrowserController(context.applicationContext)

    suspend fun init(): Boolean {
        return browserController.init()
    }

    /**
     * Dispatcher method accepting command types like:
     * - "browser.navigate" -> { "url": "https://wikipedia.org" }
     * - "browser.click" -> { "selector": "#searchButton" }
     * - "browser.fill" -> { "selector": "#searchInput", "text": "Quantum Computing" }
     * - "browser.extract" -> { "selector": "article" }
     * - "browser.pipeline" -> { "actions": [ ... ] }
     */
    suspend fun handleCommand(commandType: String, paramsJsonStr: String): String = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject(paramsJsonStr)
            val resultPayload: BrowserResultPayload = when (commandType.lowercase()) {
                "browser.navigate", "navigate" -> {
                    val url = params.optString("url", "https://google.com")
                    val timeout = params.optLong("timeoutMs", 15000L)
                    browserController.navigate(url, timeout).getOrElse {
                        BrowserResultPayload(success = false, action = "NAVIGATE", error = it.localizedMessage)
                    }
                }

                "search.web", "web_search", "tavily.search" -> {
                    val query = params.optString("query", params.optString("text", ""))
                    val apiKey = params.optString("apiKey", "").ifBlank {
                        com.example.websearch.ApiKeyStore(context).getTavilyKey() ?: ""
                    }
                    if (apiKey.isBlank()) {
                        BrowserResultPayload(success = false, action = "SEARCH_WEB", error = "No Tavily API key configured. Please enter it in API Settings.")
                    } else {
                        try {
                            val tavily = com.example.websearch.TavilyClient(apiKey)
                            val searchRes = tavily.search(query)
                            BrowserResultPayload(
                                success = true,
                                action = "SEARCH_WEB",
                                extractedText = searchRes.asContextBlock()
                            )
                        } catch (e: Exception) {
                            BrowserResultPayload(success = false, action = "SEARCH_WEB", error = e.localizedMessage ?: "Search failed")
                        }
                    }
                }

                "browser.click", "click" -> {
                    val selector = params.optString("selector", "body")
                    val timeout = params.optLong("timeoutMs", 8000L)
                    browserController.click(selector, timeout).getOrElse {
                        BrowserResultPayload(success = false, action = "CLICK", error = it.localizedMessage)
                    }
                }

                "browser.fill", "fill" -> {
                    val selector = params.optString("selector", "input")
                    val text = params.optString("text", "")
                    val isConfirmed = params.optBoolean("isUserConfirmedSensitive", false)
                    browserController.fill(selector, text, isConfirmed).getOrElse {
                        BrowserResultPayload(success = false, action = "FILL", error = it.localizedMessage)
                    }
                }

                "browser.submit", "submit", "browser.submitform" -> {
                    val selector = params.optString("selector", "form")
                    browserController.submitForm(selector).getOrElse {
                        BrowserResultPayload(success = false, action = "SUBMIT_FORM", error = it.localizedMessage)
                    }
                }

                "browser.extract", "extract", "browser.extracttext" -> {
                    val selector = params.optString("selector", null)
                    browserController.extractText(if (selector.isNull_or_blank()) null else selector).getOrElse {
                        BrowserResultPayload(success = false, action = "EXTRACT_TEXT", error = it.localizedMessage)
                    }
                }

                "browser.extracthtml" -> {
                    val selector = params.optString("selector", null)
                    browserController.extractHtml(if (selector.isNull_or_blank()) null else selector).getOrElse {
                        BrowserResultPayload(success = false, action = "EXTRACT_HTML", error = it.localizedMessage)
                    }
                }

                "browser.wait", "waitforselector" -> {
                    val selector = params.optString("selector", "body")
                    val timeout = params.optLong("timeoutMs", 8000L)
                    browserController.waitForSelector(selector, timeout).getOrElse {
                        BrowserResultPayload(success = false, action = "WAIT_FOR_SELECTOR", error = it.localizedMessage)
                    }
                }

                "browser.screenshot", "screenshot" -> {
                    browserController.screenshot().getOrElse {
                        BrowserResultPayload(success = false, action = "SCREENSHOT", error = it.localizedMessage)
                    }
                }

                "browser.pipeline", "pipeline" -> {
                    val actionsArray = params.optJSONArray("actions") ?: JSONArray()
                    val payloadList = mutableListOf<BrowserActionPayload>()
                    for (i in 0 until actionsArray.length()) {
                        val itemObj = actionsArray.getJSONObject(i)
                        payloadList.add(BrowserActionPayload.fromJson(itemObj.toString()))
                    }

                    val results = browserController.executePipeline(payloadList)
                    val jsonResults = JSONArray()
                    results.forEach { jsonResults.put(it.toJson()) }

                    return@withContext JSONObject().apply {
                        put("success", results.all { it.success })
                        put("resultsCount", results.size)
                        put("pipelineResults", jsonResults)
                    }.toString()
                }

                else -> {
                    BrowserResultPayload(
                        success = false,
                        action = commandType,
                        error = "Unknown browser command type: $commandType"
                    )
                }
            }

            return@withContext resultPayload.toJson().toString()
        } catch (e: Exception) {
            return@withContext JSONObject().apply {
                put("success", false)
                put("action", commandType)
                put("error", e.localizedMessage ?: "Command execution error")
            }.toString()
        }
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty() || this == "null"
    }
}
