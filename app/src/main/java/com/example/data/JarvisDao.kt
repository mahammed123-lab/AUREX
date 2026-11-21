package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface JarvisDao {
    // Chat Sessions
    @Query("SELECT * FROM chat_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: ChatSession): Long

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    fun deleteSession(sessionId: Long)

    @Query("DELETE FROM chat_sessions")
    fun deleteAllSessions()

    // Chat Messages
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE fileUri IS NOT NULL AND fileUri != '' ORDER BY timestamp DESC")
    fun getAllMediaMessages(): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    fun deleteMessagesForSession(sessionId: Long)

    // App Preferences
    @Query("SELECT * FROM app_preferences WHERE `key` = :key")
    fun getPreference(key: String): AppPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPreference(preference: AppPreference)

    // Voice Notes
    @Query("SELECT * FROM jarvis_notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<JarvisNote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNote(note: JarvisNote): Long

    @Query("DELETE FROM jarvis_notes WHERE id = :noteId")
    fun deleteNote(noteId: Long)

    // Unified Memory System
    @Query("SELECT * FROM unified_memories ORDER BY timestamp DESC")
    fun getAllUnifiedMemories(): Flow<List<UnifiedMemoryItem>>

    @Query("SELECT * FROM unified_memories ORDER BY timestamp DESC")
    fun getAllUnifiedMemoriesList(): List<UnifiedMemoryItem>

    @Query("SELECT * FROM unified_memories WHERE category = :category ORDER BY importance DESC, timestamp DESC LIMIT :limit")
    fun getUnifiedMemoriesByCategory(category: String, limit: Int): List<UnifiedMemoryItem>

    @Query("SELECT * FROM unified_memories WHERE `key` = :key LIMIT 1")
    fun getUnifiedMemoryByKey(key: String): UnifiedMemoryItem?

    @Query("SELECT * FROM unified_memories WHERE content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY importance DESC LIMIT :limit")
    fun searchUnifiedMemories(query: String, limit: Int): List<UnifiedMemoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertUnifiedMemory(item: UnifiedMemoryItem): Long

    @Query("DELETE FROM unified_memories WHERE id = :id")
    fun deleteUnifiedMemory(id: Long)

    @Query("DELETE FROM unified_memories WHERE category = :category")
    fun clearUnifiedMemoryCategory(category: String)
}
