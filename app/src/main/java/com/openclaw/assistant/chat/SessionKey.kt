package com.openclaw.assistant.chat

fun normalizeMainKey(raw: String?): String {
    val trimmed = raw?.trim()
    return if (!trimmed.isNullOrEmpty()) trimmed else "main"
}

fun isCanonicalMainSessionKey(raw: String?): Boolean {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    if (trimmed == "global") return true
    return trimmed.startsWith("agent:")
}
