package com.imprahari.app.security

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.imprahari.app.MainActivity
import com.imprahari.app.R
import com.imprahari.app.net.ApiException
import com.imprahari.app.net.AuthExpiredException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Home-screen widget: the security mode, with one button per mode. A tap on
 * a button changes it (admin accounts); a tap on the title opens the app.
 */
class ModeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, ModeSync.lastKnown(context), note = null)
        val app = ModeSync.app(context)
        if (!app.store.isSignedIn) {
            render(context, null, note = "Sign in in the app")
            return
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                val mode = app.api.mode()
                ModeSync.publish(context, mode)
            } catch (e: IOException) {
                render(context, ModeSync.lastKnown(context), note = "Can't reach the NVR")
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_MODE) {
            super.onReceive(context, intent)
            return
        }
        val target = intent.getStringExtra(EXTRA_MODE)?.takeIf { it in Modes.ALL } ?: return
        val app = ModeSync.app(context)
        render(context, ModeSync.lastKnown(context), note = "Switching to ${Modes.label(target)}…")
        val pending = goAsync()
        app.appScope.launch {
            try {
                ModeSync.publish(context, app.api.setMode(target, hold = false))
            } catch (e: AuthExpiredException) {
                render(context, ModeSync.lastKnown(context), note = "Sign in again in the app")
            } catch (e: ApiException) {
                render(context, ModeSync.lastKnown(context),
                       note = if (e.code == 403) "Only an admin can change the mode" else e.message)
            } catch (e: IOException) {
                render(context, ModeSync.lastKnown(context), note = "Can't reach the NVR")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SET_MODE = "com.imprahari.app.SET_MODE"
        const val EXTRA_MODE = "mode"

        private val BUTTONS = mapOf(
            "home" to R.id.widget_home,
            "away" to R.id.widget_away,
            "night" to R.id.widget_night,
            "disarmed" to R.id.widget_off,
        )

        fun updateAll(context: Context) = render(context, ModeSync.lastKnown(context), note = null)

        private fun render(context: Context, mode: String?, note: String?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ModeWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_mode)
            views.setTextViewText(R.id.widget_title, if (mode == null) "ImPrahari" else "ImPrahari · ${Modes.label(mode)}")
            views.setTextViewText(
                R.id.widget_note,
                note ?: when {
                    mode == null -> "Mode unknown"
                    Modes.isArmed(mode) -> "Armed"
                    else -> "Not armed"
                },
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_title, open)
            for ((m, id) in BUTTONS) {
                val intent = Intent(context, ModeWidget::class.java).setAction(ACTION_SET_MODE).putExtra(EXTRA_MODE, m)
                val pi = PendingIntent.getBroadcast(
                    context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                views.setOnClickPendingIntent(id, pi)
                // The current mode's button stands out.
                views.setInt(id, "setBackgroundResource",
                             if (m == mode) R.drawable.widget_button_on else R.drawable.widget_button)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
