package com.openclaw.assistant.speech.diagnostics

import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.utils.SystemInfoProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Data structure for voice diagnostic results
 */
data class VoiceDiagnostic(
    val sttStatus: DiagnosticStatus,
    val ttsStatus: DiagnosticStatus,
    val sttEngine: String? = null,
    val ttsEngine: String? = null,
    val missingLanguages: List<String> = emptyList(),
    val suggestions: List<DiagnosticSuggestion> = emptyList()
)

enum class DiagnosticStatus {
    READY,      // Ready
    WARNING,    // Warning (configuration required)
    ERROR       // Error (inoperable)
}

data class DiagnosticSuggestion(
    val message: String,
    val actionLabel: String? = null,
    val intent: Intent? = null,
    val isSystemSetting: Boolean = false
)

enum class ReliabilityStep {
    TTS_INIT,
    TTS_SPEAK,
    STT_START,
    STT_COMPLETE
}

/**
 * Class to perform diagnostic of voice features
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
            sttEngine = sttResult.engine,
            ttsEngine = ttsResult.engine,
            missingLanguages = ttsResult.missingLangs,
            suggestions = suggestions
        )
    }

    suspend fun runReliabilityCheck(
        tts: TTSManager,
        speech: SpeechRecognizerManager,
        onProgress: (ReliabilityStep, String) -> Unit
    ): Boolean {
        // 1. TTS Init
        onProgress(ReliabilityStep.TTS_INIT, "Checking TTS initialization...")
        if (!tts.isReady()) {
            onProgress(ReliabilityStep.TTS_INIT, "TTS not ready. Attempting re-init...")
            tts.reinitialize()
            delay(2000)
            if (!tts.isReady()) {
                onProgress(ReliabilityStep.TTS_INIT, "TTS failed to initialize.")
                return false
            }
        }
        onProgress(ReliabilityStep.TTS_INIT, "TTS Ready.")

        // 2. TTS Speak
        onProgress(ReliabilityStep.TTS_SPEAK, "Testing voice output...")
        val speakSuccess = tts.speak("Voice reliability test. If you hear this, TTS is working correctly.")
        if (!speakSuccess) {
            onProgress(ReliabilityStep.TTS_SPEAK, "TTS playback failed or timed out.")
            return false
        }
        onProgress(ReliabilityStep.TTS_SPEAK, "TTS playback complete.")

        // 3. STT Start
        onProgress(ReliabilityStep.STT_START, "Testing speech recognition...")
        var sttSuccess = false
        try {
            withTimeoutOrNull(8000) {
                speech.startListening().collect { result ->
                    when (result) {
                        is SpeechResult.Ready -> {
                            onProgress(ReliabilityStep.STT_START, "STT Ready. Listener is active.")
                            sttSuccess = true
                            // Stop after reaching Ready for this basic check
                            throw kotlinx.coroutines.CancellationException("STT_READY")
                        }
                        is SpeechResult.Error -> {
                            onProgress(ReliabilityStep.STT_START, "STT Error: ${result.message}")
                            sttSuccess = false
                            throw kotlinx.coroutines.CancellationException("STT_ERROR")
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e.message != "STT_READY" && e.message != "STT_ERROR") throw e
        }

        if (!sttSuccess) {
            onProgress(ReliabilityStep.STT_START, "STT failed to initialize or reach READY state.")
            return false
        }

        // 4. Cleanup
        onProgress(ReliabilityStep.STT_COMPLETE, "Verifying state cleanup...")
        speech.destroy()
        delay(500)
        onProgress(ReliabilityStep.STT_COMPLETE, "Reliability check finished successfully.")

        return true
    }

    fun generateDiagnosticReport(settings: SettingsRepository): String {
        val sysInfo = SystemInfoProvider.getSystemInfoReport(context, settings)
        val sttManager = SpeechRecognizerManager(context)
        val sttProvider = sttManager.getRecognitionServiceName()

        return """
            # Voice Reliability Diagnostic Report

            ${sysInfo}

            **Voice Subsystem Details**
            - STT Provider: $sttProvider
            - STT Available: ${SpeechRecognizer.isRecognitionAvailable(context)}
            - TTS Engine Setting: ${settings.ttsEngine.ifEmpty { "Auto" }}
            - Speech Language: ${settings.speechLanguage.ifEmpty { "System Default" }}
            - Continuous Mode: ${settings.continuousMode}
            - Silence Timeout: ${settings.speechSilenceTimeout}ms
            - Thinking Sound: ${settings.thinkingSoundEnabled}

            **Diagnostic Log**
            - Device Time: ${java.util.Date()}
            - Report Version: 1.0
        """.trimIndent()
    }

    private data class ComponentCheckResult(
        val status: DiagnosticStatus,
        val engine: String? = null,
        val suggestions: List<DiagnosticSuggestion> = emptyList(),
        val missingLangs: List<String> = emptyList()
    )

    private fun checkSTT(): ComponentCheckResult {
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!isAvailable) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                suggestions = listOf(
                    DiagnosticSuggestion(
                        "Speech recognition service not found. Make sure Google app is installed.",
                        "Open Store",
                        null
                    )
                )
            )
        }
        return ComponentCheckResult(status = DiagnosticStatus.READY, engine = "System Default")
    }

    private fun checkTTS(tts: TextToSpeech?): ComponentCheckResult {
        val engine = tts?.defaultEngine
        val currentLocale = Locale.getDefault()
        var status = DiagnosticStatus.READY
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        
        if (tts == null || engine == null) {
            // Check if Google TTS is actually installed via Package Manager
            val isGoogleInstalled = try {
                context.packageManager.getPackageInfo("com.google.android.tts", 0)
                true
            } catch (e: Exception) {
                false
            }

            val msg = if (isGoogleInstalled) {
                "Google TTS is installed but HIDDEN by the system. This is a common MIUI issue."
            } else {
                "TTS engine could not be initialized. Engine is null."
            }

            suggestions.add(
                DiagnosticSuggestion(
                    msg,
                    "Fix in Settings",
                    Intent("com.android.settings.TTS_SETTINGS"),
                    true
                )
            )
            
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                engine = "null/hidden",
                suggestions = suggestions
            )
        }

        val langResult = tts.isLanguageAvailable(currentLocale)
        if (langResult < TextToSpeech.LANG_AVAILABLE) {
            status = DiagnosticStatus.WARNING
            suggestions.add(
                DiagnosticSuggestion(
                    "Voice data for ${currentLocale.displayName} is missing in $engine.",
                    "Manage Data",
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        if (engine != "com.google.android.tts") {
            suggestions.add(
                DiagnosticSuggestion(
                    "Currently using $engine. Switch to Google for better support.",
                    "Select Google",
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        return ComponentCheckResult(
            status = status,
            engine = engine,
            suggestions = suggestions
        )
    }
}
