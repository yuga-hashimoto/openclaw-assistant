package com.openclaw.assistant.speech

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Utility to query available speech recognition languages from the device.
 */
object SpeechLanguageUtils {

    private const val TAG = "SpeechLanguageUtils"

    data class LanguageInfo(
        val tag: String,
        val displayName: String
    )

    /**
     * Query the device's speech recognition service for supported languages.
     * Returns a sorted list of LanguageInfo, or null if the query fails.
     */
    suspend fun getAvailableLanguages(context: Context): List<LanguageInfo>? {
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val detailsIntent = RecognizerIntent.getVoiceDetailsIntent(context)
                    if (detailsIntent == null) {
                        Log.w(TAG, "getVoiceDetailsIntent returned null")
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            val extras = getResultExtras(true)
                            val languages = extras?.getStringArrayList(
                                RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES
                            )

                            if (languages.isNullOrEmpty()) {
                                Log.w(TAG, "No supported languages returned")
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }

                            Log.d(TAG, "Got ${languages.size} languages from recognizer")
                            val result = languages
                                .mapNotNull { tag -> formatLanguageTag(tag) }
                                .distinctBy { it.tag }
                                .sortedBy { it.displayName }

                            if (continuation.isActive) continuation.resume(result)
                        }
                    }

                    context.sendOrderedBroadcast(
                        detailsIntent,
                        null,
                        receiver,
                        null,
                        Activity.RESULT_OK,
                        null,
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to query speech languages", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    /**
     * Convert a BCP-47 tag to a LanguageInfo with a user-friendly display name.
     * Uses native language names (e.g., "Italiano" instead of "Italian").
     */
    fun formatLanguageTag(tag: String): LanguageInfo? {
        if (tag.isBlank()) return null

        return try {
            val locale = Locale.forLanguageTag(tag)
            if (locale.language.isBlank()) return null

            val nativeName = locale.getDisplayLanguage(locale)
                .replaceFirstChar { it.uppercase(locale) }
            val nativeCountry = locale.getDisplayCountry(locale)

            if (nativeName.isBlank() || nativeName == tag) return null

            val displayName = if (nativeCountry.isNotBlank()) {
                "$nativeName ($nativeCountry)"
            } else {
                nativeName
            }

            LanguageInfo(tag = tag, displayName = displayName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse language tag: $tag", e)
            null
        }
    }
}
