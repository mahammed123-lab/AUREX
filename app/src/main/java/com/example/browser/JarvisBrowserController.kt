package com.example.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * Controller class managing a headless/offscreen Android WebView instance for
 * browser automation, web page scraping, form submission, and DOM element interactions in JARVIS.
 */
class JarvisBrowserController(
    private val context: Context,
    private var securityConfig: BrowserSecurityConfig = BrowserSecurityConfig()
) {

    private var webView: WebView? = null
    private var isInitialized = false
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null
    private var offscreenContainer: FrameLayout? = null

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 JARVIS-Browser/1.0"

        private val JS_ACTION_RUNNER = """
            (function() {
                if (window.__JARVIS_BROWSER_LOADED__) return;
                window.__JARVIS_BROWSER_LOADED__ = true;

                window.__jarvisAction = {
                    click: function(selector) {
                        try {
                            var el = document.querySelector(selector);
                            if (!el) return JSON.stringify({ success: false, error: 'Element not found for selector: ' + selector });
                            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            el.click();
                            return JSON.stringify({ success: true, message: 'Clicked element ' + selector });
                        } catch (e) {
                            return JSON.stringify({ success: false, error: e.toString() });
                        }
                    },
                    fill: function(selector, text) {
                        try {
                            var el = document.querySelector(selector);
                            if (!el) return JSON.stringify({ success: false, error: 'Element not found for selector: ' + selector });
                            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            el.focus();
                            el.value = text;
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            return JSON.stringify({ success: true, message: 'Filled text into ' + selector });
                        } catch (e) {
                            return JSON.stringify({ success: false, error: e.toString() });
                        }
                    },
                    submitForm: function(selector) {
                        try {
                            var el = document.querySelector(selector);
                            if (!el) return JSON.stringify({ success: false, error: 'Form element not found for selector: ' + selector });
                            if (el.tagName.toLowerCase() === 'form') {
                                el.submit();
                            } else {
                                el.click();
                            }
                            return JSON.stringify({ success: true, message: 'Submitted form ' + selector });
                        } catch (e) {
                            return JSON.stringify({ success: false, error: e.toString() });
                        }
                    },
                    isSensitiveField: function(selector) {
                        try {
                            var el = document.querySelector(selector);
                            if (!el) return false;
                            var type = (el.getAttribute('type') || '').toLowerCase();
                            var name = (el.getAttribute('name') || '').toLowerCase();
                            var id = (el.getAttribute('id') || '').toLowerCase();
                            var auto = (el.getAttribute('autocomplete') || '').toLowerCase();
                            return type === 'password' || name.includes('password') || id.includes('password') || auto.includes('password') || auto.includes('credit-card');
                        } catch (e) {
                            return false;
                        }
                    },
                    extractText: function(selector) {
                        try {
                            var el = selector ? document.querySelector(selector) : document.body;
                            if (!el) return JSON.stringify({ success: false, error: 'Selector not found' });
                            return JSON.stringify({ success: true, text: el.innerText || el.textContent || '' });
                        } catch (e) {
                            return JSON.stringify({ success: false, error: e.toString() });
                        }
                    },
                    extractHtml: function(selector) {
                        try {
                            var el = selector ? document.querySelector(selector) : document.documentElement;
                            if (!el) return JSON.stringify({ success: false, error: 'Selector not found' });
                            return JSON.stringify({ success: true, html: el.outerHTML || '' });
                        } catch (e) {
                            return JSON.stringify({ success: false, error: e.toString() });
                        }
                    },
                    checkSelectorExists: function(selector) {
                        try {
                            var el = document.querySelector(selector);
                            return el !== null;
                        } catch (e) {
                            return false;
                        }
                    },
                    scrollTo: function(selector) {
                        try {
                            var el = document.querySelector(selector);
                            if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
                            return JSON.stringify({ success: el !== null });
                        } catch (e) {
                            return JSON.stringify({ success: false, error: e.toString() });
                        }
                    }
                };
            })();
        """.trimIndent()
    }

    /**
     * Initializes the WebView instance on the UI thread.
     */
    suspend fun init(): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized && webView != null) return@withContext true
        try {
            offscreenContainer = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(1280, 800)
                alpha = 0.01f // Offscreen rendering
            }

            webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = USER_AGENT
                    allowFileAccess = securityConfig.allowFileAccess
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Inject JS Action Runner
                        view?.evaluateJavascript(JS_ACTION_RUNNER, null)
                        pageLoadDeferred?.complete(true)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            pageLoadDeferred?.complete(false)
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {}
            }

            offscreenContainer?.addView(webView)
            isInitialized = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Navigates to a given URL and suspends until page load completes or times out.
     */
    suspend fun navigate(url: String, timeoutMs: Long = 15000L): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        init()
        val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else url

        if (!securityConfig.isDomainAllowed(formattedUrl)) {
            return@withContext Result.failure(
                SecurityException("Domain is blocked or not allowed by JARVIS security config: $formattedUrl")
            )
        }

        pageLoadDeferred = CompletableDeferred()
        webView?.loadUrl(formattedUrl)

        val loadSuccess = withTimeoutOrNull(timeoutMs) {
            pageLoadDeferred?.await()
        } ?: false

        val currentUrl = webView?.url ?: formattedUrl
        val title = webView?.title ?: ""

        if (loadSuccess) {
            Result.success(
                BrowserResultPayload(
                    success = true,
                    action = "NAVIGATE",
                    currentUrl = currentUrl,
                    pageTitle = title
                )
            )
        } else {
            Result.success(
                BrowserResultPayload(
                    success = false,
                    action = "NAVIGATE",
                    currentUrl = currentUrl,
                    pageTitle = title,
                    error = "Page load timed out after ${timeoutMs}ms"
                )
            )
        }
    }

    /**
     * Clicks an element matching the given CSS selector.
     */
    suspend fun click(selector: String, timeoutMs: Long = 8000L): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val safeSelector = JSONObject.quote(selector)
        val js = "window.__jarvisAction ? window.__jarvisAction.click($safeSelector) : JSON.stringify({success: false, error: 'JS runner missing'})"

        val jsResult = evalJs(js)
        val currentUrl = webView?.url
        val title = webView?.title

        try {
            val jsonObj = JSONObject(jsResult)
            val success = jsonObj.optBoolean("success", false)
            val error = jsonObj.optString("error", null)

            Result.success(
                BrowserResultPayload(
                    success = success,
                    action = "CLICK",
                    currentUrl = currentUrl,
                    pageTitle = title,
                    error = error
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fills an input or textarea element with text.
     * Enforces security confirmation for password fields.
     */
    suspend fun fill(
        selector: String,
        text: String,
        isUserConfirmedSensitive: Boolean = false
    ): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val safeSelector = JSONObject.quote(selector)
        val safeText = JSONObject.quote(text)

        // Check if target is sensitive (password / credit card field)
        val checkSensitiveJs = "window.__jarvisAction ? window.__jarvisAction.isSensitiveField($safeSelector) : false"
        val isSensitive = evalJs(checkSensitiveJs).trim().equals("true", ignoreCase = true)

        if (isSensitive && securityConfig.requireConfirmationForPasswords && !isUserConfirmedSensitive) {
            return@withContext Result.success(
                BrowserResultPayload(
                    success = false,
                    action = "FILL",
                    currentUrl = webView?.url,
                    isSensitiveAction = true,
                    needsUserConfirmation = true,
                    error = "Security Policy: Filling sensitive/password field '$selector' requires explicit user confirmation."
                )
            )
        }

        val js = "window.__jarvisAction ? window.__jarvisAction.fill($safeSelector, $safeText) : JSON.stringify({success: false, error: 'JS runner missing'})"
        val jsResult = evalJs(js)

        try {
            val jsonObj = JSONObject(jsResult)
            val success = jsonObj.optBoolean("success", false)
            val error = jsonObj.optString("error", null)

            Result.success(
                BrowserResultPayload(
                    success = success,
                    action = "FILL",
                    currentUrl = webView?.url,
                    isSensitiveAction = isSensitive,
                    error = error
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Submits a form element by CSS selector or clicks its submit button.
     */
    suspend fun submitForm(selector: String): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val safeSelector = JSONObject.quote(selector)
        val js = "window.__jarvisAction ? window.__jarvisAction.submitForm($safeSelector) : JSON.stringify({success: false, error: 'JS runner missing'})"
        val jsResult = evalJs(js)

        try {
            val jsonObj = JSONObject(jsResult)
            val success = jsonObj.optBoolean("success", false)
            val error = jsonObj.optString("error", null)

            Result.success(
                BrowserResultPayload(
                    success = success,
                    action = "SUBMIT_FORM",
                    currentUrl = webView?.url,
                    pageTitle = webView?.title,
                    error = error
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts text content from a specified element or the full page.
     */
    suspend fun extractText(selector: String? = null): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val safeSelector = if (selector != null) JSONObject.quote(selector) else "null"
        val js = "window.__jarvisAction ? window.__jarvisAction.extractText($safeSelector) : JSON.stringify({success: false, error: 'JS runner missing'})"
        val jsResult = evalJs(js)

        try {
            val jsonObj = JSONObject(jsResult)
            val success = jsonObj.optBoolean("success", false)
            val extracted = jsonObj.optString("text", "")
            val error = jsonObj.optString("error", null)

            Result.success(
                BrowserResultPayload(
                    success = success,
                    action = "EXTRACT_TEXT",
                    currentUrl = webView?.url,
                    pageTitle = webView?.title,
                    extractedText = extracted,
                    error = error
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts HTML from a specified element or document root.
     */
    suspend fun extractHtml(selector: String? = null): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val safeSelector = if (selector != null) JSONObject.quote(selector) else "null"
        val js = "window.__jarvisAction ? window.__jarvisAction.extractHtml($safeSelector) : JSON.stringify({success: false, error: 'JS runner missing'})"
        val jsResult = evalJs(js)

        try {
            val jsonObj = JSONObject(jsResult)
            val success = jsonObj.optBoolean("success", false)
            val html = jsonObj.optString("html", "")
            val error = jsonObj.optString("error", null)

            Result.success(
                BrowserResultPayload(
                    success = success,
                    action = "EXTRACT_HTML",
                    currentUrl = webView?.url,
                    pageTitle = webView?.title,
                    extractedHtml = html,
                    error = error
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Polls until a CSS selector appears in the DOM or times out.
     */
    suspend fun waitForSelector(selector: String, timeoutMs: Long = 8000L): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val safeSelector = JSONObject.quote(selector)
        val js = "window.__jarvisAction ? window.__jarvisAction.checkSelectorExists($safeSelector) : false"

        val startTime = System.currentTimeMillis()
        var exists = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val checkRes = evalJs(js).trim().equals("true", ignoreCase = true)
            if (checkRes) {
                exists = true
                break
            }
            kotlinx.coroutines.delay(300)
        }

        Result.success(
            BrowserResultPayload(
                success = exists,
                action = "WAIT_FOR_SELECTOR",
                currentUrl = webView?.url,
                error = if (exists) null else "Selector '$selector' did not appear within ${timeoutMs}ms"
            )
        )
    }

    /**
     * Scrolls horizontally or vertically on the current page.
     */
    suspend fun scrollBy(dx: Int, dy: Int): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val js = "window.scrollBy($dx, $dy);"
        evalJs(js)
        Result.success(
            BrowserResultPayload(
                success = true,
                action = "SCROLL_BY",
                currentUrl = webView?.url
            )
        )
    }

    /**
     * Captures a bitmap screenshot of the rendered WebView.
     */
    suspend fun screenshot(): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext Result.failure(IllegalStateException("WebView not initialized"))
        try {
            val width = if (view.width > 0) view.width else 1080
            val height = if (view.height > 0) view.height else 1920

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val base64Str = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            Result.success(
                BrowserResultPayload(
                    success = true,
                    action = "SCREENSHOT",
                    currentUrl = view.url,
                    pageTitle = view.title,
                    screenshotBase64 = base64Str
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clears cookies, cache, and history storage.
     */
    suspend fun clearStorage(): Result<BrowserResultPayload> = withContext(Dispatchers.Main) {
        webView?.let {
            it.clearCache(true)
            it.clearHistory()
        }
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        Result.success(
            BrowserResultPayload(
                success = true,
                action = "CLEAR_STORAGE",
                currentUrl = webView?.url
            )
        )
    }

    /**
     * Executes a sequence/pipeline of browser action payloads in order.
     */
    suspend fun executePipeline(actions: List<BrowserActionPayload>): List<BrowserResultPayload> {
        val results = mutableListOf<BrowserResultPayload>()
        for (payload in actions) {
            val res = when (payload.actionType) {
                BrowserActionType.NAVIGATE -> navigate(payload.url ?: "https://google.com", payload.timeoutMs)
                BrowserActionType.CLICK -> click(payload.selector ?: "body", payload.timeoutMs)
                BrowserActionType.FILL -> fill(payload.selector ?: "input", payload.text ?: "", payload.isUserConfirmedSensitive)
                BrowserActionType.SUBMIT_FORM -> submitForm(payload.selector ?: "form")
                BrowserActionType.EXTRACT_TEXT -> extractText(payload.selector)
                BrowserActionType.EXTRACT_HTML -> extractHtml(payload.selector)
                BrowserActionType.WAIT_FOR_SELECTOR -> waitForSelector(payload.selector ?: "body", payload.timeoutMs)
                BrowserActionType.SCROLL_BY -> scrollBy(payload.dx, payload.dy)
                BrowserActionType.SCREENSHOT -> screenshot()
                BrowserActionType.CLEAR_STORAGE -> clearStorage()
                else -> extractText()
            }

            val payloadResult = res.getOrElse {
                BrowserResultPayload(
                    success = false,
                    action = payload.actionType.name,
                    error = it.localizedMessage ?: "Operation failed"
                )
            }

            results.add(payloadResult)
            // Stop pipeline if an action fails or needs security confirmation
            if (!payloadResult.success || payloadResult.needsUserConfirmation) {
                break
            }
        }
        return results
    }

    private suspend fun evalJs(script: String): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        val view = webView ?: return@withContext "{}"
        view.evaluateJavascript(script) { result ->
            val unquoted = if (result != null && result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
                try {
                    // Unescape JSON string returned from evaluateJavascript
                    JSONObject("{ \"res\": $result }").getString("res")
                } catch (e: Exception) {
                    result.substring(1, result.length - 1)
                }
            } else result ?: "{}"
            deferred.complete(unquoted)
        }
        deferred.await()
    }
}
