package com.openclaw.assistant.wear.service

import android.content.Intent
import android.speech.RecognitionService

/**
 * Stub RecognitionService required by the VoiceInteractionService framework.
 * Actual speech recognition is handled in WearSession via SpeechRecognizerManager.
 */
class WearRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
