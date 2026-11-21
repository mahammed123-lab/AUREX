package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiTest {

    @Test
    fun testParseMessageSegments_standardText() {
        val input = "Hello Sir, here is your requested information."
        val segments = parseMessageSegments(input)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertEquals(input, (segments[0] as MessageSegment.Text).content)
    }

    @Test
    fun testParseMessageSegments_generativeHtmlBlock() {
        val input = """
            Here is your requested widget:
            ```html
            <!DOCTYPE html>
            <html>
            <head><title>Calculator Widget</title></head>
            <body><div class="calc"><input id="display"/></div></body>
            </html>
            ```
            Let me know if you need changes.
        """.trimIndent()

        val segments = parseMessageSegments(input)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertTrue(segments[1] is MessageSegment.GenerativeUI)
        assertTrue(segments[2] is MessageSegment.Text)

        val genUi = segments[1] as MessageSegment.GenerativeUI
        assertEquals("HTML", genUi.language)
        assertEquals("Calculator Widget", genUi.title)
        assertTrue(genUi.code.contains("<div class=\"calc\">"))
    }

    @Test
    fun testParseMessageSegments_generativeUiExplicitTag() {
        val input = """
            ```generative-ui
            <div style="padding: 10px; background: #1e293b;">
                <h1>Weather Radar</h1>
                <p>Temp: 72°F</p>
            </div>
            ```
        """.trimIndent()

        val segments = parseMessageSegments(input)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.GenerativeUI)
        val genUi = segments[0] as MessageSegment.GenerativeUI
        assertEquals("GENERATIVE-UI", genUi.language)
        assertEquals("Weather Radar", genUi.title)
    }

    @Test
    fun testParseMessageSegments_codeBlockPreserved() {
        val input = """
            ```kotlin
            fun calculateSum(a: Int, b: Int): Int = a + b
            ```
        """.trimIndent()

        val segments = parseMessageSegments(input)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.Code)
        val code = segments[0] as MessageSegment.Code
        assertEquals("KOTLIN", code.language)
        assertTrue(code.code.contains("calculateSum"))
    }

    @Test
    fun testPrepareGenerativeHtml_viewportAndDarkMode() {
        val rawHtmlSnippet = "<div class=\"btn\">Click Me</div>"
        val preparedDark = prepareGenerativeHtml(rawHtmlSnippet, isDarkMode = true)

        assertTrue(preparedDark.contains("<!DOCTYPE html>"))
        assertTrue(preparedDark.contains("viewport"))
        assertTrue(preparedDark.contains("#0f172a"))
        assertTrue(preparedDark.contains("<div class=\"btn\">Click Me</div>"))

        val fullHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Test App</title></head>
            <body><h1>Test</h1></body>
            </html>
        """.trimIndent()
        val preparedFull = prepareGenerativeHtml(fullHtml, isDarkMode = false)
        assertTrue(preparedFull.contains("viewport"))
        assertTrue(preparedFull.contains("Test App"))
    }

    @Test
    fun testExtractUiTitle() {
        assertEquals("Interactive Calculator", extractUiTitle("<div class='calc-grid'></div>", "HTML"))
        assertEquals("Interactive Game", extractUiTitle("<div class='game-board'></div>", "HTML"))
        assertEquals("Timer Widget", extractUiTitle("<div id='timer-display'></div>", "UI"))
        assertEquals("Special Dashboard", extractUiTitle("<head><title>Special Dashboard</title></head>", "HTML"))
    }
}
