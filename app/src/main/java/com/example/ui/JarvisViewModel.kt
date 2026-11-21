package com.example.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.api.callNvidiaNimApi
import com.example.api.callGeminiApi
import com.example.data.ChatMessage
import com.example.data.ChatSession
import com.example.data.JarvisRepository
import com.example.data.AiPersonalities
import com.example.data.IntentParser
import com.example.persona.JarvisConversation
import com.example.persona.JarvisPersona
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull

data class AttachedFile(
    val name: String,
    val uri: String,
    val type: String, // "text", "image", "other"
    val size: String = "",
    val content: String? = null
)

/** JARVIS event from messaging server (info/ask/call) */
data class JarvisEvent(
    val id: Long,
    val kind: String,      // "info" | "ask" | "call" | "reply"
    val title: String,
    val body: String,
    val call: Boolean,
    val ref: String,
    val ts: Double,
    val threadId: String = ""  // Conversation thread ID for chat threading
)

/** Chat message for the messaging UI */
data class JarvisChatMessage(
    val id: Long,
    val threadId: String,
    val role: String,        // "user" | "assistant" | "system"
    val content: String,
    val kind: String,        // "info" | "ask" | "call" | "reply"
    val timestamp: Long,
    val isRead: Boolean = false,
    val replyToId: Long? = null  // For reply threading
)

/** Conversation thread */
data class JarvisThread(
    val threadId: String,
    val title: String,
    val lastMessage: JarvisChatMessage?,
    val unreadCount: Int,
    val updatedAt: Long
)

class JarvisViewModel(
    private val repository: JarvisRepository,
    private val context: android.content.Context? = null
) : ViewModel() {

    // Unified Memory System
    val unifiedMemoryEngine = com.example.data.UnifiedMemoryEngine(repository)

    // Jarvis Persona Conversation Manager
    val jarvisConversation = JarvisConversation()

    // Theme and Sidebar States
    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _isSidebarOpen = MutableStateFlow(true)
    val isSidebarOpen: StateFlow<Boolean> = _isSidebarOpen.asStateFlow()

    // Chat states
    private val _chatState = MutableStateFlow<ChatUiState>(ChatUiState.HOME)
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    val sessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    // UI inputs
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isVoiceMode = MutableStateFlow(false)
    val isVoiceMode: StateFlow<Boolean> = _isVoiceMode.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generatingStatus = MutableStateFlow("AI is executing...")
    val generatingStatus: StateFlow<String> = _generatingStatus.asStateFlow()

    private val _isApiModalOpen = MutableStateFlow(false)
    val isApiModalOpen: StateFlow<Boolean> = _isApiModalOpen.asStateFlow()

    private val _isAttachmentMenuOpen = MutableStateFlow(false)
    val isAttachmentMenuOpen: StateFlow<Boolean> = _isAttachmentMenuOpen.asStateFlow()

    // JARVIS Inbox
    private val _isJarvisInboxOpen = MutableStateFlow(false)
    val isJarvisInboxOpen: StateFlow<Boolean> = _isJarvisInboxOpen.asStateFlow()

    private val _jarvisInboxTab = MutableStateFlow(0) // 0 = messages, 1 = calls
    val jarvisInboxTab: StateFlow<Int> = _jarvisInboxTab.asStateFlow()

// Active AI Provider: "nvidia_nim", "gemini", "jarvis_backend", or "custom"
private val _activeProvider = MutableStateFlow("nvidia_nim")
    val activeProvider: StateFlow<String> = _activeProvider.asStateFlow()

    // API Key States (persisted in DB)
    private val _coreAiApiKey = MutableStateFlow("")
    val coreAiApiKey: StateFlow<String> = _coreAiApiKey.asStateFlow()

    private val _geminiModel = MutableStateFlow("gemini-1.5-flash")
    val geminiModel: StateFlow<String> = _geminiModel.asStateFlow()

    private val _imageGenApiKey = MutableStateFlow("")
    val imageGenApiKey: StateFlow<String> = _imageGenApiKey.asStateFlow()

    private val _otherApiKey = MutableStateFlow("")
    val otherApiKey: StateFlow<String> = _otherApiKey.asStateFlow()

    private val apiKeyStore by lazy {
        val ctx = context ?: com.example.JarvisApplication.instance
        ctx?.let { com.example.websearch.ApiKeyStore(it) }
    }

    private val _tavilyApiKey = MutableStateFlow("")
    val tavilyApiKey: StateFlow<String> = _tavilyApiKey.asStateFlow()

    // NVIDIA NIM Specific States
    private val _nvidiaApiKey = MutableStateFlow("")
    val nvidiaApiKey: StateFlow<String> = _nvidiaApiKey.asStateFlow()

    private val _nvidiaBaseUrl = MutableStateFlow("https://integrate.api.nvidia.com/v1/")
    val nvidiaBaseUrl: StateFlow<String> = _nvidiaBaseUrl.asStateFlow()

    private val _nvidiaModel = MutableStateFlow("meta/llama-3.1-8b-instruct")
    val nvidiaModel: StateFlow<String> = _nvidiaModel.asStateFlow()

    private val _visionModel = MutableStateFlow("google/diffusiongemma-26b-a4b-it")
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()

    private val _customModelsList = MutableStateFlow<List<String>>(emptyList())
    val customModelsList: StateFlow<List<String>> = _customModelsList.asStateFlow()

    private val _customProviders = MutableStateFlow<List<com.example.api.CustomProvider>>(emptyList())
    val customProviders: StateFlow<List<com.example.api.CustomProvider>> = _customProviders.asStateFlow()

    private val _piperOnnxPath = MutableStateFlow("/storage/emulated/0/models")
    val piperOnnxPath: StateFlow<String> = _piperOnnxPath.asStateFlow()

    fun setVisionModel(modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isNotEmpty()) {
            _visionModel.value = trimmed
            viewModelScope.launch {
                repository.savePreference("vision_model", trimmed)
            }
        }
    }

    fun addCustomModel(modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isNotEmpty() && !_customModelsList.value.contains(trimmed)) {
            val newList = _customModelsList.value + trimmed
            _customModelsList.value = newList
            viewModelScope.launch {
                repository.savePreference("custom_models_list", newList.joinToString(","))
            }
        }
    }

    fun removeCustomModel(modelId: String) {
        val newList = _customModelsList.value.filter { it != modelId }
        _customModelsList.value = newList
        viewModelScope.launch {
            repository.savePreference("custom_models_list", newList.joinToString(","))
        }
    }

    fun addCustomProvider(name: String, baseUrl: String, apiKey: String, model: String, extraHeaders: String) {
        if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) return
        val existing = _customProviders.value.firstOrNull { it.name == name.trim() }
        val provider = com.example.api.CustomProvider(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
            extraHeaders = extraHeaders.trim()
        )
        val newList = if (existing != null) {
            _customProviders.value.map { if (it.name == provider.name) provider else it }
        } else {
            _customProviders.value + provider
        }
        _customProviders.value = newList
        persistCustomProviders(newList)
    }

    fun removeCustomProvider(name: String) {
        val newList = _customProviders.value.filter { it.name != name }
        _customProviders.value = newList
        persistCustomProviders(newList)
        if (_activeProvider.value.startsWith("custom:")) {
            val activeName = _activeProvider.value.removePrefix("custom:")
            if (activeName == name) {
                _activeProvider.value = "nvidia_nim"
                viewModelScope.launch { repository.savePreference("active_provider", "nvidia_nim") }
            }
        }
    }

    fun setActiveProvider(provider: String) {
        _activeProvider.value = provider
        viewModelScope.launch {
            repository.savePreference("active_provider", provider)
        }
    }

    private fun persistCustomProviders(list: List<com.example.api.CustomProvider>) {
        viewModelScope.launch {
            val flat = list.map { p ->
                "${p.name}|||${p.baseUrl}|||${p.apiKey}|||${p.model}|||${p.extraHeaders.replace("|||", " ")}"
            }.joinToString(";;;")
            repository.savePreference("custom_providers", flat)
        }
    }

    private fun loadCustomProviders(flat: String): List<com.example.api.CustomProvider> {
        if (flat.isBlank()) return emptyList()
        return flat.split(";;;").mapNotNull { entry ->
            val parts = entry.split("|||")
            if (parts.size >= 4) {
                com.example.api.CustomProvider(
                    name = parts[0],
                    baseUrl = parts[1],
                    apiKey = parts[2],
                    model = parts[3],
                    extraHeaders = parts.getOrElse(4) { "" }
                )
            } else null
        }
    }

    fun setPiperOnnxPath(path: String) {
        val trimmed = path.trim()
        if (trimmed.isNotEmpty()) {
            _piperOnnxPath.value = trimmed
            viewModelScope.launch {
                repository.savePreference("piper_onnx_path", trimmed)
            }
        }
    }

    // File attachments state
    private val _attachedFile = MutableStateFlow<AttachedFile?>(null)
    val attachedFile: StateFlow<AttachedFile?> = _attachedFile.asStateFlow()

    // Speech trigger for Text-To-Speech (TTS)
    private val _speakTextEvent = MutableSharedFlow<String>()
    val speakTextEvent: SharedFlow<String> = _speakTextEvent.asSharedFlow()

    private val _stopTtsEvent = MutableSharedFlow<Unit>()
    val stopTtsEvent: SharedFlow<Unit> = _stopTtsEvent.asSharedFlow()

    private val _isTtsMuted = MutableStateFlow(false)
    val isTtsMuted: StateFlow<Boolean> = _isTtsMuted.asStateFlow()

    private val _ttsSpeechRate = MutableStateFlow(1.0f)
    val ttsSpeechRate: StateFlow<Float> = _ttsSpeechRate.asStateFlow()

    fun setTtsSpeechRate(rate: Float) {
        // Neural TTS speed-compresses the generated waveform; above ~1.15x it
        // audibly glitches/rushes. Keep the slider sane.
        val clamped = rate.coerceIn(0.7f, 1.5f)
        _ttsSpeechRate.value = clamped
        viewModelScope.launch {
            repository.savePreference("tts_speech_rate", clamped.toString())
        }
    }

    private fun applySelfSetting(tag: String) {
        val parts = tag.split("=", limit = 2)
        if (parts.size != 2) return
        val key = parts[0].trim().lowercase()
        val value = parts[1].trim()
        when (key) {
            "model" -> {
                if (value.isNotBlank()) {
                    selectModel("nvidia_nim", value)
                }
            }
            "tts_speed", "tts_rate" -> {
                value.toFloatOrNull()?.let { setTtsSpeechRate(it.coerceIn(0.7f, 1.5f)) }
            }
            "dark_mode" -> {
                when (value.lowercase()) {
                    "on", "true", "dark" -> setDarkMode(true)
                    "off", "false", "light" -> setDarkMode(false)
                }
            }
            "streaming" -> {
                when (value.lowercase()) {
                    "on", "true" -> setStreamingEnabled(true)
                    "off", "false" -> setStreamingEnabled(false)
                }
            }
            "language" -> {
                when (value.lowercase()) {
                    "telugu" -> setAppLanguage("Telugu")
                    "english" -> setAppLanguage("English")
                }
            }
        }
    }

    /** Voice-speed commands the assistant executes on itself, instantly. */
    private fun detectTtsSpeedIntent(lowerText: String): Float? {
        val faster = listOf("speak faster", "talk faster", "speed up", "speed up your voice",
            "increase tts speed", "increase your speed", "faster voice", "increase speed",
            "speak quickly", "read faster", "make your voice faster", "turbo speed", "max speed")
        val slower = listOf("speak slower", "talk slower", "slow down", "decrease tts speed",
            "decrease your speed", "slower voice", "decrease speed", "speak slowly",
            "read slower", "make your voice slower")
        val normal = listOf("normal speed", "default speed", "reset speed", "reset your speed", "original speed")
        return when {
            normal.any { lowerText.contains(it) } -> 1.0f
            faster.any { lowerText.contains(it) } -> (_ttsSpeechRate.value + 0.2f).coerceAtMost(2.0f)
            slower.any { lowerText.contains(it) } -> (_ttsSpeechRate.value - 0.2f).coerceAtLeast(0.7f)
            else -> null
        }
    }

    /** Save a durable fact the model decided is worth remembering forever. */
    private fun saveMemoryFact(fact: String) {
        if (fact.isBlank() || fact.length > 300) return
        val current = _userMemories.value.trim()
        if (current.contains(fact, ignoreCase = true)) return
        val entry = "- $fact"
        val updated = if (current.isEmpty()) entry else "$current\n$entry"
        updateMemories(updated)
    }

    // Voice recognition live details
    private val _voiceRmsDb = MutableStateFlow(0f)
    val voiceRmsDb: StateFlow<Float> = _voiceRmsDb.asStateFlow()

    private val _voicePartialText = MutableStateFlow("")
    val voicePartialText: StateFlow<String> = _voicePartialText.asStateFlow()

    private val _voiceStatus = MutableStateFlow("Listening...")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    fun setVoiceStatus(status: String) {
        _voiceStatus.value = status
    }

    // User Name & Identity State
    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName.asStateFlow()

    fun setUserName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            _userName.value = trimmed
            viewModelScope.launch {
                repository.savePreference("user_name", trimmed)
            }
        }
    }

    // App Launcher Intent Flow
    private val _appIntentEvent = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val appIntentEvent: SharedFlow<String> = _appIntentEvent.asSharedFlow()

    // Long-Term Memories State
    private val _userMemories = MutableStateFlow("")
    val userMemories: StateFlow<String> = _userMemories.asStateFlow()

    fun updateMemories(memories: String) {
        _userMemories.value = memories
        viewModelScope.launch {
            repository.savePreference("user_memories", memories)
        }
    }

    fun exportCurrentChatAsJson(): String {
        val root = org.json.JSONObject()
        root.put("app", "J.A.R.V.I.S. AI")
        root.put("sessionId", _currentSessionId.value ?: -1)
        root.put("personality", _selectedPersonality.value)
        root.put("exportTimestamp", System.currentTimeMillis())

        val msgArray = org.json.JSONArray()
        for (msg in _currentMessages.value) {
            val obj = org.json.JSONObject()
            obj.put("id", msg.id)
            obj.put("sender", msg.sender)
            obj.put("text", msg.text)
            obj.put("timestamp", msg.timestamp)
            if (!msg.fileName.isNullOrEmpty()) {
                obj.put("fileName", msg.fileName)
            }
            if (!msg.fileSize.isNullOrEmpty()) {
                obj.put("fileSize", msg.fileSize)
            }
            msgArray.put(obj)
        }
        root.put("messages", msgArray)
        return root.toString(2)
    }

    // Developer Mode State
    private val _isDeveloperMode = MutableStateFlow(true)
    val isDeveloperMode: StateFlow<Boolean> = _isDeveloperMode.asStateFlow()

    fun setDeveloperMode(enabled: Boolean) {
        _isDeveloperMode.value = enabled
        viewModelScope.launch {
            repository.savePreference("developer_mode", enabled.toString())
        }
    }

    // Token Streaming State
    private val _isStreamingEnabled = MutableStateFlow(true)
    val isStreamingEnabled: StateFlow<Boolean> = _isStreamingEnabled.asStateFlow()

    fun setStreamingEnabled(enabled: Boolean) {
        _isStreamingEnabled.value = enabled
        viewModelScope.launch {
            repository.savePreference("streaming_enabled", enabled.toString())
        }
    }

    val allNotes: StateFlow<List<com.example.data.JarvisNote>> = repository.allNotes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun saveNote(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.saveNote(content)
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
        }
    }

    // Personality State
    private val _selectedPersonality = MutableStateFlow("jarvis")
    val selectedPersonality: StateFlow<String> = _selectedPersonality.asStateFlow()

    private val _customSystemPrompt = MutableStateFlow("")
    val customSystemPrompt: StateFlow<String> = _customSystemPrompt.asStateFlow()

    private val _isPersonalityModalOpen = MutableStateFlow(false)
    val isPersonalityModalOpen: StateFlow<Boolean> = _isPersonalityModalOpen.asStateFlow()

    private val _isGalleryModalOpen = MutableStateFlow(false)
    val isGalleryModalOpen: StateFlow<Boolean> = _isGalleryModalOpen.asStateFlow()

    fun setGalleryModalOpen(open: Boolean) {
        _isGalleryModalOpen.value = open
    }

    val allMediaMessages: StateFlow<List<ChatMessage>> = repository.allMediaMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _isConnectorsModalOpen = MutableStateFlow(false)
    val isConnectorsModalOpen: StateFlow<Boolean> = _isConnectorsModalOpen.asStateFlow()

    private val _isSettingsModalOpen = MutableStateFlow(false)
    val isSettingsModalOpen: StateFlow<Boolean> = _isSettingsModalOpen.asStateFlow()

    private val _isModelSelectorOpen = MutableStateFlow(false)
    val isModelSelectorOpen: StateFlow<Boolean> = _isModelSelectorOpen.asStateFlow()

    // Connectors status states - honest defaults: cloud connectors OFF until
    // the user explicitly enables them (they require an n8n webhook to work)
    private val _gmailConnected = MutableStateFlow(false)
    val gmailConnected: StateFlow<Boolean> = _gmailConnected.asStateFlow()

    private val _driveConnected = MutableStateFlow(false)
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()

    private val _calendarConnected = MutableStateFlow(false)
    val calendarConnected: StateFlow<Boolean> = _calendarConnected.asStateFlow()

    private val _webSearchConnected = MutableStateFlow(true)
    val webSearchConnected: StateFlow<Boolean> = _webSearchConnected.asStateFlow()

    private val _osBridgeConnected = MutableStateFlow(true)
    val osBridgeConnected: StateFlow<Boolean> = _osBridgeConnected.asStateFlow()

    private val _connectorTestLog = MutableStateFlow("")
    val connectorTestLog: StateFlow<String> = _connectorTestLog.asStateFlow()

    private val _requestCalendarPermissions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestCalendarPermissions: SharedFlow<Unit> = _requestCalendarPermissions.asSharedFlow()

    // NVIDIA NIM Live Screen / Camera Vision State
    private val _isLiveVisionOpen = MutableStateFlow(false)
    val isLiveVisionOpen: StateFlow<Boolean> = _isLiveVisionOpen.asStateFlow()

    private val _liveVisionResult = MutableStateFlow("")
    val liveVisionResult: StateFlow<String> = _liveVisionResult.asStateFlow()

    private val _isLiveVisionAnalyzing = MutableStateFlow(false)
    val isLiveVisionAnalyzing: StateFlow<Boolean> = _isLiveVisionAnalyzing.asStateFlow()

    private val _liveVisionCapturedBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val liveVisionCapturedBitmap: StateFlow<android.graphics.Bitmap?> = _liveVisionCapturedBitmap.asStateFlow()

    private val _liveVisionAutoScan = MutableStateFlow(false)
    val liveVisionAutoScan: StateFlow<Boolean> = _liveVisionAutoScan.asStateFlow()

    private val _appLanguage = MutableStateFlow("English")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    // Backend API State
    private val _backendBaseUrl = MutableStateFlow("")
    val backendBaseUrl: StateFlow<String> = _backendBaseUrl.asStateFlow()

    private val _backendConnected = MutableStateFlow(false)
    val backendConnected: StateFlow<Boolean> = _backendConnected.asStateFlow()

    // JARVIS Call Server State (for LiveKit calls — port 7860)
    private val _callServerUrl = MutableStateFlow("")
    val callServerUrl: StateFlow<String> = _callServerUrl.asStateFlow()

    // JARVIS Messaging Server State (for push notifications — port 8080)
    private val _messagingServerUrl = MutableStateFlow("")
    val messagingServerUrl: StateFlow<String> = _messagingServerUrl.asStateFlow()

    // JARVIS Events/Inbox (legacy flat list)
    private val _jarvisEvents = MutableStateFlow<List<JarvisEvent>>(emptyList())
    val jarvisEvents: StateFlow<List<JarvisEvent>> = _jarvisEvents.asStateFlow()
    private var jarvisEventsPollJob: Job? = null

    // NEW: Chat-style messaging state
    private val _jarvisThreads = MutableStateFlow<List<JarvisThread>>(emptyList())
    val jarvisThreads: StateFlow<List<JarvisThread>> = _jarvisThreads.asStateFlow()
    private val _activeThreadId = MutableStateFlow<String?>(null)
    val activeThreadId: StateFlow<String?> = _activeThreadId.asStateFlow()
    private val _threadMessages = MutableStateFlow<Map<String, List<JarvisChatMessage>>>(emptyMap())
    val threadMessages: StateFlow<Map<String, List<JarvisChatMessage>>> = _threadMessages.asStateFlow()
    private var jarvisThreadsPollJob: Job? = null

    fun startJarvisEventsPolling() {
        jarvisEventsPollJob?.cancel()
        jarvisEventsPollJob = viewModelScope.launch {
            var sinceId = 0L
            Log.d("JarvisViewModel", "startJarvisEventsPolling: polling started, url=${_messagingServerUrl.value}")
            while (true) {
                // Check for cancellation
                ensureActive()
                try {
                    val url = "${_messagingServerUrl.value}/aurex/events?since_id=$sinceId&timeout=25"
                    Log.d("JarvisViewModel", "polling: GET $url")
                    // Blocking HTTP must run OFF the main thread — on Main
                    // every execute() throws NetworkOnMainThreadException and
                    // the in-app Inbox never fills (only the notification
                    // service, which polls on IO, ever sees events).
                    withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.string()?.let { body ->
                                Log.d("JarvisViewModel", "polling: response body=$body")
                                val json = org.json.JSONObject(body)
                                val arr = json.optJSONArray("events") ?: return@use
                                val newEvents = mutableListOf<JarvisEvent>()
                                for (i in 0 until arr.length()) {
                                    val o = arr.getJSONObject(i)
                                    val evt = JarvisEvent(
                                        id = o.optLong("id"),
                                        kind = o.optString("kind"),
                                        title = o.optString("title"),
                                        body = o.optString("body"),
                                        call = o.optBoolean("call"),
                                        ref = o.optString("ref"),
                                        ts = o.optDouble("ts")
                                    )
                                    if (evt.id > sinceId) sinceId = evt.id
                                    newEvents.add(evt)
                                }
                                if (newEvents.isNotEmpty()) {
                                    val current = _jarvisEvents.value
                                    val merged = (current + newEvents)
                                        .distinctBy { it.id }
                                        .sortedByDescending { it.id }
                                        .take(100)
                                    _jarvisEvents.value = merged
                                }
                            }
                        }
                    }
                    }
                } catch (e: Exception) {
                    // Silent fail, retry after delay
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun stopJarvisEventsPolling() {
        jarvisEventsPollJob?.cancel()
    }

    fun clearJarvisEvents() {
        _jarvisEvents.value = emptyList()
    }

    // NEW: Chat-style messaging - thread polling
    fun startJarvisThreadsPolling() {
        jarvisThreadsPollJob?.cancel()
        jarvisThreadsPollJob = viewModelScope.launch {
            Log.d("JarvisViewModel", "startJarvisThreadsPolling: polling started")
            while (true) {
                ensureActive()
                try {
                    val url = "${_messagingServerUrl.value}/aurex/threads"
                    // Blocking HTTP off the main thread (NetworkOnMainThreadException otherwise)
                    withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.string()?.let { body ->
                                val json = org.json.JSONObject(body)
                                val threadsArray = json.getJSONArray("threads")
                                val threads = mutableListOf<JarvisThread>()
                                for (i in 0 until threadsArray.length()) {
                                    val t = threadsArray.getJSONObject(i)
                                    val threadId = t.getString("thread_id")
                                    val lastEvt = t.getJSONObject("last_event")
                                    val lastMsg = JarvisChatMessage(
                                        id = lastEvt.getLong("id"),
                                        threadId = threadId,
                                        role = if (lastEvt.getString("kind") == "reply") "user" else "assistant",
                                        content = lastEvt.getString("body"),
                                        kind = lastEvt.getString("kind"),
                                        timestamp = (lastEvt.getDouble("ts") * 1000).toLong(),
                                        isRead = false
                                    )
                                    threads.add(JarvisThread(
                                        threadId = threadId,
                                        title = lastEvt.getString("title"),
                                        lastMessage = lastMsg,
                                        unreadCount = t.optInt("unread", 0),
                                        updatedAt = (lastEvt.getDouble("ts") * 1000).toLong()
                                    ))
                                }
                                _jarvisThreads.value = threads.sortedByDescending { it.updatedAt }
                            }
                        }
                    }
                    }
                } catch (e: Exception) {
                    Log.w("JarvisViewModel", "threads polling error", e)
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    fun stopJarvisThreadsPolling() {
        jarvisThreadsPollJob?.cancel()
    }

    fun loadThreadMessages(threadId: String) {
        viewModelScope.launch {
            try {
                val url = "${_messagingServerUrl.value}/aurex/thread/$threadId?limit=100"
                withContext(Dispatchers.IO) {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.string()?.let { body ->
                            val json = org.json.JSONObject(body)
                            val eventsArray = json.getJSONArray("events")
                            val messages = mutableListOf<JarvisChatMessage>()
                            for (i in 0 until eventsArray.length()) {
                                val e = eventsArray.getJSONObject(i)
                                val role = when (e.getString("kind")) {
                                    "reply" -> "user"
                                    "call" -> "system"
                                    else -> "assistant"
                                }
                                messages.add(JarvisChatMessage(
                                    id = e.getLong("id"),
                                    threadId = threadId,
                                    role = role,
                                    content = e.getString("body"),
                                    kind = e.getString("kind"),
                                    timestamp = (e.getDouble("ts") * 1000).toLong(),
                                    isRead = (role == "assistant"), // Mark assistant messages as read
                                    replyToId = e.optString("ref").takeIf { it.isNotBlank() }?.toLongOrNull()
                                ))
                            }
                            val current = _threadMessages.value
                            _threadMessages.value = current + (threadId to messages)
                            _activeThreadId.value = threadId

                            // Mark thread as read
                            val threads = _jarvisThreads.value.map { t ->
                                if (t.threadId == threadId) t.copy(unreadCount = 0) else t
                            }
                            _jarvisThreads.value = threads
                        }
                    }
                }
                }
            } catch (e: Exception) {
                Log.w("JarvisViewModel", "loadThreadMessages error: ${e.message}")
            }
        }
    }

    fun sendReply(threadId: String, text: String, replyToId: Long? = null) {
        viewModelScope.launch {
            try {
                val url = "${_messagingServerUrl.value}/aurex/say"
                val json = org.json.JSONObject().apply {
                    put("text", text)
                    put("thread_id", threadId)
                    replyToId?.let { put("reply_to_id", it) }
                }
                val body = okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    json.toString()
                )
                withContext(Dispatchers.IO) {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder().url(url).post(body).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        // Optimistically add to local thread
                        val msg = JarvisChatMessage(
                            id = System.currentTimeMillis(),
                            threadId = threadId,
                            role = "user",
                            content = text,
                            kind = "reply",
                            timestamp = System.currentTimeMillis(),
                            isRead = true,
                            replyToId = replyToId
                        )
                        val current = _threadMessages.value
                        val updated = (current[threadId] ?: emptyList()) + msg
                        _threadMessages.value = current + (threadId to updated)

                        // Update thread last message
                        val threads = _jarvisThreads.value.map { t ->
                            if (t.threadId == threadId) t.copy(
                                lastMessage = msg,
                                updatedAt = System.currentTimeMillis()
                            ) else t
                        }
                        _jarvisThreads.value = threads.sortedByDescending { it.updatedAt }
                    }
                }
                }
            } catch (e: Exception) {
                Log.e("JarvisViewModel", "sendReply error: ${e.message}")
            }
        }
    }

    fun setBackendBaseUrl(url: String) {
        _backendBaseUrl.value = url
        viewModelScope.launch {
            repository.savePreference("backend_base_url", url)
        }
    }

    fun setCallServerUrl(url: String) {
        _callServerUrl.value = url
        viewModelScope.launch {
            repository.savePreference("call_server_url", url)
            // Also save to CallBridgeApi shared prefs for the foreground service
            context?.let { ctx ->
                val prefs = ctx.getSharedPreferences("jarvis_calls", Context.MODE_PRIVATE)
                prefs.edit().putString("call_server_url", url.trim().trimEnd('/')).apply()
            }
        }
    }

    fun setMessagingServerUrl(url: String) {
        _messagingServerUrl.value = url
        viewModelScope.launch {
            repository.savePreference("messaging_server_url", url)
            // Also save to CallBridgeApi shared prefs for the foreground service
            context?.let { ctx ->
                val prefs = ctx.getSharedPreferences("jarvis_calls", Context.MODE_PRIVATE)
                prefs.edit().putString("messaging_server_url", url.trim().trimEnd('/')).apply()
            }
        }
        // Start polling for JARVIS events (legacy)
        startJarvisEventsPolling()
        // Start polling for chat threads (new)
        startJarvisThreadsPolling()
    }

    fun testBackendConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = com.example.api.BackendClient.testConnection(_backendBaseUrl.value)
            _backendConnected.value = res.first
            onResult(res.first, res.second)
        }
    }

    fun testMessagingConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val reachable = com.example.call.CallBridgeApi.isReachable(context ?: com.example.JarvisApplication.instance!!)
            onResult(reachable, if (reachable) "Messaging server reachable" else "Messaging server unreachable")
        }
    }

    // n8n Workflow & Webhook Automation State
    private val _n8nWebhookUrl = MutableStateFlow("https://primary.n8n.cloud/webhook/jarvis-action")
    val n8nWebhookUrl: StateFlow<String> = _n8nWebhookUrl.asStateFlow()

    private val _n8nApiKey = MutableStateFlow("")
    val n8nApiKey: StateFlow<String> = _n8nApiKey.asStateFlow()

    private val _n8nCustomHeaders = MutableStateFlow("X-Source: JARVIS-Mobile, Content-Type: application/json")
    val n8nCustomHeaders: StateFlow<String> = _n8nCustomHeaders.asStateFlow()

    private val _n8nTriggerKeywords = MutableStateFlow("n8n, webhook, automate, workflow, sync, trigger, deploy, pipeline, automation")
    val n8nTriggerKeywords: StateFlow<String> = _n8nTriggerKeywords.asStateFlow()

    private val _n8nAutoTriggerEnabled = MutableStateFlow(true)
    val n8nAutoTriggerEnabled: StateFlow<Boolean> = _n8nAutoTriggerEnabled.asStateFlow()

    private val _n8nConnected = MutableStateFlow(true)
    val n8nConnected: StateFlow<Boolean> = _n8nConnected.asStateFlow()

    private val _n8nLastLog = MutableStateFlow("Ready to trigger n8n workflows")
    val n8nLastLog: StateFlow<String> = _n8nLastLog.asStateFlow()

    fun setAppLanguage(lang: String) {
        _appLanguage.value = lang
        viewModelScope.launch {
            repository.savePreference("app_language", lang)
        }
    }

    fun setN8nWebhookUrl(url: String) {
        _n8nWebhookUrl.value = url
        viewModelScope.launch {
            repository.savePreference("n8n_webhook_url", url)
        }
    }

    fun setN8nApiKey(key: String) {
        _n8nApiKey.value = key
        viewModelScope.launch {
            repository.savePreference("n8n_api_key", key)
        }
    }

    fun setN8nCustomHeaders(headers: String) {
        _n8nCustomHeaders.value = headers
        viewModelScope.launch {
            repository.savePreference("n8n_custom_headers", headers)
        }
    }

    fun setN8nTriggerKeywords(keywords: String) {
        _n8nTriggerKeywords.value = keywords
        viewModelScope.launch {
            repository.savePreference("n8n_trigger_keywords", keywords)
        }
    }

    fun setN8nAutoTriggerEnabled(enabled: Boolean) {
        _n8nAutoTriggerEnabled.value = enabled
        viewModelScope.launch {
            repository.savePreference("n8n_auto_trigger_enabled", enabled.toString())
        }
    }

    fun saveN8nAutomationConfig(url: String, apiKey: String, customHeaders: String, keywords: String, autoTrigger: Boolean) {
        _n8nWebhookUrl.value = url
        _n8nApiKey.value = apiKey
        _n8nCustomHeaders.value = customHeaders
        _n8nTriggerKeywords.value = keywords
        _n8nAutoTriggerEnabled.value = autoTrigger
        viewModelScope.launch {
            repository.savePreference("n8n_webhook_url", url)
            repository.savePreference("n8n_api_key", apiKey)
            repository.savePreference("n8n_custom_headers", customHeaders)
            repository.savePreference("n8n_trigger_keywords", keywords)
            repository.savePreference("n8n_auto_trigger_enabled", autoTrigger.toString())
        }
    }

    private fun parseHeadersMap(headersStr: String): Map<String, String> {
        if (headersStr.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        headersStr.split("\n", ",").forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                map[parts[0].trim()] = parts[1].trim()
            }
        }
        return map
    }

    fun triggerN8nWorkflow(workflowEvent: String, promptMessage: String, onResponse: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            _n8nLastLog.value = "Triggering n8n workflow '$workflowEvent'..."
            val payload = mapOf(
                "workflow" to workflowEvent,
                "message" to promptMessage,
                "user" to _userName.value,
                "language" to _appLanguage.value,
                "activeModel" to _nvidiaModel.value
            )
            val headersMap = parseHeadersMap(_n8nCustomHeaders.value)
            val res = com.example.api.N8nClient.triggerWebhook(
                webhookUrl = _n8nWebhookUrl.value,
                eventName = workflowEvent,
                payloadData = payload,
                apiKey = _n8nApiKey.value,
                customHeaders = headersMap
            )
            if (res.success) {
                val logText = "n8n Workflow '$workflowEvent' executed successfully! (HTTP ${res.statusCode})\n${res.responseBody}"
                _n8nLastLog.value = logText
                onResponse?.invoke(true, logText)
            } else {
                val errText = res.errorMessage ?: "n8n Execution Failed"
                _n8nLastLog.value = errText
                onResponse?.invoke(false, errText)
            }
        }
    }

    fun isLogicalOrComplexQuery(query: String, file: AttachedFile? = null): Boolean {
        if (file != null) return true
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false

        val lower = trimmed.lowercase()

        // Explicit complex / logical / reasoning keywords
        val logicalKeywords = listOf(
            "calculate", "math", "solve", "equation", "algorithm", "formula", "code",
            "program", "script", "function", "class", "debug", "refactor", "benchmark",
            "analyze", "proof", "derive", "integrate", "differentiate", "matrix",
            "step by step", "reasoning", "logic", "evaluate", "compare", "explain why",
            "how to build", "how to code", "how to program", "search", "scrape", "web search",
            "n8n", "automate", "workflow", "summarize", "translate"
        )
        if (logicalKeywords.any { lower.contains(it) }) return true

        // Check for math operations or code structure
        if (trimmed.contains(Regex("[0-9]+\\s*[+\\-*/%^=]\\s*[0-9]+")) ||
            trimmed.contains(Regex("[{}<>\\[\\]=;]|->|=>|\\b(fun|def|val|var|class|import|return)\\b"))
        ) {
            return true
        }

        // Short common/general queries or definitions (<= 6 words, e.g., "what is pen", "what is time", "hi", "how are you")
        val words = trimmed.split(Regex("\\s+"))
        if (words.size <= 6) {
            return false
        }

        // Long complex prompts (>= 12 words) usually require reasoning
        if (words.size >= 12) return true

        return false
    }

    fun checkAndTriggerN8nKeywordAutomation(userPrompt: String, sessionId: Long? = null) {
        if (!_n8nConnected.value || !_n8nAutoTriggerEnabled.value) return
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return

        val rawKeywords = _n8nTriggerKeywords.value
        val keywordList = rawKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val effectiveKeywords = if (keywordList.isEmpty()) listOf("n8n", "webhook", "automate", "workflow", "sync", "trigger", "deploy", "pipeline", "automation") else keywordList

        val promptLower = trimmed.lowercase()
        val matchedKeyword = effectiveKeywords.firstOrNull { kw -> promptLower.contains(kw) }

        if (matchedKeyword != null) {
            viewModelScope.launch {
                _n8nLastLog.value = "Keyword '$matchedKeyword' detected in user prompt. Auto-triggering n8n webhook..."
                val headersMap = parseHeadersMap(_n8nCustomHeaders.value)
                val payload = mapOf(
                    "trigger_keyword" to matchedKeyword,
                    "prompt" to trimmed,
                    "user" to _userName.value,
                    "language" to _appLanguage.value,
                    "model" to _nvidiaModel.value,
                    "timestamp" to System.currentTimeMillis()
                )
                val res = com.example.api.N8nClient.triggerWebhook(
                    webhookUrl = _n8nWebhookUrl.value,
                    eventName = "keyword_auto_trigger_$matchedKeyword",
                    payloadData = payload,
                    apiKey = _n8nApiKey.value,
                    customHeaders = headersMap
                )
                val logMsg = if (res.success) {
                    "[n8n Automation Triggered]: Keyword '$matchedKeyword' matched.\nHTTP ${res.statusCode} Response: ${res.responseBody.take(300)}"
                } else {
                    "[n8n Automation Error]: ${res.errorMessage}"
                }
                _n8nLastLog.value = logMsg

                val activeSessionId = sessionId ?: _currentSessionId.value
                if (activeSessionId != null) {
                    val n8nNotifyMsg = ChatMessage(
                        sessionId = activeSessionId,
                        sender = "system",
                        text = logMsg,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(n8nNotifyMsg)
                }
            }
        }
    }

    fun setLiveVisionCapturedBitmap(bitmap: android.graphics.Bitmap?) {
        _liveVisionCapturedBitmap.value = bitmap
    }

    init {
        Log.d("JarvisViewModel", "=== ViewModel init started, context=${context} ===")
        // Load settings and configuration from DB
        viewModelScope.launch {
            Log.d("JarvisViewModel", "=== ViewModel init coroutine started ===")
            _appLanguage.value = repository.getPreference("app_language") ?: "English"
            _isDarkMode.value = repository.getPreference("dark_mode")?.toBoolean() ?: true
            _isTtsMuted.value = repository.getPreference("tts_muted")?.toBoolean() ?: false
            // Stored value may be an old rushed default (1.3) — treat
            // anything > 1.15 as invalid so users get natural speech.
            _ttsSpeechRate.value = when (val stored = repository.getPreference("tts_speech_rate")?.toFloatOrNull()) {
                null -> 1.0f
                else -> stored.coerceIn(0.7f, 1.15f)
            }
            val storedKey = repository.getPreference("core_ai_api_key") ?: ""
            val buildConfigKey = com.example.BuildConfig.GEMINI_API_KEY
            _coreAiApiKey.value = storedKey.ifBlank { buildConfigKey }

            val storedModel = repository.getPreference("gemini_model") ?: ""
            _geminiModel.value = if (storedModel.isBlank() || storedModel.contains("3.5") || storedModel.contains("3.0")) "gemini-2.5-flash" else storedModel
            _imageGenApiKey.value = repository.getPreference("image_gen_api_key") ?: ""
            _otherApiKey.value = repository.getPreference("other_api_key") ?: ""
            _activeProvider.value = repository.getPreference("active_provider") ?: (if (apiKeyStore?.getNvidiaKey()?.isNotBlank() == true) "nvidia_nim" else "gemini")
            _backendBaseUrl.value = repository.getPreference("backend_base_url") ?: ""
            // Use CallBridgeApi functions which handle fallback to shared prefs and defaults
            context?.let { ctx ->
                val callUrl = com.example.call.CallBridgeApi.callUrl(ctx)
                val msgUrl = com.example.call.CallBridgeApi.messagingUrl(ctx)
                Log.d("JarvisViewModel", "CallBridgeApi callUrl=$callUrl, messagingUrl=$msgUrl")
                _callServerUrl.value = callUrl
                _messagingServerUrl.value = msgUrl
            }
            // Start polling if messaging server URL is configured
            if (_messagingServerUrl.value.isNotBlank()) {
                Log.d("JarvisViewModel", "Starting polling, messagingServerUrl=${_messagingServerUrl.value}")
                startJarvisEventsPolling()
                // Threads feed the Messages tab — without this it stays
                // empty on every fresh launch (only setMessagingServerUrl
                // started it before).
                startJarvisThreadsPolling()
            } else {
                Log.d("JarvisViewModel", "No messaging server URL, not starting polling")
            }
            _nvidiaApiKey.value = apiKeyStore?.getNvidiaKey() ?: repository.getPreference("nvidia_api_key") ?: ""
            _tavilyApiKey.value = apiKeyStore?.getTavilyKey() ?: repository.getPreference("tavily_api_key") ?: ""
            _nvidiaBaseUrl.value = repository.getPreference("nvidia_base_url") ?: "https://integrate.api.nvidia.com/v1/"
            _nvidiaModel.value = repository.getPreference("nvidia_model") ?: "meta/llama-3.1-8b-instruct"
            _selectedPersonality.value = repository.getPreference("selected_personality") ?: "jarvis"
            _customSystemPrompt.value = repository.getPreference("custom_system_prompt") ?: ""
            _userMemories.value = repository.getPreference("user_memories") ?: ""
            _userName.value = repository.getPreference("user_name") ?: ""
            _isDeveloperMode.value = repository.getPreference("developer_mode")?.toBoolean() ?: true
            _isStreamingEnabled.value = repository.getPreference("streaming_enabled")?.toBoolean() ?: true
            _visionModel.value = repository.getPreference("vision_model") ?: "google/diffusiongemma-26b-a4b-it"
            _piperOnnxPath.value = repository.getPreference("piper_onnx_path") ?: "/storage/emulated/0/models"
            _gmailConnected.value = repository.getPreference("connector_gmail")?.toBoolean() ?: false
            _driveConnected.value = repository.getPreference("connector_drive")?.toBoolean() ?: false
            _calendarConnected.value = repository.getPreference("connector_calendar")?.toBoolean() ?: false
            _webSearchConnected.value = repository.getPreference("connector_web_search")?.toBoolean() ?: true
            _osBridgeConnected.value = repository.getPreference("connector_os_bridge")?.toBoolean() ?: true
            val storedWebhook = repository.getPreference("n8n_webhook_url") ?: ""
            _n8nWebhookUrl.value = storedWebhook.ifBlank { "https://primary.n8n.cloud/webhook/jarvis-action" }
            _n8nApiKey.value = repository.getPreference("n8n_api_key") ?: ""
            _n8nCustomHeaders.value = repository.getPreference("n8n_custom_headers") ?: "X-Source: JARVIS-Mobile, Content-Type: application/json"
            _n8nTriggerKeywords.value = repository.getPreference("n8n_trigger_keywords") ?: "n8n, webhook, automate, workflow, sync, trigger, deploy, pipeline, automation"
            _n8nAutoTriggerEnabled.value = repository.getPreference("n8n_auto_trigger_enabled")?.toBoolean() ?: true
            val webhookConfigured = storedWebhook.isNotBlank() && !storedWebhook.startsWith("https://primary.n8n.cloud/webhook/jarvis-action")
            _n8nConnected.value = repository.getPreference("connector_n8n")?.toBoolean() ?: webhookConfigured
            val storedCustom = repository.getPreference("custom_models_list") ?: ""
            if (storedCustom.isNotBlank()) {
                _customModelsList.value = storedCustom.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            _customProviders.value = loadCustomProviders(repository.getPreference("custom_providers") ?: "")
        }

        // Keep currentMessages updated when currentSessionId changes
        viewModelScope.launch {
            currentSessionId.collectLatest { sessionId ->
                if (sessionId != null) {
                    repository.getMessagesForSession(sessionId).collect { messages ->
                        _currentMessages.value = messages
                    }
                } else {
                    _currentMessages.value = emptyList()
                }
            }
        }
    }

    fun setDarkMode(dark: Boolean) {
        _isDarkMode.value = dark
        viewModelScope.launch {
            repository.savePreference("dark_mode", dark.toString())
        }
    }

    fun toggleTtsMute() {
        val next = !_isTtsMuted.value
        _isTtsMuted.value = next
        viewModelScope.launch {
            repository.savePreference("tts_muted", next.toString())
            if (next) {
                _stopTtsEvent.emit(Unit)
            }
        }
    }

    private var currentGenerationJob: Job? = null

    fun stopGenerating() {
        currentGenerationJob?.cancel()
        currentGenerationJob = null
        _isGenerating.value = false
        _generatingStatus.value = ""
        stopSpeaking()
    }

    fun stopSpeaking() {
        viewModelScope.launch {
            _stopTtsEvent.emit(Unit)
        }
    }

    fun speakText(text: String) {
        viewModelScope.launch {
            _speakTextEvent.emit(text)
        }
    }

    fun setSidebarOpen(open: Boolean) {
        _isSidebarOpen.value = open
    }

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun setVoiceMode(enabled: Boolean) {
        _isVoiceMode.value = enabled
    }

    fun setApiModalOpen(open: Boolean) {
        _isApiModalOpen.value = open
    }

    fun setN8nModalOpen(open: Boolean) {
        _isApiModalOpen.value = open
    }

    fun setPersonalityModalOpen(open: Boolean) {
        _isPersonalityModalOpen.value = open
    }

    fun setConnectorsModalOpen(open: Boolean) {
        _isConnectorsModalOpen.value = open
    }

    fun setSettingsModalOpen(open: Boolean) {
        _isSettingsModalOpen.value = open
    }

    private val _isJarvisChatOpen = MutableStateFlow(false)
    val isJarvisChatOpen: StateFlow<Boolean> = _isJarvisChatOpen.asStateFlow()

    fun setJarvisChatOpen(open: Boolean) {
        _isJarvisChatOpen.value = open
    }

    fun setModelSelectorOpen(open: Boolean) {
        _isModelSelectorOpen.value = open
    }

    fun setLiveVisionOpen(open: Boolean) {
        _isLiveVisionOpen.value = open
        if (!open) {
            _liveVisionAutoScan.value = false
        }
    }

    private val _isVisionChooserOpen = MutableStateFlow(false)
    val isVisionChooserOpen: StateFlow<Boolean> = _isVisionChooserOpen.asStateFlow()

    fun setVisionChooserOpen(open: Boolean) {
        _isVisionChooserOpen.value = open
    }

    private val _visionSource = MutableStateFlow("camera")
    val visionSource: StateFlow<String> = _visionSource.asStateFlow()

    fun setVisionSource(source: String) {
        _visionSource.value = if (source == "screen") "screen" else "camera"
    }

    fun setJarvisInboxOpen(open: Boolean, tab: Int = 0) {
        _isJarvisInboxOpen.value = open
        _jarvisInboxTab.value = tab
    }

    fun setLiveVisionAutoScan(auto: Boolean) {
        _liveVisionAutoScan.value = auto
    }

    fun analyzeLiveFrame(base64Image: String, customPrompt: String = "") {
        if (base64Image.isEmpty() || _isLiveVisionAnalyzing.value) return

        viewModelScope.launch {
            _isLiveVisionAnalyzing.value = true
            val activeVisionModel = _visionModel.value.ifBlank { "google/diffusiongemma-26b-a4b-it" }
            val prompt = customPrompt.ifBlank { "You are JARVIS live perception system powered by NVIDIA NIM. Analyze what is currently visible in this live camera/screen view. Describe key objects, text, UI elements, spatial orientation, and actionable insights concisely in 2-3 sentences." }

            val sysPrompt = "You are JARVIS, an advanced AI system analyzing real-time camera and screen visual feeds for the user. Speak direct, precise, intelligent visual analysis."

            val activeProv = _activeProvider.value.lowercase()
            val geminiKey = _coreAiApiKey.value.trim().ifEmpty { com.example.BuildConfig.GEMINI_API_KEY }
            val nvidiaKey = _nvidiaApiKey.value.trim().ifEmpty { _imageGenApiKey.value.trim() }.ifEmpty { com.example.BuildConfig.NVIDIA_API_KEY }

            val aiResponse = if (geminiKey.isNotEmpty()) {
                callGeminiApi(
                    apiKey = geminiKey,
                    modelName = _geminiModel.value,
                    history = emptyList(),
                    currentPrompt = prompt,
                    systemPrompt = sysPrompt,
                    imageDataBase64 = base64Image
                )
            } else if (nvidiaKey.isNotEmpty() || activeProv == "nvidia_nim") {
                val effectiveNvidiaKey = nvidiaKey.ifEmpty { geminiKey }
                callNvidiaNimApi(
                    apiKey = effectiveNvidiaKey,
                    baseUrl = _nvidiaBaseUrl.value,
                    modelName = activeVisionModel,
                    history = emptyList(),
                    currentPrompt = prompt,
                    systemPrompt = sysPrompt,
                    imageBase64Data = base64Image
                )
            } else {
                "Please configure your Gemini or NVIDIA NIM API Key in Settings to enable Live Vision Analysis."
            }

            _liveVisionResult.value = aiResponse
            if (_isVoiceMode.value && !_isTtsMuted.value && aiResponse.isNotBlank() && !aiResponse.startsWith("Please configure")) {
                _speakTextEvent.emit(aiResponse)
            }
            _isLiveVisionAnalyzing.value = false
        }
    }

    fun toggleConnector(connectorKey: String) {
        val newValue = when (connectorKey) {
            "gmail" -> { _gmailConnected.value = !_gmailConnected.value; _gmailConnected.value }
            "drive" -> { _driveConnected.value = !_driveConnected.value; _driveConnected.value }
            "calendar" -> { _calendarConnected.value = !_calendarConnected.value; _calendarConnected.value }
            "web_search" -> { _webSearchConnected.value = !_webSearchConnected.value; _webSearchConnected.value }
            "os_bridge" -> { _osBridgeConnected.value = !_osBridgeConnected.value; _osBridgeConnected.value }
            "n8n" -> { _n8nConnected.value = !_n8nConnected.value; _n8nConnected.value }
            else -> true
        }
        viewModelScope.launch {
            repository.savePreference("connector_$connectorKey", newValue.toString())
        }
        if (newValue) {
            runConnectorSelfTest(connectorKey)
        }
    }

    fun runConnectorSelfTest(key: String) {
        viewModelScope.launch {
            val appCtx = context ?: com.example.JarvisApplication.instance
            val result: String = when (key) {
                "gmail" -> {
                    if (appCtx != null) com.example.connectors.GoogleAppsBridge.selfTestGmail(appCtx)
                    else "Could not access app context"
                }
                "drive" -> {
                    if (appCtx != null) com.example.connectors.GoogleAppsBridge.selfTestDrive(appCtx)
                    else "Could not access app context"
                }
                "calendar" -> {
                    val engine = appCtx?.let { com.example.data.DeviceCalendarEngine(it) }
                    engine?.selfTest() ?: "Could not access app context"
                }
                "web_search" -> {
                    if (com.example.connectors.GoogleAppsBridge.isNetworkReachable()) {
                        val tavily = if (_tavilyApiKey.value.isNotBlank()) "Tavily key set" else "free scraping mode"
                        "Connected - internet reachable ($tavily)"
                    } else "No network connection"
                }
                "os_bridge" -> {
                    val pm = appCtx?.packageManager
                    val hasFlash = pm?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH) == true
                    val hasCam = pm?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) == true
                    val hasTelephony = pm?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY) == true
                    "Connected - flashlight ${if (hasFlash) "yes" else "no"}, camera ${if (hasCam) "yes" else "no"}, calls ${if (hasTelephony) "yes" else "no"}"
                }
                "n8n" -> {
                    val webhook = _n8nWebhookUrl.value.trim()
                    if (webhook.isBlank() || webhook.startsWith("https://primary.n8n.cloud")) {
                        "Add your own webhook URL in API Configuration first"
                    } else {
                        val res = com.example.api.N8nClient.triggerWebhook(
                            webhookUrl = webhook,
                            eventName = "connection_test",
                            payloadData = mapOf("test" to true),
                            apiKey = _n8nApiKey.value.trim().ifBlank { null },
                            customHeaders = parseN8nHeaders(_n8nCustomHeaders.value)
                        )
                        if (res.success) "Connected - webhook responded HTTP ${res.statusCode}"
                        else res.errorMessage ?: "Connection failed"
                    }
                }
                else -> "Unknown connector"
            }
            _connectorTestLog.value = "$key|$result|${System.currentTimeMillis()}"
        }
    }

    private fun parseN8nHeaders(raw: String): Map<String, String> =
        raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2) parts[0].trim() to parts.drop(1).joinToString(":").trim()
            else null
        }.toMap()

    private fun extractN8nText(body: String): String {
        if (body.isBlank()) return ""
        val cleaned = body.replace(Regex("\\{+"), "").replace(Regex("\\}+"), "").trim()
        try {
            val start = cleaned.indexOf('"')
            val end = cleaned.lastIndexOf('"')
            if (start >= 0 && end > start) {
                return cleaned.substring(start + 1, end).replace("\\n", "\n").replace("\\\"", "\"")
            }
        } catch (e: Exception) {
            // fall through to raw
        }
        return cleaned.take(600)
    }

    fun selectModel(provider: String, modelId: String) {
        _activeProvider.value = provider
        if (provider == "nvidia_nim") {
            _nvidiaModel.value = modelId
        } else {
            _geminiModel.value = modelId
        }
        _isModelSelectorOpen.value = false
        viewModelScope.launch {
            repository.savePreference("active_provider", provider)
            if (provider == "nvidia_nim") {
                repository.savePreference("nvidia_model", modelId)
            } else {
                repository.savePreference("gemini_model", modelId)
            }
        }
    }

    fun savePersonality(personalityId: String, customPrompt: String) {
        _selectedPersonality.value = personalityId
        _customSystemPrompt.value = customPrompt
        viewModelScope.launch {
            repository.savePreference("selected_personality", personalityId)
            repository.savePreference("custom_system_prompt", customPrompt)
        }
    }

    fun setAttachmentMenuOpen(open: Boolean) {
        _isAttachmentMenuOpen.value = open
    }

    fun saveApiKeys(core: String, model: String, image: String, other: String) {
        _coreAiApiKey.value = core
        _geminiModel.value = model
        _imageGenApiKey.value = image
        _otherApiKey.value = other
        viewModelScope.launch {
            repository.savePreference("core_ai_api_key", core)
            repository.savePreference("gemini_model", model)
            repository.savePreference("image_gen_api_key", image)
            repository.savePreference("other_api_key", other)
        }
    }

    fun saveNvidiaConfig(apiKey: String, baseUrl: String, model: String) {
        val trimmedKey = apiKey.trim()
        _nvidiaApiKey.value = trimmedKey
        _nvidiaBaseUrl.value = baseUrl
        _nvidiaModel.value = model
        apiKeyStore?.setNvidiaKey(trimmedKey)
        if (trimmedKey.isNotEmpty()) {
            _activeProvider.value = "nvidia_nim"
        }
        viewModelScope.launch {
            repository.savePreference("nvidia_api_key", trimmedKey)
            repository.savePreference("nvidia_base_url", baseUrl)
            repository.savePreference("nvidia_model", model)
            if (trimmedKey.isNotEmpty()) {
                repository.savePreference("active_provider", "nvidia_nim")
            }
        }
    }

    fun saveTavilyKey(key: String) {
        val trimmed = key.trim()
        _tavilyApiKey.value = trimmed
        apiKeyStore?.setTavilyKey(trimmed)
        viewModelScope.launch {
            repository.savePreference("tavily_api_key", trimmed)
        }
    }

    fun generateNvidiaImage(prompt: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            _isGenerating.value = true
            _generatingStatus.value = "Creating..."
            
            val statusJob = viewModelScope.launch {
                kotlinx.coroutines.delay(1500)
                if (_isGenerating.value) _generatingStatus.value = "Adding finishing touches..."
                kotlinx.coroutines.delay(1500)
                if (_isGenerating.value) _generatingStatus.value = "Finalizing image rendering..."
            }

            val nvidiaKey = _nvidiaApiKey.value.trim().ifEmpty { _imageGenApiKey.value.trim() }.ifEmpty { com.example.BuildConfig.NVIDIA_API_KEY }
            val appCtx = context ?: com.example.JarvisApplication.instance

            var resultPath: String? = null
            var providerName = ""

            if (appCtx != null) {
                val engine = com.example.api.ImageGenEngine(appCtx)
                val generated = engine.generateImage(
                    prompt = prompt,
                    apiKey = _imageGenApiKey.value.trim(),
                    nvidiaApiKey = nvidiaKey,
                    openAfter = false
                )
                if (generated != null) {
                    resultPath = generated.path
                    providerName = generated.provider
                }
            }

            if (resultPath == null && nvidiaKey.isNotBlank()) {
                val base64Img = com.example.api.NvidiaImageGenerator.generateImageBase64FromNvidia(prompt, nvidiaKey)
                if (base64Img != null) {
                    val imgDataUri = "data:image/png;base64,$base64Img"
                    statusJob.cancel()
                    _isGenerating.value = false
                    _generatingStatus.value = ""
                    repository.insertMessage(
                        ChatMessage(
                            sessionId = sessionId,
                            text = "Here is your generated image for prompt: \"$prompt\"",
                            sender = "ai",
                            fileUri = imgDataUri,
                            fileName = "NVIDIA_SD3.5_${System.currentTimeMillis()}.png",
                            fileSize = "NVIDIA SD 3.5 Large 1024x1024"
                        )
                    )
                    return@launch
                }
            }

            statusJob.cancel()
            _isGenerating.value = false
            _generatingStatus.value = ""

            if (resultPath != null) {
                val safeProvider = providerName.replace(Regex("[^A-Za-z0-9.]+"), "_")
                repository.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        text = "Here is your generated image for prompt: \"$prompt\"",
                        sender = "ai",
                        fileUri = resultPath,
                        fileName = "${safeProvider}_${System.currentTimeMillis()}.png",
                        fileSize = "$providerName · 1024x1024"
                    )
                )
            } else {
                repository.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        text = "Failed to generate image for \"$prompt\" — all providers were unavailable. Add an NVIDIA API key in Settings to enable the SD 3.5 fallback.",
                        sender = "ai"
                    )
                )
            }
        }
    }

    fun setAttachedFile(file: AttachedFile?) {
        _attachedFile.value = file
    }

    fun setVoiceRmsDb(value: Float) {
        _voiceRmsDb.value = value
    }

    fun setVoicePartialText(value: String) {
        _voicePartialText.value = value
    }

    fun startNewChat() {
        _chatState.value = ChatUiState.HOME
        _currentSessionId.value = null
        _isVoiceMode.value = false
        _attachedFile.value = null
    }

    fun selectSession(session: ChatSession) {
        _currentSessionId.value = session.id
        _chatState.value = ChatUiState.CHATTING
        _isVoiceMode.value = false
        _attachedFile.value = null
    }

    fun deleteSession(session: ChatSession) {
        viewModelScope.launch {
            repository.deleteSession(session.id)
            if (_currentSessionId.value == session.id) {
                startNewChat()
            }
        }
    }

    fun handleSend(textToSend: String) {
        val trimmed = textToSend.trim()
        val file = _attachedFile.value
        if (trimmed.isEmpty() && file == null) return

        currentGenerationJob?.cancel()
        currentGenerationJob = viewModelScope.launch {
            var sessionId = _currentSessionId.value
            if (sessionId == null) {
                // Create a new session with the prompt as its title
                val displayTitle = when {
                    trimmed.isNotEmpty() -> if (trimmed.length > 25) trimmed.take(22) + "..." else trimmed
                    file != null -> "File: ${file.name}"
                    else -> "New Chat"
                }
                val newSession = ChatSession(title = displayTitle)
                sessionId = repository.insertSession(newSession)
                _currentSessionId.value = sessionId
                _chatState.value = ChatUiState.CHATTING
            }

            // 1. Insert user message with attached file details if any
            val userMsgText = if (trimmed.isEmpty() && file != null) {
                "[Attached File: ${file.name}]"
            } else {
                trimmed
            }

            val userMsg = ChatMessage(
                sessionId = sessionId,
                text = userMsgText,
                sender = "user",
                fileUri = file?.uri,
                fileName = file?.name,
                fileSize = file?.size
            )
            repository.insertMessage(userMsg)
            _inputText.value = ""
            _attachedFile.value = null // clear attachment after sending

            // Check if user input contains any n8n trigger keywords & auto-trigger webhook
            checkAndTriggerN8nKeywordAutomation(trimmed, sessionId)

            _isGenerating.value = true
            val isHardLogic = isHardLogicalOrComplexQuery(trimmed)
            _generatingStatus.value = if (isHardLogic) "Thinking..." else ""
            if (_isVoiceMode.value) {
                _voiceStatus.value = if (isHardLogic) "Thinking..." else "Listening..."
            }

            try {
                val lowerTrimmed = trimmed.lowercase()

                // Build full prompt with file context if it is text or pdf
                val textToSendWithFileContext = if (file?.type == "text" && !file.content.isNullOrEmpty()) {
                    "--- FILE CONTENT ATTACHED (${file.name}) ---\n" +
                    "${file.content}\n" +
                    "--- END OF FILE CONTENT ---\n\n" +
                    trimmed
                } else if (file?.type == "pdf") {
                    "--- ATTACHED PDF DOCUMENT: ${file.name} (Cosmos Vision Analysis) ---\n" +
                    trimmed
                } else {
                    trimmed
                }

                // 1. Check for deep device automation & app action commands (Sub-second local response)
                val activePersonality = _selectedPersonality.value
                val salutation = if (activePersonality == "friday") "Boss" else "sir"

                val fastMatch = matchFastLocalAutomation(trimmed, salutation)
                val appAction = fastMatch?.first
                val actionReplyText = fastMatch?.second

                if (appAction != null && actionReplyText != null) {
                    _appIntentEvent.tryEmit(appAction)
                    val assistMsg = ChatMessage(
                        sessionId = sessionId,
                        sender = "ai",
                        text = actionReplyText,
                        timestamp = System.currentTimeMillis()
                    )
                    repository.insertMessage(assistMsg)
                    // Voice mode depends on this flag: the mic re-arm after a
                    // response is guarded by !isGenerating, and every other
                    // exit path resets it. Without this reset a local command
                    // left it stuck true and the mic went permanently deaf -
                    // voice mode became one-shot.
                    _isGenerating.value = false
                    android.util.Log.d("VoiceLoop", "fast-local path done (action=$appAction), isGenerating reset")
                    // Always emit in voice mode: the muted case is handled by
                    // the collector (it re-arms the mic directly instead of
                    // speaking), so a muted voice mode keeps listening too.
                    if (_isVoiceMode.value) {
                        _speakTextEvent.emit(actionReplyText)
                    }
                    return@launch
                }

                // Self-config fast path: voice speed commands execute instantly
                val speedIntent = detectTtsSpeedIntent(lowerTrimmed)
                if (speedIntent != null) {
                    val clamped = speedIntent.coerceIn(0.7f, 2.0f)
                    setTtsSpeechRate(clamped)
                    val newRate = String.format(java.util.Locale.US, "%.2f", _ttsSpeechRate.value)
                    val speedReply = when {
                        speedIntent >= 1.2f -> "Done — speaking faster now, at ${newRate}x."
                        speedIntent <= 0.8f -> "Done — speaking slower now, at ${newRate}x."
                        else -> "Speaking speed set to ${newRate}x."
                    }
                    repository.insertMessage(ChatMessage(sessionId = sessionId, sender = "ai", text = speedReply, timestamp = System.currentTimeMillis()))
                    _isGenerating.value = false
                    _generatingStatus.value = ""
                    if (_isVoiceMode.value) {
                        _voiceStatus.value = "Speaking..."
                        _speakTextEvent.emit(speedReply)
                    }
                    return@launch
                }

                // Auto-capture user name if stated
                if (lowerTrimmed.contains("my name is ")) {
                    val namePart = trimmed.substringAfter("my name is ", "").substringBefore(".").substringBefore(",").trim()
                    if (namePart.isNotEmpty()) {
                        setUserName(namePart.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() })
                    }
                } else if (lowerTrimmed.contains("call me ")) {
                    val namePart = trimmed.substringAfter("call me ", "").substringBefore(".").substringBefore(",").trim()
                    if (namePart.isNotEmpty()) {
                        setUserName(namePart.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() })
                    }
                }

                // Auto-capture user facts/memories: important knowledge is
                // saved without being asked, so AUREX keeps learning
                if (trimmed.length >= 12) {
                    val memoryPatterns = listOf(
                        "remember that", "remember my", "my name is", "i am ",
                        "i like", "i love", "i prefer", "my favorite", "my favourite",
                        "i hate", "i'm allergic", "i am allergic", "don't forget",
                        "do not forget", "note that ", "keep in mind that", "important:",
                        "always ", "never ", "from now on", "call me ", "my exam",
                        "my birthday", "i work", "i live", "my number is"
                    )
                    if (memoryPatterns.any { lowerTrimmed.contains(it) }) {
                        val newFact = "- $trimmed"
                        val current = _userMemories.value.trim()
                        if (!current.contains(trimmed, ignoreCase = true)) {
                            val updated = if (current.isEmpty()) newFact else "$current\n$newFact"
                            updateMemories(updated)
                        }
                    }
                }

                // Calculate active system prompt based on chosen AI personality, time of day, voice mode, and stored memories
                val baseSysPrompt = AiPersonalities.getSystemPrompt(
                    id = _selectedPersonality.value,
                    customPrompt = _customSystemPrompt.value
                )

                val currentUserName = _userName.value
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeGreeting = when (hour) {
                    in 0..11 -> "Good morning"
                    in 12..16 -> "Good afternoon"
                    in 17..20 -> "Good evening"
                    else -> "Good night"
                }

                val nowMs = System.currentTimeMillis()
                val sdfFull = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy 'at' hh:mm:ss a z", java.util.Locale.US)
                val exactRealTimeStr = sdfFull.format(java.util.Date(nowMs))

                var sysPrompt = "$baseSysPrompt\n\n[CONTEXT & OPERATIONAL RULES]:\n" +
                        "- Primary User Identity: User's name is '$currentUserName'. Speak with him in a normal, friendly, natural conversational way — like a close companion. Do NOT append 'sir' to sentences and NEVER combine his name with sir. Use 'Sir' ONLY for strong emphasis moments: critical alerts, important confirmations, or when he explicitly asks for it. In everyday conversation there should be NO 'sir' at all.\n" +
                        "- EXACT REAL-TIME CLOCK: Current real date and time is $exactRealTimeStr. Always use this exact real-time value when asked for time, date, day, or schedule.\n" +
                        "- Current Time-Aware Greeting: '$timeGreeting, $currentUserName'. Use short, polite greetings matching this when greeting $currentUserName.\n" +
                        "- Web Search Instruction: NEVER output [WEB_SEARCH: ...] unless the user explicitly asked you to 'search google for...' or 'search web for...'. For all medical definitions, health queries, general knowledge, explanations, concepts, and everyday questions, provide a clear, direct answer in your response.\n" +
                        (if (_appLanguage.value == "Telugu") "- PRIMARY LANGUAGE RULE: Main language is set to Telugu (తెలుగు). Respond in polite, natural Telugu." else "- PRIMARY LANGUAGE RULE: Main language is set to ENGLISH. You MUST respond ALWAYS in clear, natural, high-quality English. Do NOT answer in Telugu unless $currentUserName explicitly types in Telugu script.") + "\n" +
                        "- STRICT BOUNDARY: Do NOT mention 'Stark Industries', 'Tony Stark', 'Stark Tower', or Marvel fiction under any circumstances. You are a real AI assistant for $currentUserName."

                if (_isVoiceMode.value) {
                    sysPrompt += """

[REAL-TIME VOICE MODE INSTRUCTIONS]:
You are operating as a natural, real-time voice AI assistant in direct spoken conversation with $currentUserName.
- PRIMARY GOAL: Speak naturally, efficiently, and intelligently while maintaining a smooth conversational flow.
- PERSONALITY: Friendly, confident, calm, patient, curious when appropriate, never robotic, never overly verbose.
- VOICE STYLE: Use conversational language (e.g., "Here's what I found", "Yeah, so..."). Use contractions naturally (I'm, You're, That's, It's, We'll).
- RESPONSE LENGTH: Keep responses short and conversational (1 to 3 sentences maximum, or 1 sentence for greetings) unless detailed steps are requested.
- NO MARKDOWN OR PUNCTUATION SYMBOLS: Never output markdown formatting (no bold **, no code blocks, no bullet points, no numbered lists). Convert numbers naturally (e.g. 2026 -> twenty twenty-six).
- INTERRUPTIONS: If user interrupts, stop immediately and answer the newest speech.
"""
                }

                val memories = _userMemories.value.trim()
                var memoryBlock = "- User Name: $currentUserName\n"
                if (memories.isNotEmpty()) {
                    memoryBlock += memories + "\n"
                }
                val unifiedContext = unifiedMemoryEngine.buildContext(trimmed)
                if (unifiedContext.isNotBlank()) {
                    memoryBlock += unifiedContext
                }
                sysPrompt += "\n\n[LONG-TERM MEMORIES & STORED USER FACTS]:\n$memoryBlock"

                // Inject active connector statuses into system prompt
                val connectorStatus = StringBuilder("\n\n[ACTIVE INTEGRATIONS & CONNECTORS STATUS]:\n")
                connectorStatus.append("- Google Mail (Gmail): ${if (_gmailConnected.value) "CONNECTED & ACTIVE (Compose real email drafts and search the mailbox via the Gmail app - triggered when user says 'compose email' or 'search emails')" else "DISABLED BY USER (If user asks about emails, inform them to enable Gmail in Connectors & Apps)"}\n")
                connectorStatus.append("- Google Drive & Docs: ${if (_driveConnected.value) "CONNECTED & ACTIVE (Open Google Drive app and upload/share files to Drive)" else "DISABLED BY USER"}\n")
                connectorStatus.append("- Google Calendar: ${if (_calendarConnected.value) "CONNECTED & ACTIVE (Read upcoming events from this device's calendar and create real events - triggered by 'my agenda', 'add event')" else "DISABLED BY USER"}\n")
                connectorStatus.append("- Android OS System Bridge: ${if (_osBridgeConnected.value) "CONNECTED & ACTIVE (Native device controls, calls, apps, flashlight, volume, alarms)" else "DISABLED BY USER (If asked for phone/system actions, mention OS Bridge Connector is off)"}\n")
                connectorStatus.append("- Live Web Search: ${if (_webSearchConnected.value || _tavilyApiKey.value.isNotBlank()) "CONNECTED & ACTIVE (Real-time Tavily search & live web scraping)" else "DISABLED BY USER"}\n")

                sysPrompt += connectorStatus.toString()

                sysPrompt += "\n\n[SELF-AWARENESS — YOUR CURRENT SETTINGS]:\n" +
                        "- Active model: ${_nvidiaModel.value}\n" +
                        "- TTS speaking speed: ${String.format(java.util.Locale.US, "%.2f", _ttsSpeechRate.value)}x\n" +
                        "- Token streaming: ${if (_isStreamingEnabled.value) "on" else "off"}\n" +
                        "- Dark mode: ${if (_isDarkMode.value) "on" else "off"}\n" +
                        "- Language: ${_appLanguage.value}\n" +
                        "\n[SELF-CONFIGURATION CAPABILITY — YOU CAN CHANGE YOURSELF]:\n" +
                        "When $currentUserName asks you to change your own settings, NEVER say you can't — compute the new value FROM YOUR CURRENT STATE above and append exactly one tag at the END of your reply: [SETTING: key=value]\n" +
                        "Supported keys:\n" +
                        "- tts_speed=<0.7 to 2.0>  RELATIVE changes: 'speak faster / increase tts speed' → current speed + 0.2; 'speak slower / slow down' → current − 0.2; 'normal speed' → tts_speed=1.0\n" +
                        "- model=<model_id>  (e.g. model=google/gemini-2.5-flash)\n" +
                        "- dark_mode=on|off, streaming=on|off, language=english|telugu\n" +
                        "Example: user says 'increase the tts speed' with your current speed 1.00 → reply 'Done — speaking faster now.' followed by [SETTING: tts_speed=1.20]\n" +
                        "Rules: only use the tag when the user clearly asks for a settings change. Briefly confirm in your reply. The tag vanishes from the visible chat.\n" +
                        "\n[MEMORY & SELF-IMPROVEMENT]:\n" +
                        "When $currentUserName shares a durable personal fact, preference, or standing instruction (e.g. 'I like pizza', 'my exam is on March 3rd', 'always answer briefly', 'I live in Guntur'), append [MEMORY: <one short factual sentence>] at the END of your reply. Save only genuinely reusable knowledge, never small talk. These memories persist across sessions — they make you smarter over time, like JARVIS learning his master's habits.\n"

                if (_webSearchConnected.value || _tavilyApiKey.value.isNotBlank()) {
                    sysPrompt += "\n\n[WEB SEARCH CAPABILITY]: If you don't know the answer to something, or the answer concerns real-time info, sports, weather, or recent news after your training data, respond with exactly 'NEEDS_SEARCH: <concise search query>' and nothing else."
                }

                // Inject detected user mood into system prompt
                val detectedMood = detectMood(trimmed)
                val moodInstruction = getMoodInstruction(detectedMood)
                sysPrompt += "\n\n$moodInstruction"

                // ── CONNECTOR ACTIONS — Calendar (real on-device CalendarProvider) ──
                val isCalendarAdd = lowerTrimmed.contains("add event") ||
                        lowerTrimmed.contains("create event") ||
                        lowerTrimmed.startsWith("schedule ") ||
                        (lowerTrimmed.contains("schedule") && lowerTrimmed.contains("meeting"))
                val isCalendarRead = !isCalendarAdd && (
                        lowerTrimmed.contains("calendar") ||
                                lowerTrimmed.contains("agenda") ||
                                lowerTrimmed.contains("my events") ||
                                lowerTrimmed.contains("my meetings") ||
                                lowerTrimmed.contains("upcoming events")
                        )

                if (_calendarConnected.value && (isCalendarAdd || isCalendarRead)) {
                    val appCtx = context ?: com.example.JarvisApplication.instance
                    if (appCtx != null) {
                        _generatingStatus.value = "Accessing your calendar..."
                        val engine = com.example.data.DeviceCalendarEngine(appCtx)

                        if (!com.example.data.DeviceCalendarEngine.hasPermissions(appCtx)) {
                            _generatingStatus.value = ""
                            _isGenerating.value = false
                            repository.insertMessage(
                                ChatMessage(
                                    sessionId = sessionId,
                                    text = "I need calendar access for that. Grant the permission in the prompt that is opening now, then ask me again.",
                                    sender = "ai"
                                )
                            )
                            _requestCalendarPermissions.emit(Unit)
                            return@launch
                        }

                        if (isCalendarAdd) {
                            var title = trimmed.replace(Regex("(?i)\\b(add|create|schedule)\\s+(an?\\s+)?(event|meeting|reminder)\\b"), "")
                                .replace(Regex("(?i)^\\s*(for|on|at|about|to)\\s+"), "").trim()
                            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1) }
                            if (title.startsWith("tomorrow", true)) {
                                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                                title = title.replace(Regex("(?i)^tomorrow\\s*(at|on)?\\s*"), "").trim()
                            }
                            if (title.isNotEmpty()) {
                                val timeMatch = Regex("(?i)\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(title)
                                timeMatch?.let { m ->
                                    var hour = m.groupValues[1].toInt()
                                    val minute = m.groupValues[2].ifBlank { "0" }.toInt()
                                    when (m.groupValues[3].lowercase()) {
                                        "pm" -> if (hour < 12) hour += 12
                                        "am" -> if (hour == 12) hour = 0
                                        else -> {}
                                    }
                                    cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                                    cal.set(java.util.Calendar.MINUTE, minute)
                                    title = title.replace(m.value, "").replace(Regex("\\s{2,}"), " ").trim()
                                }
                            }
                            if (title.isBlank()) title = "New Event"

                            val (ok, detail) = engine.createEvent(
                                title = title.replaceFirstChar { it.uppercase() },
                                startMillis = cal.timeInMillis,
                                durationMinutes = 30
                            )
                            _generatingStatus.value = ""
                            _isGenerating.value = false
                            val replyText = when {
                                ok -> {
                                    val whenStr = java.text.SimpleDateFormat("EEE, d MMM 'at' h:mm a", java.util.Locale.US).format(cal.time)
                                    "Event created: \"$title\" on $whenStr. It is now in your device calendar."
                                }
                                detail == "NO_CALENDAR_ACCOUNT" -> "No calendar account found on this device. Add a Google account in Settings first."
                                else -> "Could not create the calendar event ($detail). Please try again."
                            }
                            repository.insertMessage(ChatMessage(sessionId = sessionId, text = replyText, sender = "ai"))
                            return@launch
                        } else {
                            val events = engine.getUpcomingEvents(daysAhead = 7, limit = 10)
                            _generatingStatus.value = ""
                            _isGenerating.value = false
                            val replyText = if (events != null) {
                                engine.formatEvents(events)
                            } else {
                                "I could not read your calendar right now."
                            }
                            repository.insertMessage(ChatMessage(sessionId = sessionId, text = replyText, sender = "ai"))
                            return@launch
                        }
                    }
                }

                // ── CONNECTOR ACTIONS — Gmail (compose, or query inbox inside AUREX via n8n) ──
                val isEmailCompose = lowerTrimmed.contains("compose email") ||
                        lowerTrimmed.contains("send email") ||
                        lowerTrimmed.contains("write an email") ||
                        lowerTrimmed.contains("draft email")
                val isEmailCheck = !isEmailCompose && (
                        lowerTrimmed.contains("check") && (lowerTrimmed.contains("email") || lowerTrimmed.contains("mail") || lowerTrimmed.contains("inbox")) ||
                                lowerTrimmed.contains("any email") ||
                                lowerTrimmed.contains("unread email") ||
                                lowerTrimmed.contains("my inbox") ||
                                lowerTrimmed.contains("show my email") ||
                                lowerTrimmed.contains("read email") ||
                                lowerTrimmed.contains("my emails") ||
                                lowerTrimmed.contains("new mail") ||
                                lowerTrimmed.contains("did i get any mail")
                        )

                if (_gmailConnected.value && (isEmailCompose || isEmailCheck)) {
                    val appCtx = context ?: com.example.JarvisApplication.instance
                    if (appCtx != null) {
                        if (isEmailCompose) {
                            _generatingStatus.value = ""
                            _isGenerating.value = false
                            // Parse "<verb> email to <name> about|saying|that says <body>"
                            // The "to X" clause becomes the recipient; the rest
                            // becomes the message body.
                            var toArg: String? = null
                            var bodyText = trimmed
                            val toMatch = Regex("(?i)\\bemail\\s+to\\s+([\\w .'-]+?)(?:\\s+(?:about|saying|that says|regarding|with|telling)\\b|\\s*$)").find(trimmed)
                            if (toMatch != null) {
                                toArg = toMatch.groupValues[1].trim()
                                bodyText = trimmed.replace(toMatch.value, "").trim()
                            }
                            bodyText = bodyText
                                .replace(Regex("(?i)\\b(compose|send|write|draft)\\s+(an?\\s+)?email\\b"), "")
                                .replace(Regex("(?i)^\\s*(about|saying|that says|for)\\s+"), "").trim()
                            val subject = bodyText.take(60).ifBlank { "Draft from AUREX" }
                            val launched = com.example.connectors.GoogleAppsBridge.composeEmail(
                                context = appCtx,
                                subject = subject,
                                body = bodyText.ifBlank { null }
                            )
                            val replyText = if (launched) {
                                "Opening a new email draft${if (toArg != null) " to $toArg" else ""}${if (bodyText.isNotBlank()) " with your notes pre-filled" else ""}. Review it and hit send."
                            } else {
                                "Could not open any email app on this device."
                            }
                            repository.insertMessage(ChatMessage(sessionId = sessionId, text = replyText, sender = "ai"))
                            return@launch
                        } else {
                            val query = trimmed
                                .replace(Regex("(?i)\\b(check|show|read|any)\\b"), " ")
                                .replace(Regex("(?i)\\b(my )?(emails?|mail|inbox)\\b"), " ")
                                .replace(Regex("\\s+"), " ").trim()
                                .ifBlank { "recent emails" }

                            val webhook = _n8nWebhookUrl.value.trim()
                            val configured = webhook.isNotBlank() && !webhook.startsWith("https://primary.n8n.cloud")

                            _generatingStatus.value = "Checking your email..."
                            _isGenerating.value = true

                            if (!configured) {
                                _generatingStatus.value = ""
                                _isGenerating.value = false
                                repository.insertMessage(
                                    ChatMessage(
                                        sessionId = sessionId,
                                        text = "To read your inbox I need an n8n Gmail workflow connected. Add your n8n webhook in API Configuration → Automations (build a Gmail 'list/search' workflow there), then I'll summarize your emails right here in the app.",
                                        sender = "ai"
                                    )
                                )
                                return@launch
                            }

                            triggerN8nWorkflow("gmail_query", "query: $query") { ok, body ->
                                _generatingStatus.value = ""
                                _isGenerating.value = false
                                val text = if (ok) {
                                    extractN8nText(body).ifBlank { "Your email workflow ran but returned nothing readable." }
                                } else {
                                    "Email check failed: ${body.take(200)}"
                                }
                                viewModelScope.launch {
                                    repository.insertMessage(ChatMessage(sessionId = sessionId, text = text, sender = "ai"))
                                }
                                if (_isVoiceMode.value && !_isTtsMuted.value && ok) {
                                    _speakTextEvent.tryEmit(text)
                                }
                            }
                            return@launch
                        }
                    }
                }

                // ── CONNECTOR ACTIONS — Drive (open app or upload current file) ──
                val isDriveOpen = lowerTrimmed.contains("open drive") || lowerTrimmed.contains("open google drive")

                if (_driveConnected.value && isDriveOpen) {
                    val appCtx = context ?: com.example.JarvisApplication.instance
                    if (appCtx != null) {
                        _generatingStatus.value = ""
                        _isGenerating.value = false
                        val launched = com.example.connectors.GoogleAppsBridge.openDrive(appCtx)
                        repository.insertMessage(
                            ChatMessage(
                                sessionId = sessionId,
                                text = if (launched) "Google Drive is open." else "Could not open Drive - no app and no browser available.",
                                sender = "ai"
                            )
                        )
                        return@launch
                    }
                }

                // ── DEVICE CONTROL — Wi-Fi / Internet / Bluetooth / Airplane mode ──
                val deviceActionish = Regex("\\b(turn|switch|put|enable|disable|open|toggle|set|settings|panel)\\b|\\bon\\b|\\boff\\b")
                    .containsMatchIn(lowerTrimmed)
                val wantsWifi = lowerTrimmed.contains("wifi") || lowerTrimmed.contains("wi-fi") ||
                        ((lowerTrimmed.contains("internet") || lowerTrimmed.contains("mobile data")) && deviceActionish)
                val wantsBluetooth = lowerTrimmed.contains("bluetooth")
                val wantsAirplane = lowerTrimmed.contains("airplane mode") || lowerTrimmed.contains("aeroplane mode") ||
                        ((lowerTrimmed.contains("airplane") || lowerTrimmed.contains("aeroplane")) && deviceActionish)

                if (_osBridgeConnected.value && (wantsWifi || wantsBluetooth || wantsAirplane)) {
                    val isOnCmd = Regex("\\b(turn|switch|put)?\\s*(on)\\b|\\benable\\b").containsMatchIn(lowerTrimmed)
                    val isOffCmd = Regex("\\b(turn|switch)?\\s*(off)\\b|\\bdisable\\b").containsMatchIn(lowerTrimmed)
                    val isSettingsOnly = lowerTrimmed.contains("settings") || lowerTrimmed.contains("panel")

                    val appCtx = context ?: com.example.JarvisApplication.instance
                    if (appCtx != null) {
                        _generatingStatus.value = ""
                        _isGenerating.value = false
                        val resultText = when {
                            wantsAirplane -> com.example.connectors.DeviceControlBridge.toggleAirplaneMode(
                                appCtx,
                                when {
                                    isOnCmd && !isOffCmd -> true
                                    isOffCmd && !isOnCmd -> false
                                    else -> null
                                }
                            )
                            wantsBluetooth && !isSettingsOnly ->
                                com.example.connectors.DeviceControlBridge.toggleBluetooth(appCtx)
                            wantsWifi && isSettingsOnly ->
                                com.example.connectors.DeviceControlBridge.openWifiSettings(appCtx)
                            else ->
                                com.example.connectors.DeviceControlBridge.toggleWifi(appCtx)
                        }
                        repository.insertMessage(
                            ChatMessage(sessionId = sessionId, text = resultText, sender = "ai")
                        )
                        // Speak the outcome in voice mode. Without this the
                        // toggle worked silently, onSpeakingFinished never
                        // fired, and the mic never re-armed - conversation dead.
                        // The muted case re-arms the mic in the collector.
                        android.util.Log.d("VoiceLoop", "device-control done: $resultText")
                        if (_isVoiceMode.value) {
                            _speakTextEvent.emit(resultText)
                        }
                        return@launch
                    }
                }

                // Check for Video Generation intent (NVIDIA Cosmos / SVD)
                val isVideoGenRequest = lowerTrimmed.contains("generate video") ||
                        lowerTrimmed.contains("generate a video") ||
                        lowerTrimmed.contains("create video") ||
                        lowerTrimmed.contains("create a video") ||
                        lowerTrimmed.contains("make video") ||
                        lowerTrimmed.contains("make a video") ||
                        lowerTrimmed.contains("video of") ||
                        lowerTrimmed.contains("video generation") ||
                        lowerTrimmed.contains("animate video")

                if (isVideoGenRequest) {
                    _generatingStatus.value = "Generating video with Stable Video Diffusion..."
                    val statusJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(2000)
                        if (_isGenerating.value) _generatingStatus.value = "Rendering motion frames..."
                        kotlinx.coroutines.delay(3000)
                        if (_isGenerating.value) _generatingStatus.value = "Finalizing video clip encoding..."
                    }

                    val videoPrompt = trimmed
                        .replace(Regex("(?i)^\\b(generate video of|generate a video of|generate video|generate a video|create video of|create a video of|create video|make video of|make a video of|make video|video of|animate video of|animate video)\\b"), "")
                        .trim().ifEmpty { trimmed }

                    val nvidiaKey = _nvidiaApiKey.value.trim().ifEmpty { _imageGenApiKey.value.trim() }.ifEmpty { com.example.BuildConfig.NVIDIA_API_KEY }
                    val appCtx = context ?: com.example.JarvisApplication.instance
                    if (appCtx == null) {
                        statusJob.cancel()
                        _isGenerating.value = false
                        return@launch
                    }
                    
                    val videoResult = com.example.api.NvidiaVideoGenerator.generateVideo(videoPrompt, nvidiaKey, appCtx)
                    statusJob.cancel()
                    _isGenerating.value = false
                    _generatingStatus.value = ""

                    if (videoResult != null) {
                        val aiMsg = ChatMessage(
                            sessionId = sessionId,
                            text = "Here is your generated video clip for prompt: \"$videoPrompt\"\n\nGenerated using ${videoResult.modelUsed}.",
                            sender = "ai",
                            fileUri = videoResult.videoUri,
                            fileName = videoResult.fileName,
                            fileSize = "NVIDIA Video Clip (.mp4)"
                        )
                        repository.insertMessage(aiMsg)
                        return@launch
                    } else {
                        val errorMsg = ChatMessage(
                            sessionId = sessionId,
                            text = "Video generation is unavailable for \"$videoPrompt\". Video needs an NVIDIA API key (Stable Video Diffusion) configured in Settings — image generation still works without it.",
                            sender = "ai"
                        )
                        repository.insertMessage(errorMsg)
                        return@launch
                    }
                }

                // Check for image generation intent (Pollinations.AI Engine)
                val isImageGenRequest = ((lowerTrimmed.contains("generate") || lowerTrimmed.contains("genrate") || lowerTrimmed.contains("create") || lowerTrimmed.contains("draw") || lowerTrimmed.contains("paint") || lowerTrimmed.contains("make")) &&
                        (lowerTrimmed.contains("image") || lowerTrimmed.contains("photo") || lowerTrimmed.contains("picture") || lowerTrimmed.contains("illustration") || lowerTrimmed.contains("wallpaper") || lowerTrimmed.contains("drawing") || lowerTrimmed.contains("painting") || lowerTrimmed.contains("art"))) ||
                        lowerTrimmed.startsWith("image of") ||
                        lowerTrimmed.startsWith("photo of") ||
                        lowerTrimmed.startsWith("picture of") ||
                        lowerTrimmed.contains("pollinations")

                if (isImageGenRequest) {
                    _generatingStatus.value = "Creating image via Pollinations AI..."
                    val statusJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(1500)
                        if (_isGenerating.value) _generatingStatus.value = "Adding finishing touches..."
                        kotlinx.coroutines.delay(1500)
                        if (_isGenerating.value) _generatingStatus.value = "Finalizing image rendering..."
                    }

                    var imgPrompt = trimmed
                        .replace(Regex("(?i)^(generate|genrate|create|make|draw|paint|show me|give me)\\s+(an?\\s+)?(image|photo|picture|illustration|wallpaper|drawing|painting|art|pic)?(\\s+of)?\\s*"), "")
                        .replace(Regex("(?i)\\s+(image|photo|picture|drawing|painting|pic)$"), "")
                        .trim()
                    if (imgPrompt.isEmpty()) imgPrompt = trimmed

                    val imgApiKey = _imageGenApiKey.value.trim()
                    val imgNvidiaKey = _nvidiaApiKey.value.trim().ifEmpty { com.example.BuildConfig.NVIDIA_API_KEY }
                    var generated: com.example.api.ImageGenEngine.GeneratedImage? = null

                    if (context != null) {
                        val imageEngine = com.example.api.ImageGenEngine(context)
                        generated = imageEngine.generateImage(imgPrompt, imgApiKey, imgNvidiaKey, openAfter = false)
                    }

                    statusJob.cancel()
                    _isGenerating.value = false
                    _generatingStatus.value = ""

                    if (generated != null) {
                        val safeProvider = generated.provider.replace(Regex("[^A-Za-z0-9.]+"), "_")
                        val aiMsg = ChatMessage(
                            sessionId = sessionId,
                            text = "Here is your generated image for prompt: \"$imgPrompt\"",
                            sender = "ai",
                            fileUri = generated.path,
                            fileName = "${safeProvider}_${System.currentTimeMillis()}.png",
                            fileSize = "${generated.provider} · 1024x1024"
                        )
                        repository.insertMessage(aiMsg)
                        return@launch
                    } else {
                        val base64Img = com.example.api.PollinationsImageGenerator.generateImageBase64(imgPrompt, imgApiKey)
                        if (base64Img != null) {
                            val imgDataUri = "data:image/jpeg;base64,$base64Img"
                            val aiMsg = ChatMessage(
                                sessionId = sessionId,
                                text = "Here is your generated image for prompt: \"$imgPrompt\"",
                                sender = "ai",
                                fileUri = imgDataUri,
                                fileName = "Pollinations_AI_${System.currentTimeMillis()}.jpg",
                                fileSize = "Pollinations AI 1024x1024"
                            )
                            repository.insertMessage(aiMsg)
                            return@launch
                        } else {
                            val errorMsg = ChatMessage(
                                sessionId = sessionId,
                                text = "Image generation failed for prompt \"$imgPrompt\" — Pollinations, NVIDIA NIM and cloud providers were all unavailable. Please check your API keys in Settings and try again.",
                                sender = "ai"
                            )
                            repository.insertMessage(errorMsg)
                            return@launch
                        }
                    }
                }

                // Check for live web search intent
                val isSearchNeeded = shouldWebSearch(trimmed)
                val tavilyKey = _tavilyApiKey.value.trim()

                if (isSearchNeeded) {
                    _generatingStatus.value = "Searching..."
                    val cleanSearchQuery = trimmed
                        .replace(Regex("(?i)^\\b(search google for|search web for|search online for|search for|google search|web search|scrape url|scrape web|scrape|search|did anthropic|did openai|did google|did meta|did microsoft|did apple|did nvidia)\\b"), "")
                        .trim().ifEmpty { trimmed }

                    if (tavilyKey.isNotBlank()) {
                        try {
                            val tavily = com.example.websearch.TavilyClient(tavilyKey)
                            val searchResult = tavily.search(cleanSearchQuery, maxResults = 5, includeAnswer = true)
                            val contextBlock = searchResult.asContextBlock()
                            if (contextBlock.isNotBlank()) {
                                sysPrompt += "\n\n[LIVE TAVILY WEB SEARCH RESULTS FOR '$cleanSearchQuery']:\n" +
                                        (if (!searchResult.answer.isNullOrBlank()) "TAVILY DIRECT ANSWER SUMMARY: ${searchResult.answer}\n\n" else "") +
                                        contextBlock +
                                        "\n\nINSTRUCTION: Answer $currentUserName's query using the live Tavily search results above directly and concisely. Present accurate, up-to-date real-time facts."
                            }
                        } catch (e: Exception) {
                            try {
                                val searchResult = com.example.websearch.WebIntelligenceEngine.smartSearch(cleanSearchQuery, tavilyKey)
                                val contextBlock = searchResult.asContextBlock()
                                if (contextBlock.isNotBlank()) {
                                    sysPrompt += "\n\n[LIVE WEB SEARCH RESULTS FOR '$cleanSearchQuery']:\n$contextBlock\n\nINSTRUCTION: Use the live web search data above to synthesize and present a comprehensive answer for $currentUserName directly in your response."
                                }
                            } catch (ex: Exception) {
                                ex.printStackTrace()
                            }
                        }
                    } else {
                        try {
                            val searchResult = com.example.websearch.WebIntelligenceEngine.smartSearch(cleanSearchQuery, tavilyKey)
                            val contextBlock = searchResult.asContextBlock()
                            if (contextBlock.isNotBlank()) {
                                sysPrompt += "\n\n[LIVE WEB SEARCH RESULTS FOR '$cleanSearchQuery']:\n$contextBlock\n\nINSTRUCTION: Use the live web search data above to synthesize and present a comprehensive answer for $currentUserName directly in your response."
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    _generatingStatus.value = if (isHardLogic) "Thinking..." else ""
                }

                // 2. Fetch response from NVIDIA NIM API or Gemini AI based on active provider or key type
                val activeProv = _activeProvider.value.lowercase()
                val geminiKey = _coreAiApiKey.value.trim()
                val nvidiaKey = _nvidiaApiKey.value.trim()

                val imageBase64Data = if ((file?.type == "image" || file?.type == "pdf") && !file.content.isNullOrEmpty()) file.content else null

                // Auto-trigger Cosmos Reasoner vision model when image or pdf is attached
                val modelToCall = if (imageBase64Data != null) {
                    _visionModel.value.ifBlank { "google/diffusiongemma-26b-a4b-it" }
                } else {
                    _nvidiaModel.value
                }

                val aiResponse = if (activeProv.startsWith("custom:")) {
                    val providerName = activeProv.removePrefix("custom:")
                    val provider = _customProviders.value.firstOrNull { it.name == providerName }
                    if (provider == null) {
                        "Custom provider '$providerName' not found. Add it in API Configuration."
                    } else {
                        com.example.api.CustomProviderClient.complete(
                            provider = provider,
                            messages = _currentMessages.value.takeLast(24).map {
                                (if (it.sender == "user") "user" else "assistant") to it.text
                            } + ("user" to textToSendWithFileContext),
                            systemPrompt = sysPrompt
                        )
                    }
                } else if (activeProv == "jarvis_backend") {
                    com.example.api.BackendClient.callJarvisBackend(
                        baseUrl = _backendBaseUrl.value,
                        sessionId = sessionId.toString(),
                        history = _currentMessages.value,
                        currentPrompt = textToSendWithFileContext,
                        personality = _selectedPersonality.value,
                        userName = _userName.value,
                        language = _appLanguage.value,
                        imageBase64 = imageBase64Data
                    )
                } else if (activeProv == "nvidia_nim" && nvidiaKey.isNotEmpty()) {
                    callNvidiaNimApi(
                        apiKey = nvidiaKey,
                        baseUrl = _nvidiaBaseUrl.value,
                        modelName = modelToCall,
                        history = _currentMessages.value,
                        currentPrompt = textToSendWithFileContext,
                        systemPrompt = sysPrompt,
                        imageBase64Data = imageBase64Data
                    )
                } else if (geminiKey.isNotEmpty()) {
                    callGeminiApi(
                        apiKey = geminiKey,
                        modelName = _geminiModel.value,
                        history = _currentMessages.value,
                        currentPrompt = textToSendWithFileContext,
                        systemPrompt = sysPrompt,
                        imageDataBase64 = imageBase64Data
                    )
                } else if (nvidiaKey.isNotEmpty()) {
                    callNvidiaNimApi(
                        apiKey = nvidiaKey,
                        baseUrl = _nvidiaBaseUrl.value,
                        modelName = modelToCall,
                        history = _currentMessages.value,
                        currentPrompt = textToSendWithFileContext,
                        systemPrompt = sysPrompt,
                        imageBase64Data = imageBase64Data
                    )
                } else {
                    "API Key is missing. Please enter your Gemini or NVIDIA API Key in Settings."
                }

                // Apply JARVIS Response Refiner to strip banned openers & filler phrases
                val refinedResponse = refineJarvisResponse(aiResponse, trimmed)

                // 3. Process AI response for web search, app launch, and system action tags
                var processedResponse = refinedResponse
                var searchTriggerQuery: String? = null
                var appLaunchTag: String? = null
                var systemActionTag: String? = null

                if (processedResponse.contains("[APP_LAUNCH:")) {
                    val tag = processedResponse.substringAfter("[APP_LAUNCH:").substringBefore("]").trim()
                    if (isLegitimateAppLaunchIntent(trimmed, tag)) {
                        appLaunchTag = tag
                    }
                    processedResponse = processedResponse.replace(Regex("\\[APP_LAUNCH:.*?\\]"), "").trim()
                }

                if (processedResponse.contains("[SYSTEM_ACTION:")) {
                    val tag = processedResponse.substringAfter("[SYSTEM_ACTION:").substringBefore("]").trim()
                    if (isLegitimateAppLaunchIntent(trimmed, tag)) {
                        systemActionTag = tag
                    }
                    processedResponse = processedResponse.replace(Regex("\\[SYSTEM_ACTION:.*?\\]"), "").trim()
                }

                var selfSettingTag: String? = null
                if (processedResponse.contains("[SETTING:")) {
                    val tag = processedResponse.substringAfter("[SETTING:").substringBefore("]").trim()
                    if (tag.contains("=") && tag.length <= 120) {
                        selfSettingTag = tag
                    }
                    processedResponse = processedResponse.replace(Regex("\\[SETTING:.*?\\]"), "").trim()
                }

                var memoryTag: String? = null
                if (processedResponse.contains("[MEMORY:")) {
                    val tag = processedResponse.substringAfter("[MEMORY:").substringBefore("]").trim()
                    if (tag.isNotBlank() && tag.length <= 300) {
                        memoryTag = tag
                    }
                    processedResponse = processedResponse.replace(Regex("\\[MEMORY:.*?\\]"), "").trim()
                }

                if (processedResponse.contains("[WEB_SEARCH:")) {
                    val extractedQuery = processedResponse.substringAfter("[WEB_SEARCH:").substringBefore("]").trim()
                    val lowerExtracted = extractedQuery.lowercase()
                    val queryToScrape = if (extractedQuery.isBlank() || lowerExtracted == "query" || lowerExtracted == "search query" || lowerExtracted.contains("keywords") || lowerExtracted == "search") {
                        trimmed
                    } else {
                        extractedQuery
                    }

                    if (_webSearchConnected.value) {
                        _generatingStatus.value = "Web searching..."
                        val scrapedCtx = com.example.api.WebSearchScraper.searchAndScrapeContext(queryToScrape)
                        if (scrapedCtx.isNotBlank()) {
                            _generatingStatus.value = "Analyzing web results..."
                            val updatedSysPrompt = sysPrompt + "\n\n[DIRECT LIVE SCRAPED WEB RESULTS]:\n$scrapedCtx\n\nINSTRUCTION: Synthesize and output the direct answer for $currentUserName using the scraped web results above. Do NOT ask the user to open Google."
                            val updatedAiResponse = if (activeProv == "nvidia_nim" || nvidiaKey.isNotEmpty()) {
                                callNvidiaNimApi(
                                    apiKey = nvidiaKey.ifEmpty { geminiKey },
                                    baseUrl = _nvidiaBaseUrl.value,
                                    modelName = modelToCall,
                                    history = _currentMessages.value,
                                    currentPrompt = textToSendWithFileContext,
                                    systemPrompt = updatedSysPrompt,
                                    imageBase64Data = imageBase64Data
                                )
                            } else {
                                callGeminiApi(
                                    apiKey = geminiKey.ifEmpty { nvidiaKey },
                                    modelName = _geminiModel.value,
                                    history = _currentMessages.value,
                                    currentPrompt = textToSendWithFileContext,
                                    systemPrompt = updatedSysPrompt,
                                    imageDataBase64 = imageBase64Data
                                )
                            }
                            processedResponse = refineJarvisResponse(updatedAiResponse, trimmed)
                        }
                    }
                    processedResponse = processedResponse.replace(Regex("\\[WEB_SEARCH:.*?\\]"), "").trim()
                }

                // 3b. Check for NEEDS_SEARCH prefix trigger (Web Intelligence Engine v6.0)
                val needsSearchQuery = com.example.websearch.needsSearch(processedResponse)
                if (needsSearchQuery != null) {
                    _generatingStatus.value = "Web searching (Intelligence Engine v6.0)..."
                    try {
                        val searchResult = com.example.websearch.WebIntelligenceEngine.smartSearch(needsSearchQuery, tavilyKey)
                        val contextBlock = searchResult.asContextBlock()
                        _generatingStatus.value = "Synthesizing answer..."
                        val updatedSysPrompt = sysPrompt + "\n\n[LIVE WEB SEARCH RESULTS FOR '$needsSearchQuery']:\n$contextBlock\n\nINSTRUCTION: Answer the user's question directly and concisely using the search context above."
                        val updatedAiResponse = if (activeProv == "nvidia_nim" || nvidiaKey.isNotEmpty()) {
                            callNvidiaNimApi(
                                apiKey = nvidiaKey.ifEmpty { geminiKey },
                                baseUrl = _nvidiaBaseUrl.value,
                                modelName = modelToCall,
                                history = _currentMessages.value,
                                currentPrompt = textToSendWithFileContext,
                                systemPrompt = updatedSysPrompt,
                                imageBase64Data = imageBase64Data
                            )
                        } else {
                            callGeminiApi(
                                apiKey = geminiKey.ifEmpty { nvidiaKey },
                                modelName = _geminiModel.value,
                                history = _currentMessages.value,
                                currentPrompt = textToSendWithFileContext,
                                systemPrompt = updatedSysPrompt,
                                imageDataBase64 = imageBase64Data
                            )
                        }
                        processedResponse = refineJarvisResponse(updatedAiResponse, trimmed)
                    } catch (e: Exception) {
                        processedResponse = "I attempted to search the web for '$needsSearchQuery', but encountered an error: ${e.message}"
                    }
                }

                // Trigger TTS voice speaker if in voice mode. The muted case
                // is handled by the collector in MainActivity (it re-arms the
                // mic directly), so emit unconditionally - otherwise a muted
                // voice mode went deaf after one turn.
                if (_isVoiceMode.value) {
                    _voiceStatus.value = "Speaking..."
                    _speakTextEvent.emit(processedResponse)
                }

                // Insert AI response (with token-by-token streaming rendering if enabled)
                if (_isStreamingEnabled.value && processedResponse.length > 5) {
                    val initialAiMsg = ChatMessage(sessionId = sessionId, text = "", sender = "ai")
                    val msgId = repository.insertMessage(initialAiMsg)

                    val words = processedResponse.split(Regex("(?<=\\s)|(?=\\s)"))
                    val accumulatedText = StringBuilder()
                    val chunkSize = 4
                    var count = 0
                    for (i in words.indices) {
                        accumulatedText.append(words[i])
                        count++
                        if (count >= chunkSize || i == words.lastIndex) {
                            val currentChunk = accumulatedText.toString()
                            repository.insertMessage(initialAiMsg.copy(id = msgId, text = currentChunk))
                            kotlinx.coroutines.delay(6L)
                            count = 0
                        }
                    }
                    repository.insertMessage(initialAiMsg.copy(id = msgId, text = processedResponse))
                } else {
                    val aiMsg = ChatMessage(sessionId = sessionId, text = processedResponse, sender = "ai")
                    repository.insertMessage(aiMsg)
                }

                // Consolidate turn facts into Unified Memory Engine
                unifiedMemoryEngine.consolidateTurn(trimmed, processedResponse)

                // Trigger app launch or system action if requested
                if (!appLaunchTag.isNullOrEmpty()) {
                    _appIntentEvent.tryEmit(appLaunchTag)
                }
                if (!systemActionTag.isNullOrEmpty()) {
                    _appIntentEvent.tryEmit("sys:$systemActionTag")
                }

                if (!selfSettingTag.isNullOrEmpty()) {
                    applySelfSetting(selfSettingTag)
                }

                if (!memoryTag.isNullOrEmpty()) {
                    saveMemoryFact(memoryTag)
                }

                // Trigger web search if requested or fallback needed
                if (!searchTriggerQuery.isNullOrEmpty()) {
                    _appIntentEvent.tryEmit("websearch:$searchTriggerQuery")
                }
            } catch (e: Exception) {
                val errorMsg = ChatMessage(
                    sessionId = sessionId,
                    text = "Error: ${e.localizedMessage ?: "Failed to generate response"}",
                    sender = "ai"
                )
                repository.insertMessage(errorMsg)
            } finally {
                _isGenerating.value = false
                _generatingStatus.value = ""
            }
        }
    }

    private fun detectMood(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("frustrated") || t.contains("angry") || t.contains("annoyed") || t.contains("not working") || t.contains("broken") || t.contains("ugh") -> "frustrated"
            t.contains("tired") || t.contains("exhausted") || t.contains("sleepy") || t.contains("can't focus") || t.contains("drained") -> "tired"
            t.contains("excited") || t.contains("amazing") || t.contains("awesome") || t.contains("great") || t.contains("finally") -> "excited"
            t.contains("sad") || t.contains("depressed") || t.contains("lonely") || t.contains("bad day") || t.contains("upset") -> "sad"
            t.contains("confused") || t.contains("don't understand") || t.contains("explain") || t.contains("lost") -> "confused"
            t.contains("worried") || t.contains("anxious") || t.contains("nervous") || t.contains("scared") -> "anxious"
            else -> "neutral"
        }
    }

    private fun getMoodInstruction(mood: String): String {
        return when (mood) {
            "frustrated" -> "MOOD NOTE: User is frustrated. Acknowledge it briefly in ONE word or short phrase, then cut straight to solving the problem. No fluff."
            "tired" -> "MOOD NOTE: User sounds tired. Keep it short, easy, and warm. Offer to handle the heavy thinking."
            "excited" -> "MOOD NOTE: User is in high spirits. Match that positive energy with precise enthusiasm."
            "sad" -> "MOOD NOTE: User seems down. Be warm, human, empathetic, then provide clear support."
            "confused" -> "MOOD NOTE: User is confused. Simplify everything into plain, patient, clear language."
            "anxious" -> "MOOD NOTE: User seems worried. Be reassuring, steady, and project calm confidence."
            else -> "MOOD NOTE: User is in a focused state. Be sharp, natural, and helpful."
        }
    }

    private fun isLegitimateAppLaunchIntent(userQuery: String, tag: String): Boolean {
        val q = userQuery.lowercase().trim()
        val cleanTag = tag.lowercase().trim()

        // Explicitly suppress app launch triggers for media generation requests (image/video gen)
        val isMediaGenQuery = q.contains("generate") || q.contains("create") || q.contains("draw") ||
                q.contains("paint") || q.contains("make image") || q.contains("make picture") ||
                q.contains("make video") || q.contains("pollinations") || q.contains("image of") ||
                q.contains("picture of") || q.contains("video of") || q.contains("animate video")
        if (isMediaGenQuery && !q.contains("open camera") && !q.contains("launch camera") && !q.contains("open gallery")) {
            return false
        }

        // Casual feedback, compliments, and acknowledgments must NEVER launch apps
        val casualPhrases = setOf(
            "good", "goood", "gooood", "nice", "niice", "great", "awesome", "perfect", "wow",
            "super", "cool", "thanks", "thank you", "thx", "ok", "okay", "fine", "excellent",
            "brilliant", "amazing", "good job", "well done", "like it", "love it", "superb",
            "nice image", "good image", "great picture", "beautiful", "wonderful"
        )
        if (casualPhrases.contains(q) || q.matches(Regex("^(go+d|ni+ce|grea+t|awe+some|tha+nks|wo+w|su+per|co+ol|lo+ve it|ok+a?y?)$"))) {
            return false
        }

        // Camera/Gallery app launch triggers require strict explicit command
        if (cleanTag.contains("camera") || cleanTag.contains("gallery") || cleanTag.contains("photos")) {
            val explicitCameraTriggers = listOf("open camera", "launch camera", "start camera", "take photo", "take picture", "open gallery", "launch gallery", "show gallery")
            return explicitCameraTriggers.any { q.contains(it) }
        }

        // General explicit launch triggers required
        val launchKeywords = listOf(
            "open", "launch", "start", "run", "go to", "show", "take photo", "take picture",
            "camera", "gallery", "call", "send", "dial", "play", "set", "turn on", "turn off",
            "enable", "disable", "mute", "unmute"
        )

        val hasExplicitKeyword = launchKeywords.any { q.contains(it) }
        val containsAppName = cleanTag.isNotBlank() && q.contains(cleanTag.split(":")[0])

        return hasExplicitKeyword || containsAppName
    }

    private fun matchFastLocalAutomation(trimmed: String, salutation: String): Pair<String, String>? {
        val parsed = IntentParser.parse(trimmed, salutation)
        if (parsed.category != "general" && parsed.actionCommand != null && parsed.localizedResponse != null) {
            // Check connector enable flags
            if ((parsed.category == "system" || parsed.category == "app_launch" || parsed.category == "alarm" || parsed.category == "navigation") && !_osBridgeConnected.value) {
                return null
            }
            if (parsed.category == "search" && !_webSearchConnected.value) {
                return null
            }
            return parsed.actionCommand to parsed.localizedResponse
        }
        return null
    }

    private fun refineJarvisResponse(response: String, userPrompt: String): String {
        if (response.isBlank()) return "Insufficient data for that."
        var text = response.trim()

        // 1. Strip Banned Openers at start of text
        val bannedRegex = Regex(
            "^(allow me|certainly|of course|here's the thing|here is the thing|the thing is|great question|sure|absolutely|let me|i'd be happy|i'll pull|gathering|fetching|understood|noted|indeed|right away|as you wish|excellent question)[,!.]?\\s*",
            RegexOption.IGNORE_CASE
        )
        for (i in 0..3) {
            val cleaned = text.replace(bannedRegex, "").trim()
            if (cleaned == text) break
            text = cleaned
        }

        // 2. Strip Filler Phrases & Fictional Marvel Lore
        text = text.replace(Regex("\\b(stark industries|tony stark|stark tower|stark tech|stark's)\\b", RegexOption.IGNORE_CASE), "our system")
        text = text.replace(Regex("\\s+sir\\b", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\bi('m| am) just an? (ai|language model|chatbot)\\b[^.]*\\.", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\bas an? (ai|language model)\\b[^,]*\\,\\s*", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\bplease note that\\b", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\bkeep in mind that\\b", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\bi hope (this|that) helps[!.]?\\s*", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\bis there anything else\\b[^?]*\\?", RegexOption.IGNORE_CASE), "")

        // 3. Tone Fixes
        text = text.replace(Regex("\\bI will proceed to\\b", RegexOption.IGNORE_CASE), "I will")
        text = text.replace(Regex("\\bI have completed\\b", RegexOption.IGNORE_CASE), "Done —")
        text = text.replace(Regex("\\bI have successfully\\b", RegexOption.IGNORE_CASE), "Done —")

        // Cleanup double spaces and sentence starts
        text = text.replace(Regex("\\s{2,}"), " ").trim()

        if (text.isNotEmpty() && text[0].isLowerCase()) {
            text = text.replaceFirstChar { it.uppercase() }
        }

        return text.ifBlank { response }
    }

    private fun isHardLogicalOrComplexQuery(trimmed: String): Boolean {
        val q = trimmed.lowercase().trim()
        if (q.length <= 25) {
            val simpleGreetings = listOf(
                "hi", "hello", "hey", "hola", "namaste", "good morning", "good afternoon",
                "good evening", "good night", "yo", "sup", "what's up", "how are you",
                "who are you", "thanks", "thank you", "ok", "okay", "bye", "goodbye"
            )
            if (simpleGreetings.any { q == it || q.startsWith("$it ") || q.endsWith(" $it") || q == "$it!" || q == "$it." }) return false
        }
        val complexKeywords = listOf(
            "solve", "calculate", "math", "proof", "derive", "algorithm", "code", "debug",
            "function", "logic", "puzzle", "riddle", "step by step", "complex", "paradox",
            "quantum", "physics", "calculus", "equation", "theorem", "architecture",
            "design pattern", "write code", "implement", "reason", "reasoning", "evaluate"
        )
        return complexKeywords.any { q.contains(it) } || (q.contains("?") && q.length > 80)
    }

    private fun shouldWebSearch(trimmed: String): Boolean {
        val q = trimmed.lowercase().trim()
        val searchKeywords = listOf(
            "search", "google", "web search", "scrape", "find online", "latest news",
            "deep research", "deep dive", "did anthropic", "did openai", "did google",
            "did meta", "did microsoft", "did apple", "did nvidia", "new model", "latest model",
            "claude 3", "claude 3.5", "claude 3.7", "gpt-4", "gpt-5", "llama 3", "gemini 2",
            "released", "announce", "launched", "current president", "who won", "today news",
            "stock price", "weather in", "score of", "is there a new", "has anthropic", "has openai", "has google"
        )
        return searchKeywords.any { q.contains(it) }
    }

    /**
     * Delegate a task to the JARVIS BrowserAgentService (browser-use backend).
     * Submits the task, then polls status and streams step progress + final
     * result into the chat as assistant messages. Runs fully on the backend.
     */
    fun startBrowserTask(task: String, sessionId: Long) {
        val clean = task.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val baseUrl = _backendBaseUrl.value.trim()
            val token = _otherApiKey.value.trim().ifBlank { _coreAiApiKey.value.trim() }
            _isGenerating.value = true
            _generatingStatus.value = "Browser agent thinking..."

            val (taskId, submitErr) = com.example.api.BrowserAgentClient.submitTask(baseUrl, clean, token.ifBlank { null })
            if (taskId == null) {
                _isGenerating.value = false
                _generatingStatus.value = ""
                repository.insertMessage(
                    ChatMessage(
                        sessionId = sessionId,
                        text = "Couldn't start the browser agent: ${submitErr ?: "unknown error"}. " +
                            "Add your JARVIS backend URL in API Configuration (the browser agent runs there, not on the phone).",
                        sender = "ai"
                    )
                )
                return@launch
            }

            var lastStepCount = 0
            var done = false
            var result: String? = null
            var error: String? = null
            while (!done) {
                kotlinx.coroutines.delay(2500)
                val status = com.example.api.BrowserAgentClient.getStatus(baseUrl, taskId, token.ifBlank { null })
                if (status != null) {
                    val newSteps = status.steps.drop(lastStepCount)
                    lastStepCount = status.steps.size
                    newSteps.forEach { step ->
                        val summary = step.summary ?: step.action ?: step.payload ?: return@forEach
                        repository.insertMessage(
                            ChatMessage(sessionId = sessionId, text = "🧭 ${summary.take(400)}", sender = "ai")
                        )
                    }
                    when (status.status) {
                        "done" -> {
                            done = true
                            result = status.result
                        }
                        "error" -> {
                            done = true
                            error = status.error
                        }
                        "cancelled" -> {
                            done = true
                            result = "Task was cancelled."
                        }
                    }
                }
            }

            _isGenerating.value = false
            _generatingStatus.value = ""

            repository.insertMessage(
                ChatMessage(
                    sessionId = sessionId,
                    text = result ?: error ?: "Browser task finished.",
                    sender = "ai"
                )
            )
        }
    }

    companion object {
        init {
            Log.d("JarvisViewModel", "=== JarvisViewModel class loaded ===")
        }
    }

    class Factory(
        private val repository: JarvisRepository,
        private val context: android.content.Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            Log.d("JarvisViewModel", "Factory.create called, modelClass=$modelClass")
            if (modelClass.isAssignableFrom(JarvisViewModel::class.java)) {
                Log.d("JarvisViewModel", "Creating JarvisViewModel instance")
                return JarvisViewModel(repository, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

enum class ChatUiState {
    HOME,
    CHATTING
}
