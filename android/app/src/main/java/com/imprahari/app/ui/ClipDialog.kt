package com.imprahari.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imprahari.app.media.ClipMath
import java.time.ZoneId

/**
 * Save part of the recording: drag the two handles over the trim bar (green
 * where there's footage), or nudge start and end by 5 s, or set either to the
 * moment playing now. Up to 2 hours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDialog(
    cameraName: String,
    moment: () -> Double,
    spans: List<List<Double>>,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (start: Double, end: Double, h264: Boolean) -> Unit,
) {
    val now = remember { System.currentTimeMillis() / 1000.0 }
    val initial = remember { ClipMath.defaultRange(moment(), now) }
    var start by remember { mutableDoubleStateOf(initial.first) }
    var end by remember { mutableDoubleStateOf(initial.second) }
    var spanIndex by remember { mutableIntStateOf(1) }
    var window by remember { mutableStateOf(ClipMath.window(start, end, ClipMath.SPANS[spanIndex], now)) }
    var h264 by remember { mutableStateOf(false) }

    // Nudges and "Here" may move the clip outside the bar: the bar follows.
    fun set(s: Double, e: Double) {
        start = s
        end = minOf(e, now)
        window = ClipMath.window(start, end, ClipMath.SPANS[spanIndex], now, window)
    }

    val problem = ClipMath.problem(start, end)
    val covered = ClipMath.covered(spans, start, end)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save clip") },
        text = {
            Column {
                Text(cameraName, style = MaterialTheme.typography.bodyMedium)
                val (w0, w1) = window
                val width = (w1 - w0).toFloat()
                val footage = MaterialTheme.colorScheme.primary
                val chosen = MaterialTheme.colorScheme.tertiary
                // Footage in the window, and the chosen part highlighted.
                Canvas(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(10.dp)) {
                    val px = size.width / width
                    drawRect(Color(0xFF2A2F36), Offset.Zero, size)
                    for (s in spans) {
                        if (s.size < 2) continue
                        val x0 = ((s[0] - w0).toFloat() * px).coerceIn(0f, size.width)
                        val x1 = ((s[1] - w0).toFloat() * px).coerceIn(0f, size.width)
                        if (x1 > x0) drawRect(footage.copy(alpha = 0.55f), Offset(x0, 0f), Size(maxOf(1f, x1 - x0), size.height))
                    }
                    val c0 = ((start - w0).toFloat() * px).coerceIn(0f, size.width)
                    val c1 = ((end - w0).toFloat() * px).coerceIn(0f, size.width)
                    drawRect(chosen.copy(alpha = 0.45f), Offset(c0, 0f), Size(maxOf(2f, c1 - c0), size.height))
                }
                // Relative to the window: unix seconds don't fit a Float's precision.
                RangeSlider(
                    value = (start - w0).toFloat().coerceIn(0f, width)..(end - w0).toFloat().coerceIn(0f, width),
                    onValueChange = { r ->
                        start = w0 + r.start
                        end = w0 + r.endInclusive
                    },
                    valueRange = 0f..width,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(w0, zone), style = MaterialTheme.typography.labelSmall)
                    Text(fmtTime(w1, zone), style = MaterialTheme.typography.labelSmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ClipMath.SPANS.forEachIndexed { i, span ->
                        TextButton(onClick = {
                            spanIndex = i
                            window = ClipMath.window(start, end, span, now)
                        }) {
                            Text(
                                if (span < 3600) "${(span / 60).toInt()} min" else "${(span / 3600).toInt()} h",
                                fontWeight = if (i == spanIndex) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Endpoint("Start", start, zone, onNudge = { set(start + it, end) }, onHere = { set(moment(), end) })
                Endpoint("End", end, zone, onNudge = { set(start, end + it) }, onHere = { set(start, moment()) })
                Text(
                    "Length ${ClipMath.length(maxOf(0.0, end - start))}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val warning = problem ?: when {
                    spans.isNotEmpty() && covered <= 0 -> "There's no recording in this range"
                    spans.isNotEmpty() && covered < (end - start) * 0.95 ->
                        "Recorded: ${ClipMath.length(covered)} of it (the gaps are left out)"
                    else -> null
                }
                if (warning != null) {
                    Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Checkbox(checked = h264, onCheckedChange = { h264 = it })
                    Column {
                        Text("Plays on any phone (H.264)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Slower - the NVR converts it. Off: the camera's original, full quality.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onSave(start, end, h264) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Endpoint(label: String, at: Double, zone: ZoneId, onNudge: (Double) -> Unit, onHere: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium)
        Text(fmtTime(at, zone), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        TextButton(onClick = { onNudge(-5.0) }) { Text("−5s") }
        TextButton(onClick = { onNudge(5.0) }) { Text("+5s") }
        TextButton(onClick = onHere) { Text("Here") }
    }
}
