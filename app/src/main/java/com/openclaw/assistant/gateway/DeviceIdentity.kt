package com.openclaw.assistant.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.signature.SignatureConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class DeviceIdentity(context: Context) {

    companion object {
        private const val TAG = "DeviceIdentity"
        private const val PREF_FILE_NAME = "device_identity_prefs"
        private const val KEYSET_NAME = "device_identity_keyset_v2"
        private const val MASTER_KEY_URI = "android-keystore://device_identity_master_key"
    }

    private var signer: PublicKeySign? = null
    var deviceId: String? = null
        private set
    var publicKeyBase64Url: String? = null
        private set

    init {
        try {
            SignatureConfig.register()

            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("ED25519WithRawOutput"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()

            val handle = manager.keysetHandle
            signer = handle.getPrimitive(PublicKeySign::class.java)

            extractPublicKey(handle)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}")
        }
    }

    private fun extractPublicKey(handle: KeysetHandle) {
        try {
            val publicHandle = handle.getPublicKeysetHandle()
            val outputStream = ByteArrayOutputStream()
            // Using CleartextKeysetHandle to export public key is safe and necessary here
            // because we need the raw key bytes for the protocol, not just a Tink primitive.
            CleartextKeysetHandle.write(
                publicHandle, 
                JsonKeysetWriter.withOutputStream(outputStream)
            )
            
            val jsonStr = outputStream.toString("UTF-8")
            val json = JSONObject(jsonStr)
            val keys = json.getJSONArray("key")
            
            if (keys.length() > 0) {
                // Find the primary key if possible, or just take the first one
                val primaryKeyId = json.optLong("primaryKeyId")
                var keyObj: JSONObject? = null
                
                for (i in 0 until keys.length()) {
                    val k = keys.getJSONObject(i)
                    if (k.optLong("keyId") == primaryKeyId) {
                        keyObj = k
                        break
                    }
                }
                if (keyObj == null) keyObj = keys.getJSONObject(0)

                val keyData = keyObj!!.getJSONObject("keyData")
                val typeUrl = keyData.getString("typeUrl")
                
                if (typeUrl.endsWith("Ed25519PublicKey")) {
                    val valBase64 = keyData.getString("value")
                    val protoBytes = Base64.decode(valBase64, Base64.DEFAULT)
                    
                    // Ed25519PublicKey proto: [08 00 12 20 <32 bytes>]
                    // We need the last 32 bytes
                    if (protoBytes.size >= 32) {
                         val rawKey = protoBytes.copyOfRange(protoBytes.size - 32, protoBytes.size)
                         
                         // Base64Url encode without padding for transport
                         publicKeyBase64Url = Base64.encodeToString(
                             rawKey, 
                             Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                         )
                         
                         // Device ID is SHA-256 of raw public key
                         val digest = MessageDigest.getInstance("SHA-256")
                         val hash = digest.digest(rawKey)
                         deviceId = bytesToHex(hash)
                         
                         Log.d(TAG, "Initialized deviceId=$deviceId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract public key: ${e.message}")
        }
    }

    fun sign(data: String): String? {
        return try {
            val signature = signer?.sign(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(
                signature, 
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sign failed: ${e.message}")
            null
        }
    }
    
    fun buildAuthPayload(
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String?,
        nonce: String?
    ): String {
        val version = if (nonce != null) "v2" else "v1"
        val scopesStr = scopes.joinToString(",")
        val parts = mutableListOf(
            version,
            deviceId ?: "",
            clientId,
            clientMode,
            role,
            scopesStr,
            signedAtMs.toString(),
            token ?: ""
        )
        if (version == "v2") {
            parts.add(nonce ?: "")
        }
        return parts.joinToString("|")
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
