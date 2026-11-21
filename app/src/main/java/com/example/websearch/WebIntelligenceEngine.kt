package com.example.websearch

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.api.ScrapedPageResult
import com.example.api.WebSearchScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

object WebIntelligenceEngine {

    // ═══════════════════════════════════════════════════════
    // CONSTANTS & QUERY TYPES
    // ═══════════════════════════════════════════════════════

    const val QTYPE_NEWS = "news"
    const val QTYPE_PRODUCT = "product"
    const val QTYPE_COMPARISON = "comparison"
    const val QTYPE_PERSON = "person"
    const val QTYPE_HOWTO = "howto"
    const val QTYPE_TECHNICAL = "technical"
    const val QTYPE_GENERAL = "general"

    const val INTENT_NEW_SEARCH = "new_search"
    const val INTENT_REFINE = "refine_search"
    const val INTENT_SHOW_RESULTS = "show_results"
    const val INTENT_OPEN_RESULT = "open_result"
    const val INTENT_READ_RESULT = "read_result"
    const val INTENT_VIDEO = "video"
    const val INTENT_SHOW_PANEL = "show_panel"
    const val INTENT_DEEP_RESEARCH = "deep_research"
    const val INTENT_CONVERSATIONAL = "conversational"

    private val TRUST_HIGH = setOf(
        "arxiv.org", "nature.com", "science.org", "pubmed", "scholar.google",
        "ieee.org", "acm.org", "openai.com", "anthropic.com", "deepmind.com",
        "mit.edu", "stanford.edu", "berkeley.edu", "wikipedia.org"
    )

    private val TRUST_MEDIUM = setOf(
        "nytimes", "bbc", "reuters", "wired", "techcrunch", "arstechnica",
        "technologyreview", "microsoft.com", "aws.amazon", "huggingface",
        "tomsguide", "gsmarena", "theverge", "engadget", "androidauthority",
        "nvidia.com", "amd.com", "intel.com", "tomshardware"
    )

    private val TECHNICAL_SHORT_KW = listOf("api", "rag", "llm", "sdk", "orm", "cdn", "cli", "ide")
    private val TECHNICAL_LONG_KW = listOf(
        "python", "javascript", "typescript", "golang", "rust", "kotlin",
        "code", "library", "architecture", "algorithm", "exception",
        "parameter", "function", "class", "transformer", "fine-tune",
        "fine tuning", "embedding", "neural network", "inference", "training",
        "deployment", "microservice", "docker", "kubernetes", "database",
        "sql", "nosql", "regex", "recursion", "data structure"
    )

    // Session State
    data class SearchSession(
        var query: String = "",
        var refinedQuery: String = "",
        var results: List<SearchResultItem> = emptyList(),
        var summary: String = "",
        var directAnswer: String = "",
        var timestamp: Long = 0L
    ) {
        fun hasResults(): Boolean = results.isNotEmpty() && (System.currentTimeMillis() - timestamp < 10 * 60 * 1000L)
        fun getTopUrl(index: Int = 0): String = results.getOrNull(index)?.url ?: ""
        fun formattedList(): String {
            if (results.isEmpty()) return "No search results cached, sir."
            return results.take(8).mapIndexed { i, r ->
                val host = try { URL(r.url).host ?: "" } catch (e: Exception) { "" }
                "[${i + 1}] ${r.title}\n    ${r.content.take(120)}\n    $host"
            }.joinToString("\n\n")
        }
        fun clear() {
            query = ""
            refinedQuery = ""
            results = emptyList()
            summary = ""
            directAnswer = ""
            timestamp = 0L
        }
    }

    val currentSession = SearchSession()

    // ═══════════════════════════════════════════════════════
    // DOMAIN TRUST & QUERY ANALYSIS
    // ═══════════════════════════════════════════════════════

    fun domainTrust(url: String): Double {
        val host = try { URL(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        if (TRUST_HIGH.any { host.contains(it) }) return 2.0
        if (TRUST_MEDIUM.any { host.contains(it) }) return 1.5
        if (host.endsWith(".edu") || host.endsWith(".gov")) return 1.8
        return 1.0
    }

    fun isTechnical(query: String): Boolean {
        val t = query.lowercase()
        for (kw in TECHNICAL_SHORT_KW) {
            if (Regex("\\b" + Regex.escape(kw) + "\\b").containsMatchIn(t)) return true
        }
        for (kw in TECHNICAL_LONG_KW) {
            if (t.contains(kw)) return true
        }
        return false
    }

    fun detectQueryType(query: String): String {
        val t = query.lowercase()

        if (listOf("vs ", "versus", " vs.", "compare", "difference between", "better or", "or better", "which is better", "pros and cons").any { t.contains(it) }) {
            return QTYPE_COMPARISON
        }

        val newsPatterns = listOf(
            "latest", "breaking", "today", "yesterday", "this week", "this month",
            "just released", "just announced", "just launched", "just dropped",
            "announced", "release", "released", "launched", "dropped",
            "new model", "new version", "new ai", "new gpu", "new cpu",
            "update", "2025", "2026", "recently", "upcoming", "preview", "revealed", "unveil"
        )
        if (newsPatterns.any { t.contains(it) }) return QTYPE_NEWS

        if (listOf("buy", "best ", "top ", "under ₹", "under $", "review", "specs", "laptop", "phone", "gpu", "cpu", "earbuds", "budget", "affordable").any { t.contains(it) } && !isTechnical(t)) {
            return QTYPE_PRODUCT
        }

        if (listOf("who is", "who are", "who was", "who were", "ceo", "founder", "director", "actor", "author", "biography").any { t.contains(it) }) {
            return QTYPE_PERSON
        }

        if (listOf("how to", "how do i", "how can i", "steps to", "tutorial", "guide to", "install", "setup", "configure", "fix").any { t.contains(it) }) {
            return QTYPE_HOWTO
        }

        if (isTechnical(t)) return QTYPE_TECHNICAL

        return QTYPE_GENERAL
    }

    // ═══════════════════════════════════════════════════════
    // QUERY EXPANSION
    // ═══════════════════════════════════════════════════════

    fun extractSubject(query: String): String {
        var t = query.lowercase().trim()
        val prefixes = listOf(
            "^did\\s+", "^does\\s+", "^has\\s+", "^have\\s+", "^is\\s+there\\s+",
            "^are\\s+there\\s+", "^can\\s+you\\s+(tell\\s+me\\s+)?", "^what\\s+is\\s+the?\\s*",
            "^what\\s+are\\s+the?\\s*", "^tell\\s+me\\s+(about\\s+)?", "^show\\s+me\\s+", "^find\\s+"
        )
        for (p in prefixes) {
            t = t.replace(Regex(p), "").trim()
        }
        t = t.replace(Regex("\\b(any|some)\\b"), "").replace(Regex("\\s{2,}"), " ").trim()
        return if (t.isNotEmpty()) t.take(80) else query.take(80)
    }

    fun expandQueryFast(query: String, qtype: String): List<String> {
        val q = query.trim()
        val subj = extractSubject(q)
        val yr = "2026"
        val angles = mutableListOf(q)

        when (qtype) {
            QTYPE_NEWS -> {
                val nounSubj = subj.replace(Regex("(?i)\\b(release|released|launch|launched|announce|announced|drop|dropped|unveil|unveiled)\\b"), "").replace(Regex("\\s{2,}"), " ").trim()
                angles.add("$nounSubj $yr")
                angles.add("$nounSubj announcement $yr")
            }
            QTYPE_COMPARISON -> {
                val parts = q.split(Regex("(?i)\\s+vs\\.?\\s+|\\s+versus\\s+|\\s+or\\s+|\\s+compare\\s+to\\s+"))
                if (parts.size == 2) {
                    angles.add("${parts[0].trim()} review benchmarks $yr")
                    angles.add("${parts[1].trim()} review benchmarks $yr")
                } else {
                    angles.add("$subj review analysis $yr")
                }
            }
            QTYPE_PRODUCT -> {
                angles.add("$subj review $yr pros cons")
                angles.add("$subj price specifications comparison")
            }
            QTYPE_PERSON -> {
                angles.add("$subj biography career achievements")
                angles.add("$subj news $yr")
            }
            QTYPE_HOWTO -> {
                angles.add("$subj step by step $yr")
            }
            QTYPE_TECHNICAL -> {
                angles.add("$subj documentation official")
                angles.add("$subj example tutorial $yr")
            }
            else -> {
                angles.add("$subj explained overview")
            }
        }

        return angles.distinctBy { it.lowercase().trim() }.take(3)
    }

    // ═══════════════════════════════════════════════════════
    // INTENT CLASSIFICATION
    // ═══════════════════════════════════════════════════════

    fun classifyQuery(text: String): String {
        val t = text.trim().lowercase()

        val convBlocklist = listOf(
            "^hi[\\s!?.]*$", "^hello[\\s!?.]*$", "^hey[\\s!?.]*$", "^thanks[\\s!?.]*$", "^thank you[\\s!?.]*$",
            "^ok[\\s!?.]*$", "^okay[\\s!?.]*$", "^yes[\\s!?.]*$", "^no[\\s!?.]*$", "^bye[\\s!?.]*$",
            "^what (are|were|is|was) (we|you|i) (doing|discussing|talking|working on)$",
            "^how (are|r) (you|u)(\\?|$)", "^are you (ok|okay|fine|working|there)[\\s?]*$"
        )
        if (convBlocklist.any { Regex(it).containsMatchIn(t) }) return INTENT_CONVERSATIONAL

        val hasSession = currentSession.hasResults()

        if (t == "panel" || t == "show panel" || t.contains("open panel") || t.contains("show news panel")) return INTENT_SHOW_PANEL
        if (t.contains("show results") || t.contains("show them") || t.contains("give me results") || t == "results") {
            return if (hasSession) INTENT_SHOW_RESULTS else INTENT_CONVERSATIONAL
        }
        if (t.contains("open it") || t.contains("open link") || t.contains("visit page")) {
            return if (hasSession) INTENT_OPEN_RESULT else INTENT_NEW_SEARCH
        }
        if (t == "video" || t.contains("youtube") || t.contains("watch video") || t.contains("play video")) return INTENT_VIDEO
        if (t.contains("explain it") || t.contains("elaborate") || t.contains("read article")) {
            return if (hasSession) INTENT_READ_RESULT else INTENT_NEW_SEARCH
        }
        if (hasSession && (t.startsWith("i mean") || t.startsWith("under ") || t.startsWith("for gaming") || t.startsWith("in india"))) return INTENT_REFINE
        if (listOf("deep research", "deep dive", "comprehensive research", "exhaustive analysis", "buying guide").any { t.contains(it) }) return INTENT_DEEP_RESEARCH

        if (needsWebSearch(t)) return INTENT_NEW_SEARCH
        return INTENT_CONVERSATIONAL
    }

    fun needsWebSearch(text: String): Boolean {
        val t = text.lowercase().trim()

        val liveSignals = listOf(
            "today", "yesterday", "this week", "this month", "right now", "currently",
            "latest", "recent", "breaking", "new ", "just released", "just launched",
            "score", "result", "election", "stock", "price", "market", "weather", "news",
            "update", "release", "version", "2025", "2026", "announced", "launched", "space mission", "isro", "nasa", "spacex"
        )
        if (liveSignals.any { t.contains(it) }) return true

        val lookupSignals = listOf(
            "who is", "who are", "where is", "when did", "when is", "what is the latest",
            "best ", "top ", "vs ", "versus ", "compare", "review", "specs", "features",
            "how to ", "tutorial", "guide", "buy", "price of", "cost of", "search", "find", "look up"
        )
        if (lookupSignals.any { t.contains(it) }) return true

        return false
    }

    // ═══════════════════════════════════════════════════════
    // SMART SEARCH & DEEP RESEARCH PIPELINE
    // ═══════════════════════════════════════════════════════

    suspend fun smartSearch(
        query: String,
        tavilyApiKey: String = ""
    ): WebSearchResult = withContext(Dispatchers.IO) {
        val qtype = detectQueryType(query)
        val angles = expandQueryFast(query, qtype)

        // Execute searches in parallel across Tavily and DuckDuckGo/Bing HTML scraper
        val allResults = mutableListOf<SearchResultItem>()
        var directAnswer: String? = null

        coroutineScope {
            val tavilyDeferred = async {
                if (tavilyApiKey.isNotBlank()) {
                    try {
                        val client = TavilyClient(tavilyApiKey)
                        client.search(
                            query = query,
                            maxResults = 8,
                            searchDepth = if (qtype == QTYPE_NEWS) SearchDepth.ADVANCED else SearchDepth.BASIC,
                            topic = if (qtype == QTYPE_NEWS) SearchTopic.NEWS else SearchTopic.GENERAL
                        )
                    } catch (e: Exception) { null }
                } else null
            }

            val scraperDeferred = async {
                try {
                    WebSearchScraper.searchWeb(query, maxResults = 6)
                } catch (e: Exception) { emptyList() }
            }

            val angleScraperDeferreds = angles.drop(1).map { angle ->
                async {
                    try { WebSearchScraper.searchWeb(angle, maxResults = 3) } catch (e: Exception) { emptyList() }
                }
            }

            val tavilyRes = tavilyDeferred.await()
            val scraperRes = scraperDeferred.await()
            val angleResList = angleScraperDeferreds.awaitAll()

            tavilyRes?.let { t ->
                directAnswer = t.answer
                t.results.forEach { r ->
                    allResults.add(SearchResultItem(title = r.title, url = r.url, content = r.content.take(300), score = r.score + domainTrust(r.url)))
                }
            }

            scraperRes.forEach { r ->
                if (allResults.none { it.url == r.url }) {
                    allResults.add(SearchResultItem(title = r.title, url = r.url, content = r.snippet, score = domainTrust(r.url)))
                }
            }

            angleResList.flatten().forEach { r ->
                if (allResults.none { it.url == r.url }) {
                    allResults.add(SearchResultItem(title = r.title, url = r.url, content = r.snippet, score = domainTrust(r.url)))
                }
            }
        }

        // Rank by domain trust & score
        allResults.sortByDescending { it.score }

        val finalResults = allResults.take(15)

        // Deep scrape top 2 page contents for detailed context
        val scrapedPages = mutableListOf<ScrapedPageResult>()
        for (item in finalResults.take(2)) {
            if (item.url.startsWith("http")) {
                val page = WebSearchScraper.scrapePageContent(item.url)
                if (page.mainContent.length > 100 && page.title != "Error") {
                    scrapedPages.add(page)
                }
            }
        }

        // Format summary/context block
        val contextBuilder = StringBuilder()
        if (!directAnswer.isNullOrBlank()) {
            contextBuilder.append("QUICK ANSWER: $directAnswer\n\n")
        }
        contextBuilder.append("TOP SEARCH RESULTS FOR '$query':\n")
        finalResults.take(6).forEachIndexed { i, r ->
            contextBuilder.append("[${i + 1}] ${r.title} (${r.url})\n    ${r.content.take(200)}\n\n")
        }
        if (scrapedPages.isNotEmpty()) {
            contextBuilder.append("DETAILED PAGE EXTRACTS:\n")
            scrapedPages.forEach { p ->
                contextBuilder.append("--- PAGE: ${p.title} ---\n${p.mainContent.take(1000)}\n\n")
            }
        }

        val summaryText = contextBuilder.toString().trim()

        currentSession.query = query
        currentSession.results = finalResults
        currentSession.summary = summaryText
        currentSession.directAnswer = directAnswer ?: ""
        currentSession.timestamp = System.currentTimeMillis()

        return@withContext WebSearchResult(query = query, answer = directAnswer, results = finalResults)
    }

    suspend fun deepResearch(
        query: String,
        context: Context? = null,
        tavilyApiKey: String = ""
    ): String = withContext(Dispatchers.IO) {
        val qtype = detectQueryType(query)
        val expandedQueries = expandQueryFast(query, qtype) + listOf("$query 2026 overview", "$query details documentation")

        val accumulatedResults = mutableListOf<SearchResultItem>()
        val scrapedPages = mutableListOf<ScrapedPageResult>()

        coroutineScope {
            val tasks = expandedQueries.map { q ->
                async {
                    if (tavilyApiKey.isNotBlank()) {
                        try {
                            TavilyClient(tavilyApiKey).search(q, maxResults = 5)
                        } catch (e: Exception) { null }
                    } else null
                }
            }
            val scraperTasks = expandedQueries.map { q ->
                async {
                    try { WebSearchScraper.searchWeb(q, maxResults = 4) } catch (e: Exception) { emptyList() }
                }
            }

            val tavilyResults = tasks.awaitAll()
            val scraperResults = scraperTasks.awaitAll()

            tavilyResults.filterNotNull().forEach { t ->
                t.results.forEach { r ->
                    if (accumulatedResults.none { it.url == r.url }) {
                        accumulatedResults.add(SearchResultItem(r.title, r.url, r.content, domainTrust(r.url)))
                    }
                }
            }

            scraperResults.flatten().forEach { r ->
                if (accumulatedResults.none { it.url == r.url }) {
                    accumulatedResults.add(SearchResultItem(r.title, r.url, r.snippet, domainTrust(r.url)))
                }
            }
        }

        accumulatedResults.sortByDescending { it.score }

        // Scrape top 4 pages
        for (item in accumulatedResults.take(4)) {
            if (item.url.startsWith("http")) {
                val p = WebSearchScraper.scrapePageContent(item.url)
                if (p.mainContent.length > 150 && p.title != "Error") {
                    scrapedPages.add(p)
                }
            }
        }

        val reportBuilder = StringBuilder()
        val timeStr = SimpleDateFormat("MMMM dd, yyyy HH:mm", Locale.US).format(Date())
        reportBuilder.append("# Deep Research Report: $query\n")
        reportBuilder.append("*Generated on $timeStr*\n\n")
        reportBuilder.append("## Executive Summary\n")
        reportBuilder.append("An exhaustive multi-angle web research was executed covering ${expandedQueries.size} sub-topics and ${accumulatedResults.size} sources.\n\n")

        reportBuilder.append("## Key Findings & Source Evidence\n")
        accumulatedResults.take(10).forEachIndexed { index, item ->
            reportBuilder.append("### [${index + 1}] ${item.title}\n")
            reportBuilder.append("- **Source URL**: `${item.url}`\n")
            reportBuilder.append("- **Extract**: ${item.content}\n\n")
        }

        if (scrapedPages.isNotEmpty()) {
            reportBuilder.append("## Deep Page Content Analysis\n")
            scrapedPages.forEach { p ->
                reportBuilder.append("### Source: ${p.title}\n")
                reportBuilder.append("```\n${p.mainContent.take(1200)}\n```\n\n")
            }
        }

        val fullReport = reportBuilder.toString()

        // Save report file locally
        try {
            if (context != null) {
                val outputDir = File(context.filesDir, "research_reports")
                outputDir.mkdirs()
                val file = File(outputDir, "research_${System.currentTimeMillis()}.md")
                file.writeText(fullReport)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        currentSession.query = query
        currentSession.results = accumulatedResults
        currentSession.summary = fullReport
        currentSession.timestamp = System.currentTimeMillis()

        return@withContext fullReport
    }

    fun openInBrowser(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openYouTubeVideo(context: Context, query: String): Boolean {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.youtube.com/results?search_query=$encoded"
        return openInBrowser(context, url)
    }
}
