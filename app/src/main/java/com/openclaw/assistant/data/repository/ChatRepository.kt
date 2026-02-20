package com.openclaw.assistant.data.repository

import android.content.Context
import com.openclaw.assistant.data.local.AppDatabase
import com.openclaw.assistant.data.local.entity.MessageEntity
import com.openclaw.assistant.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository private constructor(context: Context) {
    private val chatDao = AppDatabase.getDatabase(context).chatDao()

    // Singleton
    companion object {
        @Volatile
        private var INSTANCE: ChatRepository? = null

        fun getInstance(context: Context): ChatRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Sessions
    val allSessions: Flow<List<SessionEntity>> = chatDao.getAllSessions()

    suspend fun createSession(title: String = "New Conversation"): String {
        val id = UUID.randomUUID().toString()
        val session = SessionEntity(id = id, title = title)
        chatDao.insertSession(session)
        return id
    }

    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteSession(sessionId)
    }

    suspend fun getLatestSession(): SessionEntity? {
        return chatDao.getLatestSession()
    }

    // Messages
    fun getMessages(sessionId: String): Flow<List<MessageEntity>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun addMessage(
        sessionId: String,
        text: String,
        isUser: Boolean,
        attachmentUri: String? = null,
        attachmentMimeType: String? = null,
        attachmentFileName: String? = null,
        attachmentBase64: String? = null
    ) {
        val session = chatDao.getSessionById(sessionId)
        if (session == null) {
            chatDao.insertSession(SessionEntity(id = sessionId, title = text.take(20)))
        }

        val message = MessageEntity(
            sessionId = sessionId,
            content = text,
            isUser = isUser,
            attachmentUri = attachmentUri,
            attachmentMimeType = attachmentMimeType,
            attachmentFileName = attachmentFileName,
            attachmentBase64 = attachmentBase64
        )
        chatDao.insertMessage(message)
    }
}
