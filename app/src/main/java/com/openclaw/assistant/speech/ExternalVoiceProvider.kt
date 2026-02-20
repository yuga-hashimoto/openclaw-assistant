package com.openclaw.assistant.speech

import android.content.Context
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose

private const val TAG = "ExternalVoiceProvider"

/**
 * Placeholder for External Voice Provider (e.g. ElevenLabs, OpenAI, etc.)
 */
class ExternalVoiceProvider(private val context: Context) : VoiceOutputProvider {

    private val settings = SettingsRepository.getInstance(context)

    override suspend fun speak(text: String): Boolean {
        Log.d(TAG, "ExternalVoiceProvider: Speaking text: $text")

        val apiKey = settings.externalVoiceApiKey
        val voiceId = settings.externalVoiceId

        if (apiKey.isEmpty()) {
            Log.e(TAG, "External voice API key is missing")
            return false
        }

        Log.d(TAG, "External provider configured with Voice ID: $voiceId")

        // Simulating network call
        delay(1500)

        // Currently this is a skeleton, so we return false to demonstrate fallback behavior
        Log.w(TAG, "External provider placeholder: failing intentionally to test fallback.")
        return false
    }

    override fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        trySend(TTSState.Preparing)
        val success = speak(text)
        if (success) {
            trySend(TTSState.Done)
        } else {
            trySend(TTSState.Error("External provider failed"))
        }
        close()
        awaitClose { stop() }
    }

    override fun speakQueued(text: String) {
        Log.d(TAG, "ExternalVoiceProvider: speakQueued (not implemented for placeholder)")
    }

    override fun stop() {
        Log.d(TAG, "ExternalVoiceProvider: stop")
    }

    override fun shutdown() {
        Log.d(TAG, "ExternalVoiceProvider: shutdown")
    }

    override fun isReady(): Boolean = settings.externalVoiceApiKey.isNotEmpty()
}
