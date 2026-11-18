package com.imprahari.app.net

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Thrown during the TLS handshake when the NVR's certificate is neither
 *  publicly trusted nor the one the user already chose to trust. */
class UntrustedServerCertificate(val sha256: String, val subject: String) :
    CertificateException("Untrusted server certificate $sha256")

/**
 * Trust-on-first-use for ImPrahari's default self-signed certificate.
 *
 * A publicly-trusted certificate (e.g. Let's Encrypt) is accepted the normal
 * way. Anything else is accepted only if its SHA-256 matches the one pin the
 * user explicitly trusted after seeing its fingerprint - never "trust all".
 * The pinned cert also stands in for hostname checks, since a self-signed
 * cert rarely names the IP or Tailscale name it's reached by.
 */
class PinnedTrust(private val pin: () -> String?) {
    private val system: X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }

    /** Last certificate rejected for not being pinned, for the trust prompt. */
    @Volatile
    var lastRejected: UntrustedServerCertificate? = null
        private set

    // Deliberately custom (lint: CustomX509TrustManager): it defers to the
    // system trust store first and only adds one user-approved pin on top.
    @SuppressLint("CustomX509TrustManager")
    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val leaf = chain.firstOrNull() ?: throw e
                val fingerprint = sha256Hex(leaf.encoded)
                if (pin()?.equals(fingerprint, ignoreCase = true) == true) return
                val rejected = UntrustedServerCertificate(fingerprint, leaf.subjectX500Principal.name)
                lastRejected = rejected
                throw rejected
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    val sslSocketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory

    val hostnameVerifier = HostnameVerifier { host, session ->
        if (HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
            true
        } else {
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
            leaf != null && pin()?.equals(sha256Hex(leaf.encoded), ignoreCase = true) == true
        }
    }

    fun clearLastRejected() {
        lastRejected = null
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

        /** "AB12CD..." -> "AB:12:CD:..." for showing to a person. */
        fun pretty(hex: String): String = hex.chunked(2).joinToString(":")
    }
}
