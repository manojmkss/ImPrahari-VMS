package com.lightnvr.companion.security

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.lightnvr.companion.LightNvrApplication
import com.lightnvr.companion.net.SecurityMode

/**
 * The last known security mode, shared by the app, the Quick Settings tile
 * and the home-screen widget - so each shows the same thing, and a change in
 * one updates the others.
 */
object ModeSync {
    private const val PREFS = "lightnvr_mode"

    fun lastKnown(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", null)

    /** Remember [mode] and refresh the tile and widgets. */
    fun publish(context: Context, mode: SecurityMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode.mode).apply()
        refreshSurfaces(context)
    }

    fun refreshSurfaces(context: Context) {
        TileService.requestListeningState(context, ComponentName(context, ModeTileService::class.java))
        ModeWidget.updateAll(context)
    }

    fun app(context: Context) = context.applicationContext as LightNvrApplication
}
