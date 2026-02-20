package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "SystemTTSProvider"

/**
 * Android System Text-to-Speech Provider
 */
class SystemTTSProvider(private val context: Context) : VoiceOutputProvider {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private val settings = SettingsRepository.getInstance(context)

    init {
        initializeWithBruteForce()
    }

    private fun initializeWithBruteForce() {
        Log.e(TAG, "Force-starting TTS sequence...")

        val preferredEngine = settings.ttsEngine

        if (preferredEngine.isNotEmpty()) {
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
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.e(TAG, "Initialized with Google engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "Google TTS failed, falling back to system default")
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
        TTSUtils.setupVoice(tts, settings.ttsSpeed, settings.speechLanguage.ifEmpty { null })
        pendingSpeak?.invoke()
        pendingSpeak = null
    }

    override suspend fun speak(text: String): Boolean {
        val maxLen = TTSUtils.getMaxInputLength(tts)
        val chunks = TTSUtils.splitTextForTTS(text, maxLen)
        Log.d(TAG, "TTS splitting text (${text.length} chars) into ${chunks.size} chunks (maxLen=$maxLen)")

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
        val timeoutMs = (30_000L + (text.length * 15L)).coerceAtMost(120_000L)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                val started = java.util.concurrent.atomic.AtomicBoolean(false)

                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        started.set(true)
                    }
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
                        CoroutineScope(Dispatchers.Main).launch {
                            var waitedMs = 0L
                            while (!started.get() && continuation.isActive && waitedMs < 10_000L) {
                                delay(200)
                                waitedMs += 200
                            }
                            if (!started.get() || !continuation.isActive) return@launch
                            delay(1000)
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
                    val existingPending = pendingSpeak
                    pendingSpeak = {
                        existingPending?.invoke()
                        TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
                        tts?.setOnUtteranceProgressListener(listener)
                        val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        tts?.speak(text, queueMode, null, utteranceId)
                    }
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

    override fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { trySend(TTSState.Speaking) }
            override fun onDone(utteranceId: String?) { trySend(TTSState.Done); close() }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { close() }
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

    override fun speakQueued(text: String) {
        if (isInitialized) {
            TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.shutdown()
        isInitialized = false
    }

    override fun isReady(): Boolean = isInitialized
}
