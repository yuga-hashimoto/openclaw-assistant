package com.openclaw.assistant.service

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

/**
 * Minimal RecognitionService required for VoiceInteractionService to function correctly.
 */
class OpenClawAssistantRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "OpenClawAssistantRec"
    }

    override fun onStartListening(intent: Intent?, listener: RecognitionService.Callback?) {
        Log.d(TAG, "onStartListening")
        // No-op: Actual recognition is handled in OpenClawSession
    }

    override fun onCancel(listener: RecognitionService.Callback?) {
        Log.d(TAG, "onCancel")
    }

    override fun onStopListening(listener: RecognitionService.Callback?) {
        Log.d(TAG, "onStopListening")
    }
}
