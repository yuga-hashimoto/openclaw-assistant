package com.openclaw.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.gateway.GatewayClient
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService
import com.openclaw.assistant.service.OpenClawAssistantService
import com.openclaw.assistant.speech.TTSUtils
import com.openclaw.assistant.speech.diagnostics.DiagnosticStatus
import com.openclaw.assistant.speech.diagnostics.VoiceDiagnostic
import com.openclaw.assistant.speech.diagnostics.VoiceDiagnostics
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

data class PermissionInfo(
    val permission: String,
    val nameResId: Int,
    val descResId: Int
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var settings: SettingsRepository
    private var tts: TextToSpeech? = null
    private var voiceDiagnostic by mutableStateOf<VoiceDiagnostic?>(null)
    private var missingPermissions by mutableStateOf<List<PermissionInfo>>(emptyList())
    private var pendingHotwordStart = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (pendingHotwordStart) {
            pendingHotwordStart = false
            if (recordAudioGranted) {
                settings.hotwordEnabled = true
                HotwordService.start(this)
                Toast.makeText(this, getString(R.string.hotword_started), Toast.LENGTH_SHORT).show()
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    showPermissionSettingsDialog()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        } else {
            val allGranted = permissions.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_SHORT).show()
            }
        }
        refreshMissingPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)
        
        initializeTTS()
        checkPermissions()
        refreshMissingPermissions()

        setContent {
            OpenClawAssistantTheme {
                MainScreen(
                    settings = settings,
                    diagnostic = voiceDiagnostic,
                    missingPermissions = missingPermissions,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenAssistantSettings = { openAssistantSettings() },
                    onToggleHotword = { enabled -> toggleHotwordService(enabled) },
                    onRefreshDiagnostics = {
                        initializeTTS() // Re-init on manual refresh
                    },
                    onRequestPermissions = { permissions ->
                        val canRequest = permissions.any {
                            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                        } || permissions.any {
                            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (canRequest) {
                            permissionLauncher.launch(permissions.toTypedArray())
                        } else {
                            openAppSettings()
                        }
                    },
                    onOpenAppSettings = { openAppSettings() }
                )
            }
        }
    }

    private fun initializeTTS() {
        tts?.shutdown()
        Log.e("MainActivity", "Initializing TTS with Google Engine priority...")
        tts = TextToSpeech(this, this, TTSUtils.GOOGLE_TTS_PACKAGE)
    }

    override fun onInit(status: Int) {
        Log.e("MainActivity", "TTS onInit status=$status")
        if (status == TextToSpeech.SUCCESS) {
            runDiagnostics()
        } else {
            Log.e("MainActivity", "Google TTS failed, trying default...")
            tts = TextToSpeech(this) { 
                runDiagnostics()
            }
        }
    }

    private fun runDiagnostics() {
        voiceDiagnostic = VoiceDiagnostics(this).performFullCheck(tts)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshMissingPermissions() {
        val missing = mutableListOf<PermissionInfo>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(PermissionInfo(Manifest.permission.RECORD_AUDIO, R.string.permission_record_audio, R.string.permission_record_audio_desc))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(PermissionInfo(Manifest.permission.POST_NOTIFICATIONS, R.string.permission_post_notifications, R.string.permission_post_notifications_desc))
        }
        missingPermissions = missing
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.could_not_open_settings), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleHotwordService(enabled: Boolean) {
        if (enabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                settings.hotwordEnabled = true
                HotwordService.start(this)
                Toast.makeText(this, getString(R.string.hotword_started), Toast.LENGTH_SHORT).show()
            } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                // Show rationale dialog before requesting permission
                showPermissionRationaleDialog()
            } else {
                // First-time request: launch directly
                pendingHotwordStart = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        } else {
            settings.hotwordEnabled = false
            HotwordService.stop(this)
            Toast.makeText(this, getString(R.string.hotword_stopped), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPermissionRationaleDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_rationale_title))
            .setMessage(getString(R.string.mic_permission_rationale_message))
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                pendingHotwordStart = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_denied_title))
            .setMessage(getString(R.string.mic_permission_denied_message))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mic_permission_denied_title))
            .setMessage(getString(R.string.mic_permission_denied_permanently))
            .setPositiveButton(getString(R.string.open_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    fun isAssistantActive(): Boolean {
        return try {
            Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true
        } catch (e: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        refreshMissingPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: SettingsRepository,
    diagnostic: VoiceDiagnostic?,
    missingPermissions: List<PermissionInfo> = emptyList(),
    onOpenSettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onToggleHotword: (Boolean) -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit = {},
    onOpenAppSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    var isConfigured by remember { mutableStateOf(settings.isConfigured()) }
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    var isAssistantSet by remember { mutableStateOf((context as? MainActivity)?.isAssistantActive() ?: false) }
    var showTroubleshooting by remember { mutableStateOf(false) }
    var showHowToUse by remember { mutableStateOf(false) }
    
    // Permission error observation
    val gatewayClient = remember { GatewayClient.getInstance() }
    val missingScopeError by gatewayClient.missingScopeError.collectAsState()
    val isPairingRequired by gatewayClient.isPairingRequired.collectAsState()
    val deviceId = gatewayClient.deviceId

    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-connect WS on launch for agent list and pairing detection
    LaunchedEffect(Unit) {
        if (settings.isConfigured()) {
             val baseUrl = settings.getBaseUrl()
             if (baseUrl.isNotBlank()) {
                 try {
                     val url = java.net.URL(baseUrl)
                     val host = url.host
                     val useTls = url.protocol == "https"
                     val port = if (useTls) {
                         if (url.port > 0) url.port else 443
                     } else {
                         if (settings.gatewayPort > 0) settings.gatewayPort else
                             if (url.port > 0) url.port else 18789
                     }
                     val token = settings.authToken.takeIf { it.isNotBlank() }
                     gatewayClient.connect(host, port, token, useTls = useTls)
                 } catch (e: Exception) {
                     // Ignore parse errors here
                 }
             }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isConfigured = settings.isConfigured()
                hotwordEnabled = settings.hotwordEnabled
                isAssistantSet = (context as? MainActivity)?.isAssistantActive() ?: false
                onRefreshDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showHowToUse = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.how_to_use))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Show alert if missing scope error is present
            if (missingScopeError != null) {
                MissingScopeCard(error = missingScopeError!!, onClick = onOpenSettings)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Show pairing required banner
            if (isPairingRequired && deviceId != null) {
                PairingRequiredCard(deviceId = deviceId)
                Spacer(modifier = Modifier.height(16.dp))
            }

            StatusCard(isConfigured = isConfigured)
            Spacer(modifier = Modifier.height(16.dp))

            if (missingPermissions.isNotEmpty()) {
                PermissionStatusCard(
                    missingPermissions = missingPermissions,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAppSettings = onOpenAppSettings
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (diagnostic != null) {
                DiagnosticPanel(diagnostic, onRefreshDiagnostics)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(text = stringResource(R.string.activation_methods), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactActionCard(modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.Default.Home, title = stringResource(R.string.home_button), description = if (isAssistantSet) stringResource(R.string.active) else stringResource(R.string.not_set), isActive = isAssistantSet, onClick = onOpenAssistantSettings, showInfoIcon = true, onInfoClick = { showTroubleshooting = true })
                CompactActionCard(modifier = Modifier.weight(1f).fillMaxHeight(), icon = Icons.Default.Mic, title = settings.getWakeWordDisplayName(), description = if (hotwordEnabled) stringResource(R.string.active) else stringResource(R.string.disabled), isActive = hotwordEnabled, showSwitch = true, switchValue = hotwordEnabled, onSwitchChange = { enabled -> if (enabled && !isConfigured) return@CompactActionCard; onToggleHotword(enabled); hotwordEnabled = settings.hotwordEnabled })
            }

            Spacer(modifier = Modifier.height(24.dp))
            val chatContext = LocalContext.current
            Button(onClick = { chatContext.startActivity(Intent(chatContext, ChatActivity::class.java)) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.open_chat), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            if (!isConfigured) {
                Spacer(modifier = Modifier.height(24.dp))
                WarningCard(message = stringResource(R.string.error_no_webhook), onClick = onOpenSettings)
            }
        }
    }
    if (showTroubleshooting) TroubleshootingDialog(onDismiss = { showTroubleshooting = false })
    if (showHowToUse) HowToUseDialog(onDismiss = { showHowToUse = false })
}

@Composable
fun StatusCard(isConfigured: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isConfigured) Color(0xFF4CAF50) else Color(0xFFFFC107))) {
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = if (isConfigured) stringResource(R.string.ready) else stringResource(R.string.setup_required), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(text = if (isConfigured) stringResource(R.string.connected_to_openclaw) else stringResource(R.string.error_no_webhook), fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f) )
            }
        }
    }
}

@Composable
fun DiagnosticPanel(diagnostic: VoiceDiagnostic, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Voice System Check", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticItem(label = "STT: ${diagnostic.sttEngine?.take(10) ?: "Def"}", status = diagnostic.sttStatus, modifier = Modifier.weight(1f))
                DiagnosticItem(label = "TTS: ${diagnostic.ttsEngine?.split('.')?.lastOrNull() ?: "null"}", status = diagnostic.ttsStatus, modifier = Modifier.weight(1f))
                DiagnosticItem(label = "Wake: ${diagnostic.hotwordEngine?.take(10) ?: "null"}", status = diagnostic.hotwordStatus, modifier = Modifier.weight(1f))
            }
            if (diagnostic.suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                diagnostic.suggestions.forEach { SuggestionItem(it) }
            }
        }
    }
}

@Composable
fun DiagnosticItem(label: String, status: DiagnosticStatus, modifier: Modifier = Modifier) {
    val color = when (status) { DiagnosticStatus.READY -> Color(0xFF4CAF50); DiagnosticStatus.WARNING -> Color(0xFFFFC107); DiagnosticStatus.ERROR -> Color(0xFFF44336) }
    val icon = when (status) { DiagnosticStatus.READY -> Icons.Default.Check; DiagnosticStatus.WARNING -> Icons.Default.Info; DiagnosticStatus.ERROR -> Icons.Default.Error }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SuggestionItem(suggestion: com.openclaw.assistant.speech.diagnostics.DiagnosticSuggestion) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = suggestion.message, modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp)
            if (suggestion.actionLabel != null && suggestion.intent != null) {
                TextButton(onClick = { try { context.startActivity(suggestion.intent) } catch (e: Exception) { Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show() } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(suggestion.actionLabel, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun CompactActionCard(modifier: Modifier = Modifier, icon: ImageVector, title: String, description: String, isActive: Boolean = false, onClick: (() -> Unit)? = null, showSwitch: Boolean = false, switchValue: Boolean = false, onSwitchChange: ((Boolean) -> Unit)? = null, showInfoIcon: Boolean = false, onInfoClick: (() -> Unit)? = null) {
    Card(modifier = modifier, onClick = { onClick?.invoke() }, enabled = onClick != null && !showSwitch, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = icon, contentDescription = null, tint = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray, modifier = Modifier.size(28.dp))
                    if (showInfoIcon) Icon(imageVector = Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).clickable { onInfoClick?.invoke() })
                    if (showSwitch) Switch(checked = switchValue, onCheckedChange = onSwitchChange, modifier = Modifier.scale(0.8f).offset(y = (-8).dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text(text = description, fontSize = 12.sp, color = if (isActive) Color(0xFF4CAF50) else Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun MissingScopeCard(error: String, onClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(), 
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), 
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Error, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error, 
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permission_error_title), 
                        fontWeight = FontWeight.Bold, 
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.permission_error_desc), 
                        fontSize = 13.sp, 
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                    )
                    if (!expanded) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.permission_error_action), 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                // Fix Request Section
                Text(
                    text = stringResource(R.string.fix_request_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                val fixMessage = stringResource(R.string.fix_request_message)
                Text(
                    text = fixMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = Intent(context, ChatActivity::class.java).apply {
                            putExtra("EXTRA_PREFILL_TEXT", fixMessage)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_ask_ai))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Technical Details:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Error", error)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, R.string.error_copied, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_copy_error))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onClick, // This now just toggles via the card click, wait... user might want to open settings. 
                        // The `onClick` passed to MissingScopeCard was originally to open settings?
                        // Let's check where it's called.
                        // In MainScreen: MissingScopeCard(error = it) { settingsIntent... }
                        // So onClick DOES open settings.
                        // My previous edit in step 284 changed the Card's onClick to expansion toggle.
                        // So I need to make sure the "Open Settings" button calls the `onClick` param.
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    missingPermissions: List<PermissionInfo>,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.permissions_missing_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE65100)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            missingPermissions.forEach { perm ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(perm.nameResId),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = stringResource(perm.descResId),
                            fontSize = 12.sp,
                            color = Color(0xFF795548)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.open_settings), color = Color(0xFFE65100))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onRequestPermissions(missingPermissions.map { it.permission }) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
        }
    }
}

@Composable
fun WarningCard(message: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = message, color = Color(0xFFE65100), modifier = Modifier.weight(1f))
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFFFF9800))
        }
    }
}

@Composable
fun UsageStep(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text(text = number, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
fun HowToUseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.how_to_use)) }, text = { Column { (1..4).forEach { 
        val resId = context.resources.getIdentifier("step_$it", "string", context.packageName)
        UsageStep(it.toString(), stringResource(resId)) } } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) } })
}

@Composable
fun TroubleshootingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.assist_gesture_not_working)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.troubleshooting_intro), fontSize = 14.sp)
            listOf(
                "circle_to_search",
                "gesture_navigation",
                "google_app_setting",
                "refresh_binding",
                "wake_word_mic",
                "wake_word_interference"
            ).forEach { key ->
                val titleId = context.resources.getIdentifier("${key}_title", "string", context.packageName)
                val descId = context.resources.getIdentifier("${key}_desc", "string", context.packageName)
                if (titleId != 0 && descId != 0) {
                    BulletPoint(stringResource(titleId), stringResource(descId))
                }
            }
            Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { context.startService(Intent(context, OpenClawAssistantService::class.java).apply { action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT }) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) { Text(stringResource(R.string.debug_force_start)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) } })
}

@Composable
fun PairingRequiredCard(deviceId: String) {
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val oneCommand = "openclaw devices approve \$(openclaw devices list --json | python3 -c \"import sys, json; print(next((r['Request'] for r in json.load(sys.stdin)['pending'] if r['Device'] == '$deviceId'), 'NOT_FOUND'))\")"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.pairing_required_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.pairing_required_desc),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = oneCommand,
                        modifier = Modifier.padding(12.dp),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val clip = ClipData.newPlainText("OpenClaw Approval Command", oneCommand)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(context, context.getString(R.string.pairing_command_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.pairing_copy_command))
            }
        }
    }
}

@Composable
fun BulletPoint(title: String, desc: String) {
    Column { Text("• $title", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(desc, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp)) }
}
