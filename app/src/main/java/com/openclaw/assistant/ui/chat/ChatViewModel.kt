package com.openclaw.assistant.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.gateway.GatewayClient
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "ChatViewModel"

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentPath: String? = null,
    val attachmentType: String? = null
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val partialText: String = "", // For real-time speech transcription
    val availableAgents: List<AgentInfo> = emptyList(),
    val selectedAgentId: String? = null, // null = use default from settings
    val defaultAgentId: String = "main", // From settings, for display when agent list unavailable
    val isPairingRequired: Boolean = false,
    val deviceId: String? = null,
    val selectedAttachmentUri: Uri? = null,
    val selectedAttachmentMimeType: String? = null,
    val selectedAttachmentName: String? = null
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
                         timestamp = entity.timestamp,
                         attachmentPath = entity.attachmentPath,
                         attachmentType = entity.attachmentType
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

        // Observe pairing required state
        viewModelScope.launch {
            gatewayClient.isPairingRequired.collect { required ->
                _uiState.update { it.copy(isPairingRequired = required, deviceId = gatewayClient.deviceId) }
            }
        }

        // Observe agent list from gateway (fetched via WS)
        viewModelScope.launch {
            gatewayClient.agentList.collect { agentListResult ->
                _uiState.update { it.copy(
                    availableAgents = agentListResult?.agents ?: emptyList()
                )}
            }
        }

        // Initialize default agent from settings
        val savedAgentId = settings.defaultAgentId
        if (savedAgentId.isNotBlank() && savedAgentId != "main") {
            _uiState.update { it.copy(defaultAgentId = savedAgentId, selectedAgentId = savedAgentId) }
        }

        // Auto-connect to WebSocket if configured
        connectGatewayIfNeeded()
    }

    fun connectGatewayIfNeeded() {
        val baseUrl = settings.getBaseUrl()
        if (baseUrl.isBlank()) return

        try {
            val url = java.net.URL(baseUrl)
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

    fun setAgent(agentId: String?) {
        _uiState.update { it.copy(selectedAgentId = agentId) }
    }

    fun setAttachment(uri: Uri?, name: String?, mimeType: String?) {
        _uiState.update { it.copy(
            selectedAttachmentUri = uri,
            selectedAttachmentName = name,
            selectedAttachmentMimeType = mimeType
        )}
    }

    private fun getEffectiveAgentId(): String? {
        val selected = _uiState.value.selectedAgentId
        if (selected != null) return selected
        val default = settings.defaultAgentId
        return if (default.isNotBlank() && default != "main") default else null
    }

    fun sendMessage(text: String) {
        if (text.isBlank() && _uiState.value.selectedAttachmentUri == null) return

        // Ensure we have a session
        val sessionId = _currentSessionId.value ?: return
        val attachmentUri = _uiState.value.selectedAttachmentUri
        val attachmentMimeType = _uiState.value.selectedAttachmentMimeType

        _uiState.update { it.copy(
            isThinking = true,
            selectedAttachmentUri = null,
            selectedAttachmentName = null,
            selectedAttachmentMimeType = null
        ) }

        if (lastInputWasVoice) {
            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        }
        startThinkingSound()

        viewModelScope.launch {
            try {
                var attachmentPath: String? = null
                var attachmentBase64: String? = null
                var finalMimeType = attachmentMimeType

                withContext(Dispatchers.IO) {
                    attachmentUri?.let { uri ->
                        val context = getApplication<Application>()
                        val fileName = "attach_${System.currentTimeMillis()}"
                        val attachmentsDir = File(context.filesDir, "attachments")
                        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()

                        val file = File(attachmentsDir, fileName)

                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                        attachmentPath = file.absolutePath

                        if (attachmentMimeType?.startsWith("image/") == true) {
                            val bitmap = BitmapFactory.decodeFile(attachmentPath)
                            if (bitmap != null) {
                                // Resize if too large (e.g. max 1024) to avoid OOM and large payloads
                                val maxDim = 1024
                                val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                                    val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                                } else {
                                    bitmap
                                }

                                val outputStream = ByteArrayOutputStream()
                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                                val bytes = outputStream.toByteArray()
                                attachmentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                finalMimeType = "image/jpeg"

                                if (scaledBitmap != bitmap) scaledBitmap.recycle()
                                bitmap.recycle()
                            }
                        }
                    }
                }

                // Save User Message
                chatRepository.addMessage(
                    sessionId,
                    text,
                    isUser = true,
                    attachmentPath = attachmentPath,
                    attachmentType = attachmentMimeType
                )

                // If it's a non-image file, we can't send it via Vision API,
                // but we can at least mention it in the text.
                val finalMessage = if (attachmentPath != null && finalMimeType?.startsWith("image/") != true) {
                    val fileName = attachmentPath!!.substringAfterLast('/')
                    if (text.isNotBlank()) "$text\n\n[Attached file: $fileName]" else "[Attached file: $fileName]"
                } else {
                    text
                }

                Log.d(TAG, "Sending message with attachment: $finalMimeType, base64 length: ${attachmentBase64?.length ?: 0}")
                sendViaHttp(sessionId, finalMessage, attachmentBase64, finalMimeType)
            } catch (e: Exception) {
                stopThinkingSound()
                _uiState.update { it.copy(isThinking = false, error = e.message) }
            }
        }
    }

    private suspend fun sendViaHttp(
        sessionId: String,
        text: String,
        attachmentBase64: String? = null,
        attachmentMimeType: String? = null
    ) {
        val result = apiClient.sendMessage(
            webhookUrl = settings.getChatCompletionsUrl(),
            message = text,
            sessionId = sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() },
            agentId = getEffectiveAgentId(),
            attachmentBase64 = attachmentBase64,
            attachmentMimeType = attachmentMimeType
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

                    speechManager.startListening(settings.speechLanguage.ifEmpty { null }, settings.speechSilenceTimeout).collect { result ->
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

            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak error", e)
                _uiState.update { it.copy(isSpeaking = false) }
                tts?.stop()
                releaseWakeLock()
                sendResumeBroadcast()
            }
        }
    }

    private suspend fun speakWithTTS(text: String): Boolean {
        // Query the engine's actual max input length
        val engineMaxLen = com.openclaw.assistant.speech.TTSUtils.getMaxInputLength(tts)
        // Further limit to 1000 for stability and consistent timeout behavior
        val maxLen = minOf(engineMaxLen, 1000)
        val chunks = com.openclaw.assistant.speech.TTSUtils.splitTextForTTS(text, maxLen)
        Log.d(TAG, "TTS splitting text (${text.length} chars) into ${chunks.size} chunks (targetMaxLen=$maxLen, engineMaxLen=$engineMaxLen)")

        for ((index, chunk) in chunks.withIndex()) {
            val success = speakSingleChunk(chunk, index == 0)
            if (!success) {
                Log.e(TAG, "TTS chunk $index failed, aborting remaining chunks")
                return false
            }
        }
        return true
    }

    private suspend fun speakSingleChunk(text: String, isFirst: Boolean): Boolean {
        // Scale timeout based on text length (minimum 30s, ~150ms per character to accommodate Japanese)
        val timeoutMs = (30_000L + (text.length * 150L)).coerceAtMost(600_000L) // Max 10 mins
        val callbackResult = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                val started = java.util.concurrent.atomic.AtomicBoolean(false)

                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS onStart")
                        started.set(true)
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
                val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val result = tts?.speak(text, queueMode, null, utteranceId)
                Log.d(TAG, "TTS speak result=$result, text length=${text.length}")

                if (result != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS speak failed immediately with result=$result")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                } else {
                    // Polling fallback: only start polling AFTER onStart fires,
                    // to avoid false-positive when engine hasn't begun speaking yet
                    viewModelScope.launch {
                        // Wait for onStart (up to 10s)
                        var waitedMs = 0L
                        while (!started.get() && continuation.isActive && waitedMs < 10_000L) {
                            delay(200)
                            waitedMs += 200
                        }
                        if (!started.get() || !continuation.isActive) return@launch
                        // Now poll isSpeaking - only treat false as "done" after speech started
                        delay(1000)
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
            Log.w(TAG, "TTS chunk timed out, forcing stop")
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
