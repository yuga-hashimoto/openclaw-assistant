package com.openclaw.assistant.wear.service

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Wear OS Voice Interaction Service entry point.
 * Activated when the user long-presses the hardware button (or selects OpenClaw as default assistant).
 */
class WearVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "WearVISvc"
    }

    private var isServiceReady = false

    override fun onReady() {
        super.onReady()
        isServiceReady = true
        Log.d(TAG, "VoiceInteractionService ready")
    }

    override fun onShutdown() {
        super.onShutdown()
        isServiceReady = false
        Log.d(TAG, "VoiceInteractionService shutdown")
    }
}
