package com.openclaw.assistant.utils

import android.util.Log
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Utility for TLS certificate fingerprint pinning.
 */
object SslUtils {
    private const val TAG = "SslUtils"

    /**
     * Compute SHA-256 fingerprint of a certificate.
     * Returns lowercase hex string without colons.
     */
    fun computeSha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Normalize fingerprint string (remove colons, lowercase).
     */
    fun normalizeFingerprint(fingerprint: String): String {
        return fingerprint.replace(":", "").lowercase().trim()
    }

    /**
     * Create a pinned OkHttpClient based on an existing one.
     */
    fun createPinnedClient(baseClient: OkHttpClient, fingerprint: String): OkHttpClient {
        val trustManager = FingerprintTrustManager(fingerprint)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())

        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // Trust the fingerprint regardless of hostname
            .build()
    }

    /**
     * Custom TrustManager that validates the server certificate against a specific SHA-256 fingerprint.
     */
    class FingerprintTrustManager(private val expectedFingerprint: String) : X509TrustManager {
        private val normalizedExpected = normalizeFingerprint(expectedFingerprint)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // No-op for client auth
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("Empty certificate chain")
            }

            val serverCert = chain[0]
            val actualFingerprint = computeSha256Fingerprint(serverCert)

            if (actualFingerprint != normalizedExpected) {
                Log.e(TAG, "Fingerprint mismatch! Expected: $normalizedExpected, Got: $actualFingerprint")
                throw CertificateException("Fingerprint mismatch")
            }

            Log.d(TAG, "Fingerprint matched: $actualFingerprint")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
