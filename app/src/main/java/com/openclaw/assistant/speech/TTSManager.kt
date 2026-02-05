package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "TTSManager"

/**
 * テキスト読み上げ（TTS）マネージャー
 */
class TTSManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null

    init {
        initializeWithPriority(context.applicationContext)
    }

    private fun initializeWithPriority(context: Context) {
        Log.e(TAG, "Initializing TTS with Google engine priority...")
        // Try Google TTS explicitly for better compatibility on MIUI/Chinese ROMs
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS initialized successfully with Google engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "Google TTS initialization FAILED (status=$status), retrying with system default...")
                retryWithDefault(context)
            }
        }, TTSUtils.GOOGLE_TTS_PACKAGE)
    }

    private fun retryWithDefault(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS initialized successfully with system default engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "All TTS initialization attempts FAILED (status=$status)")
            }
        }
    }

    private fun onInitSuccess() {
        isInitialized = true
        TTSUtils.setupVoice(tts)
        pendingSpeak?.invoke()
        pendingSpeak = null
    }

    /**
     * テキストを読み上げ（suspend版）
     */
    suspend fun speak(text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }

        if (isInitialized) {
            TTSUtils.applyLanguageForText(tts, text)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            // 初期化待ち
            pendingSpeak = {
                TTSUtils.applyLanguageForText(tts, text)
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (continuation.isActive && !isInitialized) {
                    pendingSpeak = null
                    continuation.resume(false)
                }
            }, 3000)
        }

        continuation.invokeOnCancellation {
            stop()
        }
    }

    /**
     * テキストを読み上げ（Flow版 - 進捗通知あり）
     */
    fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                trySend(TTSState.Speaking)
            }

            override fun onDone(utteranceId: String?) {
                trySend(TTSState.Done)
                close()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                trySend(TTSState.Error("読み上げエラー"))
                close()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                trySend(TTSState.Error("読み上げエラー: $errorCode"))
                close()
            }
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

        awaitClose {
            stop()
        }
    }

    /**
     * キューに追加して読み上げ
     */
    fun speakQueued(text: String) {
        if (isInitialized) {
            TTSUtils.applyLanguageForText(tts, text)
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    /**
     * 読み上げを停止
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * リソースを解放
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * 初期化済みかチェック
     */
    fun isReady(): Boolean = isInitialized
}

/**
 * TTSの状態
 */
sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}
