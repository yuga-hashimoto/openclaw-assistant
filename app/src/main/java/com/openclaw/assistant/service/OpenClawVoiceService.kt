package com.openclaw.assistant.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Voice Interaction Service
 * ホームボタン長押しでシステムアシスタントとして起動
 */
class OpenClawVoiceService : VoiceInteractionService() {

    companion object {
        private const val TAG = "OpenClawVoiceService"
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService ready")
    }
}

/**
 * Voice Interaction Session Service
 */
class OpenClawSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OpenClawSession(this)
    }
}
