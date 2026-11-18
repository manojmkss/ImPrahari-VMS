package com.imprahari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.imprahari.app.ImPrahariApplication
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.PtzInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** How often a held button re-sends its move (the NVR stops a move by itself
 *  0.8 s after the last one, so a dropped connection never leaves it turning). */
private const val REPEAT_MS = 300L

/**
 * Pan / tilt / lens zoom, presets and home, over the full-screen live view.
 * Each arrow and zoom button moves the camera while held.
 */
@Composable
fun PtzControls(api: ApiClient, cameraId: Int, info: PtzInfo, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // Outlives this screen: the last "stop" must still go out after it's gone.
    val appScope = (LocalContext.current.applicationContext as ImPrahariApplication).appScope
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var moveJob by remember { mutableStateOf<Job?>(null) }
    val speed = 0.5

    fun stopMoving() {
        val job = moveJob ?: return
        moveJob = null
        job.cancel()
        scope.launch {
            try {
                api.ptzStop(cameraId)
            } catch (e: IOException) {
                error = e.message // the NVR stops it by itself anyway
            }
        }
    }

    fun startMoving(pan: Double, tilt: Double, zoom: Double) {
        moveJob?.cancel()
        error = null
        moveJob = scope.launch {
            while (isActive) {
                try {
                    api.ptzMove(cameraId, pan * speed, tilt * speed, zoom * speed)
                } catch (e: IOException) {
                    error = e.message
                    break
                }
                delay(REPEAT_MS)
            }
        }
    }
    // Leaving the screen mid-move stops the camera.
    DisposableEffect(Unit) {
        onDispose {
            if (moveJob != null) {
                moveJob?.cancel()
                appScope.launch { runCatching { api.ptzStop(cameraId) } }
            }
        }
    }

    fun act(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try {
                block()
            } catch (e: IOException) {
                error = e.message
            }
        }
    }

    @Composable
    fun HoldButton(label: String, pan: Double, tilt: Double, zoom: Double, wide: Boolean = false) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = if (wide) 72.dp else 44.dp, height = 44.dp)
                .background(Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(10.dp))
                .pointerInput(pan, tilt, zoom) {
                    detectTapGestures(onPress = {
                        startMoving(pan, tilt, zoom)
                        tryAwaitRelease()
                        stopMoving()
                    })
                },
        ) {
            Text(label, color = Color.White, fontSize = if (wide) 14.sp else 18.sp)
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.panTilt) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoldButton("↖", -1.0, 1.0, 0.0); HoldButton("▲", 0.0, 1.0, 0.0); HoldButton("↗", 1.0, 1.0, 0.0)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoldButton("◀", -1.0, 0.0, 0.0)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(44.dp).pointerInput(info.home) {
                                if (info.home) detectTapGestures(onTap = { act { api.ptzHome(cameraId) } })
                            },
                        ) { if (info.home) Text("⌂", color = Color.White, fontSize = 20.sp) }
                        HoldButton("▶", 1.0, 0.0, 0.0)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoldButton("↙", -1.0, -1.0, 0.0); HoldButton("▼", 0.0, -1.0, 0.0); HoldButton("↘", 1.0, -1.0, 0.0)
                    }
                }
            }
            if (info.zoom) {
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HoldButton("Zoom +", 0.0, 0.0, 1.0, wide = true)
                    Text("lens", color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelSmall)
                    HoldButton("Zoom −", 0.0, 0.0, -1.0, wide = true)
                }
            }
        }
        if (info.presets) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(info.presetsList, key = { it.token }) { p ->
                    // Tap: go there. Long-press: delete.
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(14.dp))
                            .pointerInput(p.token) {
                                detectTapGestures(
                                    onTap = { act { api.ptzGotoPreset(cameraId, p.token) } },
                                    onLongPress = { deleting = p.token },
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text(p.name, color = Color.White, style = MaterialTheme.typography.labelMedium) }
                }
                item {
                    AssistChip(onClick = { naming = true }, label = { Text("+ Save view", color = Color.White) })
                }
            }
        }
        error?.let { Text(it, color = Color(0xFFFFB4A9), style = MaterialTheme.typography.labelSmall) }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save this view") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, singleLine = true,
                                  label = { Text("Name, e.g. Gate") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    act { api.ptzSavePreset(cameraId, name.trim()); onChanged() }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { token ->
        val name = info.presetsList.firstOrNull { it.token == token }?.name ?: "this view"
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$name\"?") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    act { api.ptzRemovePreset(cameraId, token); onChanged() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
