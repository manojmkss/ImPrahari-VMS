package com.lightnvr.companion.security

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.lightnvr.companion.R
import com.lightnvr.companion.net.ApiException
import com.lightnvr.companion.net.AuthExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Quick Settings tile: shows the NVR's security mode, tap to arm (Away) or,
 * when armed, back to Home. Needs an admin account, like the web app.
 * On a locked phone it asks to unlock first.
 */
class ModeTileService : TileService() {
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        listening = scope
        show(ModeSync.lastKnown(this), busy = false)
        val app = ModeSync.app(this)
        if (!app.store.isSignedIn) {
            show(null, busy = false, note = "Sign in in the app")
            return
        }
        scope.launch {
            try {
                val mode = app.api.mode()
                ModeSync.publish(this@ModeTileService, mode)
                show(mode.mode, busy = false)
            } catch (e: IOException) {
                show(ModeSync.lastKnown(this@ModeTileService), busy = false, note = "Can't reach the NVR")
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        val app = ModeSync.app(this)
        if (!app.store.isSignedIn) {
            Toast.makeText(this, "Sign in to LightNVR first", Toast.LENGTH_SHORT).show()
            return
        }
        val target = Modes.toggled(ModeSync.lastKnown(this) ?: "home")
        show(target, busy = true)
        val context = applicationContext
        // The app's scope: the change goes through even if the panel closes.
        app.appScope.launch {
            val message = try {
                val mode = app.api.setMode(target, hold = false)
                ModeSync.publish(context, mode)
                "Security mode: ${Modes.label(mode.mode)}"
            } catch (e: AuthExpiredException) {
                "Sign in to LightNVR again"
            } catch (e: ApiException) {
                if (e.code == 403) "Only an admin account can change the mode" else e.message
            } catch (e: IOException) {
                "Can't reach the NVR"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            ModeSync.refreshSurfaces(context)
        }
    }

    private fun show(mode: String?, busy: Boolean, note: String? = null) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        tile.label = if (mode == null) "LightNVR" else "NVR: ${Modes.label(mode)}"
        tile.state = when {
            mode == null || busy -> Tile.STATE_UNAVAILABLE
            Modes.isArmed(mode) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                busy -> "Switching…"
                note != null -> note
                mode == null -> null
                Modes.isArmed(mode) -> "Armed"
                else -> "Tap to arm (Away)"
            }
        }
        tile.updateTile()
    }
}
