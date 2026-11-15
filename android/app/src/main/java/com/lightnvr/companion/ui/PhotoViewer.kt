package com.lightnvr.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightnvr.companion.net.ApiClient

/**
 * A picture from the NVR, full screen: pinch to zoom into a face or a number
 * plate (main-stream pictures have the detail), drag to look around,
 * double-tap to zoom in / out.
 */
@Composable
fun PhotoViewer(api: ApiClient, path: String, title: String?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val zoom = rememberZoomState()
        // Full resolution (capped): zooming is the point.
        val image = rememberRemoteImage(api, path, maxWidth = 2560)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (image == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                ZoomableBox(state = zoom, modifier = Modifier.fillMaxSize()) {
                    Image(image, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color(0x66000000), MaterialTheme.shapes.small)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            title?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .background(Color(0x66000000), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
