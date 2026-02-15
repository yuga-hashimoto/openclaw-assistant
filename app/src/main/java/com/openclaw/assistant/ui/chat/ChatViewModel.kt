package com.openclaw.assistant.ui.chat

import android.app.Application
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.gateway.ConnectionState
import com.openclaw.assistant.gateway.GatewayClient
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "ChatViewModel"

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isStreaming: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val partialText: String = "", // For real-time speech transcription
    val streamingContent: String? = null, // Real-time AI response text
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val settings = SettingsRepository.getInstance(application)
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(application)
    private val apiClient = OpenClawClient()
    private val gatewayClient = GatewayClient.getInstance()
    private val speechManager = SpeechRecognizerManager(application)
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)

    // WebSocket streaming state
    private var currentRunId: String? = null
    private var thinkingSoundJob: Job? = null

    // WakeLock to keep CPU alive during voice interaction with screen off
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    // Session Management
    val allSessions = chatRepository.allSessions.stateIn(
        viewModelScope, 
        SharingStarted.WhileSubscribed(5000), 
        emptyList()
    )
    
    // Current Session
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()
    
    // Sync current session with Settings if needed, or just let UI drive it?
    // Let's load the last one if available, or create new.
    
    init {
        // Load messages for current session if set
        viewModelScope.launch {
             // If we have a sessionId in settings, try to use it?
             // Or better, let's start fresh or let user select.
             // For now, let's observe whatever session we have.
             
             // Actually, we want to watch the session ID and update the message flow
        }
    }

    // Messages Flow - mapped from current Session ID
    private val _messagesFlow = _currentSessionId.flatMapLatest { sessionId ->
         if (sessionId != null) {
             chatRepository.getMessages(sessionId).map { entities ->
                 entities.map { entity ->
                     ChatMessage(
                         id = entity.id,
                         text = entity.content,
                         isUser = entity.isUser,
                         timestamp = entity.timestamp
                     )
                 }
             }
         } else {
             flowOf(emptyList())
         }
    }
    
    // We combine _messagesFlow into uiState
    init {
        viewModelScope.launch {
            _messagesFlow.collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }

        // Initial session setup
        viewModelScope.launch {
            // Check if there are sessions. If yes, pick latest.
            val latest = chatRepository.getLatestSession()
            if (latest != null) {
                _currentSessionId.value = latest.id
                settings.sessionId = latest.id
            } else {
                createNewSession()
            }
        }

        // Observe gateway connection state
        viewModelScope.launch {
            gatewayClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        // Observe chat events (final/error/aborted)
        viewModelScope.launch {
            gatewayClient.chatEvents.collect { event ->
                when (event.state) {
                    "final" -> onStreamFinal()
                    "error" -> onStreamError(event.errorMessage ?: "Chat failed")
                    "aborted" -> onStreamAborted()
                }
            }
        }

        // Observe agent events (streaming assistant text)
        viewModelScope.launch {
            gatewayClient.agentEvents.collect { event ->
                if (event.stream == "assistant" && !event.data?.text.isNullOrEmpty()) {
                    _uiState.update { it.copy(streamingContent = event.data?.text, isStreaming = true) }
                }
            }
        }

        // Auto-connect to WebSocket if configured
        connectGatewayIfNeeded()
    }

    fun connectGatewayIfNeeded() {
        val connectionMode = settings.connectionMode
        if (connectionMode == "http") return

        val webhookUrl = settings.webhookUrl
        if (webhookUrl.isBlank()) return

        try {
            val url = java.net.URL(webhookUrl)
            val host = url.host
            val useTls = url.protocol == "https"

            // For tunneled connections (HTTPS, e.g. ngrok), use the URL's port (443 default).
            // For direct LAN connections (HTTP), use gatewayPort setting or URL port.
            val port = if (useTls) {
                if (url.port > 0) url.port else 443
            } else {
                if (settings.gatewayPort > 0) settings.gatewayPort else
                    if (url.port > 0) url.port else 18789
            }
            val token = settings.authToken.takeIf { it.isNotBlank() }

            Log.d(TAG, "Connecting gateway: $host:$port, tls=$useTls")
            gatewayClient.connect(host, port, token, useTls = useTls)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse webhook URL for WS: ${e.message}")
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val simpleDateFormat = java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            val app = getApplication<Application>()
            val newId = chatRepository.createSession(String.format(app.getString(com.openclaw.assistant.R.string.chat_session_title_format), simpleDateFormat.format(java.util.Date())))
            _currentSessionId.value = newId
            settings.sessionId = newId // Sync for API use
        }
    }

    fun selectSession(sessionId: String) {
        _currentSessionId.value = sessionId
        settings.sessionId = sessionId
    }

    fun deleteSession(sessionId: String) {
        // Immediate UI update if deleting current session
        val isCurrent = _currentSessionId.value == sessionId
        if (isCurrent) {
            _currentSessionId.value = null
        }

        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            if (isCurrent) {
                // Determine if we should switch to another or create new
                val nextSession = chatRepository.getLatestSession()
                if (nextSession != null) {
                    _currentSessionId.value = nextSession.id
                    settings.sessionId = nextSession.id
                } else {
                    createNewSession()
                }
            }
        }
    }

    // TTS will be set from Activity
    private var tts: TextToSpeech? = null
    private var isTTSReady = false

    /**
     * ActivityからTTSを設定する
     */
    fun setTTS(textToSpeech: TextToSpeech) {
        Log.e(TAG, "setTTS called")
        tts = textToSpeech
        isTTSReady = true
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Ensure we have a session
        val sessionId = _currentSessionId.value ?: return

        _uiState.update { it.copy(isThinking = true, streamingContent = null, isStreaming = false) }
        if (lastInputWasVoice) {
            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        }
        startThinkingSound()

        viewModelScope.launch {
            try {
                // Save User Message
                chatRepository.addMessage(sessionId, text, isUser = true)

                if (gatewayClient.isConnected() && settings.connectionMode != "http") {
                    sendViaWebSocket(sessionId, text)
                } else {
                    sendViaHttp(sessionId, text)
                }
            } catch (e: Exception) {
                stopThinkingSound()
                _uiState.update { it.copy(isThinking = false, isStreaming = false, error = e.message) }
            }
        }
    }

    private suspend fun sendViaWebSocket(sessionId: String, text: String) {
        try {
            val sessionKey = gatewayClient.mainSessionKey ?: "main"
            currentRunId = gatewayClient.sendChat(sessionKey, text)
            stopThinkingSound()
            _uiState.update { it.copy(isThinking = false, isStreaming = true) }
            // Response will arrive via chatEvents + agentEvents observers
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket send failed, falling back to HTTP: ${e.message}")
            sendViaHttp(sessionId, text)
        }
    }

    private suspend fun sendViaHttp(sessionId: String, text: String) {
        val result = apiClient.sendMessage(
            webhookUrl = settings.webhookUrl,
            message = text,
            sessionId = sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() }
        )

        result.fold(
            onSuccess = { response ->
                val responseText = response.getResponseText() ?: "No response"
                chatRepository.addMessage(sessionId, responseText, isUser = false)

                stopThinkingSound()
                _uiState.update { it.copy(isThinking = false) }
                afterResponseReceived(responseText)
            },
            onFailure = { error ->
                stopThinkingSound()
                _uiState.update { it.copy(isThinking = false, error = error.message) }
            }
        )
    }

    private fun onStreamFinal() {
        val sessionId = _currentSessionId.value ?: return
        val streamedText = _uiState.value.streamingContent

        currentRunId = null
        stopThinkingSound()
        _uiState.update { it.copy(isStreaming = false, streamingContent = null) }

        if (!streamedText.isNullOrBlank()) {
            viewModelScope.launch {
                chatRepository.addMessage(sessionId, streamedText, isUser = false)
                afterResponseReceived(streamedText)
            }
        }
    }

    private fun onStreamError(errorMessage: String) {
        currentRunId = null
        stopThinkingSound()
        _uiState.update { it.copy(isThinking = false, isStreaming = false, streamingContent = null, error = errorMessage) }
    }

    private fun onStreamAborted() {
        val sessionId = _currentSessionId.value
        val partialText = _uiState.value.streamingContent

        currentRunId = null
        _uiState.update { it.copy(isStreaming = false, streamingContent = null) }

        // Save partial text if any
        if (sessionId != null && !partialText.isNullOrBlank()) {
            viewModelScope.launch {
                chatRepository.addMessage(sessionId, partialText, isUser = false)
            }
        }
    }

    private fun afterResponseReceived(responseText: String) {
        if (settings.ttsEnabled) {
            speak(responseText)
        } else if (lastInputWasVoice && settings.continuousMode) {
            viewModelScope.launch {
                delay(500)
                startListening()
            }
        }
    }

    fun stopGeneration() {
        val sessionKey = gatewayClient.mainSessionKey ?: "main"
        viewModelScope.launch {
            gatewayClient.abortChat(sessionKey, currentRunId)
        }
    }

    private var lastInputWasVoice = false
    private var listeningJob: kotlinx.coroutines.Job? = null

    fun startListening() {
        Log.e(TAG, "startListening() called, isListening=${_uiState.value.isListening}")
        if (_uiState.value.isListening) return

        // Pause Hotword Service to prevent microphone conflict
        sendPauseBroadcast()

        // Keep CPU alive during voice interaction (screen off)
        acquireWakeLock()

        lastInputWasVoice = true // Mark as voice input
        listeningJob?.cancel()

        // Stop TTS if speaking
        tts?.stop()

        listeningJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for TTS resource release before starting mic
            delay(500)

            try {
                while (isActive && !hasActuallySpoken) {
                    Log.e(TAG, "Starting speechManager.startListening(), isListening=true")
                    _uiState.update { it.copy(isListening = true, partialText = "") }

                    speechManager.startListening(null, settings.speechSilenceTimeout).collect { result ->
                        Log.e(TAG, "SpeechResult: $result")
                        when (result) {
                            is SpeechResult.Ready -> {
                                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                            }
                            is SpeechResult.Processing -> {
                                // No sound here - thinking ACK sound will play when AI starts processing
                            }
                            is SpeechResult.PartialResult -> {
                                _uiState.update { it.copy(partialText = result.text) }
                            }
                            is SpeechResult.Result -> {
                                hasActuallySpoken = true
                                _uiState.update { it.copy(isListening = false, partialText = "") }
                                sendMessage(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH
                                
                                if (isTimeout && elapsed < settings.speechSilenceTimeout) {
                                    Log.d(TAG, "Speech timeout within ${settings.speechSilenceTimeout}ms window ($elapsed ms), retrying loop...")
                                    // Just fall through to next while iteration
                                    _uiState.update { it.copy(isListening = false) }
                                } else if (isTimeout) {
                                    // Timeout - stop listening silently (no error message)
                                    toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    _uiState.update { it.copy(isListening = false, error = null) }
                                    lastInputWasVoice = false
                                    hasActuallySpoken = true // Break the while loop
                                } else {
                                    // Permanent error
                                    toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    _uiState.update { it.copy(isListening = false, error = result.message) }
                                    lastInputWasVoice = false
                                    hasActuallySpoken = true // Break the while loop
                                }
                            }
                            else -> {}
                        }
                    }
                    
                    if (!hasActuallySpoken) {
                        delay(300) // Small gap between retries
                    }
                }
            } finally {
                // If the loop finishes (e.g. error or spoken), and we are NOT continuing to speak/think immediately,
                // we might want to resume hotword...
                // HOWEVER: if we successfully spoke, we are now "Thinking" or "Speaking", so we shouldn't resume yet.
                // We only resume if we are truly done (e.g. stopped listening without input).
                
                // But actually, sendMessage() triggers Thinking -> Speaking -> (maybe) startListening again.
                // So we should only resume hotword if we are definitely NOT going to loop back.
                
                if (!lastInputWasVoice) {
                    releaseWakeLock()
                    sendResumeBroadcast()
                }
            }
        }
    }

    fun stopListening() {
        lastInputWasVoice = false // User manually stopped
        listeningJob?.cancel()
        _uiState.update { it.copy(isListening = false) }
        releaseWakeLock()
        sendResumeBroadcast()
    }

    private var speakingJob: kotlinx.coroutines.Job? = null

    private fun speak(text: String) {
        val cleanText = com.openclaw.assistant.speech.TTSUtils.stripMarkdownForSpeech(text)
        speakingJob = viewModelScope.launch {
            _uiState.update { it.copy(isSpeaking = true) }

            val success = if (isTTSReady && tts != null) {
                speakWithTTS(cleanText)
            } else {
                Log.e(TAG, "TTS not ready, skipping speech")
                false
            }

            _uiState.update { it.copy(isSpeaking = false) }

            // If it was a voice conversation and continuous mode is on, continue listening
            if (success && lastInputWasVoice && settings.continuousMode) {
                // Explicit cleanup and wait for TTS to fully release audio focus
                speechManager.destroy()
                kotlinx.coroutines.delay(1000)

                // Restart listening
                startListening()
            } else {
                // Conversation ended
                releaseWakeLock()
                sendResumeBroadcast()
            }
        }
    }

    private suspend fun speakWithTTS(text: String): Boolean {
        // Use timeout + polling fallback in case TTS callbacks don't fire
        val callbackResult = withTimeoutOrNull(90_000L) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()

                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS onStart")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS onDone")
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        Log.d(TAG, "TTS onStop, interrupted=$interrupted")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS onError (deprecated)")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS onError: $errorCode")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }

                tts?.setOnUtteranceProgressListener(listener)
                val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                Log.d(TAG, "TTS speak result=$result, text length=${text.length}")

                if (result != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS speak failed immediately with result=$result")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                } else {
                    // Polling fallback: check tts.isSpeaking() periodically
                    // in case onDone callback never fires (common on some devices)
                    viewModelScope.launch {
                        delay(2000) // Wait at least 2s before polling
                        while (continuation.isActive) {
                            val speaking = tts?.isSpeaking ?: false
                            if (!speaking) {
                                Log.w(TAG, "TTS poll detected speech finished (callback missed)")
                                if (continuation.isActive) {
                                    continuation.resume(true)
                                }
                                break
                            }
                            delay(500)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    tts?.stop()
                }
            }
        }

        if (callbackResult == null) {
            Log.w(TAG, "TTS timed out after 90s, forcing stop")
            tts?.stop()
            return false
        }
        return callbackResult
    }

    fun stopSpeaking() {
        lastInputWasVoice = false // Stop loop if manually stopped
        tts?.stop()
        speakingJob?.cancel()
        speakingJob = null
        _uiState.update { it.copy(isSpeaking = false) }
        releaseWakeLock()
        sendResumeBroadcast()
    }

    fun interruptAndListen() {
        tts?.stop()
        speakingJob?.cancel()
        speakingJob = null
        _uiState.update { it.copy(isSpeaking = false) }
        sendPauseBroadcast()
        startListening()
    }

    // REMOVED private fun addMessage because we now flow from DB

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val app = getApplication<Application>()
        val powerManager = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::ChatWakeLock"
        ).apply {
            acquire(5 * 60 * 1000L) // 5 min max to prevent leak
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled || !lastInputWasVoice) return
        thinkingSoundJob = viewModelScope.launch {
            delay(2000)
            while (isActive) {
                toneGenerator.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopThinkingSound()
        speechManager.destroy()
        toneGenerator.release()
        releaseWakeLock()
        sendResumeBroadcast()
        // Don't shutdown TTS here - Activity owns it
    }

    private fun sendPauseBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }
}
