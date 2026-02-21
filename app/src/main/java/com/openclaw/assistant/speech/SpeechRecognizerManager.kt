package com.openclaw.assistant.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 音声認識マネージャー
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * 音声認識を利用可能かチェック
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * 音声認識を開始し、結果をFlowで返す
     * language が null の場合はシステムデフォルトを使用する
     */
    fun startListening(language: String? = null, silenceTimeoutMs: Long = 2500L, completeTimeoutMs: Long = 2000L): Flow<SpeechResult> = callbackFlow {
        // デフォルト言語の決定
        val targetLanguage = language ?: Locale.getDefault().toLanguageTag()
        
        android.util.Log.e("SpeechRecognizerManager", "startListening called, language=$targetLanguage, isAvailable=${isAvailable()}")

        // Reuse existing recognizer or create new one if needed
        if (recognizer == null) {
            // Ensure creation on Main thread
            withContext(Dispatchers.Main) {
                if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
                    val appContext = context.applicationContext
                    val serviceComponent = findRecognitionService(appContext)
                    recognizer = if (serviceComponent != null) {
                        SpeechRecognizer.createSpeechRecognizer(appContext, serviceComponent)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(appContext)
                    }
                }
            }
        }

        if (recognizer == null) {
            trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_speech_client)))
            close()
            return@callbackFlow
        }
        val currentRecognizer = recognizer!!

        currentRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechResult.Ready)
            }

            override fun onBeginningOfSpeech() {
                trySend(SpeechResult.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechResult.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                trySend(SpeechResult.Processing)
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> context.getString(com.openclaw.assistant.R.string.error_speech_audio)
                    SpeechRecognizer.ERROR_CLIENT -> context.getString(com.openclaw.assistant.R.string.error_speech_client)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(com.openclaw.assistant.R.string.error_speech_permissions)
                    SpeechRecognizer.ERROR_NETWORK -> context.getString(com.openclaw.assistant.R.string.error_speech_network)
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(com.openclaw.assistant.R.string.error_speech_timeout)
                    SpeechRecognizer.ERROR_NO_MATCH -> context.getString(com.openclaw.assistant.R.string.error_speech_no_match)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(com.openclaw.assistant.R.string.error_speech_busy)
                    SpeechRecognizer.ERROR_SERVER -> context.getString(com.openclaw.assistant.R.string.error_speech_server)
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(com.openclaw.assistant.R.string.error_speech_input_timeout)
                    else -> context.getString(com.openclaw.assistant.R.string.error_speech_unknown, error)
                }
                
                trySend(SpeechResult.Error(errorMessage, error))
                
                // For critical errors, force recreation of the recognizer
                // Soft errors (No Match, Timeout) can reuse the instance for speed
                val isSoftError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                  error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                if (!isSoftError) {
                    destroy()
                }

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
                    trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_no_recognition_result), SpeechRecognizer.ERROR_NO_MATCH))
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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, completeTimeoutMs)
            
            // Try hidden/unofficial extra to enforce minimum length if supported
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", silenceTimeoutMs)
        }

        // Run on Main thread
        Dispatchers.Main.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
             try {
                 currentRecognizer.startListening(intent)
             } catch (e: Exception) {
                 trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_start_failed, e.message)))
                 close()
             }
        })

        awaitClose {
            // Use GlobalScope to ensure cleanup runs even if flow scope is cancelled
            // But we must be careful not to leak. Dispatchers.Main is fine.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.Main) {
                try {
                    // Cancel only - do NOT destroy to allow reuse
                    currentRecognizer.cancel()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Stop listening manually
     */
    fun stopListening() { 
        // No-op, flow cancellation triggers cleanup
    }

    /**
     * Completely destroy the recognizer resources
     */
    fun destroy() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        recognizer = null
    }

    /**
     * Find a real speech recognition service, skipping our own stub service.
     * This app registers a no-op RecognitionService (required for VoiceInteractionService),
     * which some devices may select as the default, breaking SpeechRecognizer.
     */
    private fun findRecognitionService(context: Context): ComponentName? {
        val pm = context.packageManager
        val services = pm.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA
        )

        val ownPackage = context.packageName

        // Prefer Google's service
        val google = services.firstOrNull {
            it.serviceInfo.packageName == "com.google.android.googlequicksearchbox"
        }
        if (google != null) {
            return ComponentName(google.serviceInfo.packageName, google.serviceInfo.name)
        }

        // Use any other service that is NOT our own stub
        val other = services.firstOrNull { it.serviceInfo.packageName != ownPackage }
        if (other != null) {
            return ComponentName(other.serviceInfo.packageName, other.serviceInfo.name)
        }

        // No external service found; fall back to default
        return null
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
    data class Error(val message: String, val code: Int? = null) : SpeechResult()
}
