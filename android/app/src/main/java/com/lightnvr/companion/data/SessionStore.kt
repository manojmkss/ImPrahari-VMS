package com.lightnvr.companion.data

import android.content.Context

/**
 * Which NVR we talk to and how we're signed in to it. App-private prefs; the
 * manifest's allowBackup=false keeps the refresh token on this device only.
 * The password is never stored - only the server-issued tokens.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("lightnvr_session", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER, null)
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

    /** SHA-256 (hex) of the NVR's certificate the user chose to trust - set for
     *  LightNVR's default self-signed cert; unused for a publicly-trusted one. */
    var certPinSha256: String?
        get() = prefs.getString(KEY_PIN, null)
        set(value) = prefs.edit().putString(KEY_PIN, value).apply()

    val isSignedIn: Boolean get() = !serverUrl.isNullOrBlank() && !refreshToken.isNullOrBlank()

    fun saveTokens(access: String, refresh: String) {
        prefs.edit().putString(KEY_ACCESS, access).putString(KEY_REFRESH, refresh).apply()
    }

    fun signOut() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_USER = "username"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_PIN = "cert_pin_sha256"
    }
}
