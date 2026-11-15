package com.lightnvr.companion.push

import android.app.Activity
import android.content.Context
import com.lightnvr.companion.net.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.unifiedpush.android.connector.UnifiedPush
import java.io.IOException

/**
 * Alerts on the phone through UnifiedPush - no Google/Firebase account: a
 * "distributor" app on the phone (e.g. ntfy) holds one connection for all its
 * apps and hands this app its alerts. The NVR sends them exactly as it sends
 * browser notifications (Web Push, end-to-end encrypted with this app's keys;
 * the push server in between can't read them) - so it registers through the
 * same /api/push/subscribe the web app uses.
 */
object Push {
    private const val PREFS = "lightnvr_push"
    const val INSTANCE = "default"

    sealed interface Status {
        data object Off : Status
        data object Registering : Status
        data object On : Status
        data class Problem(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _status.value = when {
            prefs.getString("endpoint", null) != null -> Status.On
            prefs.getString("problem", null) != null -> Status.Problem(prefs.getString("problem", null)!!)
            else -> Status.Off
        }
    }

    fun endpoint(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("endpoint", null)

    /** Apps on this phone that can deliver UnifiedPush alerts. */
    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context)

    /**
     * Turn alerts on: pick the phone's distributor (asking the user if there
     * are several) and register with the NVR's VAPID key. The endpoint
     * arrives later, in [PushReceiver.onNewEndpoint].
     */
    suspend fun enable(activity: Activity, api: ApiClient) {
        if (distributors(activity).isEmpty()) {
            problem(activity, "Install a UnifiedPush app such as ntfy first - it delivers the alerts.")
            return
        }
        val vapid = try {
            api.vapidPublicKey()
        } catch (e: IOException) {
            problem(activity, e.message ?: "Can't reach the NVR")
            return
        }
        if (vapid == null) {
            problem(activity, "The NVR has no push key yet - restart it once, then try again.")
            return
        }
        _status.value = Status.Registering
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) {
                UnifiedPush.register(activity, INSTANCE, "LightNVR alerts", vapid)
            } else {
                problem(activity, "No alert app was chosen.")
            }
        }
    }

    /** Turn alerts off: tell the NVR to stop sending, and the distributor to forget us. */
    suspend fun disable(context: Context, api: ApiClient) {
        endpoint(context)?.let { ep ->
            try {
                api.unsubscribePush(ep)
            } catch (e: IOException) {
                // the NVR drops it by itself once the push server reports it gone
            }
        }
        UnifiedPush.unregister(context, INSTANCE)
        clear(context)
    }

    internal fun saved(context: Context, endpoint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", endpoint).remove("problem").apply()
        _status.value = Status.On
    }

    internal fun problem(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("endpoint").putString("problem", message).apply()
        _status.value = Status.Problem(message)
    }

    internal fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _status.value = Status.Off
    }
}
