package com.imprahari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.AuthExpiredException
import com.imprahari.app.net.PtzInfo
import java.io.IOException

/** Zooming past this switches to the main stream (the sub stream is too
 *  small to zoom into); back to 1× and the viewer's own choice returns. */
const val AUTO_HD_ABOVE = 1.5f

/**
 * One camera full screen. SD = the camera's sub stream (light on data), HD =
 * its main stream - which is also the one carrying the camera's sound, so
 * turning sound on switches to HD. Pinch to zoom (HD by itself while
 * zoomed); a camera with motors gets PTZ controls (admin accounts).
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
    val zoom = rememberZoomState()

    // Zoomed in: HD by itself (kept until back at 1x, so it doesn't flap).
    var autoHd by remember { mutableStateOf(false) }
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.scale }.collect { scale ->
            if (scale > AUTO_HD_ABOVE) autoHd = true else if (scale <= 1.01f) autoHd = false
        }
    }
    val effective = if (autoHd || sound) "main" else quality

    // PTZ: only for a camera with motors, and an account allowed to turn it.
    var ptz by remember { mutableStateOf<PtzInfo?>(null) }
    var ptzOpen by rememberSaveable { mutableStateOf(false) }
    var ptzReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(cameraId, ptzReload) {
        ptz = try {
            api.ptz(cameraId)?.takeIf { it.supported }
        } catch (e: AuthExpiredException) {
            onSignedOut()
            null
        } catch (e: IOException) {
            null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ZoomableBox(state = zoom, modifier = Modifier.fillMaxSize()) {
            LiveVideo(
                api = api,
                cameraId = cameraId,
                quality = effective,
                muted = !sound,
                onSignedOut = onSignedOut,
                modifier = Modifier.fillMaxSize(),
                zoomable = true,
                onLive = { live = it },
            )
        }

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
                TextButton(onClick = { sound = !sound }) {
                    Text(if (sound) "🔊" else "🔇", color = Color.White)
                }
            }
            TextButton(onClick = { quality = if (quality == "sub") "main" else "sub" }) {
                Text(
                    when {
                        autoHd && quality == "sub" -> "HD·auto"
                        effective == "main" -> "HD"
                        else -> "SD"
                    },
                    color = Color.White,
                )
            }
            if (ptz != null) {
                TextButton(onClick = { ptzOpen = !ptzOpen }) {
                    Text(if (ptzOpen) "PTZ ▾" else "PTZ", color = if (ptzOpen) Color(0xFF7FB3FF) else Color.White)
                }
            }
            TextButton(onClick = onPlayback) { Text("Playback", color = Color.White) }
        }

        val info = ptz
        if (ptzOpen && info != null) {
            PtzControls(
                api = api,
                cameraId = cameraId,
                info = info,
                onChanged = { ptzReload++ },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            )
        }
    }
}
