package com.openclaw.assistant.wear.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Session factory — creates WearSession instances for each voice interaction.
 */
class WearSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return WearSession(this)
    }
}
