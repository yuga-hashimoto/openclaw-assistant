package com.openclaw.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.telnyx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Service for managing phone calls via Telnyx.
 * 
 * Handles:
 * - Outbound call initiation
 * - Call state management
 * - Integration with OpenClaw session for AI-powered conversations
 */
class PhoneCallService(private val context: Context) {

    companion object {
        private const val TAG = "PhoneCallService"
        private const val NOTIFICATION_CHANNEL_ID = "phone_call_channel"
        private const val NOTIFICATION_ID = 2001
        
        const val ACTION_MAKE_CALL = "com.openclaw.assistant.MAKE_CALL"
        const val ACTION_HANGUP = "com.openclaw.assistant.HANGUP"
        const val EXTRA_PHONE_NUMBER = "phone_number"
    }

    private val settings = SettingsRepository.getInstance(context)
    private val telnyxClient = TelnyxClient.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _uiState = MutableStateFlow<PhoneCallUiState>(PhoneCallUiState.Idle)
    val uiState: StateFlow<PhoneCallUiState> = _uiState.asStateFlow()

    init {
        // Observe call state changes
        scope.launch {
            telnyxClient.callState.collect { state ->
                updateUiState(state)
            }
        }
        
        // Observe call events
        scope.launch {
            telnyxClient.callEvents.collect { event ->
                handleCallEvent(event)
            }
        }
    }

    /**
     * Initialize Telnyx client with stored credentials.
     */
    fun initialize() {
        val apiKey = settings.telnyxApiKey
        val connectionId = settings.telnyxConnectionId
        val callerId = settings.telnyxCallerId
        
        if (apiKey.isNotBlank() && connectionId.isNotBlank()) {
            telnyxClient.configure(apiKey, connectionId, callerId)
            Log.d(TAG, "Telnyx client initialized")
        } else {
            Log.d(TAG, "Telnyx not configured")
        }
    }

    /**
     * Make an outbound phone call.
     */
    suspend fun makeCall(phoneNumber: String): Result<String> {
        if (!telnyxClient.isConfigured()) {
            return Result.failure(IllegalStateException("Telnyx not configured. Add API key in settings."))
        }

        // Show notification
        showCallNotification("Calling $phoneNumber...")
        
        _uiState.value = PhoneCallUiState.Connecting(phoneNumber)
        
        val result = telnyxClient.makeCall(phoneNumber)
        
        if (result.isFailure) {
            _uiState.value = PhoneCallUiState.Error(result.exceptionOrNull()?.message ?: "Call failed")
            hideCallNotification()
        }
        
        return result
    }

    /**
     * Hang up the current call.
     */
    suspend fun hangup(): Result<Unit> {
        val result = telnyxClient.hangup()
        hideCallNotification()
        _uiState.value = PhoneCallUiState.Idle
        return result
    }

    /**
     * Send DTMF tones during a call.
     */
    suspend fun sendDtmf(digits: String): Result<Unit> {
        return telnyxClient.sendDtmf(digits)
    }

    /**
     * Transfer the current call to another number.
     */
    suspend fun transfer(destination: String): Result<Unit> {
        return telnyxClient.transferCall(destination)
    }

    private fun updateUiState(state: CallState) {
        _uiState.value = when (state) {
            is CallState.Idle -> PhoneCallUiState.Idle
            is CallState.Connecting -> PhoneCallUiState.Connecting(
                telnyxClient.currentCall.value?.destination ?: ""
            )
            is CallState.Ringing -> PhoneCallUiState.Ringing(
                telnyxClient.currentCall.value?.destination ?: ""
            )
            is CallState.Active -> PhoneCallUiState.Active(
                telnyxClient.currentCall.value ?: return
            )
            is CallState.OnHold -> PhoneCallUiState.OnHold
            is CallState.Error -> PhoneCallUiState.Error(state.message)
        }
        
        // Update notification based on state
        when (state) {
            is CallState.Active -> {
                val call = telnyxClient.currentCall.value
                if (call != null) {
                    showCallNotification("Call with ${call.destination}")
                }
            }
            else -> {}
        }
    }

    private fun handleCallEvent(event: CallEvent) {
        when (event) {
            is CallEvent.Answered -> {
                Log.d(TAG, "Call answered: ${event.callLegId}")
            }
            is CallEvent.Hangup -> {
                Log.d(TAG, "Call ended: ${event.callLegId}")
                hideCallNotification()
            }
            is CallEvent.Error -> {
                Log.e(TAG, "Call error: ${event.message}")
            }
            else -> {}
        }
    }

    private fun showCallNotification(title: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Phone Calls",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val hangupIntent = Intent(context, PhoneCallReceiver::class.java).apply {
            action = ACTION_HANGUP
        }
        val hangupPendingIntent = PendingIntent.getBroadcast(
            context, 0, hangupIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_phone)
            .setOngoing(true)
            .addAction(R.drawable.ic_call_end, "Hang Up", hangupPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun hideCallNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun cleanup() {
        scope.cancel()
        telnyxClient.cleanup()
    }
}

// --- UI State ---

sealed class PhoneCallUiState {
    object Idle : PhoneCallUiState()
    data class Connecting(val destination: String) : PhoneCallUiState()
    data class Ringing(val destination: String) : PhoneCallUiState()
    data class Active(val callInfo: CallInfo) : PhoneCallUiState()
    object OnHold : PhoneCallUiState()
    data class Error(val message: String) : PhoneCallUiState()
}

/**
 * BroadcastReceiver for handling notification actions.
 */
class PhoneCallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HANGUP -> {
                CoroutineScope(Dispatchers.IO).launch {
                    TelnyxClient.getInstance().hangup()
                }
            }
            ACTION_MAKE_CALL -> {
                val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
                if (!phoneNumber.isNullOrBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        TelnyxClient.getInstance().makeCall(phoneNumber)
                    }
                }
            }
        }
    }
}
