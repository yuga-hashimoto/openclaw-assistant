package com.openclaw.assistant.gateway

const val GATEWAY_PROTOCOL_VERSION = 3

/** WebSocket connection state. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/** Chat event payload received via "chat" gateway event. */
data class ChatEventPayload(
    val runId: String?,
    val sessionKey: String?,
    val state: String?,       // "delta", "final", "aborted", "error"
    val errorMessage: String?
)

/** Agent streaming event payload received via "agent" gateway event. */
data class AgentEventPayload(
    val runId: String?,
    val stream: String?,      // "assistant", "tool", "error"
    val data: AgentStreamData?
)

data class AgentStreamData(
    val text: String?,
    val phase: String?,       // "start", "result"
    val name: String?,
    val toolCallId: String?
)
