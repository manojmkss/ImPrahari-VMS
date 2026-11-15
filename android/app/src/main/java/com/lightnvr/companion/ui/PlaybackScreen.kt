package com.lightnvr.companion.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lightnvr.companion.media.Feed
import com.lightnvr.companion.media.LivePlayer
import com.lightnvr.companion.media.PlaybackConnection
import com.lightnvr.companion.net.ApiClient
import com.lightnvr.companion.net.AuthExpiredException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val SPEEDS = listOf(1f, 2f, 4f, 8f, 0.5f)
private val WINDOWS = listOf(3600.0, 6 * 3600.0, 24 * 3600.0, 15 * 60.0)

/**
 * Recorded footage of one camera from [startAt] (unix s): a scrub bar of the
 * day's recordings (drag it, or tap a moment), pause, ±30 s, speed, date and
 * time picker. Gaps in the recordings are skipped by themselves.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(
    api: ApiClient,
    cameraId: Int,
    cameraName: String,
    startAt: Double,
    onBack: () -> Unit,
    onLive: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    // The moment on screen (unix s); also where to resume after backgrounding.
    var wall by rememberSaveable { mutableDoubleStateOf(startAt) }
    var playing by rememberSaveable { mutableStateOf(true) }
    var speedIndex by rememberSaveable { mutableIntStateOf(0) }
    var windowIndex by rememberSaveable { mutableIntStateOf(0) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }
    val zoom = rememberZoomState()

    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }

    val player = remember { LivePlayer.createPlayback(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(speedIndex) { player.setPlaybackSpeed(SPEEDS[speedIndex]) }
    LaunchedEffect(playing) { player.playWhenReady = playing }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = error.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val connection = remember(cameraId, started) {
        if (started) PlaybackConnection(api, cameraId, scope) else null
    }
    DisposableEffect(connection) {
        connection?.play(wall)
        onDispose {
            connection?.stop()
            player.stop()
            player.clearMediaItems()
        }
    }
    val feedFlow: StateFlow<Feed?> = remember(connection) { connection?.feed ?: MutableStateFlow(null) }
    val stateFlow: StateFlow<PlaybackConnection.State> =
        remember(connection) { connection?.state ?: MutableStateFlow(PlaybackConnection.State.Connecting) }
    val feed by feedFlow.collectAsStateWithLifecycle()
    val state by stateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(feed) {
        val f = feed
        if (f == null) {
            // Seeking: the old footage goes at once, not after its buffer.
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        playerError = null
        player.setMediaSource(LivePlayer.source(f, cameraId, "playback"))
        player.prepare()
        player.playWhenReady = playing
    }
    LaunchedEffect(state) {
        if (state is PlaybackConnection.State.SignedOut) onSignedOut()
    }

    // The day's recordings, for the scrub bar (reloaded every minute: today's grows).
    var spans by remember { mutableStateOf<List<List<Double>>>(emptyList()) }
    val windowSec = WINDOWS[windowIndex]
    var dragCenter by remember { mutableStateOf<Double?>(null) }
    val center = dragCenter ?: wall
    val loadFrom = TimelineMath.dayStart(center - windowSec / 2, zone)
    val loadTo = TimelineMath.dayStart(center + windowSec / 2, zone) + 86_400
    LaunchedEffect(cameraId, loadFrom, loadTo) {
        while (true) {
            try {
                spans = api.timeline(cameraId, loadFrom, loadTo).spans
            } catch (e: AuthExpiredException) {
                onSignedOut()
                return@LaunchedEffect
            } catch (e: IOException) {
                // keep the last spans; try again next round
            }
            delay(60_000)
        }
    }

    fun seek(to: Double) {
        val target = TimelineMath.snap(spans, to) ?: to
        notice = if (target != to && spans.isNotEmpty()) "No recording at ${fmtTime(to, zone)} - from ${fmtTime(target, zone)}" else null
        wall = target
        playerError = null
        connection?.play(target)
    }

    // Report what's on screen (the NVR paces itself by it) and follow gaps.
    LaunchedEffect(connection) {
        val conn = connection ?: return@LaunchedEffect
        while (true) {
            delay(500)
            val f = feed
            val now = f?.wallAt(player.currentPosition)
            if (now != null && player.playbackState != Player.STATE_IDLE && dragCenter == null) {
                wall = now
                conn.reportPosition(now)
            }
            val s = conn.state.value
            if (s is PlaybackConnection.State.Gap) {
                // Jump once what's buffered has played out (or nothing was playing).
                val drained = player.playbackState == Player.STATE_ENDED || f == null || f.closed && now == null
                if (drained) {
                    if (s.nextAt != null) {
                        notice = "No recording ${fmtTime(s.at, zone)} – ${fmtTime(s.nextAt, zone)}, skipped"
                        wall = s.nextAt
                        conn.play(s.nextAt)
                    } else {
                        notice = "End of the recordings"
                        playing = false
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
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
            TextButton(onClick = { muted = !muted }) { Text(if (muted) "🔇" else "🔊", color = Color.White) }
            TextButton(onClick = onLive) { Text("Live", color = Color.White) }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Pinch to zoom into the recording (it's the main stream: full detail).
            ZoomableBox(state = zoom, modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx -> newPlayerView(ctx, zoomable = true).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val s = state
            val status = when {
                playerError != null -> "Can't play this recording here ($playerError)"
                s is PlaybackConnection.State.Failed -> s.message
                s is PlaybackConnection.State.Retrying -> "Reconnecting… ${s.reason}"
                s is PlaybackConnection.State.Connecting -> "Loading…"
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xB3000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            notice?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                LaunchedEffect(it) {
                    delay(6_000)
                    notice = null
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                fmtDateTime(center, zone),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            ScrubBar(
                spans = spans,
                center = center,
                windowSec = windowSec,
                zone = zone,
                onDrag = { dragCenter = it },
                onRelease = { at -> dragCenter = null; seek(at) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { seek(wall - 30) }) { Text("−30s", color = Color.White) }
                TextButton(onClick = { playing = !playing }) {
                    Text(if (playing) "❚❚ Pause" else "▶ Play", color = Color.White)
                }
                TextButton(onClick = { seek(wall + 30) }) { Text("+30s", color = Color.White) }
                TextButton(onClick = { speedIndex = (speedIndex + 1) % SPEEDS.size }) {
                    val sp = SPEEDS[speedIndex]
                    Text(if (sp < 1f) "½×" else "${sp.toInt()}×", color = Color.White)
                }
                TextButton(onClick = { windowIndex = (windowIndex + 1) % WINDOWS.size }) {
                    Text(windowLabel(windowSec), color = Color.White)
                }
                IconButton(onClick = {
                    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli((wall * 1000).toLong()), zone)
                    DatePickerDialog(context, { _, y, m, d ->
                        TimePickerDialog(context, { _, h, min ->
                            seek(ZonedDateTime.of(y, m + 1, d, h, min, 0, 0, zone).toEpochSecond().toDouble())
                        }, now.hour, now.minute, true).show()
                    }, now.year, now.monthValue - 1, now.dayOfMonth).show()
                }) {
                    Icon(Icons.Default.DateRange, contentDescription = "Go to a date and time", tint = Color.White)
                }
            }
        }
    }
}

/** The scrub bar: footage spans, hour ticks and the moment on screen in the middle. */
@Composable
private fun ScrubBar(
    spans: List<List<Double>>,
    center: Double,
    windowSec: Double,
    zone: ZoneId,
    onDrag: (Double) -> Unit,
    onRelease: (Double) -> Unit,
) {
    val density = LocalDensity.current
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(200, 255, 255, 255)
            textSize = with(density) { 11.dp.toPx() }
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val footage = MaterialTheme.colorScheme.primary
    var dragged by remember { mutableDoubleStateOf(center) }
    val currentCenter by androidx.compose.runtime.rememberUpdatedState(center)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 4.dp)
            .pointerInput(windowSec) {
                detectTapGestures { offset ->
                    onRelease(TimelineMath.wallOf(offset.x, currentCenter, windowSec, size.width.toFloat()))
                }
            }
            .pointerInput(windowSec) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = currentCenter },
                    onDragEnd = { onRelease(dragged) },
                    onDragCancel = { onRelease(dragged) },
                ) { change, dx ->
                    change.consume()
                    // Dragging the tape right goes back in time.
                    dragged -= dx / size.width * windowSec
                    onDrag(dragged)
                }
            },
    ) {
        val w = size.width
        val barTop = 0f
        val barH = size.height * 0.55f
        drawRect(Color(0xFF2A2F36), Offset(0f, barTop), Size(w, barH))
        for (span in spans) {
            val x0 = TimelineMath.xOf(span[0], center, windowSec, w).coerceIn(0f, w)
            val x1 = TimelineMath.xOf(span[1], center, windowSec, w).coerceIn(0f, w)
            if (x1 > x0) drawRect(footage, Offset(x0, barTop), Size(maxOf(1f, x1 - x0), barH))
        }
        val labelY = size.height - 2f
        for (t in TimelineMath.ticks(center, windowSec, zone)) {
            val x = TimelineMath.xOf(t, center, windowSec, w)
            drawLine(Color(0x99FFFFFF), Offset(x, barTop + barH), Offset(x, barTop + barH + 6f))
            drawContext.canvas.nativeCanvas.drawText(fmtTick(t, zone), x, labelY, labelPaint)
        }
        drawLine(Color(0xFFEF4444), Offset(w / 2, 0f), Offset(w / 2, barH + 8f), strokeWidth = 4f)
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
private val TICK = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME = DateTimeFormatter.ofPattern("EEE d MMM  HH:mm:ss")

private fun at(t: Double, zone: ZoneId) = Instant.ofEpochMilli((t * 1000).toLong()).atZone(zone)
internal fun fmtTime(t: Double, zone: ZoneId): String = TIME.format(at(t, zone))
private fun fmtTick(t: Double, zone: ZoneId): String = TICK.format(at(t, zone))
private fun fmtDateTime(t: Double, zone: ZoneId): String = DATE_TIME.format(at(t, zone))
private fun windowLabel(sec: Double): String = if (sec < 3600) "${(sec / 60).toInt()}m" else "${(sec / 3600).toInt()}h"
