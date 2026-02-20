package com.openclaw.assistant.wear

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import com.openclaw.assistant.data.SettingsRepository

/**
 * Minimal launcher activity for Wear OS.
 * Shows configuration status and a button to set OpenClaw as the default assistant.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.getInstance(this)

        setContent {
            WearMainScreen(
                isConfigured = settings.isConfigured(),
                onSetDefault = {
                    // Open system default assistant picker
                    try {
                        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent("com.google.android.clockwork.home.assistant.ASSISTANT_SETTINGS"))
                        } catch (_: Exception) {
                            // Fallback: open general settings
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun WearMainScreen(
    isConfigured: Boolean,
    onSetDefault: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "OpenClaw",
            color = Color.White,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isConfigured) "Ready to use" else "Configure on phone",
            color = if (isConfigured) Color(0xFF4CAF50) else Color(0xFFFFC107),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onSetDefault) {
            Text(
                text = "Set as Default",
                fontSize = 12.sp
            )
        }
    }
}
