package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.embedded.EmbeddedTTSManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "TTSManager"

/**
 * Text-to-Speech (TTS) Manager with brute-force recovery and Embedded engine support
 */
class TTSManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private val settings = SettingsRepository.getInstance(context)
    private val embeddedTts = EmbeddedTTSManager(context)

    init {
        initializeWithBruteForce()
    }

    private fun initializeWithBruteForce() {
        Log.e(TAG, "Force-starting TTS sequence...")
        
        // 1. Try Google TTS explicitly
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.e(TAG, "Initialized with Google engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "Google TTS failed, falling back to system default")
                // 2. Try System Default
                tts = TextToSpeech(context.applicationContext) { status2 ->
                    if (status2 == TextToSpeech.SUCCESS) {
                        Log.e(TAG, "Initialized with System Default engine")
                        onInitSuccess()
                    } else {
                        Log.e(TAG, "FATAL: All TTS initialization attempts failed")
                    }
                }
            }
        }, TTSUtils.GOOGLE_TTS_PACKAGE)
    }

    private fun onInitSuccess() {
        isInitialized = true
        TTSUtils.setupVoice(tts)
        pendingSpeak?.invoke()
        pendingSpeak = null
    }

    /**
     * Attempts to "wake up" the engine if it was hidden or lost
     */
    fun reinitialize() {
        isInitialized = false
        tts?.shutdown()
        initializeWithBruteForce()
    }

    suspend fun speak(text: String): Boolean {
        if (settings.ttsEngine == SettingsRepository.ENGINE_EMBEDDED) {
            embeddedTts.speak(text, Locale.getDefault())
            return true
        }

        return suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()

            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (continuation.isActive) continuation.resume(true) }
                override fun onError(utteranceId: String?) { if (continuation.isActive) continuation.resume(false) }
            }

            if (isInitialized) {
                TTSUtils.applyLanguageForText(tts, text)
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                pendingSpeak = {
                    TTSUtils.applyLanguageForText(tts, text)
                    tts?.setOnUtteranceProgressListener(listener)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
                // Wait up to 5s
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (continuation.isActive && !isInitialized) {
                        continuation.resume(false)
                    }
                }, 5000)
            }
        }
    }

    fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        if (settings.ttsEngine == SettingsRepository.ENGINE_EMBEDDED) {
            trySend(TTSState.Speaking)
            embeddedTts.speak(text, Locale.getDefault())
            trySend(TTSState.Done)
            close()
            return@callbackFlow
        }

        val utteranceId = UUID.randomUUID().toString()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { trySend(TTSState.Speaking) }
            override fun onDone(utteranceId: String?) { trySend(TTSState.Done); close() }
            override fun onError(utteranceId: String?) { trySend(TTSState.Error("Error")); close() }
        }

        if (isInitialized) {
            TTSUtils.applyLanguageForText(tts, text)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            trySend(TTSState.Preparing)
        } else {
            trySend(TTSState.Preparing)
            pendingSpeak = {
                TTSUtils.applyLanguageForText(tts, text)
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
        awaitClose { stop() }
    }

    fun speakQueued(text: String) {
        if (settings.ttsEngine == SettingsRepository.ENGINE_EMBEDDED) {
            embeddedTts.speak(text, Locale.getDefault())
            return
        }

        if (isInitialized) {
            TTSUtils.applyLanguageForText(tts, text)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    fun stop() { 
        tts?.stop() 
        embeddedTts.stop()
    }
    
    fun shutdown() { 
        tts?.shutdown()
        isInitialized = false 
    }
    
    fun isReady(): Boolean = if (settings.ttsEngine == SettingsRepository.ENGINE_EMBEDDED) true else isInitialized
}

sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}
