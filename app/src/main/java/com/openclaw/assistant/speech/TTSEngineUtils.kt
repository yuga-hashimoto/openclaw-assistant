package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech

object TTSEngineUtils {
    
    data class EngineInfo(
        val name: String,
        val label: String,
        val icon: Int? = null // Optional icon resource
    )

    fun getAvailableEngines(context: Context): List<EngineInfo> {
        val pm = context.packageManager
        val intent = android.content.Intent("android.intent.action.TTS_SERVICE")
        val services = pm.queryIntentServices(intent, 0)
        
        return services.map { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo
            val applicationInfo = serviceInfo.applicationInfo
            val label = applicationInfo.loadLabel(pm).toString()
            val packageName = serviceInfo.packageName
            
            // Add package name to label for clarity if it's generic
            val displayLabel = if (label == "Text-to-speech engine" || label == "TTS") {
                "$label ($packageName)"
            } else {
                label
            }

            EngineInfo(
                name = packageName, 
                label = displayLabel
            )
        }.distinctBy { it.name } // Ensure no duplicates
    }


    fun getDefaultEngine(context: Context): String? {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "tts_default_synth"
            )
        } catch (e: Exception) {
            null
        }
    }
}
