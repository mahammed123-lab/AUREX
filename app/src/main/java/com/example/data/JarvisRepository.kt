package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class JarvisRepository(private val dao: JarvisDao) {
    val allSessions: Flow<List<ChatSession>> = dao.getAllSessions()

    val allMediaMessages: Flow<List<ChatMessage>> = dao.getAllMediaMessages()

    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessage>> =
        dao.getMessagesForSession(sessionId)

    suspend fun insertSession(session: ChatSession): Long = withContext(Dispatchers.IO) {
        dao.insertSession(session)
    }

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        dao.deleteSession(sessionId)
        dao.deleteMessagesForSession(sessionId)
    }

    suspend fun insertMessage(message: ChatMessage): Long = withContext(Dispatchers.IO) {
        dao.insertMessage(message)
    }

    suspend fun getPreference(key: String): String? = withContext(Dispatchers.IO) {
        dao.getPreference(key)?.value
    }

    suspend fun savePreference(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.insertPreference(AppPreference(key, value))
    }

    val allNotes: Flow<List<JarvisNote>> = dao.getAllNotes()

    suspend fun saveNote(content: String): Long = withContext(Dispatchers.IO) {
        dao.insertNote(JarvisNote(content = content))
    }

    suspend fun deleteNote(noteId: Long) = withContext(Dispatchers.IO) {
        dao.deleteNote(noteId)
    }

    // Unified Memory Repository functions
    val allUnifiedMemories: Flow<List<UnifiedMemoryItem>> = dao.getAllUnifiedMemories()

    suspend fun getAllUnifiedMemoriesList(): List<UnifiedMemoryItem> = withContext(Dispatchers.IO) {
        dao.getAllUnifiedMemoriesList()
    }

    suspend fun getUnifiedMemoriesByCategory(category: String, limit: Int = 100): List<UnifiedMemoryItem> = withContext(Dispatchers.IO) {
        dao.getUnifiedMemoriesByCategory(category, limit)
    }

    suspend fun getUnifiedMemoryByKey(key: String): UnifiedMemoryItem? = withContext(Dispatchers.IO) {
        dao.getUnifiedMemoryByKey(key)
    }

    suspend fun searchUnifiedMemories(query: String, limit: Int = 20): List<UnifiedMemoryItem> = withContext(Dispatchers.IO) {
        dao.searchUnifiedMemories(query, limit)
    }

    suspend fun insertUnifiedMemory(item: UnifiedMemoryItem): Long = withContext(Dispatchers.IO) {
        dao.insertUnifiedMemory(item)
    }

    suspend fun deleteUnifiedMemory(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteUnifiedMemory(id)
    }

    suspend fun clearUnifiedMemoryCategory(category: String) = withContext(Dispatchers.IO) {
        dao.clearUnifiedMemoryCategory(category)
    }
}
