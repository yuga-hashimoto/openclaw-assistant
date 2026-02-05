package com.openclaw.assistant.speech.embedded

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Manager for embedded TTS engine (Sherpa-ONNX implementation)
 * 
 * Supports: English, Chinese, and Japanese.
 */
class EmbeddedTTSManager(private val context: Context) {
    companion object {
        private const val TAG = "EmbeddedTTSManager"
        
        // Model download URLs from GitHub releases
        private const val MODEL_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
        
        // Available models
        private val MODELS = mapOf(
            "ja" to ModelInfo(
                archiveName = "vits-piper-ja_JP-nanami-medium.tar.bz2",
                folderName = "vits-piper-ja_JP-nanami-medium",
                files = listOf("ja_JP-nanami-medium.onnx", "tokens.txt")
            ),
            "zh" to ModelInfo(
                archiveName = "vits-melo-tts-zh_en.tar.bz2",
                folderName = "vits-melo-tts-zh_en",
                files = listOf("model.onnx", "tokens.txt", "lexicon.txt")
            ),
            "en" to ModelInfo(
                archiveName = "vits-piper-en_US-glados.tar.bz2",
                folderName = "vits-piper-en_US-glados",
                files = listOf("en_US-glados.onnx", "tokens.txt")
            )
        )
    }
    
    data class ModelInfo(
        val archiveName: String,
        val folderName: String,
        val files: List<String>
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Directory for storing voice models
    private val modelDir = File(context.filesDir, "tts_models")

    init {
        if (!modelDir.exists()) modelDir.mkdirs()
    }

    /**
     * Check if a specific language model is installed
     */
    fun isModelInstalled(locale: Locale): Boolean {
        val modelInfo = getModelInfo(locale) ?: return false
        val dir = File(modelDir, modelInfo.folderName)
        if (!dir.exists()) return false
        
        // Check if all required files exist
        return modelInfo.files.all { File(dir, it).exists() }
    }

    /**
     * Get model info for a locale
     */
    private fun getModelInfo(locale: Locale): ModelInfo? {
        return when (locale.language) {
            "ja" -> MODELS["ja"]
            "zh" -> MODELS["zh"]
            "en" -> MODELS["en"]
            else -> {
                Log.w(TAG, "No embedded TTS model available for ${locale.language}. Use system TTS instead.")
                null
            }
        }
    }
    
    /**
     * Check if embedded TTS is available for this locale
     */
    fun isLocaleSupported(locale: Locale): Boolean {
        return when (locale.language) {
            "ja", "zh", "en" -> true
            else -> false
        }
    }
    
    /**
     * Get list of supported language display names
     */
    fun getSupportedLanguages(): List<String> {
        return listOf("日本語 (Japanese)", "English", "中文 (Chinese)")
    }

    /**
     * Download voice models
     */
    fun downloadModel(locale: Locale, onProgress: (Float) -> Unit, onComplete: (Boolean) -> Unit) {
        val modelInfo = getModelInfo(locale)
        
        if (modelInfo == null) {
            Log.e(TAG, "No model available for locale: $locale")
            scope.launch(Dispatchers.Main) {
                onComplete(false)
            }
            return
        }
        
        val targetDir = File(modelDir, modelInfo.folderName)
        if (!targetDir.exists()) targetDir.mkdirs()

        val downloadUrl = MODEL_BASE_URL + modelInfo.archiveName
        Log.d(TAG, "Downloading model from: $downloadUrl")

        scope.launch {
            try {
                // Download the archive
                val archiveFile = File(modelDir, modelInfo.archiveName)
                val downloadSuccess = downloadFile(downloadUrl, archiveFile) { progress ->
                    scope.launch(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
                
                if (!downloadSuccess) {
                    Log.e(TAG, "Failed to download model archive")
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }
                
                // Extract the archive
                Log.d(TAG, "Extracting archive...")
                val extractSuccess = extractTarBz2(archiveFile, modelDir)
                
                // Clean up archive
                archiveFile.delete()
                
                withContext(Dispatchers.Main) {
                    onComplete(extractSuccess && isModelInstalled(locale))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download/extract error: ${e.message}", e)
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    private suspend fun downloadFile(
        url: String, 
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download: $url")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    return@withContext false
                }
                
                val body = response.body ?: return@withContext false
                val contentLength = body.contentLength()
                var bytesRead = 0L
                
                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                onProgress(bytesRead.toFloat() / contentLength)
                            }
                        }
                    }
                }
                Log.d(TAG, "Download complete: ${outputFile.length()} bytes")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        }
    }
    
    private fun extractTarBz2(archiveFile: File, outputDir: File): Boolean {
        return try {
            // Use Android's Runtime to extract
            val process = Runtime.getRuntime().exec(
                arrayOf("tar", "-xjf", archiveFile.absolutePath, "-C", outputDir.absolutePath)
            )
            val exitCode = process.waitFor()
            Log.d(TAG, "Extract exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Extract error: ${e.message}", e)
            false
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
    }
    
    /**
     * Speak text using embedded TTS
     */
    fun speak(text: String, locale: Locale) {
        val modelInfo = getModelInfo(locale)
        if (modelInfo == null) {
            Log.w(TAG, "Embedded TTS not available for ${locale.language}. Use system TTS.")
            return
        }
        
        if (!isModelInstalled(locale)) {
            Log.w(TAG, "Model not installed for ${locale.language}")
            return
        }
        
        // Implementation for actual TTS playback
        Log.d(TAG, "speak() called. Text: $text")
    }
}
