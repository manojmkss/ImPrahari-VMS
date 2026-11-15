package com.lightnvr.companion.push

import com.lightnvr.companion.LightNvrApplication
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.io.IOException

/** Where the distributor delivers: new endpoints (sent on to the NVR) and alerts. */
class PushReceiver : PushService() {
    private val app get() = application as LightNvrApplication

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            // Without the Web Push keys the NVR can't encrypt for us.
            Push.problem(this, "The alert app didn't give encryption keys - update it and try again.")
            return
        }
        val context = applicationContext
        app.appScope.launch {
            try {
                app.api.subscribePush(endpoint.url, keys.pubKey, keys.auth)
                Push.saved(context, endpoint.url)
            } catch (e: IOException) {
                Push.problem(context, e.message ?: "Couldn't register with the NVR")
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return // not from our NVR (or tampered with)
        val alert = AlertPayload.parse(message.content) ?: return
        Alerts.show(applicationContext, app, alert)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Push.problem(
            this,
            when (reason) {
                FailedReason.NETWORK -> "No network - try again when online."
                FailedReason.ACTION_REQUIRED -> "Open your alert app (e.g. ntfy) - it needs attention."
                FailedReason.VAPID_REQUIRED -> "The alert app needs a key the NVR didn't give."
                FailedReason.INTERNAL_ERROR -> "The alert app couldn't register - try again."
            },
        )
    }

    override fun onUnregistered(instance: String) {
        Push.clear(this)
    }
}
