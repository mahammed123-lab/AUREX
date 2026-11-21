package com.example.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SearchResultItem(
    val title: String,
    val snippet: String,
    val url: String
)

data class ScrapedPageResult(
    val url: String,
    val title: String,
    val mainContent: String
)

object WebSearchScraper {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

    /**
     * Performs a web search across DuckDuckGo / Bing HTML endpoints and returns top results.
     */
    suspend fun searchWeb(query: String, maxResults: Int = 5): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResultItem>()
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext results

        // 1. Try DuckDuckGo HTML search
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                val parsed = parseDuckDuckGoHtml(html)
                results.addAll(parsed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback or supplementary search via Bing HTML search if DuckDuckGo returned few results
        if (results.size < maxResults) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val url = "https://www.bing.com/search?q=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val parsed = parseBingHtml(html)
                    for (item in parsed) {
                        if (results.none { it.url == item.url || it.title == item.title }) {
                            results.add(item)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return@withContext results.take(maxResults)
    }

    /**
     * Scrapes and cleans paragraph text content from a given web URL.
     */
    suspend fun scrapePageContent(url: String): ScrapedPageResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext ScrapedPageResult(url, "Error", "Unable to load webpage (HTTP ${response.code})")
            }

            val html = response.body?.string() ?: ""
            val title = extractHtmlTitle(html).ifBlank { url }
            val cleanContent = extractMainTextFromHtml(html)

            ScrapedPageResult(url = url, title = title, mainContent = cleanContent)
        } catch (e: Exception) {
            ScrapedPageResult(url = url, title = "Error", mainContent = "Scraping failed: ${e.localizedMessage}")
        }
    }

    /**
     * Performs web search and scrapes top result pages, formatting into a prompt context for Jarvis.
     */
    suspend fun searchAndScrapeContext(query: String): String = withContext(Dispatchers.IO) {
        val searchItems = searchWeb(query, maxResults = 4)
        if (searchItems.isEmpty()) {
            return@withContext "No live web search results found for query: '$query'"
        }

        val sb = StringBuilder()
        sb.append("LIVE WEB SEARCH RESULTS FOR QUERY: \"$query\"\n\n")

        for ((index, item) in searchItems.withIndex()) {
            sb.append("${index + 1}. TITLE: ${item.title}\n")
            sb.append("   URL: ${item.url}\n")
            sb.append("   SUMMARY / SNIPPET: ${item.snippet}\n\n")
        }

        // Deep scrape top 2 URLs for detailed context
        val scrapedPages = mutableListOf<ScrapedPageResult>()
        for (item in searchItems.take(2)) {
            if (item.url.startsWith("http://") || item.url.startsWith("https://")) {
                val scraped = scrapePageContent(item.url)
                if (scraped.mainContent.length > 100 && !scraped.title.equals("Error")) {
                    scrapedPages.add(scraped)
                }
            }
        }

        if (scrapedPages.isNotEmpty()) {
            sb.append("DETAILED SCRAPED PAGE EXTRACTS:\n")
            for (page in scrapedPages) {
                sb.append("--- PAGE: ${page.title} (${page.url}) ---\n")
                sb.append(page.mainContent.take(1200))
                sb.append("\n---\n\n")
            }
        }

        return@withContext sb.toString().trim()
    }

    private fun parseDuckDuckGoHtml(html: String): List<SearchResultItem> {
        val list = mutableListOf<SearchResultItem>()
        val resultPattern = Pattern.compile("<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL)
        val titlePattern = Pattern.compile("<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL)

        val titleMatcher = titlePattern.matcher(html)
        val snippetMatcher = resultPattern.matcher(html)

        val titlesAndUrls = mutableListOf<Pair<String, String>>()
        while (titleMatcher.find()) {
            val rawHref = titleMatcher.group(1) ?: ""
            val rawTitle = titleMatcher.group(2) ?: ""
            val cleanTitle = stripHtmlTags(rawTitle)
            val cleanUrl = decodeDdGUrl(rawHref)
            if (cleanTitle.isNotBlank() && cleanUrl.startsWith("http")) {
                titlesAndUrls.add(cleanTitle to cleanUrl)
            }
        }

        val snippets = mutableListOf<String>()
        while (snippetMatcher.find()) {
            val rawSnippet = snippetMatcher.group(1) ?: ""
            val cleanSnippet = stripHtmlTags(rawSnippet)
            if (cleanSnippet.isNotBlank()) {
                snippets.add(cleanSnippet)
            }
        }

        for (i in titlesAndUrls.indices) {
            val (title, url) = titlesAndUrls[i]
            val snippet = if (i < snippets.size) snippets[i] else "No snippet available."
            list.add(SearchResultItem(title = title, snippet = snippet, url = url))
        }

        return list
    }

    private fun parseBingHtml(html: String): List<SearchResultItem> {
        val list = mutableListOf<SearchResultItem>()
        val bAlgoPattern = Pattern.compile("<li[^>]+class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL)
        val matcher = bAlgoPattern.matcher(html)

        while (matcher.find()) {
            val block = matcher.group(1) ?: continue
            val hrefMatcher = Pattern.compile("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(block)
            if (hrefMatcher.find()) {
                val url = hrefMatcher.group(1) ?: continue
                val title = stripHtmlTags(hrefMatcher.group(2) ?: "")
                
                val pPattern = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(block)
                val snippet = if (pPattern.find()) stripHtmlTags(pPattern.group(1) ?: "") else "No description available."

                if (title.isNotBlank() && url.isNotBlank()) {
                    list.add(SearchResultItem(title = title, snippet = snippet, url = url))
                }
            }
        }

        return list
    }

    private fun decodeDdGUrl(href: String): String {
        return try {
            if (href.contains("uddg=")) {
                val encoded = href.substringAfter("uddg=").substringBefore("&")
                java.net.URLDecoder.decode(encoded, "UTF-8")
            } else if (href.startsWith("//")) {
                "https:$href"
            } else {
                href
            }
        } catch (e: Exception) {
            href
        }
    }

    private fun extractHtmlTitle(html: String): String {
        val matcher = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        return if (matcher.find()) {
            stripHtmlTags(matcher.group(1) ?: "")
        } else ""
    }

    private fun extractMainTextFromHtml(html: String): String {
        var clean = html
        // Remove scripts, styles, head, nav, footer, header
        clean = clean.replace(Regex("(?s)<script.*?>.*?</script>"), " ")
        clean = clean.replace(Regex("(?s)<style.*?>.*?</style>"), " ")
        clean = clean.replace(Regex("(?s)<head.*?>.*?</head>"), " ")
        clean = clean.replace(Regex("(?s)<nav.*?>.*?</nav>"), " ")
        clean = clean.replace(Regex("(?s)<footer.*?>.*?</footer>"), " ")
        clean = clean.replace(Regex("(?s)<header.*?>.*?</header>"), " ")

        // Replace line breaks & paragraph tags with newlines
        clean = clean.replace(Regex("(?i)<br\\s*/?>"), "\n")
        clean = clean.replace(Regex("(?i)</p>"), "\n\n")
        clean = clean.replace(Regex("(?i)</div>"), "\n")
        clean = clean.replace(Regex("(?i)</li>"), "\n")

        val textOnly = stripHtmlTags(clean)
        return textOnly.lines()
            .map { it.trim() }
            .filter { it.length > 20 }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun stripHtmlTags(input: String): String {
        var res = input.replace(Regex("<[^>]*>"), " ")
        res = res.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
        return res.replace(Regex("\\s+"), " ").trim()
    }
}
