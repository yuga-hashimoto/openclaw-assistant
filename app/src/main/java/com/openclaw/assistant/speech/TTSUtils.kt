package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Common utilities for TTS
 */
object TTSUtils {
    private const val TAG = "TTSUtils"
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    /**
     * Setup locale and high-quality voice
     */
    fun setupVoice(tts: TextToSpeech?, speed: Float, languageTag: String? = null) {
        val currentLocale = if (!languageTag.isNullOrEmpty()) {
            Locale.forLanguageTag(languageTag)
        } else {
            Locale.getDefault()
        }
        Log.e(TAG, "Current system locale: $currentLocale")

        // Log engine information
        try {
            val engine = tts?.defaultEngine
            Log.e(TAG, "Using TTS Engine: $engine")
        } catch (e: Exception) {
            Log.e(TAG, "Could not get default engine: ${e.message}")
        }

        // Try to set system locale
        val result = tts?.setLanguage(currentLocale)
        Log.e(TAG, "setLanguage($currentLocale) result=$result")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to English (US) if default fails
            val fallbackResult = tts?.setLanguage(Locale.US)
            Log.e(TAG, "Fallback setLanguage(Locale.US) result=$fallbackResult")
        }

        // Select high-quality voice (prioritizing non-network ones)
        try {
            val targetLang = tts?.language?.language
            val voices = tts?.voices
            Log.e(TAG, "Available voices count: ${voices?.size ?: 0}")
            
            val bestVoice = voices?.filter { it.locale.language == targetLang }
                ?.firstOrNull { !it.isNetworkConnectionRequired }
                ?: voices?.firstOrNull { it.locale.language == targetLang }

            if (bestVoice != null) {
                tts?.voice = bestVoice
                Log.e(TAG, "Selected voice: ${bestVoice.name} (${bestVoice.locale})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting voice: ${e.message}")
        }

        applyUserConfig(tts, speed)
    }

    /**
     * Apply user-configured speed
     */
    fun applyUserConfig(tts: TextToSpeech?, speed: Float) {
        if (tts == null) return
        tts.setSpeechRate(speed)
        tts.setPitch(1.0f)
    }

    /**
     * Strip Markdown formatting and convert to plain text for TTS
     */
    fun stripMarkdownForSpeech(text: String): String {
        var result = text
        // Code block (```...```) -> keep only the content
        // Adjustments like removing backticks and the first line (language name) for cases with language specification (```kotlin ...) are needed, but
        // simplify by just removing backticks and reading the content. Or should it say "code block"?
        // According to user request "read everything except symbols", keep the content.
        result = result.replace(Regex("```.*\\n?"), "") // Remove starting ```language
        result = result.replace(Regex("```"), "")       // Remove ending ```

        // Remove headers (# ## ### etc.)
        result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        
        // Bold/Italic (**text**, *text*, __text__, _text_)
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        result = result.replace(Regex("\\*([^*]+)\\*"), "$1")
        result = result.replace(Regex("__([^_]+)__"), "$1")
        result = result.replace(Regex("_([^_]+)_"), "$1")
        
        // Inline code (code)
        result = result.replace(Regex("`([^`]+)`"), "$1")
        
        // Link [text](url) → text
        result = result.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        
        // Image ![alt](url) → alt
        result = result.replace(Regex("!\\[([^\\]]*)]\\([^)]+\\)"), "$1")
        
        // Horizontal rule (---, ***) -> Remove
        result = result.replace(Regex("^[-*_]{3,}$", RegexOption.MULTILINE), "")
        
        // Blockquote (>)
        result = result.replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
        
        // Bullet point markers (-, *, +)
        result = result.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        
        // Numbered list markers (1., 2., etc.) - these might be okay to read, but keep only the numbers
        // result = result.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        
        // Organize consecutive newlines
        result = result.replace(Regex("\n{3,}"), "\n\n")
        
        return result.trim()
    }

    /**
     * Query the engine's actual max input length, with a safe fallback.
     */
    fun getMaxInputLength(tts: TextToSpeech?): Int {
        return try {
            val limit = TextToSpeech.getMaxSpeechInputLength()
            // Use 90% of the reported limit as safety margin
            (limit * 9 / 10).coerceIn(500, limit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query maxSpeechInputLength, using default 3900")
            3900
        }
    }

    /**
     * Splits long text into chunks that fit within the TTS max input length.
     * Splits naturally at sentence boundaries (period, newline, etc.), keeping each chunk under maxLength.
     */
    fun splitTextForTTS(text: String, maxLength: Int = 1000): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }

            // Find the last sentence boundary within maxLength
            val searchRange = remaining.substring(0, maxLength)
            val splitIndex = findBestSplitPoint(searchRange)

            if (splitIndex > 0) {
                chunks.add(remaining.substring(0, splitIndex).trim())
                remaining = remaining.substring(splitIndex).trim()
            } else {
                // No boundary found, force split at maxLength
                chunks.add(remaining.substring(0, maxLength).trim())
                remaining = remaining.substring(maxLength).trim()
            }
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun findBestSplitPoint(text: String): Int {
        // Priority: paragraph break > sentence end > comma > space
        val paragraphBreak = text.lastIndexOf("\n\n")
        if (paragraphBreak > text.length / 2) return paragraphBreak + 2

        val sentenceEnders = listOf("。", "．", ". ", "! ", "? ", "！", "？")
        var bestPos = -1
        for (ender in sentenceEnders) {
            val pos = text.lastIndexOf(ender)
            if (pos > bestPos) bestPos = pos + ender.length
        }
        if (bestPos > text.length / 3) return bestPos

        val lineBreak = text.lastIndexOf("\n")
        if (lineBreak > text.length / 3) return lineBreak + 1

        val commaEnders = listOf("、", "，", ", ")
        for (ender in commaEnders) {
            val pos = text.lastIndexOf(ender)
            if (pos > bestPos) bestPos = pos + ender.length
        }
        if (bestPos > text.length / 3) return bestPos

        val space = text.lastIndexOf(" ")
        if (space > text.length / 3) return space + 1

        return -1
    }
}
