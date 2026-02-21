package com.openclaw.assistant.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.openclaw.assistant.node.NodeRuntime

@Composable
fun GatewayTrustDialog(
  prompt: NodeRuntime.GatewayTrustPrompt,
  onAccept: () -> Unit,
  onDecline: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDecline,
    title = { Text("Trust this gateway?") },
    text = {
      Text(
        "First-time TLS connection.\n\n" +
          "Verify this SHA-256 fingerprint out-of-band before trusting:\n" +
          prompt.fingerprintSha256,
      )
    },
    confirmButton = {
      TextButton(onClick = onAccept) {
        Text("Trust and connect")
      }
    },
    dismissButton = {
      TextButton(onClick = onDecline) {
        Text("Cancel")
      }
    },
  )
}
