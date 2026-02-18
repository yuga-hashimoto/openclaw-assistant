package com.openclaw.assistant

import android.app.Application

import com.openclaw.assistant.gateway.GatewayClient

class OpenClawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GatewayClient.getInstance(this)
    }
}
