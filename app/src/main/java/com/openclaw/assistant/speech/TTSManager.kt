package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "TTSManager"

/**
 * Text-to-Speech (TTS) Manager with brute-force recovery for MIUI
 */
class TTSManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private val settings = com.openclaw.assistant.data.SettingsRepository.getInstance(context)

    init {
        initializeWithBruteForce()
    }

    private fun initializeWithBruteForce() {
        Log.e(TAG, "Force-starting TTS sequence...")
        
        val preferredEngine = settings.ttsEngine
        
        if (preferredEngine.isNotEmpty()) {
             // 0. Try Preferred Engine
            Log.e(TAG, "Attempting to initialize with preferred engine: $preferredEngine")
            tts = TextToSpeech(context.applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.e(TAG, "Initialized with preferred engine: $preferredEngine")
                    onInitSuccess()
                } else {
                    Log.e(TAG, "Preferred engine failed, falling back to Google/Default")
                     tryGoogleOrFallback()
                }
            }, preferredEngine)
        } else {
            tryGoogleOrFallback()
        }
    }
    
    private fun tryGoogleOrFallback() {
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
        TTSUtils.setupVoice(tts, settings.ttsSpeed)
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
        // Split long text into chunks to avoid Android TTS 4000 char limit
        val chunks = TTSUtils.splitTextForTTS(text)
        Log.d(TAG, "TTS splitting text (${text.length} chars) into ${chunks.size} chunks")

        for ((index, chunk) in chunks.withIndex()) {
            val success = speakSingleChunk(chunk, index == 0)
            if (!success) {
                Log.e(TAG, "TTS chunk $index failed, aborting remaining chunks")
                return false
            }
        }
        return true
    }

    private suspend fun speakSingleChunk(text: String, isFirst: Boolean): Boolean {
        // Scale timeout based on text length (minimum 30s, ~15s per 1000 chars)
        val timeoutMs = (30_000L + (text.length * 15L)).coerceAtMost(120_000L)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()

                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (continuation.isActive) continuation.resume(true) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { if (continuation.isActive) continuation.resume(false) }
                    override fun onError(utteranceId: String?) { if (continuation.isActive) continuation.resume(false) }
                }

                if (isInitialized) {
                    TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
                    tts?.setOnUtteranceProgressListener(listener)
                    val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val speakResult = tts?.speak(text, queueMode, null, utteranceId)
                    if (speakResult != TextToSpeech.SUCCESS) {
                        Log.e(TAG, "TTS speak failed immediately: $speakResult")
                        if (continuation.isActive) continuation.resume(false)
                    } else {
                        // Polling fallback: check tts.isSpeaking() in case callback never fires
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(2000)
                            while (continuation.isActive) {
                                val speaking = tts?.isSpeaking ?: false
                                if (!speaking) {
                                    Log.w(TAG, "TTS poll detected speech finished (callback missed)")
                                    if (continuation.isActive) continuation.resume(true)
                                    break
                                }
                                delay(500)
                            }
                        }
                    }

                    continuation.invokeOnCancellation { tts?.stop() }
                } else {
                    if (isFirst) {
                        pendingSpeak = {
                            TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
                            tts?.setOnUtteranceProgressListener(listener)
                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                        }
                    }
                    // Wait up to 5s for init
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (continuation.isActive && !isInitialized) {
                            continuation.resume(false)
                        }
                    }, 5000)
                }
            }
        }

        if (result == null) {
            Log.w(TAG, "TTS chunk timed out, forcing stop")
            tts?.stop()
            return false
        }
        return result
    }

    fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { trySend(TTSState.Speaking) }
            override fun onDone(utteranceId: String?) { trySend(TTSState.Done); close() }
            override fun onError(utteranceId: String?) { trySend(TTSState.Error("Error")); close() }
        }

        if (isInitialized) {
            TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            trySend(TTSState.Preparing)
        } else {
            trySend(TTSState.Preparing)
            pendingSpeak = {
                TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
        awaitClose { stop() }
    }

    fun speakQueued(text: String) {
        if (isInitialized) {
            TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    fun stop() { tts?.stop() }
    fun shutdown() { tts?.shutdown(); isInitialized = false }
    fun isReady(): Boolean = isInitialized
}

sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}
