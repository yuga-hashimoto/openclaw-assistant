package com.openclaw.assistant.speech.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 音声診断結果のデータ構造
 */
data class VoiceDiagnostic(
    val sttStatus: DiagnosticStatus,
    val ttsStatus: DiagnosticStatus,
    val sttReason: String? = null,
    val ttsReason: String? = null,
    val sttEngine: String? = null,
    val ttsEngine: String? = null,
    val missingLanguages: List<String> = emptyList(),
    val suggestions: List<DiagnosticSuggestion> = emptyList()
)

enum class DiagnosticStatus {
    READY,      // 準備完了
    WARNING,    // 注意（設定が必要）
    ERROR       // エラー（動作不能）
}

data class DiagnosticSuggestion(
    val message: String,
    val actionLabel: String? = null,
    val intent: Intent? = null,
    val isPermissionRequest: Boolean = false
)

/**
 * 音声機能の診断を行うクラス
 */
class VoiceDiagnostics(private val context: Context) {

    fun performFullCheck(tts: TextToSpeech?): VoiceDiagnostic {
        val sttResult = checkSTT()
        val ttsResult = checkTTS(tts)
        
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        suggestions.addAll(sttResult.suggestions)
        suggestions.addAll(ttsResult.suggestions)

        return VoiceDiagnostic(
            sttStatus = sttResult.status,
            ttsStatus = ttsResult.status,
            sttReason = sttResult.reason,
            ttsReason = ttsResult.reason,
            sttEngine = sttResult.engine,
            ttsEngine = ttsResult.engine,
            missingLanguages = ttsResult.missingLangs,
            suggestions = suggestions
        )
    }

    private data class ComponentCheckResult(
        val status: DiagnosticStatus,
        val reason: String? = null,
        val engine: String? = null,
        val suggestions: List<DiagnosticSuggestion> = emptyList(),
        val missingLangs: List<String> = emptyList()
    )

    private fun checkSTT(): ComponentCheckResult {
        // 1. マイク権限チェック
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                reason = "Microphone permission denied",
                suggestions = listOf(
                    DiagnosticSuggestion(
                        message = "Microphone permission is required for voice input. Please grant permission in app settings.",
                        actionLabel = "Open Settings",
                        intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                )
            )
        }

        // 2. 音声認識サービスチェック
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!isAvailable) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                reason = "No speech recognition service",
                suggestions = listOf(
                    DiagnosticSuggestion(
                        message = "Speech recognition service not found. Please install Google app or enable Google voice services.",
                        actionLabel = "Open Play Store",
                        intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("market://details?id=com.google.android.googlequicksearchbox")
                            setPackage("com.android.vending")
                        }
                    )
                )
            )
        }

        return ComponentCheckResult(
            status = DiagnosticStatus.READY,
            reason = null,
            engine = "System Default"
        )
    }

    private fun checkTTS(tts: TextToSpeech?): ComponentCheckResult {
        if (tts == null) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                reason = "TTS engine not initialized",
                suggestions = listOf(
                    DiagnosticSuggestion(
                        message = "Text-to-speech engine failed to initialize. Please check TTS settings.",
                        actionLabel = "Open TTS Settings",
                        intent = Intent("com.android.settings.TTS_SETTINGS")
                    )
                )
            )
        }

        val engine = tts.defaultEngine ?: "Unknown"
        val currentLocale = Locale.getDefault()
        val langResult = tts.isLanguageAvailable(currentLocale)
        
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        val missingLangs = mutableListOf<String>()

        // 言語サポートチェック
        if (langResult < TextToSpeech.LANG_AVAILABLE) {
            missingLangs.add(currentLocale.displayName)
            
            val reason = "Voice data for ${currentLocale.displayName} not installed"
            
            suggestions.add(
                DiagnosticSuggestion(
                    message = "Voice data for ${currentLocale.displayName} is not available. Download it in TTS settings.",
                    actionLabel = "Download Voice",
                    intent = Intent("com.android.settings.TTS_SETTINGS")
                )
            )

            // Google TTS以外を使っている場合の追加提案
            if (engine != "com.google.android.tts") {
                suggestions.add(
                    DiagnosticSuggestion(
                        message = "Consider using Google Text-to-Speech for better language support.",
                        actionLabel = "Get Google TTS",
                        intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("market://details?id=com.google.android.tts")
                            setPackage("com.android.vending")
                        }
                    )
                )
            }

            return ComponentCheckResult(
                status = DiagnosticStatus.WARNING,
                reason = reason,
                engine = engine,
                suggestions = suggestions,
                missingLangs = missingLangs
            )
        }

        return ComponentCheckResult(
            status = DiagnosticStatus.READY,
            reason = null,
            engine = getEngineDisplayName(engine)
        )
    }

    private fun getEngineDisplayName(enginePackage: String): String {
        return when (enginePackage) {
            "com.google.android.tts" -> "Google TTS"
            "com.samsung.SMT" -> "Samsung TTS"
            else -> enginePackage.substringAfterLast(".")
        }
    }
}
