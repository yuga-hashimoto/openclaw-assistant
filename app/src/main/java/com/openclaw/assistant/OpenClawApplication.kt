package com.openclaw.assistant

import android.app.Application
import android.util.Log
import com.openclaw.assistant.gateway.GatewayClient
import java.security.Security

class OpenClawApplication : Application() {

    val nodeRuntime: com.openclaw.assistant.node.NodeRuntime by lazy {
        com.openclaw.assistant.node.NodeRuntime(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Register Bouncy Castle as highest-priority provider for Ed25519 support
        try {
            val bcProvider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getDeclaredConstructor().newInstance() as java.security.Provider
            Security.removeProvider("BC")
            Security.insertProviderAt(bcProvider, 1)
        } catch (e: Throwable) {
            Log.e("OpenClawApp", "Failed to register Bouncy Castle provider", e)
        }
        GatewayClient.getInstance(this)
    }
}
