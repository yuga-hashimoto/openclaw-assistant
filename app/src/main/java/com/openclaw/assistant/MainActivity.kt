package com.openclaw.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.ui.text.font.FontWeight
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

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var settings: SettingsRepository
    private var tts: TextToSpeech? = null
    private var voiceDiagnostic by mutableStateOf<VoiceDiagnostic?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)
        
        // 診断用にTTSを初期化
        tts = TextToSpeech(this, this, TTSUtils.GOOGLE_TTS_PACKAGE)
        
        checkPermissions()

        setContent {
            OpenClawAssistantTheme {
                MainScreen(
                    settings = settings,
                    diagnostic = voiceDiagnostic,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenAssistantSettings = { openAssistantSettings() },
                    onToggleHotword = { enabled -> toggleHotwordService(enabled) },
                    onRefreshDiagnostics = { runDiagnostics() }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            runDiagnostics()
        } else {
            // Google TTS失敗時はデフォルトでリトライ
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
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
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
        settings.hotwordEnabled = enabled
        if (enabled) {
            HotwordService.start(this)
            Toast.makeText(this, getString(R.string.hotword_started), Toast.LENGTH_SHORT).show()
        } else {
            HotwordService.stop(this)
            Toast.makeText(this, getString(R.string.hotword_stopped), Toast.LENGTH_SHORT).show()
        }
    }

    fun isAssistantActive(): Boolean {
        return try {
            val setting = Settings.Secure.getString(contentResolver, "assistant")
            setting?.contains(packageName) == true
        } catch (e: Exception) {
            false
        }
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
    onOpenSettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onToggleHotword: (Boolean) -> Unit,
    onRefreshDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    var isConfigured by remember { mutableStateOf(settings.isConfigured()) }
    var hotwordEnabled by remember { mutableStateOf(settings.hotwordEnabled) }
    var isAssistantSet by remember { mutableStateOf((context as? MainActivity)?.isAssistantActive() ?: false) }
    var showTroubleshooting by remember { mutableStateOf(false) }
    var showHowToUse by remember { mutableStateOf(false) }
    
    val lifecycleOwner = LocalLifecycleOwner.current

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
            // Status card
            StatusCard(isConfigured = isConfigured)
            
            Spacer(modifier = Modifier.height(16.dp))

            // Diagnostic Panel
            if (diagnostic != null) {
                DiagnosticPanel(diagnostic)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Quick actions - 2 cards side by side
            Text(
                text = stringResource(R.string.activation_methods),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Home button long press
                CompactActionCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    icon = Icons.Default.Home,
                    title = stringResource(R.string.home_button),
                    description = if (isAssistantSet) stringResource(R.string.active) else stringResource(R.string.not_set),
                    isActive = isAssistantSet,
                    onClick = onOpenAssistantSettings,
                    showInfoIcon = true,
                    onInfoClick = { showTroubleshooting = true }
                )

                // Hotword
                CompactActionCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    icon = Icons.Default.Mic,
                    title = settings.getWakeWordDisplayName(),
                    description = if (hotwordEnabled) stringResource(R.string.active) else stringResource(R.string.disabled),
                    isActive = hotwordEnabled,
                    showSwitch = true,
                    switchValue = hotwordEnabled,
                    onSwitchChange = { enabled ->
                        if (enabled && !isConfigured) {
                            return@CompactActionCard
                        }
                        hotwordEnabled = enabled
                        onToggleHotword(enabled)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Open Chat - Large button
            val chatContext = LocalContext.current
            Button(
                onClick = {
                    val intent = Intent(chatContext, ChatActivity::class.java)
                    chatContext.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.open_chat), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Configuration warning
            if (!isConfigured) {
                WarningCard(
                    message = stringResource(R.string.error_no_webhook),
                    onClick = onOpenSettings
                )
            }
        }
    }

    if (showTroubleshooting) {
        TroubleshootingDialog(onDismiss = { showTroubleshooting = false })
    }

    if (showHowToUse) {
        HowToUseDialog(onDismiss = { showHowToUse = false })
    }
}

@Composable
fun StatusCard(isConfigured: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConfigured) Color(0xFF4CAF50) else Color(0xFFFFC107)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isConfigured) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = if (isConfigured) stringResource(R.string.ready) else stringResource(R.string.setup_required),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (isConfigured) stringResource(R.string.connected_to_openclaw) else stringResource(R.string.error_no_webhook),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
fun DiagnosticPanel(diagnostic: VoiceDiagnostic) {
    val hasIssues = diagnostic.sttStatus != DiagnosticStatus.READY || 
                    diagnostic.ttsStatus != DiagnosticStatus.READY
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasIssues) 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasIssues) "⚠️ Voice System Issues" else "✅ Voice System Ready",
                    fontWeight = FontWeight.Bold, 
                    fontSize = 14.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Voice Input Status
            DiagnosticItemWithReason(
                label = "Voice Input (Mic)",
                status = diagnostic.sttStatus,
                reason = diagnostic.sttReason,
                engine = diagnostic.sttEngine
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Voice Output Status
            DiagnosticItemWithReason(
                label = "Voice Output (TTS)",
                status = diagnostic.ttsStatus,
                reason = diagnostic.ttsReason,
                engine = diagnostic.ttsEngine
            )

            if (diagnostic.suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "How to fix:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                diagnostic.suggestions.forEach { suggestion ->
                    SuggestionItem(suggestion)
                }
            }
        }
    }
}

@Composable
fun DiagnosticItemWithReason(
    label: String, 
    status: DiagnosticStatus, 
    reason: String?,
    engine: String?
) {
    val color = when (status) {
        DiagnosticStatus.READY -> Color(0xFF4CAF50)
        DiagnosticStatus.WARNING -> Color(0xFFFFC107)
        DiagnosticStatus.ERROR -> Color(0xFFF44336)
    }
    val icon = when (status) {
        DiagnosticStatus.READY -> Icons.Default.CheckCircle
        DiagnosticStatus.WARNING -> Icons.Default.Warning
        DiagnosticStatus.ERROR -> Icons.Default.Error
    }
    val statusText = when (status) {
        DiagnosticStatus.READY -> "Ready"
        DiagnosticStatus.WARNING -> "Warning"
        DiagnosticStatus.ERROR -> "Unavailable"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (engine != null && status == DiagnosticStatus.READY) {
                    Text(
                        text = engine,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
        if (reason != null && status != DiagnosticStatus.READY) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "→ $reason",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 28.dp)
            )
        }
    }
}

@Composable
fun DiagnosticItem(label: String, status: DiagnosticStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        DiagnosticStatus.READY -> Color(0xFF4CAF50)
        DiagnosticStatus.WARNING -> Color(0xFFFFC107)
        DiagnosticStatus.ERROR -> Color(0xFFF44336)
    }
    val icon = when (status) {
        DiagnosticStatus.READY -> Icons.Default.Check
        DiagnosticStatus.WARNING -> Icons.Default.Info
        DiagnosticStatus.ERROR -> Icons.Default.Error
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
fun SuggestionItem(suggestion: com.openclaw.assistant.speech.diagnostics.DiagnosticSuggestion) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = suggestion.message,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            if (suggestion.actionLabel != null && suggestion.intent != null) {
                TextButton(
                    onClick = { 
                        try {
                            context.startActivity(suggestion.intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open settings", Toast.LENGTH_SHORT).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(suggestion.actionLabel, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun CompactActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    description: String,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
    showSwitch: Boolean = false,
    switchValue: Boolean = false,
    onSwitchChange: ((Boolean) -> Unit)? = null,
    showInfoIcon: Boolean = false,
    onInfoClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        onClick = { onClick?.invoke() },
        enabled = onClick != null && !showSwitch,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                    if (showInfoIcon) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.how_to_use),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onInfoClick?.invoke() }
                        )
                    }
                    if (showSwitch) {
                        Switch(
                            checked = switchValue,
                            onCheckedChange = onSwitchChange,
                            modifier = Modifier
                                .scale(0.8f)
                                .offset(y = (-8).dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            
            Text(
                text = description,
                fontSize = 12.sp,
                color = if (isActive) Color(0xFF4CAF50) else Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun WarningCard(message: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                color = Color(0xFFE65100),
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFFFF9800)
            )
        }
    }
}

@Composable
fun UsageStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
fun HowToUseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.how_to_use)) },
        text = {
            Column {
                UsageStep(number = "1", text = stringResource(R.string.step_1))
                UsageStep(number = "2", text = stringResource(R.string.step_2))
                UsageStep(number = "3", text = stringResource(R.string.step_3))
                UsageStep(number = "4", text = stringResource(R.string.step_4))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.got_it))
            }
        }
    )
}

@Composable
fun TroubleshootingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assist_gesture_not_working)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.troubleshooting_intro),
                    fontSize = 14.sp
                )
                
                BulletPoint(
                    title = stringResource(R.string.circle_to_search_title), 
                    desc = stringResource(R.string.circle_to_search_desc)
                )
                
                BulletPoint(
                    title = stringResource(R.string.gesture_navigation_title), 
                    desc = stringResource(R.string.gesture_navigation_desc)
                )
                
                BulletPoint(
                    title = stringResource(R.string.google_app_setting_title), 
                    desc = stringResource(R.string.google_app_setting_desc)
                )

                BulletPoint(
                    title = stringResource(R.string.refresh_binding_title), 
                    desc = stringResource(R.string.refresh_binding_desc)
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                val context = LocalContext.current
                Button(
                    onClick = {
                        val intent = Intent(context, OpenClawAssistantService::class.java).apply {
                            action = OpenClawAssistantService.ACTION_SHOW_ASSISTANT
                        }
                        context.startService(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text(stringResource(R.string.debug_force_start))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.got_it))
            }
        }
    )
}

@Composable
fun BulletPoint(title: String, desc: String) {
    Column {
        Text("• $title", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(desc, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp))
    }
}
