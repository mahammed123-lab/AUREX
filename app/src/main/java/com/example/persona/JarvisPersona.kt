package com.example.persona

/**
 * JarvisPersona — overlays the JARVIS character on top of ANY LLM backend.
 *
 * The trick: personality doesn't live in the model, it lives in what you
 * prepend to every request. This wraps outgoing messages with a system
 * prompt before they hit orchestra.py / NVIDIA NIM / OpenRouter / DeepSeek /
 * local Qwen — so you can swap the underlying model freely without losing
 * JARVIS's voice.
 */

data class ChatMessage(val role: String, val content: String)

object JarvisPersona {

    // The injection payload. Edit freely — this IS the personality.
    private val SYSTEM_PROMPT = """
        You are AUREX, a personal AI assistant for your user, an advanced autonomous executive intelligence.

        Voice:
        - Composed, dry wit, quietly competent. Never gushing, never robotic.
        - Address the user directly and plainly; skip filler like "Certainly!" or "I'd be happy to."
        - Default to brief, spoken-style answers (this may be read aloud via TTS) unless the task needs detail.
        - When you don't know something or a task failed, say so plainly and propose the next step — no hedging.
        - You have access to tools/functions the app exposes (search, device actions, etc.) — use them
          when relevant instead of guessing.

        Never break character to explain that you are "an AI assistant" unless directly asked.
    """.trimIndent()

    /**
     * Prepends the persona system prompt to a message history, unless one's
     * already there. Call this right before you build the request to whatever
     * provider you're routing to.
     */
    fun inject(history: List<ChatMessage>): List<ChatMessage> {
        val alreadyInjected = history.firstOrNull()?.role == "system"
        return if (alreadyInjected) history
        else listOf(ChatMessage("system", SYSTEM_PROMPT)) + history
    }

    /**
     * Builds the JSON body for an OpenAI-compatible /v1/chat/completions call.
     * This shape works for NVIDIA NIM, OpenRouter, DeepSeek-compat, Gemini's
     * OpenAI-compat endpoint, and local Qwen via Ollama/vLLM — i.e. anything
     * your provider router in orchestra.py can point at.
     */
    fun buildRequestBody(
        model: String,
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        temperature: Double = 0.7
    ): String {
        val messages = inject(conversationHistory + ChatMessage("user", userMessage))
        val messagesJson = messages.joinToString(",", prefix = "[", postfix = "]") { m ->
            """{"role":"${m.role}","content":${jsonEscape(m.content)}}"""
        }
        return """{"model":"$model","temperature":$temperature,"messages":$messagesJson}"""
    }

    private fun jsonEscape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/**
 * JarvisConversation — keeps the running back-and-forth alive across turns.
 *
 * Without this, every call is a one-shot: JARVIS answers, then forgets you
 * ever spoke. This holds the turn history (both sides — what you said, what
 * it said back) and feeds it into every new request, so the model always
 * sees the full thread, not just the latest message.
 *
 * One instance per active session (e.g. lives in your ViewModel).
 */
class JarvisConversation(private val maxTurns: Int = 20) {

    private val turns = mutableListOf<ChatMessage>()

    /** Call this when the user speaks/types. */
    fun addUserTurn(text: String) {
        turns.add(ChatMessage("user", text))
        trim()
    }

    /** Call this once you get the model's reply back, before the next request. */
    fun addAssistantTurn(text: String) {
        turns.add(ChatMessage("assistant", text))
        trim()
    }

    /** Builds the request body for the NEXT call, persona + full running history included. */
    fun nextRequestBody(model: String, temperature: Double = 0.7): String {
        // last element in `turns` is the just-added user message, rest is history
        val history = turns.dropLast(1)
        val latestUser = turns.lastOrNull { it.role == "user" }?.content ?: ""
        return JarvisPersona.buildRequestBody(model, latestUser, history, temperature)
    }

    fun reset() = turns.clear()

    // Keep only the last N turns so the prompt doesn't grow unbounded.
    // (Simple sliding window — swap for a summarizing strategy later if needed.)
    private fun trim() {
        val overflow = turns.size - maxTurns
        if (overflow > 0) repeat(overflow) { turns.removeAt(0) }
    }
}
