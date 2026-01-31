package com.openclaw.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Secure settings storage
 */
class SettingsRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Webhook URL (required)
    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    // Auth Token (optional)
    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    // Session ID (auto-generated)
    var sessionId: String
        get() {
            val existing = prefs.getString(KEY_SESSION_ID, null)
            return existing ?: generateNewSessionId().also { sessionId = it }
        }
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    // Picovoice Access Key (hardcoded)
    val picovoiceAccessKey: String
        get() = PICOVOICE_ACCESS_KEY

    // Hotword enabled
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOTWORD_ENABLED, value).apply()

    // Check if configured
    fun isConfigured(): Boolean {
        return webhookUrl.isNotBlank()
    }

    // Generate new session ID
    fun generateNewSessionId(): String {
        return UUID.randomUUID().toString()
    }

    // Reset session
    fun resetSession() {
        sessionId = generateNewSessionId()
    }

    companion object {
        private const val PREFS_NAME = "openclaw_secure_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"

        // Hardcoded Picovoice Access Key
        private const val PICOVOICE_ACCESS_KEY = "NvmV8rffJPNXTVlKl1kctpOBQv1pHIycrLkEG1u8aMvNFGp/i7MWHQ=="

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
