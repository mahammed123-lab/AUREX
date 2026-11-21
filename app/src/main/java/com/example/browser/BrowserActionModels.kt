package com.example.browser

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Supported browser automation action types in JARVIS.
 */
enum class BrowserActionType {
    NAVIGATE,
    CLICK,
    FILL,
    SUBMIT_FORM,
    EXTRACT_TEXT,
    EXTRACT_HTML,
    WAIT_FOR_SELECTOR,
    SCROLL_TO,
    SCROLL_BY,
    GO_BACK,
    GO_FORWARD,
    RELOAD,
    SCREENSHOT,
    CLEAR_STORAGE
}

/**
 * Incoming command payload for a browser action.
 */
data class BrowserActionPayload(
    val actionType: BrowserActionType,
    val url: String? = null,
    val selector: String? = null,
    val text: String? = null,
    val dx: Int = 0,
    val dy: Int = 0,
    val timeoutMs: Long = 10000L,
    val isUserConfirmedSensitive: Boolean = false
) {
    companion object {
        fun fromJson(jsonStr: String): BrowserActionPayload {
            val obj = JSONObject(jsonStr)
            val actionName = obj.optString("action", "NAVIGATE").uppercase()
            val type = try {
                BrowserActionType.valueOf(actionName)
            } catch (e: Exception) {
                BrowserActionType.NAVIGATE
            }
            return BrowserActionPayload(
                actionType = type,
                url = obj.optString("url", null),
                selector = obj.optString("selector", null),
                text = obj.optString("text", null),
                dx = obj.optInt("dx", 0),
                dy = obj.optInt("dy", 0),
                timeoutMs = obj.optLong("timeoutMs", 10000L),
                isUserConfirmedSensitive = obj.optBoolean("isUserConfirmedSensitive", false)
            )
        }
    }
}

/**
 * Structured result returned by every browser operation.
 */
data class BrowserResultPayload(
    val success: Boolean,
    val action: String,
    val currentUrl: String? = null,
    val pageTitle: String? = null,
    val extractedText: String? = null,
    val extractedHtml: String? = null,
    val screenshotBase64: String? = null,
    val isSensitiveAction: Boolean = false,
    val needsUserConfirmation: Boolean = false,
    val error: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("action", action)
            put("currentUrl", currentUrl ?: "")
            put("pageTitle", pageTitle ?: "")
            put("extractedText", extractedText ?: "")
            put("extractedHtml", extractedHtml ?: "")
            put("screenshotBase64", screenshotBase64 ?: "")
            put("isSensitiveAction", isSensitiveAction)
            put("needsUserConfirmation", needsUserConfirmation)
            put("error", error ?: "")
            put("timestampMs", timestampMs)
        }
    }
}

/**
 * Configuration rules for browser security & domains.
 */
data class BrowserSecurityConfig(
    val allowedDomains: Set<String> = emptySet(), // empty means all allowed
    val blockedDomains: Set<String> = setOf("malicious-site.test", "phishing-example.com"),
    val requireConfirmationForPasswords: Boolean = true,
    val allowFileAccess: Boolean = false
) {
    fun isDomainAllowed(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host?.lowercase() ?: return false
            if (blockedDomains.any { host.contains(it) }) return false
            if (allowedDomains.isNotEmpty()) {
                allowedDomains.any { host.endsWith(it) }
            } else true
        } catch (e: Exception) {
            false
        }
    }
}
