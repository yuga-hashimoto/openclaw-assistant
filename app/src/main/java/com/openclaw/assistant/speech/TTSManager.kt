package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * テキスト読み上げ（TTS）マネージャー
 */
class TTSManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                // 日本語を優先、なければデフォルト
                val result = tts?.setLanguage(Locale.JAPANESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                // 読み上げ速度調整
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                
                // 初期化待ちの発話があれば実行
                pendingSpeak?.invoke()
                pendingSpeak = null
            }
        }
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
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            // 初期化待ち
            pendingSpeak = {
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
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
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            trySend(TTSState.Preparing)
        } else {
            trySend(TTSState.Preparing)
            pendingSpeak = {
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
