package com.example.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchScraperTest {

    @Test
    fun testWebSearch() = runBlocking {
        println("Testing WebSearchScraper.searchWeb...")
        val query = "Android Jetpack Compose"
        val results = WebSearchScraper.searchWeb(query, maxResults = 3)
        
        println("Found ${results.size} results for query: '$query'")
        for ((index, item) in results.withIndex()) {
            println("[$index] Title: ${item.title}")
            println("    URL: ${item.url}")
            println("    Snippet: ${item.snippet}")
        }

        assertNotNull(results)
        // Check if search returned results
        assertTrue("Expected search results to not be empty", results.isNotEmpty())
    }

    @Test
    fun testSearchAndScrapeContext() = runBlocking {
        println("Testing WebSearchScraper.searchAndScrapeContext...")
        val query = "Kotlin programming language"
        val context = WebSearchScraper.searchAndScrapeContext(query)

        println("Scraped Context output (first 500 chars):")
        println(context.take(500))

        assertNotNull(context)
        assertTrue("Expected context to contain search text", context.contains("SEARCH RESULTS") || context.contains("Kotlin"))
    }
}
