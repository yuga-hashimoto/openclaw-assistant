package com.openclaw.assistant.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenClaw Webhook APIクライアント
 */
class OpenClawClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // OpenClawの応答は時間がかかる場合がある
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * ユーザーのメッセージをOpenClawに送信し、応答を取得
     */
    suspend fun sendMessage(
        webhookUrl: String,
        message: String,
        sessionId: String,
        userId: String? = null,
        authToken: String? = null
    ): Result<OpenClawResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = OpenClawRequest(
                message = message,
                sessionId = sessionId,
                userId = userId
            )

            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            // 認証トークンがあれば追加
            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: ${response.message}")
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IOException("Empty response from server")
                    )
                }

                try {
                    val openClawResponse = gson.fromJson(responseBody, OpenClawResponse::class.java)
                    Result.success(openClawResponse)
                } catch (e: Exception) {
                    // JSONパース失敗時は生のレスポンスをそのまま使用
                    Result.success(OpenClawResponse(response = responseBody))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * OpenClawへのリクエスト形式
 */
data class OpenClawRequest(
    @SerializedName("message")
    val message: String,

    @SerializedName("session_id")
    val sessionId: String,

    @SerializedName("user_id")
    val userId: String? = null
)

/**
 * OpenClawからのレスポンス形式
 */
data class OpenClawResponse(
    @SerializedName("response")
    val response: String? = null,

    @SerializedName("text")
    val text: String? = null,  // 別形式のレスポンスにも対応

    @SerializedName("message")
    val message: String? = null,  // 別形式のレスポンスにも対応

    @SerializedName("error")
    val error: String? = null
) {
    /**
     * 応答テキストを取得（複数のフィールド形式に対応）
     */
    fun getResponseText(): String? {
        return response ?: text ?: message
    }
}
