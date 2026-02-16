package com.openclaw.assistant.telnyx

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Telnyx API client for making phone calls via Telnyx.
 * 
 * Uses Telnyx Programmable Voice API to enable the assistant to make
 * real phone calls. Integrates with ClawdTalk for AI-powered conversations.
 * 
 * Documentation: https://developers.telnyx.com/docs/call-control
 */
class TelnyxClient {

    companion object {
        private const val TAG = "TelnyxClient"
        private const val API_BASE_URL = "https://api.telnyx.com/v2"
        
        @Volatile
        private var instance: TelnyxClient? = null

        fun getInstance(): TelnyxClient {
            return instance ?: synchronized(this) {
                instance ?: TelnyxClient().also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // State
    private var apiKey: String? = null
    private var connectionId: String? = null
    private var callerId: String? = null
    
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()
    
    private val _currentCall = MutableStateFlow<CallInfo?>(null)
    val currentCall: StateFlow<CallInfo?> = _currentCall.asStateFlow()
    
    private val _callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 32)
    val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()

    /**
     * Configure the Telnyx client with API credentials.
     */
    fun configure(apiKey: String, connectionId: String? = null, callerId: String? = null) {
        this.apiKey = apiKey
        this.connectionId = connectionId
        this.callerId = callerId
        Log.d(TAG, "Telnyx client configured")
    }

    /**
     * Make an outbound phone call.
     * 
     * @param destination Phone number in E.164 format (e.g., "+15551234567")
     * @param callerIdOverride Optional caller ID override
     * @return Call leg ID if successful
     */
    suspend fun makeCall(
        destination: String,
        callerIdOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Telnyx API key not configured"))
        }

        val connection = connectionId
        if (connection.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Telnyx connection ID not configured"))
        }

        _callState.value = CallState.Connecting
        _callEvents.tryEmit(CallEvent.Connecting(destination))

        try {
            val requestBody = JsonObject().apply {
                addProperty("connection_id", connection)
                addProperty("to", destination)
                addProperty("from", callerIdOverride ?: callerId ?: "")
                addProperty("timeout_secs", 30)
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/calls")
                .post(gson.toJson(requestBody).toRequestBody(MediaTypes.JSON))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "HTTP ${response.code}"
                    _callState.value = CallState.Error(error)
                    _callEvents.tryEmit(CallEvent.Error(error))
                    return@withContext Result.failure(Exception(error))
                }

                val responseBody = response.body?.string()
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val data = json.getAsJsonObject("data")
                val callLegId = data.get("call_leg_id")?.asString ?: ""
                val callSessionId = data.get("call_session_id")?.asString ?: ""

                val callInfo = CallInfo(
                    callLegId = callLegId,
                    callSessionId = callSessionId,
                    destination = destination,
                    callerId = callerIdOverride ?: callerId ?: "",
                    direction = "outbound"
                )
                
                _currentCall.value = callInfo
                _callState.value = CallState.Ringing
                _callEvents.tryEmit(CallEvent.Ringing(callLegId))
                
                Log.d(TAG, "Call initiated: $callLegId to $destination")
                Result.success(callLegId)
            }
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            _callState.value = CallState.Error(e.message ?: "Unknown error")
            _callEvents.tryEmit(CallEvent.Error(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    /**
     * Answer an incoming call.
     */
    suspend fun answerCall(callLegId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Telnyx API key not configured"))
        }

        try {
            val requestBody = JsonObject().apply {
                addProperty("call_leg_id", callLegId)
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/calls/$callLegId/actions/answer")
                .post(gson.toJson(requestBody).toRequestBody(MediaTypes.JSON))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "HTTP ${response.code}"
                    return@withContext Result.failure(Exception(error))
                }

                _callState.value = CallState.Active
                _currentCall.value?.let { call ->
                    _currentCall.value = call.copy(state = "active")
                }
                _callEvents.tryEmit(CallEvent.Answered(callLegId))
                
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Hang up the current call.
     */
    suspend fun hangup(): Result<Unit> = withContext(Dispatchers.IO) {
        val key = apiKey
        val call = _currentCall.value
        
        if (key.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Telnyx API key not configured"))
        }
        
        if (call == null) {
            return@withContext Result.failure(IllegalStateException("No active call"))
        }

        try {
            val request = Request.Builder()
                .url("$API_BASE_URL/calls/${call.callLegId}/actions/hangup")
                .post("{}".toRequestBody(MediaTypes.JSON))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                _callState.value = CallState.Idle
                _callEvents.tryEmit(CallEvent.Hangup(call.callLegId))
                _currentCall.value = null
                
                if (!response.isSuccessful) {
                    Log.w(TAG, "Hangup returned non-success: ${response.code}")
                }
                
                Result.success(Unit)
            }
        } catch (e: Exception) {
            _callState.value = CallState.Idle
            _currentCall.value = null
            Result.failure(e)
        }
    }

    /**
     * Transfer a call to another number.
     */
    suspend fun transferCall(destination: String): Result<Unit> = withContext(Dispatchers.IO) {
        val key = apiKey
        val call = _currentCall.value
        
        if (key.isNullOrBlank() || call == null) {
            return@withContext Result.failure(IllegalStateException("Not configured or no active call"))
        }

        try {
            val requestBody = JsonObject().apply {
                addProperty("call_leg_id", call.callLegId)
                addProperty("to", destination)
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/calls/${call.callLegId}/actions/transfer")
                .post(gson.toJson(requestBody).toRequestBody(MediaTypes.JSON))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "HTTP ${response.code}"
                    return@withContext Result.failure(Exception(error))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Send DTMF tones during a call.
     */
    suspend fun sendDtmf(digits: String): Result<Unit> = withContext(Dispatchers.IO) {
        val key = apiKey
        val call = _currentCall.value
        
        if (key.isNullOrBlank() || call == null) {
            return@withContext Result.failure(IllegalStateException("Not configured or no active call"))
        }

        try {
            val requestBody = JsonObject().apply {
                addProperty("call_leg_id", call.callLegId)
                addProperty("digits", digits)
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/calls/${call.callLegId}/actions/send_dtmf")
                .post(gson.toJson(requestBody).toRequestBody(MediaTypes.JSON))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "HTTP ${response.code}"
                    return@withContext Result.failure(Exception(error))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Play audio during a call.
     */
    suspend fun playAudio(audioUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val key = apiKey
        val call = _currentCall.value
        
        if (key.isNullOrBlank() || call == null) {
            return@withContext Result.failure(IllegalStateException("Not configured or no active call"))
        }

        try {
            val requestBody = JsonObject().apply {
                addProperty("call_leg_id", call.callLegId)
                addProperty("audio_url", audioUrl)
            }

            val request = Request.Builder()
                .url("$API_BASE_URL/calls/${call.callLegId}/actions/playback_start")
                .post(gson.toJson(requestBody).toRequestBody(MediaTypes.JSON))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "HTTP ${response.code}"
                    return@withContext Result.failure(Exception(error))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Text-to-speech via Telnyx AI Inference API.
     */
    suspend fun synthesizeSpeech(text: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Telnyx API key not configured"))
        }

        try {
            val requestBody = JsonObject().apply {
                addProperty("model", "tts-1-hd")
                addProperty("input", text)
                addProperty("voice", "alloy")
                addProperty("response_format", "pcm")
            }

            val request = Request.Builder()
                .url("https://api.telnyx.com/v2/ai/speech")
                .post(gson.toJson(requestBody).toRequestBody(MediaTypes.JSON))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "audio/pcm")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body?.string() ?: "HTTP ${response.code}"
                    return@withContext Result.failure(Exception(error))
                }
                
                val audioBytes = response.body?.bytes() ?: byteArrayOf()
                Result.success(audioBytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if configured with valid credentials.
     */
    fun isConfigured(): Boolean = !apiKey.isNullOrBlank()

    /**
     * Check if currently in a call.
     */
    fun isInCall(): Boolean = _callState.value == CallState.Active

    /**
     * Clear credentials.
     */
    fun reset() {
        apiKey = null
        connectionId = null
        callerId = null
        _callState.value = CallState.Idle
        _currentCall.value = null
    }

    fun cleanup() {
        scope.cancel()
    }
}

// --- Data Classes ---

sealed class CallState {
    object Idle : CallState()
    object Connecting : CallState()
    object Ringing : CallState()
    object Active : CallState()
    object OnHold : CallState()
    data class Error(val message: String) : CallState()
}

data class CallInfo(
    val callLegId: String,
    val callSessionId: String = "",
    val destination: String,
    val callerId: String,
    val direction: String = "outbound",
    val state: String = "ringing",
    val durationSeconds: Long = 0
)

sealed class CallEvent {
    data class Connecting(val destination: String) : CallEvent()
    data class Ringing(val callLegId: String) : CallEvent()
    data class Answered(val callLegId: String) : CallEvent()
    data class Hangup(val callLegId: String) : CallEvent()
    data class Error(val message: String) : CallEvent()
    data class Incoming(val callLegId: String, val from: String) : CallEvent()
}

private object MediaTypes {
    val JSON = "application/json; charset=utf-8".toMediaType()
}
