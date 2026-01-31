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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

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
                        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
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
    var sessionId by remember { mutableStateOf(settings.sessionId) }
    var userId by remember { mutableStateOf(settings.userId) }
    var picovoiceKey by remember { mutableStateOf(settings.picovoiceAccessKey) }
    var startOnBoot by remember { mutableStateOf(settings.startOnBoot) }
    
    var showAuthToken by remember { mutableStateOf(false) }
    var showPicovoiceKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            // 保存
                            settings.webhookUrl = webhookUrl
                            settings.authToken = authToken
                            settings.sessionId = sessionId
                            settings.userId = userId
                            settings.picovoiceAccessKey = picovoiceKey
                            settings.startOnBoot = startOnBoot
                            onSave()
                        },
                        enabled = webhookUrl.isNotBlank()
                    ) {
                        Text("保存")
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
            // OpenClaw設定
            Text(
                text = "OpenClaw接続設定",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            // Webhook URL（必須）
            OutlinedTextField(
                value = webhookUrl,
                onValueChange = { webhookUrl = it },
                label = { Text("Webhook URL *") },
                placeholder = { Text("https://your-openclaw.com/webhook") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = webhookUrl.isBlank(),
                supportingText = {
                    if (webhookUrl.isBlank()) {
                        Text("必須項目です", color = MaterialTheme.colorScheme.error)
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 認証トークン（オプション）
            OutlinedTextField(
                value = authToken,
                onValueChange = { authToken = it },
                label = { Text("認証トークン (Bearer)") },
                placeholder = { Text("オプション") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showAuthToken = !showAuthToken }) {
                        Icon(
                            if (showAuthToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "表示切替"
                        )
                    }
                },
                visualTransformation = if (showAuthToken) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // セッション設定
            Text(
                text = "セッション設定",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            // セッションID
            OutlinedTextField(
                value = sessionId,
                onValueChange = { sessionId = it },
                label = { Text("セッションID") },
                leadingIcon = { Icon(Icons.Default.Fingerprint, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { sessionId = settings.generateNewSessionId() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "新規生成")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("連続会話を維持するためのID") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ユーザーID
            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it },
                label = { Text("ユーザーID") },
                placeholder = { Text("オプション") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Picovoice設定
            Text(
                text = "ホットワード設定 (Picovoice)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            // Picovoice Access Key
            OutlinedTextField(
                value = picovoiceKey,
                onValueChange = { picovoiceKey = it },
                label = { Text("Picovoice Access Key") },
                placeholder = { Text("console.picovoice.ai で取得") },
                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPicovoiceKey = !showPicovoiceKey }) {
                        Icon(
                            if (showPicovoiceKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "表示切替"
                        )
                    }
                },
                visualTransformation = if (showPicovoiceKey) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("ホットワード検知に必要（無料枠あり）") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 起動時に開始
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "起動時にホットワード検知を開始")
                    Text(
                        text = "デバイス起動時に自動で常時聴取を開始",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = startOnBoot,
                    onCheckedChange = { startOnBoot = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ヒント
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "設定のヒント",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Webhook URLはOpenClawのエンドポイントを設定\n" +
                               "• セッションIDは会話を維持するために使用\n" +
                               "• Picovoice Access Keyはconsole.picovoice.aiで無料取得可能\n" +
                               "• デフォルトのウェイクワードは「Porcupine」",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
