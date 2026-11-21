package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val sender: String, // "user" or "ai"
    val timestamp: Long = System.currentTimeMillis(),
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileSize: String? = null
)

@Entity(tableName = "app_preferences")
data class AppPreference(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "jarvis_notes")
data class JarvisNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "unified_memories")
data class UnifiedMemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String? = null,
    val category: String = "semantic", // identity, semantic, episodic, procedural, working, activity, realtime, youtube, calendar, habit, personality
    val content: String,
    val tags: String = "",
    val importance: Float = 0.5f,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
)
