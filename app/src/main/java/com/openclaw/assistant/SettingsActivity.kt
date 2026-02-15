package com.openclaw.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        setContent {
            OpenClawAssistantTheme {
                SettingsScreen(
                    settings = settings,
                    onSave = { 
                        Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    var webhookUrl by remember { mutableStateOf(settings.webhookUrl) }
    var authToken by remember { mutableStateOf(settings.authToken) }
    var connectionMode by remember { mutableStateOf(settings.connectionMode) }
    var gatewayPort by remember { mutableStateOf(settings.gatewayPort.toString()) }
    var ttsEnabled by remember { mutableStateOf(settings.ttsEnabled) }
    var ttsSpeed by remember { mutableStateOf(settings.ttsSpeed) }
    var continuousMode by remember { mutableStateOf(settings.continuousMode) }
    var wakeWordPreset by remember { mutableStateOf(settings.wakeWordPreset) }
    var customWakeWord by remember { mutableStateOf(settings.customWakeWord) }
    var speechSilenceTimeout by remember { mutableStateOf(settings.speechSilenceTimeout.toFloat().coerceIn(5000f, 30000f)) }
    var thinkingSoundEnabled by remember { mutableStateOf(settings.thinkingSoundEnabled) }

    var showAuthToken by remember { mutableStateOf(false) }
    var showWakeWordMenu by remember { mutableStateOf(false) }
    var showConnectionModeMenu by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val apiClient = remember { OpenClawClient() }
    
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    // TTS Engines
    var ttsEngine by remember { mutableStateOf(settings.ttsEngine) }
    var availableEngines by remember { mutableStateOf<List<com.openclaw.assistant.speech.TTSEngineUtils.EngineInfo>>(emptyList()) }
    var showEngineMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Load engines off-main thread ideally, but for now simple
        availableEngines = com.openclaw.assistant.speech.TTSEngineUtils.getAvailableEngines(context)
    }

    // Wake word options
    val wakeWordOptions = listOf(
        SettingsRepository.WAKE_WORD_OPEN_CLAW to stringResource(R.string.wake_word_openclaw),
        SettingsRepository.WAKE_WORD_HEY_ASSISTANT to stringResource(R.string.wake_word_hey_assistant),
        SettingsRepository.WAKE_WORD_JARVIS to stringResource(R.string.wake_word_jarvis),
        SettingsRepository.WAKE_WORD_COMPUTER to stringResource(R.string.wake_word_computer),
        SettingsRepository.WAKE_WORD_CUSTOM to stringResource(R.string.wake_word_custom)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_button))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            settings.webhookUrl = webhookUrl
                            settings.authToken = authToken
                            settings.connectionMode = connectionMode
                            settings.gatewayPort = gatewayPort.toIntOrNull() ?: 18789
                            settings.ttsEnabled = ttsEnabled
                            settings.ttsSpeed = ttsSpeed
                            settings.ttsEngine = ttsEngine
                            settings.continuousMode = continuousMode
                            settings.wakeWordPreset = wakeWordPreset
                            settings.customWakeWord = customWakeWord
                            settings.speechSilenceTimeout = speechSilenceTimeout.toLong()
                            settings.thinkingSoundEnabled = thinkingSoundEnabled
                            onSave()
                        },
                        enabled = webhookUrl.isNotBlank() && !isTesting
                    ) {
                        Text(stringResource(R.string.save_button))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // === CONNECTION SECTION ===
            Text(
                text = stringResource(R.string.connection),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Webhook URL
                    OutlinedTextField(
                        value = webhookUrl,
                        onValueChange = { 
                            webhookUrl = it
                            testResult = null
                        },
                        label = { Text(stringResource(R.string.webhook_url_label) + " *") },
                        placeholder = { Text(stringResource(R.string.webhook_url_hint)) },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        isError = webhookUrl.isBlank()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Auth Token
                    OutlinedTextField(
                        value = authToken,
                        onValueChange = { 
                            authToken = it
                            testResult = null
                        },
                        label = { Text(stringResource(R.string.auth_token_label)) },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showAuthToken = !showAuthToken }) {
                                Icon(
                                    if (showAuthToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        visualTransformation = if (showAuthToken) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Connection Mode
                    ExposedDropdownMenuBox(
                        expanded = showConnectionModeMenu,
                        onExpandedChange = { showConnectionModeMenu = it }
                    ) {
                        val modeLabel = when (connectionMode) {
                            "websocket" -> stringResource(R.string.connection_mode_websocket)
                            "http" -> stringResource(R.string.connection_mode_http)
                            else -> stringResource(R.string.connection_mode_auto)
                        }
                        OutlinedTextField(
                            value = modeLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connection_mode)) },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showConnectionModeMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = showConnectionModeMenu,
                            onDismissRequest = { showConnectionModeMenu = false }
                        ) {
                            listOf(
                                "auto" to stringResource(R.string.connection_mode_auto),
                                "websocket" to stringResource(R.string.connection_mode_websocket),
                                "http" to stringResource(R.string.connection_mode_http)
                            ).forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        connectionMode = value
                                        showConnectionModeMenu = false
                                    },
                                    leadingIcon = {
                                        if (connectionMode == value) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Gateway Port (only for websocket/auto modes)
                    if (connectionMode != "http") {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = gatewayPort,
                            onValueChange = { gatewayPort = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.gateway_port)) },
                            leadingIcon = { Icon(Icons.Default.Router, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Test Connection Button
                    Button(
                        onClick = {
                            if (webhookUrl.isBlank()) return@Button
                            scope.launch {
                                try {
                                    isTesting = true
                                    testResult = null
                                    val result = apiClient.testConnection(webhookUrl, authToken)
                                    result.fold(
                                        onSuccess = {
                                            testResult = TestResult(success = true, message = context.getString(R.string.connected))
                                            settings.webhookUrl = webhookUrl
                                            settings.authToken = authToken
                                            settings.isVerified = true
                                        },
                                        onFailure = {
                                            testResult = TestResult(success = false, message = context.getString(R.string.failed, it.message ?: ""))
                                        }
                                    )
                                } catch (e: Exception) {
                                    testResult = TestResult(success = false, message = context.getString(R.string.error, e.message ?: ""))
                                } finally {
                                    isTesting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                testResult?.success == true -> Color(0xFF4CAF50)
                                testResult?.success == false -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        ),
                        enabled = webhookUrl.isNotBlank() && !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.testing))
                        } else {
                            Icon(
                                when {
                                    testResult?.success == true -> Icons.Default.Check
                                    testResult?.success == false -> Icons.Default.Error
                                    else -> Icons.Default.NetworkCheck
                                },
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(testResult?.message ?: stringResource(R.string.test_connection_button))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === VOICE SECTION ===
            Text(
                text = stringResource(R.string.voice),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.voice_output), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.read_ai_responses), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }

                    if (ttsEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        Column(modifier = Modifier.fillMaxWidth()) {
                            // TTS Engine Selection
                            ExposedDropdownMenuBox(
                                expanded = showEngineMenu,
                                onExpandedChange = { showEngineMenu = it }
                            ) {
                                val currentLabel = if (ttsEngine.isEmpty()) {
                                    stringResource(R.string.tts_engine_auto)
                                } else {
                                    availableEngines.find { it.name == ttsEngine }?.label ?: ttsEngine
                                }
                                
                                OutlinedTextField(
                                    value = currentLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.tts_engine_label)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEngineMenu) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )
                                
                                ExposedDropdownMenu(
                                    expanded = showEngineMenu,
                                    onDismissRequest = { showEngineMenu = false }
                                ) {
                                    // Auto option
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tts_engine_auto)) },
                                        onClick = {
                                            ttsEngine = ""
                                            showEngineMenu = false
                                        },
                                        leadingIcon = {
                                            if (ttsEngine.isEmpty()) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                    
                                    availableEngines.forEach { engine ->
                                        DropdownMenuItem(
                                            text = { Text(engine.label) },
                                            onClick = {
                                                ttsEngine = engine.name
                                                showEngineMenu = false
                                            },
                                            leadingIcon = {
                                                if (ttsEngine == engine.name) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        )
                                    }
                                }
                            }



                            // Show Speed setting ONLY if Google TTS is selected (or Auto resolving to Google)
                            val effectiveEngine = if (ttsEngine.isEmpty()) {
                                com.openclaw.assistant.speech.TTSEngineUtils.getDefaultEngine(context)
                            } else {
                                ttsEngine
                            }
                            
                            val isGoogleTTS = effectiveEngine == SettingsRepository.GOOGLE_TTS_PACKAGE

                            if (isGoogleTTS) {
                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(R.string.voice_speed), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "%.1fx".format(ttsSpeed),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                Slider(
                                    value = ttsSpeed,
                                    onValueChange = { ttsSpeed = it },
                                    valueRange = 0.5f..3.0f,
                                    steps = 24, // Steps of 0.1
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.continuous_conversation), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.auto_start_mic), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = continuousMode, onCheckedChange = { continuousMode = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Speech silence timeout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.speech_silence_timeout), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.speech_silence_timeout_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Text(
                            text = "%.1fs".format(speechSilenceTimeout / 1000f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = speechSilenceTimeout,
                        onValueChange = { speechSilenceTimeout = it },
                        valueRange = 5000f..30000f,
                        steps = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Thinking sound
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.thinking_sound), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.thinking_sound_desc), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = thinkingSoundEnabled, onCheckedChange = { thinkingSoundEnabled = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === WAKE WORD SECTION ===
            Text(
                text = stringResource(R.string.wake_word),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = showWakeWordMenu,
                        onExpandedChange = { showWakeWordMenu = it }
                    ) {
                        OutlinedTextField(
                            value = wakeWordOptions.find { it.first == wakeWordPreset }?.second ?: stringResource(R.string.wake_word_openclaw),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.activation_phrase)) },
                            leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showWakeWordMenu) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = showWakeWordMenu,
                            onDismissRequest = { showWakeWordMenu = false }
                        ) {
                            wakeWordOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        wakeWordPreset = value
                                        showWakeWordMenu = false
                                    },
                                    leadingIcon = {
                                        if (wakeWordPreset == value) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    
                    if (wakeWordPreset == SettingsRepository.WAKE_WORD_CUSTOM) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customWakeWord,
                            onValueChange = { customWakeWord = it.lowercase() },
                            label = { Text(stringResource(R.string.custom_wake_word)) },
                            placeholder = { Text(stringResource(R.string.custom_wake_word_hint)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = {
                                Text(stringResource(R.string.custom_wake_word_help), color = Color.Gray, fontSize = 12.sp)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

data class TestResult(
    val success: Boolean,
    val message: String
)
