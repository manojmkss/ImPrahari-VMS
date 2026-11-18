package com.imprahari.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imprahari.app.data.SessionStore
import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.Me
import com.imprahari.app.push.Alerts
import com.imprahari.app.push.Push
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    api: ApiClient,
    store: SessionStore,
    me: Me?,
    onWebSettings: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val push by Push.status.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Push.load(context) }

    val turnOn: () -> Unit = {
        note = null
        val activity = context.findActivity()
        if (activity != null) scope.launch { Push.enable(activity, api) }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) turnOn() else note = "Notifications are off for ImPrahari - allow them in Android settings."
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NVR", style = MaterialTheme.typography.labelLarge)
                    Text(store.serverUrl.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(me?.username ?: store.username, me?.role).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alerts on this phone", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Delivered through a UnifiedPush app such as ntfy (no Google account). The NVR encrypts " +
                            "each alert for this phone; which events alert follows your rules in web settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (val s = push) {
                        Push.Status.On -> Text("On", color = StatusOnline)
                        Push.Status.Registering -> Text("Registering…")
                        Push.Status.Off -> Text("Off", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is Push.Status.Problem -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (push == Push.Status.On) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    note = try {
                                        api.testPush().message
                                    } catch (e: IOException) {
                                        e.message
                                    }
                                }
                            }) { Text("Send a test") }
                            TextButton(onClick = { scope.launch { Push.disable(context, api) } }) { Text("Turn off") }
                        } else {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Alerts.canNotify(context)) {
                                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    turnOn()
                                }
                            }, enabled = push != Push.Status.Registering) { Text("Turn on alerts") }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everything else", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cameras, recording, AI, alert rules, people and users - the NVR's own settings pages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onWebSettings) { Text("Open web settings") }
                }
            }

            TextButton(onClick = {
                scope.launch {
                    // This phone should stop getting alerts for the account it leaves.
                    if (Push.endpoint(context) != null) Push.disable(context, api)
                    store.signOut()
                    onSignedOut()
                }
            }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
