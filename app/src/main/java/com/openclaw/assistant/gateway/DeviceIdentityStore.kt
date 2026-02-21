package com.openclaw.assistant.gateway

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class DeviceIdentityStore(context: Context) {
  private val appContext = context.applicationContext

  @Volatile
  private var cached: DeviceIdentity? = null

  @Synchronized
  fun loadOrCreate(): DeviceIdentity {
    val existing = cached
    if (existing != null) return existing
    return DeviceIdentity(appContext).also { cached = it }
  }

  fun signPayload(payload: String, identity: DeviceIdentity): String? {
    return identity.sign(payload)
  }

  fun publicKeyBase64Url(identity: DeviceIdentity): String? {
    return identity.publicKeyBase64Url
  }

  fun verifySelfSignature(payload: String, signatureBase64Url: String, identity: DeviceIdentity): Boolean {
    return try {
      val publicKeyUrl = identity.publicKeyBase64Url ?: return false
      val publicKey = Base64.decode(publicKeyUrl, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      val signature = Base64.decode(signatureBase64Url, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      val verifier = Ed25519Signer()
      verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
      val bytes = payload.toByteArray(Charsets.UTF_8)
      verifier.update(bytes, 0, bytes.size)
      verifier.verifySignature(signature)
    } catch (_: Throwable) {
      false
    }
  }
}
