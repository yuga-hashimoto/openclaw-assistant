package com.openclaw.assistant.speech

import kotlinx.coroutines.flow.Flow

/**
 * Interface for voice output providers (System TTS, External API, etc.)
 */
interface VoiceOutputProvider {
    /**
     * Speaks the given text. Returns true if successful, false otherwise.
     */
    suspend fun speak(text: String): Boolean

    /**
     * Speaks the given text with progress updates.
     */
    fun speakWithProgress(text: String): Flow<TTSState>

    /**
     * Speaks the given text without waiting for completion (queued).
     */
    fun speakQueued(text: String)

    /**
     * Stops current playback.
     */
    fun stop()

    /**
     * Releases resources.
     */
    fun shutdown()

    /**
     * Checks if the provider is initialized and ready.
     */
    fun isReady(): Boolean
}

sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}
