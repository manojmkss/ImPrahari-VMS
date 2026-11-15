package com.lightnvr.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lightnvr.companion.media.Feed
import com.lightnvr.companion.media.LiveConnection
import com.lightnvr.companion.media.LivePlayer
import com.lightnvr.companion.net.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One camera's live picture: its own websocket + player, running only while
 * on screen (leaving the screen, scrolling the tile away or backgrounding
 * the app closes the socket - the NVR stops sending, nothing decodes).
 *
 * Until the first frame, and if the phone can't decode it (e.g. it has run
 * out of hardware decoders in a big grid), the camera's latest snapshot is
 * shown instead, refreshed every few seconds.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LiveVideo(
    api: ApiClient,
    cameraId: Int,
    quality: String,
    muted: Boolean,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onLive: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restartKey by remember { mutableIntStateOf(0) }

    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }

    val player = remember { LivePlayer.create(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    var firstFrame by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(firstFrame) { onLive(firstFrame) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = error.errorCodeName
                firstFrame = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // A player error stops streaming (snapshots instead) until "Try again".
    val playable = playerError == null
    val connection = remember(cameraId, quality, started, restartKey, playable) {
        if (started && playable) LiveConnection(api, cameraId, quality, scope) else null
    }
    DisposableEffect(connection) {
        connection?.start()
        onDispose {
            connection?.stop()
            player.stop()
            player.clearMediaItems()
            firstFrame = false
        }
    }

    val feedFlow: StateFlow<Feed?> = remember(connection) { connection?.feed ?: MutableStateFlow(null) }
    val stateFlow: StateFlow<LiveConnection.State> =
        remember(connection) { connection?.state ?: MutableStateFlow(LiveConnection.State.Connecting) }
    val feed by feedFlow.collectAsStateWithLifecycle()
    val state by stateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(feed) {
        val f = feed ?: return@LaunchedEffect
        firstFrame = false
        player.setMediaSource(LivePlayer.source(f, cameraId))
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(state) {
        if (state is LiveConnection.State.SignedOut) onSignedOut()
    }

    // Stay close to real time: if more than ~1.5 s piles up after a network
    // hiccup, play slightly fast until it drains.
    LaunchedEffect(player) {
        while (true) {
            delay(1_000)
            val ahead = player.bufferedPosition - player.currentPosition
            val current = player.playbackParameters.speed
            val target = when {
                ahead > 1_500 -> 1.15f
                ahead < 500 -> 1f
                else -> current
            }
            if (target != current) player.setPlaybackSpeed(target)
        }
    }

    // The placeholder snapshot: refreshed while there's no live picture.
    var snapshotVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(firstFrame, playerError) {
        while (!firstFrame) {
            delay(if (playerError != null) 5_000 else 10_000)
            snapshotVersion++
        }
    }
    val snapshot = rememberRemoteImage(
        api, "/api/cameras/$cameraId/snapshot.jpg", snapshotVersion, maxWidth = if (compact) 640 else 1280,
    )

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrame) {
            snapshot?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            val err = playerError
            val s = state
            val message = when {
                err != null -> if (compact) "Snapshot" else "Can't play this stream here ($err) - showing snapshots"
                s is LiveConnection.State.Retrying -> if (compact) "Reconnecting…" else "Reconnecting… ${s.reason}"
                else -> "Connecting…"
            }
            Column(
                modifier = Modifier
                    .align(if (compact) Alignment.BottomStart else Alignment.Center)
                    .padding(6.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    message,
                    color = Color.White,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                )
                if (err != null && !compact) {
                    TextButton(onClick = { playerError = null; restartKey++ }) { Text("Try live again") }
                }
            }
        }
    }
}
