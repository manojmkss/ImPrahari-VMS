package com.lightnvr.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lightnvr.companion.net.ApiClient
import com.lightnvr.companion.net.AuthExpiredException
import com.lightnvr.companion.net.SecurityMode
import com.lightnvr.companion.security.ModeSync
import com.lightnvr.companion.security.Modes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The security mode. Changing it is for admin accounts (the NVR refuses a
 * viewer); a viewer sees it read-only. Also on the Quick Settings tile and
 * the home-screen widget.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(api: ApiClient, isAdmin: Boolean, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<SecurityMode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var hold by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val m = api.mode()
                mode = m
                hold = m.hold
                ModeSync.publish(context, m)
                error = null
            } catch (e: AuthExpiredException) {
                onSignedOut()
                return@LaunchedEffect
            } catch (e: IOException) {
                error = e.message ?: "Can't reach the NVR"
            }
            delay(20_000)
        }
    }

    fun set(target: String) {
        busy = target
        scope.launch {
            try {
                val m = api.setMode(target, hold)
                mode = m
                ModeSync.publish(context, m)
                error = null
            } catch (e: AuthExpiredException) {
                onSignedOut()
            } catch (e: IOException) {
                error = e.message ?: "Couldn't change the mode"
            } finally {
                busy = null
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Security") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val m = mode
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mode now", style = MaterialTheme.typography.labelLarge)
                    Text(
                        m?.let { Modes.label(it.mode) } ?: "…",
                        style = MaterialTheme.typography.displaySmall,
                        color = if (m != null && Modes.isArmed(m.mode)) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (m != null) {
                        Text(Modes.about(m.mode), style = MaterialTheme.typography.bodyMedium)
                        val by = listOfNotNull(
                            m.setBy?.let { "set by $it" },
                            m.setAt?.let { "at ${fmt(it)}" },
                            if (m.source == "schedule") "by the schedule" else null,
                        ).joinToString(" ")
                        if (by.isNotEmpty()) {
                            Text(by.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        m.nextChangeAt?.let {
                            Text(
                                if (m.hold) "Held - the schedule won't change it" else "Schedule changes it at ${fmt(it)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (isAdmin) {
                Modes.ALL.forEach { target ->
                    val current = m?.mode == target
                    val label = if (busy == target) "Switching…" else Modes.label(target)
                    if (current) {
                        Button(onClick = { set(target) }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                            Text("$label  ✓")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { set(target) },
                            enabled = busy == null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(),
                        ) { Text(label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep until I change it", style = MaterialTheme.typography.bodyLarge)
                        Text("The schedule won't switch it", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = hold, onCheckedChange = { hold = it })
                }
                Text(
                    "Also from Quick Settings (edit the panel → add \"NVR mode\") and the home-screen widget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Only an admin account can change the mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val WHEN = DateTimeFormatter.ofPattern("EEE HH:mm")

private fun fmt(iso: String): String = try {
    WHEN.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
} catch (e: java.time.format.DateTimeParseException) {
    iso
}
