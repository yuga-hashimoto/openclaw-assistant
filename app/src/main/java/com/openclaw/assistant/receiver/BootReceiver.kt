package com.openclaw.assistant.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.HotwordService

/**
 * Start hotword service on boot
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed")
            
            val settings = SettingsRepository.getInstance(context)
            
            if (settings.hotwordEnabled && settings.isConfigured()) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Starting HotwordService on boot")
                    HotwordService.start(context)
                } else {
                    Log.w(TAG, "RECORD_AUDIO not granted, skipping HotwordService on boot")
                }
            }
        }
    }
}
