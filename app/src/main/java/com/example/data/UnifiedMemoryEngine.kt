package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class UnifiedMemoryEngine(private val repository: JarvisRepository) {

    val categories = mapOf(
        "identity" to "Who user/JARVIS is - name, preferences, core identity facts",
        "semantic" to "Facts, knowledge, learned preferences",
        "episodic" to "Time-stamped events and experiences",
        "procedural" to "How-to patterns and user work style preferences",
        "working" to "Short-term session context (auto-expires)",
        "activity" to "Logged actions and behaviors",
        "realtime" to "Real-time system events and alerts",
        "youtube" to "YouTube analytics and media history",
        "calendar" to "Calendar events, reminders, schedules",
        "habit" to "Learned habits, routines, and user patterns",
        "personality" to "JARVIS tone, rapport, and relationship dynamic with user"
    )

    suspend fun remember(
        content: String,
        category: String = "semantic",
        importance: Float = 0.5f,
        tags: String = "",
        key: String? = null,
        expiresInHours: Float? = null
    ): Long = withContext(Dispatchers.IO) {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) return@withContext -1L

        val expiresAt = expiresInHours?.let { System.currentTimeMillis() + (it * 3600 * 1000).toLong() }
        val item = UnifiedMemoryItem(
            key = key?.trim()?.lowercase(Locale.ROOT),
            category = category.trim().lowercase(Locale.ROOT),
            content = cleanContent,
            tags = tags.trim(),
            importance = importance.coerceIn(0.0f, 1.0f),
            timestamp = System.currentTimeMillis(),
            expiresAt = expiresAt
        )
        repository.insertUnifiedMemory(item)
    }

    suspend fun rememberPersonality(note: String, importance: Float = 0.8f): Long {
        return remember(
            content = note,
            category = "personality",
            importance = importance,
            tags = "personality rapport tone"
        )
    }

    suspend fun rememberIdentity(key: String, value: String, secret: Boolean = false): Long {
        val cleanKey = key.trim().lowercase(Locale.ROOT)
        val content = "$cleanKey: ${value.trim()}"
        return remember(
            content = content,
            category = "identity",
            importance = 0.95f,
            tags = if (secret) "identity secret" else "identity",
            key = cleanKey
        )
    }

    suspend fun getIdentity(key: String): String? = withContext(Dispatchers.IO) {
        val cleanKey = key.trim().lowercase(Locale.ROOT)
        val item = repository.getUnifiedMemoryByKey(cleanKey)
        if (item != null) {
            val content = item.content
            if (content.contains(":")) {
                return@withContext content.substringAfter(":").trim()
            }
            return@withContext content
        }
        null
    }

    suspend fun recall(query: String, topK: Int = 8, category: String? = null): List<UnifiedMemoryItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val results = repository.searchUnifiedMemories(cleanQuery, topK * 2)
        val filtered = if (category != null) {
            val cleanCat = category.lowercase(Locale.ROOT)
            results.filter { it.category == cleanCat || it.tags.lowercase(Locale.ROOT).contains(cleanCat) }
        } else {
            results
        }
        filtered.take(topK)
    }

    suspend fun trackRealtimeEvent(eventType: String, data: String) {
        remember(
            content = "$eventType: $data",
            category = "realtime",
            importance = 0.5f,
            tags = "realtime $eventType"
        )
    }

    suspend fun trackHabit(habitName: String, completed: Boolean, notes: String = "") {
        val status = if (completed) "completed" else "missed"
        var content = "Habit '$habitName': $status"
        if (notes.isNotBlank()) content += " - $notes"

        remember(
            content = content,
            category = "habit",
            importance = if (completed) 0.75f else 0.4f,
            tags = "habit $habitName $status",
            key = "habit:$habitName"
        )
    }

    suspend fun getAllMemories(category: String? = null, limit: Int = 100): List<UnifiedMemoryItem> = withContext(Dispatchers.IO) {
        if (category != null && category.isNotBlank()) {
            repository.getUnifiedMemoriesByCategory(category.lowercase(Locale.ROOT), limit)
        } else {
            repository.getAllUnifiedMemoriesList().take(limit)
        }
    }

    suspend fun deleteMemory(id: Long) = withContext(Dispatchers.IO) {
        repository.deleteUnifiedMemory(id)
    }

    suspend fun buildContext(currentMessage: String, maxMemories: Int = 10): String = withContext(Dispatchers.IO) {
        val identities = repository.getUnifiedMemoriesByCategory("identity", 5)
        val personalities = repository.getUnifiedMemoriesByCategory("personality", 5)
        val habits = repository.getUnifiedMemoriesByCategory("habit", 5)

        val relevantRecalls = if (currentMessage.isNotBlank()) {
            recall(currentMessage, topK = maxMemories)
        } else {
            emptyList()
        }

        val seenIds = mutableSetOf<Long>()
        val combinedList = mutableListOf<UnifiedMemoryItem>()

        for (item in identities + personalities + habits) {
            if (seenIds.add(item.id)) {
                combinedList.add(item)
            }
        }

        for (item in relevantRecalls) {
            if (seenIds.add(item.id)) {
                combinedList.add(item)
            }
        }

        val finalSelection = combinedList.take(maxMemories)
        if (finalSelection.isEmpty()) return@withContext ""

        val lines = finalSelection.map { item ->
            "- [${item.category.uppercase(Locale.ROOT)}] ${item.content}"
        }

        "UNIFIED MEMORY ENGINE (Stored User Identity, Preferences & Context):\n" + lines.joinToString("\n")
    }

    suspend fun consolidateTurn(userQuery: String, assistantResponse: String) = withContext(Dispatchers.IO) {
        val q = userQuery.trim()
        val lowerQ = q.lowercase(Locale.ROOT)

        // 1. Identity / Name detection
        if (lowerQ.contains("my name is ") || lowerQ.contains("call me ")) {
            val name = q.substringAfter("name is ", "").substringAfter("call me ", "").trim().take(30)
            if (name.isNotEmpty()) {
                rememberIdentity("name", name)
            }
        }

        // 2. Explicit memory prompts
        if (lowerQ.contains("remember that ") || lowerQ.contains("remember my ")) {
            val fact = q.substringAfter("remember that ", "").substringAfter("remember my ", "").trim()
            if (fact.isNotEmpty()) {
                remember(content = fact, category = "semantic", importance = 0.85f, tags = "user_preference")
            }
        }

        // 3. User Preferences
        if (lowerQ.contains("i prefer ") || lowerQ.contains("i like ") || lowerQ.contains("i love ")) {
            val pref = q.substringAfter("i prefer ", "").substringAfter("i like ", "").substringAfter("i love ", "").trim()
            if (pref.length > 3) {
                remember(content = "User preference: $pref", category = "semantic", importance = 0.75f, tags = "preference")
            }
        }

        // 4. Personality / Rapport praise
        if (lowerQ.matches(Regex(".*\\b(good|nice|awesome|great|super|brilliant|love it|thanks|perfect)\\b.*"))) {
            if (assistantResponse.lowercase(Locale.ROOT).contains("image") || lowerQ.contains("image")) {
                rememberPersonality("User is pleased with generated artwork and visual outputs.", importance = 0.7f)
            }
        }
    }
}
