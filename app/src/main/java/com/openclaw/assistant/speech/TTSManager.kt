package com.openclaw.assistant.speech

import android.content.Context
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.collect

private const val TAG = "TTSManager"

/**
 * Text-to-Speech (TTS) Manager that handles multiple providers and fallback logic.
 */
class TTSManager(private val context: Context) {

    private val settings = SettingsRepository.getInstance(context)
    private var systemProvider: SystemTTSProvider? = null
    private var externalProvider: ExternalVoiceProvider? = null

    private fun getProvider(): VoiceOutputProvider {
        return if (settings.voiceOutputMode == SettingsRepository.VOICE_MODE_EXTERNAL) {
            if (externalProvider == null) {
                externalProvider = ExternalVoiceProvider(context)
            }
            externalProvider!!
        } else {
            if (systemProvider == null) {
                systemProvider = SystemTTSProvider(context)
            }
            systemProvider!!
        }
    }

    /**
     * Speaks the given text using the configured provider, with fallback to System TTS.
     */
    suspend fun speak(text: String): Boolean {
        val provider = getProvider()
        Log.d(TAG, "Speaking with provider: ${provider.javaClass.simpleName}")

        // Try the selected provider
        val success = try {
            provider.speak(text)
        } catch (e: Exception) {
            Log.e(TAG, "Provider ${provider.javaClass.simpleName} failed with exception", e)
            false
        }

        if (success) return true

        // Fallback logic: If external provider is configured but fails, use system TTS
        if (settings.voiceOutputMode == SettingsRepository.VOICE_MODE_EXTERNAL) {
            Log.w(TAG, "External voice provider failed, falling back to System TTS")
            if (systemProvider == null) {
                systemProvider = SystemTTSProvider(context)
            }
            return try {
                systemProvider?.speak(text) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Fallback to System TTS failed", e)
                false
            }
        }

        return false
    }

    /**
     * Speaks the given text with progress updates, supporting fallback.
     */
    fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val provider = getProvider()
        var success = false

        try {
            provider.speakWithProgress(text).collect { state ->
                if (state is TTSState.Error) {
                    throw Exception(state.message)
                }
                if (state is TTSState.Done) {
                    success = true
                }
                trySend(state)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flow from ${provider.javaClass.simpleName} failed: ${e.message}")
        }

        if (!success && settings.voiceOutputMode == SettingsRepository.VOICE_MODE_EXTERNAL) {
            Log.w(TAG, "External provider failed in flow, falling back to System TTS")
            if (systemProvider == null) {
                systemProvider = SystemTTSProvider(context)
            }
            try {
                systemProvider!!.speakWithProgress(text).collect { state ->
                    trySend(state)
                }
            } catch (e: Exception) {
                trySend(TTSState.Error("Fallback failed: ${e.message}"))
            }
        } else if (!success) {
            trySend(TTSState.Error("Speech failed"))
        }

        close()
        awaitClose { stop() }
    }

    /**
     * Speaks the given text without waiting for completion (queued).
     */
    fun speakQueued(text: String) {
        val provider = getProvider()
        provider.speakQueued(text)
    }

    fun stop() {
        systemProvider?.stop()
        externalProvider?.stop()
    }

    fun shutdown() {
        systemProvider?.shutdown()
        externalProvider?.shutdown()
        systemProvider = null
        externalProvider = null
    }

    fun isReady(): Boolean = getProvider().isReady()
}
