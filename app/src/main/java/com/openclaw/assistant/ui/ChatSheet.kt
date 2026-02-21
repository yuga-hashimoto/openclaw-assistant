package com.openclaw.assistant.ui

import androidx.compose.runtime.Composable
import com.openclaw.assistant.MainViewModel
import com.openclaw.assistant.ui.chat.ChatSheetContent

@Composable
fun ChatSheet(viewModel: MainViewModel) {
  ChatSheetContent(viewModel = viewModel)
}
