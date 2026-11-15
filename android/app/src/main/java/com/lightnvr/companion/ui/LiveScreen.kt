package com.lightnvr.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightnvr.companion.net.ApiClient

/**
 * One camera full screen. SD = the camera's sub stream (light on data), HD =
 * its main stream - which is also the one carrying the camera's sound, so
 * turning sound on switches to HD.
 */
@Composable
fun LiveScreen(
    api: ApiClient,
    cameraId: Int,
    cameraName: String,
    hasAudio: Boolean,
    onBack: () -> Unit,
    onPlayback: () -> Unit,
    onSignedOut: () -> Unit,
) {
    var quality by rememberSaveable { mutableStateOf("sub") }
    var sound by rememberSaveable { mutableStateOf(false) }
    var live by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LiveVideo(
            api = api,
            cameraId = cameraId,
            quality = quality,
            muted = !sound,
            onSignedOut = onSignedOut,
            modifier = Modifier.fillMaxSize(),
            onLive = { live = it },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                cameraName,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (live) {
                Text(
                    "● LIVE",
                    color = Color(0xFFEF4444),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            if (hasAudio) {
                TextButton(onClick = {
                    sound = !sound
                    if (sound) quality = "main" // the camera's sound is on its main stream
                }) {
                    Text(if (sound) "🔊" else "🔇", color = Color.White)
                }
            }
            TextButton(onClick = {
                quality = if (quality == "sub") "main" else "sub"
                if (quality == "sub") sound = false
            }) {
                Text(if (quality == "sub") "SD" else "HD", color = Color.White)
            }
            TextButton(onClick = onPlayback) { Text("Playback", color = Color.White) }
        }
    }
}
