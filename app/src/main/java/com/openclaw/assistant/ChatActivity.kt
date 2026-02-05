package com.openclaw.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.speech.TTSUtils
import com.openclaw.assistant.ui.chat.ChatMessage
import com.openclaw.assistant.ui.chat.ChatUiState
import com.openclaw.assistant.ui.chat.ChatViewModel
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import androidx.compose.material3.TextButton
import java.util.Locale

private const val TAG = "ChatActivity"

class ChatActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val viewModel: ChatViewModel by viewModels()
    private var tts: TextToSpeech? = null
    private var isRetry = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize TTS with Activity context (important for MIUI!)
        // Try Google TTS first for better compatibility on Chinese ROMs
        initializeTTS()

        // Request Microphone permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.RECORD_AUDIO), 
                REQUEST_RECORD_AUDIO
            )
        }

        setContent {
            OpenClawAssistantTheme {
                val uiState by viewModel.uiState.collectAsState()
                
                ChatScreen(
                    uiState = uiState,
                    onSendMessage = { viewModel.sendMessage(it) },
                    onStartListening = { 
                        Log.e(TAG, "onStartListening called, permission=${checkPermission()}")
                        if (checkPermission()) {
                            viewModel.startListening()
                        } else {
                            Toast.makeText(this, getString(R.string.mic_permission_required), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStopListening = { viewModel.stopListening() },
                    onStopSpeaking = { viewModel.stopSpeaking() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun initializeTTS() {
        Log.e(TAG, "Initializing TTS with Google engine priority (isRetry=$isRetry)...")
        if (!isRetry) {
            tts = TextToSpeech(this, this, TTSUtils.GOOGLE_TTS_PACKAGE)
        } else {
            tts = TextToSpeech(this, this)
        }
    }

    override fun onInit(status: Int) {
        Log.e(TAG, "TTS onInit callback, status=$status (SUCCESS=${TextToSpeech.SUCCESS})")
        if (status == TextToSpeech.SUCCESS) {
            TTSUtils.setupVoice(tts)
            
            // Pass TTS to ViewModel
            tts?.let { viewModel.setTTS(it) }
            Log.e(TAG, "TTS initialized successfully and passed to ViewModel")
        } else {
            Log.e(TAG, "TTS initialization FAILED with status=$status")
            if (!isRetry) {
                isRetry = true
                initializeTTS()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 200
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSendMessage: (String) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
    onBack: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.app_name), 
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold 
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_button))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            Column {
                 if (uiState.partialText.isNotBlank()) {
                     Text(
                         text = uiState.partialText,
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(horizontal = 16.dp, vertical = 8.dp)
                             .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                             .padding(12.dp),
                         color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                 }
                
                ChatInputArea(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = {
                        onSendMessage(inputText)
                        inputText = ""
                        keyboardController?.hide()
                    },
                    isListening = uiState.isListening,
                    onMicClick = {
                        if (uiState.isListening) onStopListening() else onStartListening()
                    }
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
        ) {
            items(uiState.messages) { message ->
                MessageBubble(message = message)
            }
            
            if (uiState.isThinking) {
                item {
                    ThinkingIndicator()
                }
            }
            if (uiState.isSpeaking) {
                item {
                    SpeakingIndicator(onStop = onStopSpeaking)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    
    // Friendly rounded shapes
    val shape = if (isUser) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Card(
                colors = CardDefaults.cardColors(containerColor = containerColor),
                shape = shape,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.text,
                    color = contentColor,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
            }
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.thinking), 
                fontSize = 14.sp, 
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        }
    }
}

@Composable
fun SpeakingIndicator(onStop: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
         Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.speaking), 
                fontSize = 14.sp, 
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onStop, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_description), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
fun ChatInputArea(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isListening: Boolean,
    onMicClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            placeholder = { Text(stringResource(R.string.ask_hint)) },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() })
        )

        FloatingActionButton(
            onClick = {
                android.util.Log.e("ChatInputArea", "FAB clicked, value.isBlank=${value.isBlank()}, isListening=$isListening")
                if (value.isBlank()) onMicClick() else onSend()
            },
            containerColor = if (value.isBlank() && isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            shape = CircleShape
        ) {
            Icon(
                imageVector = if (value.isBlank()) {
                     if (isListening) Icons.Default.Stop else Icons.Default.Mic
                } else {
                     Icons.AutoMirrored.Filled.Send
                },
                contentDescription = stringResource(R.string.send_description),
                tint = Color.White
            )
        }
    }
}
