package com.openclaw.assistant.wear.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.wear.compose.material.Text
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSUtils
import com.openclaw.assistant.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.media.AudioFocusRequest as AndroidAudioFocusRequest

/**
 * Wear OS Voice Interaction Session.
 * Handles the full conversation flow: listen → recognize → API call → TTS response.
 * Simplified from the phone version — single-turn only, minimal UI.
 */
class WearSession(context: Context) : VoiceInteractionSession(context),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "WearSession"
    }

    private val settings = SettingsRepository.getInstance(context)
    private val apiClient = OpenClawClient()
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager

    private val chatRepository = ChatRepository.getInstance(context)
    private var currentSessionId: String? = null

    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI State
    private var currentState = mutableStateOf(AssistantState.IDLE)
    private var displayText = mutableStateOf("")
    private var userQuery = mutableStateOf("")
    private var partialText = mutableStateOf("")
    private var errorMessage = mutableStateOf<String?>(null)

    // Lifecycle
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    private var audioFocusRequest: AndroidAudioFocusRequest? = null
    private var listeningJob: Job? = null
    private var speakingJob: Job? = null

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        try { savedStateRegistryController.performAttach() } catch (_: Exception) {}
        try { savedStateRegistryController.performRestore(null) } catch (_: Exception) {}
        try { lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE) } catch (_: Exception) {}

        speechManager = SpeechRecognizerManager(context)
        ttsManager = TTSManager(context)
        Log.d(TAG, "Session created")
    }

    override fun onCreateContentView(): View {
        val composeView = ComposeView(context).apply {
            try {
                setViewTreeLifecycleOwner(this@WearSession)
                setViewTreeViewModelStoreOwner(this@WearSession)
                setViewTreeSavedStateRegistryOwner(this@WearSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }

            setContent {
                WearAssistantUI(
                    state = currentState.value,
                    displayText = displayText.value,
                    userQuery = userQuery.value,
                    partialText = partialText.value,
                    errorMessage = errorMessage.value
                )
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        if (this::speechManager.isInitialized) {
            try { speechManager.destroy() } catch (_: Exception) {}
        }
        speechManager = SpeechRecognizerManager(context)

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        WearSessionForegroundService.start(context)
        Log.d(TAG, "Session shown")

        // Create chat session
        scope.launch {
            try {
                currentSessionId = chatRepository.createSession(
                    title = "Wear ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                )
                settings.sessionId = currentSessionId!!
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create DB session", e)
            }
        }

        // Check configuration
        if (!settings.isConfigured()) {
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_config_required)
            return
        }

        startListening()
    }

    override fun onHide() {
        super.onHide()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        abandonAudioFocus()
        WearSessionForegroundService.stop(context)
        scope.cancel()
        speechManager.destroy()
        ttsManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        WearSessionForegroundService.stop(context)
        ttsManager.shutdown()
        toneGenerator.release()
    }

    private fun startListening() {
        listeningJob?.cancel()

        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null

        listeningJob = scope.launch {
            var hasResult = false

            delay(50)

            // Request audio focus
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AndroidAudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                ).build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            }

            val listenResult = withTimeoutOrNull(15_000L) {
                speechManager.startListening(
                    settings.speechLanguage.ifEmpty { null },
                    settings.speechSilenceTimeout
                ).collectLatest { result ->
                    when (result) {
                        is SpeechResult.Ready -> {
                            currentState.value = AssistantState.LISTENING
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP)
                        }
                        is SpeechResult.Listening -> {
                            if (currentState.value != AssistantState.LISTENING) {
                                currentState.value = AssistantState.LISTENING
                            }
                        }
                        is SpeechResult.RmsChanged -> {
                            // Skip audio level visualization on watch for simplicity
                        }
                        is SpeechResult.PartialResult -> {
                            partialText.value = result.text
                        }
                        is SpeechResult.Result -> {
                            hasResult = true
                            userQuery.value = result.text
                            sendToOpenClaw(result.text)
                        }
                        is SpeechResult.Error -> {
                            val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                result.code == SpeechRecognizer.ERROR_NO_MATCH
                            if (isTimeout) {
                                toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 100)
                                finish()
                            } else {
                                toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 100)
                                currentState.value = AssistantState.ERROR
                                errorMessage.value = result.message
                            }
                            hasResult = true
                        }
                        else -> {}
                    }
                }
            }

            if (listenResult == null && !hasResult) {
                Log.w(TAG, "Speech recognition timed out (15s)")
                finish()
            }
        }
    }

    private fun sendToOpenClaw(message: String) {
        currentState.value = AssistantState.THINKING
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        displayText.value = ""

        scope.launch {
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, message, isUser = true)
            }
            sendViaHttp(message)
        }
    }

    private suspend fun sendViaHttp(message: String) {
        val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }
        val result = apiClient.sendMessage(
            webhookUrl = settings.getChatCompletionsUrl(),
            message = message,
            sessionId = settings.sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() },
            agentId = agentId
        )

        result.fold(
            onSuccess = { response ->
                val responseText = response.getResponseText()
                if (responseText != null) {
                    displayText.value = responseText
                    handleResponseReceived(responseText)
                } else if (response.error != null) {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = response.error
                } else {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_no_response)
                }
            },
            onFailure = { error ->
                Log.e(TAG, "API error", error)
                currentState.value = AssistantState.ERROR
                errorMessage.value = error.message ?: context.getString(R.string.error_network)
            }
        )
    }

    private suspend fun handleResponseReceived(responseText: String) {
        // Save AI message
        currentSessionId?.let { sessionId ->
            chatRepository.addMessage(sessionId, responseText, isUser = false)
        }

        if (settings.ttsEnabled) {
            speakResponse(responseText)
        } else {
            // No TTS — show text and stay idle (single-turn, no continuous mode on watch)
            currentState.value = AssistantState.IDLE
            WearSessionForegroundService.stop(context)
        }
    }

    private fun speakResponse(text: String) {
        currentState.value = AssistantState.SPEAKING
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)

        speakingJob = scope.launch {
            try {
                val success = ttsManager.speak(cleanText)
                abandonAudioFocus()

                if (success) {
                    // Single-turn on watch — done after TTS
                    currentState.value = AssistantState.IDLE
                    WearSessionForegroundService.stop(context)
                } else {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_speech_general)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS error", e)
                abandonAudioFocus()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_speech_general)
            }
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        audioFocusRequest = null
    }
}

/** Assistant state for wear UI */
enum class AssistantState {
    IDLE, LISTENING, PROCESSING, THINKING, SPEAKING, ERROR
}

/** Minimal Wear OS UI — state indicator + text, designed for ~280x280 round screen */
@Composable
fun WearAssistantUI(
    state: AssistantState,
    displayText: String,
    userQuery: String,
    partialText: String,
    errorMessage: String?
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // State indicator (colored circle)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            AssistantState.LISTENING -> Color(0xFF4CAF50) // Green
                            AssistantState.SPEAKING -> Color(0xFF2196F3) // Blue
                            AssistantState.THINKING, AssistantState.PROCESSING -> Color(0xFFFFC107) // Amber
                            AssistantState.ERROR -> Color(0xFFF44336) // Red
                            else -> Color(0xFF9E9E9E) // Gray
                        }
                    )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // State label
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> "Listening…"
                    AssistantState.PROCESSING, AssistantState.THINKING -> "Thinking…"
                    AssistantState.SPEAKING -> "Speaking…"
                    AssistantState.ERROR -> "Error"
                    else -> "Ready"
                },
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            // Partial recognition text
            if (partialText.isNotBlank() && state == AssistantState.LISTENING) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = partialText,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // User query
            if (userQuery.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = userQuery,
                    color = Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // AI response text
            if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = displayText,
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    color = Color(0xFFF44336),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
