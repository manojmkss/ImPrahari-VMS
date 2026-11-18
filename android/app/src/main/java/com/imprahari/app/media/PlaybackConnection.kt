package com.imprahari.app.media

import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.AuthExpiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException

/**
 * Recorded footage of one camera over the media engine's websocket - the
 * protocol the web app's MediaPlayer speaks:
 *
 *  -> {"op":"play","camera":N,"at":<unix s>,"audio":true}
 *  <- {"type":"init","reset","origin","offset",...} + binary init, then fragments
 *  -> {"op":"pos","wall":<unix s on screen>} about every second: the NVR stays
 *     at most ~20 s ahead of it, so a paused viewer costs nothing
 *  <- {"type":"gap","at","next_at"}: no footage from `at` (the job ends)
 *
 * One "play" is a session: the NVR goes from file to file by itself (see
 * [PlaybackProtocol] for how files continue one stream).
 *
 * Seeking opens a fresh socket: the protocol doesn't say which "play" a
 * message answers, so a gap from the previous position arriving just after a
 * seek could otherwise send the viewer somewhere they didn't ask for.
 *
 * If the connection drops, it reconnects and carries on from the last
 * position reported on screen.
 */
class PlaybackConnection(
    private val api: ApiClient,
    private val cameraId: Int,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Connecting : State
        data object Playing : State
        /** Footage stops at [at]; more from [nextAt] (null = nothing later yet). */
        data class Gap(val at: Double, val nextAt: Double?) : State
        data class Retrying(val reason: String) : State
        data class Failed(val message: String) : State
        data object SignedOut : State
    }

    private val _feed = MutableStateFlow<Feed?>(null)
    val feed: StateFlow<Feed?> = _feed.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var stopped = false
    @Volatile private var target = 0.0 // what to play from (on connect / reconnect)
    @Volatile private var lastWall = 0.0 // last position on screen
    @Volatile private var seekPending = false
    @Volatile private var current: Attempt? = null
    private var job: Job? = null

    /** Start (or seek) playback at wall time [at] (unix s). */
    fun play(at: Double) {
        target = at
        lastWall = at
        _feed.value?.close()
        _feed.value = null
        _state.value = State.Connecting
        val attempt = current
        if (job?.isActive == true) {
            if (attempt != null) {
                seekPending = true
                current = null // whatever it still delivers is ignored
                attempt.socket?.cancel()
            }
            // else: between reconnects - the next attempt starts at `target`.
        } else {
            job = scope.launch { run() }
        }
    }

    /** The wall time now on screen: keeps the NVR's read-ahead in step. */
    fun reportPosition(wall: Double) {
        lastWall = wall
        current?.socket?.send("""{"op":"pos","wall":$wall}""")
    }

    fun stop() {
        stopped = true
        current?.socket?.cancel()
        current = null
        _feed.value?.close()
        job?.cancel()
    }

    private fun playOp(at: Double) = """{"op":"play","camera":$cameraId,"at":$at,"audio":true}"""

    private suspend fun run() {
        var backoffMs = 1_000L
        var authRetried = false
        while (!stopped) {
            seekPending = false
            val token = try {
                api.accessToken(forceRefresh = authRetried)
            } catch (e: AuthExpiredException) {
                _state.value = State.SignedOut
                return
            } catch (e: IOException) {
                _state.value = State.Retrying(e.message ?: "Can't reach the NVR")
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 15_000L)
                continue
            }
            val attempt = Attempt(target)
            current = attempt
            attempt.socket = api.http.newWebSocket(api.mediaSocketRequest(token), attempt)
            val code = attempt.ended.await()
            if (current === attempt) current = null
            if (stopped) return
            if (seekPending) continue // a seek closed it: straight on, from the new target
            if ((code == 401 || code == 403 || code == 4401) && !authRetried) {
                authRetried = true
                continue
            }
            authRetried = false
            if (attempt.finished) return // the server ended it (gap/error); the UI decides
            if (attempt.gotData) backoffMs = 1_000L
            target = maxOf(target, lastWall) // pick up where the viewer is
            _state.value = State.Retrying(attempt.reason ?: "Connection lost")
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 15_000L)
        }
    }

    private inner class Attempt(private val from: Double) : WebSocketListener() {
        val ended = CompletableDeferred<Int>()
        @Volatile var socket: WebSocket? = null
        @Volatile var reason: String? = null
        @Volatile var gotData = false
        /** The server ended this playback itself (gap/error): don't reconnect. */
        @Volatile var finished = false
        private val protocol = PlaybackProtocol { origin -> Feed(onOverflow = { socket?.cancel() }, sessionOrigin = origin) }

        private val live get() = current === this && !stopped

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(playOp(from))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!live) return
            when (val e = protocol.onText(text)) {
                is PlaybackProtocol.Event.Gap -> {
                    finished = true
                    _state.value = State.Gap(e.at ?: lastWall, e.nextAt)
                }
                is PlaybackProtocol.Event.Error -> {
                    finished = true
                    _state.value = State.Failed(e.message)
                }
                else -> Unit
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!live) return
            for (e in protocol.onBinary(bytes.toByteArray())) {
                when (e) {
                    is PlaybackProtocol.Event.NewFeed -> {
                        _feed.value?.takeIf { it !== e.feed }?.close()
                        _feed.value = e.feed
                    }
                    PlaybackProtocol.Event.Started -> {
                        gotData = true
                        _state.value = State.Playing
                    }
                    else -> Unit
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ended.complete(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (reason == null) reason = t.message ?: t.javaClass.simpleName
            ended.complete(response?.code ?: -1)
        }
    }
}
