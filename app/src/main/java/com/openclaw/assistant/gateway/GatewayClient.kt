package com.openclaw.assistant.gateway

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.math.pow

/**
 * Simplified WebSocket gateway client for OpenClaw.
 * Based on the official app's GatewaySession + ChatController, but:
 * - Token auth only (no Ed25519 device auth)
 * - Gson instead of kotlinx.serialization
 * - Merged connection + chat control into a single class
 */
class GatewayClient(context: android.content.Context) {

    companion object {
        private const val TAG = "GatewayClient"

        @Volatile
        private var instance: GatewayClient? = null

        fun getInstance(context: android.content.Context? = null): GatewayClient {
            return instance ?: synchronized(this) {
                instance ?: GatewayClient(context!!).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResult>>()
    
    private val deviceIdentity = DeviceIdentity(context)

    // --- Public state ---

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _chatEvents = MutableSharedFlow<ChatEventPayload>(extraBufferCapacity = 64)
    val chatEvents: SharedFlow<ChatEventPayload> = _chatEvents.asSharedFlow()

    private val _agentEvents = MutableSharedFlow<AgentEventPayload>(extraBufferCapacity = 64)
    val agentEvents: SharedFlow<AgentEventPayload> = _agentEvents.asSharedFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _agentList = MutableStateFlow<AgentListResult?>(null)
    val agentList: StateFlow<AgentListResult?> = _agentList.asStateFlow()

    private val _missingScopeError = MutableStateFlow<String?>(null)
    val missingScopeError: StateFlow<String?> = _missingScopeError.asStateFlow()

    private val _isPairingRequired = MutableStateFlow(false)
    val isPairingRequired: StateFlow<Boolean> = _isPairingRequired.asStateFlow()

    /** The main session key received from the server during connect. */
    @Volatile
    var mainSessionKey: String? = null
        private set

    val deviceId: String?
        get() = deviceIdentity.deviceId

    // --- Internal connection state ---

    private var desiredHost: String? = null
    private var desiredPort: Int = 18789
    private var desiredToken: String? = null
    private var desiredUseTls: Boolean = false

    private var runLoopJob: Job? = null
    private var currentSocket: WebSocket? = null
    private val isClosed = AtomicBoolean(false)
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var closeDeferred: CompletableDeferred<Unit>? = null
    private var connectNonceDeferred: CompletableDeferred<String?>? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket: no read timeout
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    // --- Public API ---

    fun connect(host: String, port: Int = 18789, token: String? = null, useTls: Boolean = false) {
        val changed = desiredHost != host || desiredPort != port || desiredToken != token || desiredUseTls != useTls

        desiredHost = host
        desiredPort = port
        desiredToken = token
        desiredUseTls = useTls

        if (runLoopJob == null || changed) {
            scope.launch {
                runLoopJob?.cancelAndJoin()
                runLoopJob = scope.launch { runLoop() }
            }
        }
    }

    fun disconnect() {
        desiredHost = null
        currentSocket?.close(1000, "bye")
        scope.launch {
            runLoopJob?.cancelAndJoin()
            runLoopJob = null
            mainSessionKey = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _streamingText.value = null
            _agentList.value = null
        }
    }

    /**
     * Force an immediate reconnection attempt, cancelling any current backoff delay.
     */
    fun reconnect() {
        Log.d(TAG, "Manual reconnect requested")
        scope.launch {
            runLoopJob?.cancelAndJoin()
            runLoopJob = scope.launch { runLoop() }
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /**
     * Send a chat message via WebSocket RPC.
     * @return runId from the server
     */
    suspend fun sendChat(sessionKey: String, message: String, thinkingLevel: String? = null): String? {
        val params = JsonObject().apply {
            addProperty("sessionKey", sessionKey)
            addProperty("message", message)
            addProperty("timeoutMs", 30_000)
            addProperty("idempotencyKey", UUID.randomUUID().toString())
            if (!thinkingLevel.isNullOrBlank() && thinkingLevel != "off") {
                addProperty("thinking", thinkingLevel)
            }
        }
        val result = request("chat.send", params, timeoutMs = 35_000)
        if (!result.ok) {
            throw IllegalStateException("chat.send failed: ${result.errorMessage ?: result.errorCode ?: "unknown"}")
        }
        val runId = result.payload?.get("runId")?.asString
        Log.d(TAG, "chat.send ok, runId=$runId")
        return runId
    }

    /**
     * Abort an in-progress chat run.
     */
    suspend fun abortChat(sessionKey: String, runId: String?) {
        val params = JsonObject().apply {
            addProperty("sessionKey", sessionKey)
            if (runId != null) addProperty("runId", runId)
        }
        try {
            request("chat.abort", params, timeoutMs = 5_000)
        } catch (e: Exception) {
            Log.w(TAG, "abort failed: ${e.message}")
        }
    }

    /**
     * Get chat history for a session.
     */
    suspend fun getChatHistory(sessionKey: String): ChatHistoryResult? {
        val params = JsonObject().apply {
            addProperty("sessionKey", sessionKey)
        }
        val result = request("chat.history", params)
        return parseChatHistory(result.payload)
    }

    /**
     * Fetch the list of available agents from the gateway.
     */

    suspend fun getAgentList(): AgentListResult? {
        Log.e(TAG, "Requesting agent list (agents.list)...")
        val result = request("agents.list", null)
        
        if (!result.ok) {
            val errorMsg = result.errorMessage ?: result.errorCode ?: "Unknown error"
            Log.e(TAG, "Failed to get agent list: $errorMsg")

            // Check for missing scope error
            if (errorMsg.contains("missing scope", ignoreCase = true)) {
                _missingScopeError.value = errorMsg
            }

            throw IllegalStateException(errorMsg)
        }

        // Clear error on success
        _missingScopeError.value = null

        return parseAgentListResult(result.payload).also { 
            Log.e(TAG, "Agent list received: ${it?.agents?.size} agents")
            _agentList.value = it 
        }
    }

    /**
     * Check server health.
     */
    suspend fun checkHealth(): Boolean {
        return try {
            request("health", null, timeoutMs = 5_000)
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Internal: Run loop with auto-reconnect ---

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            val host = desiredHost
            if (host == null) {
                delay(250)
                continue
            }

            try {
                _connectionState.value = if (attempt == 0)
                    ConnectionState.CONNECTING
                else
                    ConnectionState.RECONNECTING

                connectOnce(host, desiredPort, desiredToken, desiredUseTls)
                attempt = 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed (attempt $attempt): ${e.message}")
                if (attempt == 0 && !isTransientNetworkError(e)) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
                attempt++
                _connectionState.value = ConnectionState.RECONNECTING
                val sleepMs = min(8_000L, (350.0 * 1.7.pow(attempt.toDouble())).toLong())
                delay(sleepMs)
            }
        }
    }

    private suspend fun connectOnce(host: String, port: Int, token: String?, useTls: Boolean) {
        isClosed.set(false)
        connectDeferred = CompletableDeferred()
        closeDeferred = CompletableDeferred()
        connectNonceDeferred = CompletableDeferred()

        val scheme = if (useTls) "wss" else "ws"
        val url = "$scheme://$host:$port"
        Log.e(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        currentSocket = httpClient.newWebSocket(request, WsListener())

        try {
            connectDeferred?.await()
        } catch (e: Exception) {
            throw e
        }

        // Send connect RPC
        sendConnectRpc(token)

        _connectionState.value = ConnectionState.CONNECTED
        Log.e(TAG, "Connected to $url, mainSessionKey=$mainSessionKey")

        // Auto-fetch agent list after successful connect
        scope.launch {
            try {
                getAgentList()
            } catch (e: Exception) {
                Log.w(TAG, "Auto-fetch agent list failed: ${e.message}")
            }
        }

        // Wait until socket closes
        closeDeferred?.await()
        _connectionState.value = ConnectionState.DISCONNECTED
        _streamingText.value = null
        mainSessionKey = null
    }

    private suspend fun sendConnectRpc(token: String?) {
        // Wait briefly for connect.challenge nonce (loopback skips this)
        val nonce = try {
            withTimeout(2_000) { connectNonceDeferred?.await() }
        } catch (e: Exception) {
            null
        }

        val clientObj = JsonObject().apply {
            addProperty("id", "openclaw-android")
            addProperty("version", "1.0")
            addProperty("platform", "android")
            addProperty("mode", "ui")
        }

        val params = JsonObject().apply {
            addProperty("minProtocol", GATEWAY_PROTOCOL_VERSION)
            addProperty("maxProtocol", GATEWAY_PROTOCOL_VERSION)
            add("client", clientObj)
            if (!token.isNullOrBlank()) {
                val authObj = JsonObject().apply {
                    addProperty("token", token)
                }
                add("auth", authObj)
            }
            
            // Add device auth if available
            if (deviceIdentity.deviceId != null && deviceIdentity.publicKeyBase64Url != null) {
                val signedAtMs = System.currentTimeMillis()
                val role = "operator" // Default role
                val scopes = listOf("operator.admin") // Default scopes requested
                
                val payload = deviceIdentity.buildAuthPayload(
                    clientId = "openclaw-android",
                    clientMode = "ui",
                    role = role,
                    scopes = scopes,
                    signedAtMs = signedAtMs,
                    token = token,
                    nonce = nonce
                )
                
                val signature = deviceIdentity.sign(payload)
                
                if (signature != null) {
                    val deviceObj = JsonObject().apply {
                        addProperty("id", deviceIdentity.deviceId)
                        addProperty("publicKey", deviceIdentity.publicKeyBase64Url)
                        addProperty("signature", signature)
                        addProperty("signedAt", signedAtMs)
                        if (nonce != null) addProperty("nonce", nonce)
                    }
                    add("device", deviceObj)
                    
                    // Also explicitly request the scopes in the top level
                    val scopesArray = JsonArray()
                    scopes.forEach { scopesArray.add(it) }
                    add("scopes", scopesArray)
                    addProperty("role", role)
                }
            }
        }

        val result = request("connect", params, timeoutMs = 8_000)
        if (!result.ok) {
            val errorCode = result.errorCode
            val errorMsg = result.errorMessage ?: "connect failed"
            
            if (errorCode == "NOT_PAIRED" || errorMsg.contains("pairing required", ignoreCase = true)) {
                _isPairingRequired.value = true
            }
            
            throw IllegalStateException("$errorMsg ($errorCode)")
        }

        // Clear pairing error on success
        _isPairingRequired.value = false

        // Extract mainSessionKey from response
        result.payload?.let { payload ->
            Log.d(TAG, "Connect payload keys: ${payload.keySet()}")
            val snapshot = payload.getAsJsonObject("snapshot")
            Log.d(TAG, "Snapshot keys: ${snapshot?.keySet()}")
            val sessionDefaults = snapshot?.getAsJsonObject("sessionDefaults")
            mainSessionKey = sessionDefaults?.get("mainSessionKey")?.asString
            Log.d(TAG, "mainSessionKey=$mainSessionKey")
        }
    }

    // --- Internal: RPC ---

    private data class RpcResult(
        val ok: Boolean,
        val payload: JsonObject?,
        val errorCode: String?,
        val errorMessage: String?
    )

    private suspend fun request(
        method: String,
        params: JsonObject?,
        timeoutMs: Long = 15_000
    ): RpcResult {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<RpcResult>()
        pending[id] = deferred

        val frame = JsonObject().apply {
            addProperty("type", "req")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        sendJson(frame)

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            throw IllegalStateException("Request '$method' timed out")
        }
    }

    private suspend fun sendJson(obj: JsonObject) {
        val jsonString = gson.toJson(obj)
        writeLock.withLock {
            currentSocket?.send(jsonString)
        }
    }

    // --- Internal: Message handling ---

    private fun handleMessage(text: String) {
        try {
            val frame = JsonParser.parseString(text).asJsonObject
            val type = frame.get("type")?.asString
            Log.d(TAG, "WS recv type=$type, keys=${frame.keySet()}")
            when (type) {
                "res" -> handleResponse(frame)
                "event" -> handleEvent(frame)
                else -> Log.w(TAG, "Unknown frame type: $type, frame=${text.take(200)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}, raw=${text.take(200)}")
        }
    }

    private fun handleResponse(frame: JsonObject) {
        val id = frame.get("id")?.asString ?: return
        val ok = frame.get("ok")?.asBoolean ?: false
        val payload = frame.getAsJsonObject("payload")
        val error = frame.getAsJsonObject("error")
        val errorCode = error?.get("code")?.asString
        val errorMessage = error?.get("message")?.asString

        Log.d(TAG, "RPC response id=${id.take(8)}… ok=$ok, error=$errorCode: $errorMessage")
        pending.remove(id)?.complete(RpcResult(ok, payload, errorCode, errorMessage))
    }

    private fun handleEvent(frame: JsonObject) {
        val event = frame.get("event")?.asString ?: return
        val payloadJson = frame.get("payload")?.toString()
            ?: frame.get("payloadJSON")?.asString
        Log.d(TAG, "WS event=$event, payloadLen=${payloadJson?.length ?: 0}")

        when (event) {
            "connect.challenge" -> {
                // Extract nonce for connect handshake
                val nonce = try {
                    val obj = JsonParser.parseString(payloadJson ?: "{}").asJsonObject
                    obj.get("nonce")?.asString
                } catch (e: Exception) {
                    null
                }
                connectNonceDeferred?.complete(nonce)
            }
            "tick" -> {
                // Keep-alive, no action needed
            }
            "chat" -> {
                if (payloadJson.isNullOrBlank()) return
                handleChatEvent(payloadJson)
            }
            "agent" -> {
                if (payloadJson.isNullOrBlank()) return
                handleAgentEvent(payloadJson)
            }
            else -> Log.w(TAG, "Unhandled event: $event, payload=${payloadJson?.take(200)}")
        }
    }

    private fun handleChatEvent(payloadJson: String) {
        try {
            val payload = JsonParser.parseString(payloadJson).asJsonObject
            val event = ChatEventPayload(
                runId = payload.get("runId")?.asString,
                sessionKey = payload.get("sessionKey")?.asString,
                state = payload.get("state")?.asString,
                errorMessage = payload.get("errorMessage")?.asString
            )
            Log.d(TAG, "Chat event: state=${event.state}, runId=${event.runId}")
            _chatEvents.tryEmit(event)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse chat event: ${e.message}, json=${payloadJson.take(200)}")
        }
    }

    private fun handleAgentEvent(payloadJson: String) {
        try {
            val payload = JsonParser.parseString(payloadJson).asJsonObject
            val data = payload.getAsJsonObject("data")
            val streamData = if (data != null) {
                AgentStreamData(
                    text = data.get("text")?.asString,
                    phase = data.get("phase")?.asString,
                    name = data.get("name")?.asString,
                    toolCallId = data.get("toolCallId")?.asString
                )
            } else null

            val event = AgentEventPayload(
                runId = payload.get("runId")?.asString,
                stream = payload.get("stream")?.asString,
                data = streamData
            )
            Log.d(TAG, "Agent event: stream=${event.stream}, textLen=${streamData?.text?.length ?: 0}, phase=${streamData?.phase}")
            _agentEvents.tryEmit(event)

            // Update streaming text for "assistant" stream
            if (event.stream == "assistant" && !streamData?.text.isNullOrEmpty()) {
                _streamingText.value = streamData?.text
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse agent event: ${e.message}")
        }
    }

    // --- Internal: Agent list parsing ---

    private fun parseAgentListResult(payload: JsonObject?): AgentListResult? {
        if (payload == null) return null
        val defaultId = payload.get("defaultId")?.asString ?: "main"
        val agentsArray = payload.getAsJsonArray("agents") ?: return AgentListResult(defaultId, emptyList())

        val agents = agentsArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val id = obj.get("id")?.asString ?: return@mapNotNull null
            val name = obj.get("name")?.asString ?: id
            AgentInfo(id = id, name = name)
        }
        return AgentListResult(defaultId = defaultId, agents = agents)
    }

    // --- Internal: Chat history parsing ---

    data class ChatHistoryMessage(
        val role: String,
        val content: String?,
        val timestampMs: Long?
    )

    data class ChatHistoryResult(
        val sessionId: String?,
        val messages: List<ChatHistoryMessage>
    )

    private fun parseChatHistory(payload: JsonObject?): ChatHistoryResult? {
        if (payload == null) return null
        val sessionId = payload.get("sessionId")?.asString
        val messagesArray = payload.getAsJsonArray("messages") ?: return ChatHistoryResult(sessionId, emptyList())

        val messages = messagesArray.mapNotNull { item ->
            val obj = item.asJsonObject ?: return@mapNotNull null
            val role = obj.get("role")?.asString ?: return@mapNotNull null
            val contentArray = obj.getAsJsonArray("content")
            val text = contentArray?.firstOrNull()?.asJsonObject?.get("text")?.asString
            val ts = obj.get("timestamp")?.asLong
            ChatHistoryMessage(role = role, content = text, timestampMs = ts)
        }
        return ChatHistoryResult(sessionId, messages)
    }

    // --- Internal: WebSocket Listener ---

    private inner class WsListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.e(TAG, "WebSocket opened")
            // Don't complete connectDeferred here; wait for connect RPC response
            connectDeferred?.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            if (!isTransientNetworkError(t)) {
                FirebaseCrashlytics.getInstance().recordException(t)
            }
            if (connectDeferred?.isCompleted == false) {
                connectDeferred?.completeExceptionally(t)
            }
            failPending()
            if (isClosed.compareAndSet(false, true)) {
                closeDeferred?.complete(Unit)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            if (connectDeferred?.isCompleted == false) {
                connectDeferred?.completeExceptionally(IllegalStateException("Closed: $reason"))
            }
            failPending()
            if (isClosed.compareAndSet(false, true)) {
                closeDeferred?.complete(Unit)
            }
        }
    }

    private fun failPending() {
        for ((_, waiter) in pending) {
            waiter.cancel()
        }
        pending.clear()
    }

    private fun isTransientNetworkError(t: Throwable): Boolean {
        return t is SocketTimeoutException ||
                t is SocketException ||
                t is ConnectException ||
                t is java.io.EOFException ||
                t is javax.net.ssl.SSLException ||
                t is java.net.ProtocolException ||
                t is android.system.ErrnoException ||
                (t.cause != null && isTransientNetworkError(t.cause!!))
    }
}
