package com.openclaw.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * 暗号化されたSharedPreferencesで設定を安全に保存
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

    // Webhook URL（必須）
    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    // 認証トークン（オプション）
    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    // セッションID（自動生成可）
    var sessionId: String
        get() {
            val existing = prefs.getString(KEY_SESSION_ID, null)
            return existing ?: generateNewSessionId().also { sessionId = it }
        }
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    // ユーザーID（オプション）
    var userId: String
        get() = prefs.getString(KEY_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    // Picovoice Access Key
    var picovoiceAccessKey: String
        get() = prefs.getString(KEY_PICOVOICE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PICOVOICE_KEY, value).apply()

    // ホットワード検知有効化
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOTWORD_ENABLED, value).apply()

    // 起動時にサービス開始
    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    // 設定が有効かチェック
    fun isConfigured(): Boolean {
        return webhookUrl.isNotBlank()
    }

    // 新しいセッションIDを生成
    fun generateNewSessionId(): String {
        return UUID.randomUUID().toString()
    }

    // セッションをリセット
    fun resetSession() {
        sessionId = generateNewSessionId()
    }

    companion object {
        private const val PREFS_NAME = "openclaw_secure_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PICOVOICE_KEY = "picovoice_access_key"
        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"
        private const val KEY_START_ON_BOOT = "start_on_boot"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
