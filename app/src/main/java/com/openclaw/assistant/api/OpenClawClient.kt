package com.openclaw.assistant.api

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Simple webhook client - POSTs to the configured URL
 */
class OpenClawClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * POST message to webhook URL and return response
     */
    suspend fun sendMessage(
        webhookUrl: String,
        message: String,
        sessionId: String,
        authToken: String? = null,
        agentId: String? = null,
        thinkingLevel: String? = null
    ): Result<OpenClawResponse> = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Webhook URL is not configured")
            )
        }

        try {
            // OpenAI Chat Completions format for /v1/chat/completions
            val requestBody = JsonObject().apply {
                addProperty("model", "openclaw")
                addProperty("user", sessionId)
                val messagesArray = JsonArray()
                val userMessage = JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", message)
                }
                messagesArray.add(userMessage)
                add("messages", messagesArray)

                if (!thinkingLevel.isNullOrBlank() && thinkingLevel != "off") {
                    addProperty("thinking", thinkingLevel)
                }
            }

            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${authToken.trim()}")
            }

            if (!agentId.isNullOrBlank()) {
                requestBuilder.addHeader("x-openclaw-agent-id", agentId)
            }

            if (!thinkingLevel.isNullOrBlank() && thinkingLevel != "off") {
                requestBuilder.addHeader("x-openclaw-thinking", thinkingLevel)
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: response.message
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: $errorBody")
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IOException("Empty response")
                    )
                }

                // Extract response text from JSON
                val text = extractResponseText(responseBody)
                Result.success(OpenClawResponse(response = text ?: responseBody))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTransientNetworkError(e)) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
            Result.failure(e)
        }
    }

    /**
     * Test connection to the webhook
     */
    suspend fun testConnection(
        webhookUrl: String,
        authToken: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Webhook URL is not configured")
            )
        }

        try {
            // Try a HEAD request first (lightweight)
            var requestBuilder = Request.Builder()
                .url(webhookUrl)
                .head()

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${authToken.trim()}")
            }

            var request = requestBuilder.build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@withContext Result.success(true)
                    // If Method Not Allowed (405), try POST
                    if (response.code == 405) {
                         // Fallthrough to POST
                    } else {
                         return@withContext Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                // Fallthrough to POST on error (some servers reject HEAD)
            }

            // Fallback: POST with minimal OpenAI format
            val requestBody = JsonObject().apply {
                addProperty("model", "openclaw")
                addProperty("user", "connection-test")
                val messagesArray = JsonArray()
                val testMessage = JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "ping")
                }
                messagesArray.add(testMessage)
                add("messages", messagesArray)
            }
            
            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${authToken.trim()}")
            }

            request = requestBuilder.build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val errorBody = response.body?.string() ?: response.message
                    Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isTransientNetworkError(e: Throwable): Boolean {
        return e is java.net.SocketTimeoutException ||
                e is java.net.SocketException ||
                e is java.net.ConnectException ||
                e is java.io.EOFException ||
                e is java.net.UnknownHostException ||
                (e.cause != null && isTransientNetworkError(e.cause!!))
    }

    /**
     * Extract response text from various JSON formats
     */
    private fun extractResponseText(json: String): String? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)

            // Check for API error response
            obj.getAsJsonObject("error")?.let { error ->
                val errorMsg = error.get("message")?.asString ?: "Unknown error"
                throw IOException("API Error: $errorMsg")
            }

            // OpenAI format (primary): choices[0].message.content
            obj.getAsJsonArray("choices")?.let { choices ->
                choices.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
            }
            // Fallback formats
            ?: obj.get("response")?.asString
            ?: obj.get("text")?.asString
            ?: obj.get("message")?.asString
            ?: obj.get("content")?.asString
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Response wrapper
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null
) {
    fun getResponseText(): String? = response
}
