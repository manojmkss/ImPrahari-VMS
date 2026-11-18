package com.imprahari.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.imprahari.app.push.Alerts
import com.imprahari.app.ui.DeepLink
import com.imprahari.app.ui.ImPrahariNav
import com.imprahari.app.ui.ImPrahariTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val deepLinks = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ImPrahariApplication
        if (savedInstanceState == null) handle(intent)
        setContent {
            ImPrahariTheme {
                ImPrahariNav(api = app.api, store = app.store, deepLinks = deepLinks)
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
