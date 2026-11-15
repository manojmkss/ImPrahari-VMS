package com.lightnvr.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lightnvr.companion.push.Alerts
import com.lightnvr.companion.ui.DeepLink
import com.lightnvr.companion.ui.LightNvrNav
import com.lightnvr.companion.ui.LightNvrTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val deepLinks = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LightNvrApplication
        if (savedInstanceState == null) handle(intent)
        setContent {
            LightNvrTheme {
                LightNvrNav(api = app.api, store = app.store, deepLinks = deepLinks)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A tapped alert carries its camera and time. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val camera = intent.getIntExtra(Alerts.EXTRA_CAMERA, -1)
        val at = intent.getDoubleExtra(Alerts.EXTRA_AT, -1.0)
        if (camera > 0 && at > 0) deepLinks.value = DeepLink(camera, at)
    }
}
