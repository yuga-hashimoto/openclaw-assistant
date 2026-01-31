package com.openclaw.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * 音声認識マネージャー
 */
class SpeechRecognizerManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * 音声認識が利用可能かチェック
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * 音声認識を開始し、結果をFlowで返す
     */
    fun startListening(language: String = "ja-JP"): Flow<SpeechResult> = callbackFlow {
        if (!isAvailable()) {
            trySend(SpeechResult.Error("音声認識が利用できません"))
            close()
            return@callbackFlow
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    trySend(SpeechResult.Ready)
                }

                override fun onBeginningOfSpeech() {
                    trySend(SpeechResult.Listening)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // 音量レベルの変化（UI表示用）
                    trySend(SpeechResult.RmsChanged(rmsdB))
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    trySend(SpeechResult.Processing)
                }

                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "オーディオエラー"
                        SpeechRecognizer.ERROR_CLIENT -> "クライアントエラー"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "権限が不足しています"
                        SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
                        SpeechRecognizer.ERROR_NO_MATCH -> "認識できませんでした"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識サービスがビジー"
                        SpeechRecognizer.ERROR_SERVER -> "サーバーエラー"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声入力がありませんでした"
                        else -> "不明なエラー ($error)"
                    }
                    trySend(SpeechResult.Error(errorMessage))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    
                    if (!matches.isNullOrEmpty()) {
                        trySend(SpeechResult.Result(
                            text = matches[0],
                            confidence = confidence?.getOrNull(0) ?: 0f,
                            alternatives = matches.drop(1)
                        ))
                    } else {
                        trySend(SpeechResult.Error("認識結果がありません"))
                    }
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        trySend(SpeechResult.PartialResult(matches[0]))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        speechRecognizer?.startListening(intent)

        awaitClose {
            stopListening()
        }
    }

    /**
     * 音声認識を停止
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * リソースを解放
     */
    fun destroy() {
        stopListening()
    }
}

/**
 * 音声認識の結果
 */
sealed class SpeechResult {
    object Ready : SpeechResult()
    object Listening : SpeechResult()
    object Processing : SpeechResult()
    data class RmsChanged(val rmsdB: Float) : SpeechResult()
    data class PartialResult(val text: String) : SpeechResult()
    data class Result(
        val text: String,
        val confidence: Float,
        val alternatives: List<String>
    ) : SpeechResult()
    data class Error(val message: String) : SpeechResult()
}
