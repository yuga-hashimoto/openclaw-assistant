package com.openclaw.assistant.utils

import android.content.Context
import android.os.Build
import com.openclaw.assistant.data.SettingsRepository

object SystemInfoProvider {

    fun getSystemInfoReport(context: Context, settings: SettingsRepository): String {
        val appVersion = getAppVersion(context)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        val ttsEngine = if (settings.ttsEnabled) {
            settings.ttsEngine.ifEmpty { "System Default" }
        } else {
            "Disabled"
        }
        val wakeWord = settings.getWakeWordDisplayName()
        val language = settings.speechLanguage.ifEmpty { "System Default" }

        return """

            **Device Information**
            - App Version: $appVersion
            - Device: $deviceModel
            - Android Version: $androidVersion
            - Language: $language
            - TTS Engine: $ttsEngine
            - Wake Word: $wakeWord
        """.trimIndent()
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = packageInfo.versionCode
            "${packageInfo.versionName} ($versionCode)"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
