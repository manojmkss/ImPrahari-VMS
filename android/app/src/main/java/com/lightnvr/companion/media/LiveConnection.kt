package com.lightnvr.companion.media

import com.lightnvr.companion.net.ApiClient
import com.lightnvr.companion.net.AuthExpiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException

/**
 * One camera's live view over the NVR media engine's websocket
 * (/api/media/ws - the same socket the web app's MediaPlayer uses).
 *
 * Protocol: send {"op":"live","camera":N,"quality":"sub"|"main","audio":false};
 * receive a JSON {"type":"init",...} followed by a binary init segment, then
 * binary fMP4 fragments; {"type":"error"} on trouble. Every "init" starts a
 * fresh [Feed] (the camera reconnected or its codec changed), which the UI
 * turns into a fresh player source.
 *
 * Reconnects on its own with backoff; reports [State.SignedOut] only when the
 * refresh token itself is rejected.
 */
class LiveConnection(
    private val api: ApiClient,
    private val cameraId: Int,
    private val quality: String,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Connecting : State
        data object Live : State
        data class Retrying(val reason: String) : State
        data object SignedOut : State
    }

    private val _feed = MutableStateFlow<Feed?>(null)
    val feed: StateFlow<Feed?> = _feed.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch { run() }
    }

    /** Stop for good: closes the socket so the NVR can drop this viewer at once. */
    fun stop() {
        stopped = true
        socket?.cancel()
        _feed.value?.close()
        job?.cancel()
    }

    private suspend fun run() {
        var backoffMs = INITIAL_BACKOFF_MS
        var authRetried = false
        while (!stopped) {
            val token = try {
                api.accessToken(forceRefresh = authRetried)
            } catch (e: AuthExpiredException) {
                _state.value = State.SignedOut
                return
            } catch (e: IOException) {
                _state.value = State.Retrying(e.message ?: "Can't reach the NVR")
                delay(backoffMs)
                backoffMs = nextBackoff(backoffMs)
                continue
            }

            val attempt = Attempt()
            socket = api.http.newWebSocket(api.mediaSocketRequest(token), attempt)
            val code = attempt.ended.await()
            _feed.value?.close()
            if (stopped) return

            // A bad token makes the server refuse the upgrade. Refresh once and
            // retry; if a fresh token is refused too it isn't an auth problem.
            if ((code == 401 || code == 403 || code == 4401) && !authRetried) {
                authRetried = true
                continue
            }
            authRetried = false
            if (attempt.gotData) backoffMs = INITIAL_BACKOFF_MS
            _state.value = State.Retrying(attempt.reason ?: "Connection lost")
            delay(backoffMs)
            backoffMs = nextBackoff(backoffMs)
        }
    }

    private inner class Attempt : WebSocketListener() {
        val ended = CompletableDeferred<Int>()
        @Volatile var reason: String? = null
        @Volatile var gotData = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"op":"live","camera":$cameraId,"quality":"$quality","audio":false}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                return
            }
            when (msg["type"]?.jsonPrimitive?.contentOrNull) {
                "init" -> {
                    _feed.value?.close()
                    _feed.value = Feed(onOverflow = { webSocket.cancel() })
                }
                "error" -> {
                    val message = msg["message"]?.jsonPrimitive?.contentOrNull ?: "Stream error"
                    reason = message
                    _state.value = State.Retrying(message)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val feed = _feed.value ?: return
            feed.push(bytes.toByteArray())
            if (!gotData) {
                gotData = true
                _state.value = State.Live
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

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
        fun nextBackoff(ms: Long) = minOf(ms * 2, MAX_BACKOFF_MS)
    }
}
