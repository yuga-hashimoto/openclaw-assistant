package com.openclaw.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService

/**
 * 起動時にホットワードサービスを開始
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed")
            
            val settings = SettingsRepository.getInstance(context)
            
            if (settings.startOnBoot && settings.hotwordEnabled && settings.isConfigured()) {
                Log.d(TAG, "Starting HotwordService on boot")
                HotwordService.start(context)
            }
        }
    }
}
