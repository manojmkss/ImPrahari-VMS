package com.lightnvr.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lightnvr.companion.data.SessionStore
import com.lightnvr.companion.net.ApiClient
import com.lightnvr.companion.net.PinnedTrust
import com.lightnvr.companion.net.ProbeResult
import com.lightnvr.companion.net.ServerUrl
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun ConnectScreen(api: ApiClient, store: SessionStore, onSignedIn: () -> Unit) {
    var server by rememberSaveable { mutableStateOf(store.serverUrl ?: "") }
    var username by rememberSaveable { mutableStateOf(store.username ?: "") }
    var password by remember { mutableStateOf("") } // never saved, not even across rotation
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var untrusted by remember { mutableStateOf<ProbeResult.UntrustedCertificate?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        scope.launch {
            busy = true
            error = null
            try {
                val base = ServerUrl.normalize(server)
                if (base == null) {
                    error = "Enter the NVR's address, e.g. https://192.168.1.10:8443"
                    return@launch
                }
                when (val probe = api.probe(base)) {
                    is ProbeResult.Ok -> {
                        api.login(base, username.trim(), password)
                        password = ""
                        onSignedIn()
                    }
                    is ProbeResult.UntrustedCertificate -> untrusted = probe
                    is ProbeResult.Failed -> error = "Can't reach the NVR: ${probe.message}"
                }
            } catch (e: IOException) {
                error = e.message ?: "Sign-in failed"
            } finally {
                busy = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("LightNVR", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Sign in to your NVR",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("NVR address") },
                placeholder = { Text("https://192.168.1.10:8443") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            Button(
                onClick = { connect() },
                enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Connect")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Away from home? Use your NVR's Tailscale address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    untrusted?.let { cert ->
        AlertDialog(
            onDismissRequest = { untrusted = null },
            title = { Text("Trust this NVR's certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your phone doesn't recognise this server's certificate. That's expected " +
                            "for LightNVR's built-in self-signed certificate - but only continue if " +
                            "this is your NVR. The app will trust exactly this certificate from now on."
                    )
                    Text("SHA-256 fingerprint:", style = MaterialTheme.typography.labelMedium)
                    Text(PinnedTrust.pretty(cert.sha256), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.certPinSha256 = cert.sha256
                    untrusted = null
                    connect()
                }) { Text("Trust & connect") }
            },
            dismissButton = { TextButton(onClick = { untrusted = null }) { Text("Cancel") } },
        )
    }
}
