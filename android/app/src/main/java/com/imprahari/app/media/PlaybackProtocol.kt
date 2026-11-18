package com.imprahari.app.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server-to-viewer half of one playback session, without the socket (so
 * a recorded session can be replayed in a test): turns the NVR's messages
 * into feeds and events.
 *
 * A JSON "init" announces the next file (its `offset` in the session, and
 * `reset` if its codecs changed); the binary message right after it is that
 * file's init segment. A file that fits the current [Feed] continues it;
 * anything else starts a new one.
 */
internal class PlaybackProtocol(
    private val makeFeed: (sessionOrigin: Double) -> Feed,
) {
    sealed interface Event {
        /** Hand the player this new continuous stream (the old one is done). */
        data class NewFeed(val feed: Feed) : Event
        /** The first media of this session arrived. */
        data object Started : Event
        data class Gap(val at: Double?, val nextAt: Double?) : Event
        data class Error(val message: String) : Event
    }

    var feed: Feed? = null
        private set
    private var pendingInit: PendingInit? = null
    private var started = false

    private data class PendingInit(val reset: Boolean, val origin: Double, val offset: Double)

    fun onText(text: String): Event? {
        val msg = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            return null
        }
        return when (msg.str("type")) {
            "init" -> {
                pendingInit = PendingInit(
                    reset = msg.bool("reset") ?: true,
                    origin = msg.num("origin") ?: 0.0,
                    offset = msg.num("offset") ?: 0.0,
                )
                null
            }
            "gap" -> {
                // Whatever is buffered still plays out; the feed ends after it.
                feed?.close()
                Event.Gap(msg.num("at"), msg.num("next_at"))
            }
            "error" -> {
                feed?.close()
                Event.Error(msg.str("message") ?: "Playback failed")
            }
            else -> null
        }
    }

    fun onBinary(data: ByteArray): List<Event> {
        val out = ArrayList<Event>(2)
        val init = pendingInit
        if (init != null) {
            pendingInit = null
            val current = feed
            if (continuesFeed(init.reset, current, data)) {
                current!!.beginPiece(init.offset)
            } else {
                current?.close()
                val next = makeFeed(init.origin).also { it.beginPiece(init.offset) }
                feed = next
                out += Event.NewFeed(next)
            }
        }
        val f = feed ?: return out
        f.push(data)
        if (!started) {
            started = true
            out += Event.Started
        }
        return out
    }

    companion object {
        /**
         * Whether a new file's init segment can continue [current]: the server
         * says nothing changed (no `reset`), the old feed is still open, and the
         * file has the same tracks (the player's MP4 reader requires that).
         */
        fun continuesFeed(reset: Boolean, current: Feed?, initSegment: ByteArray): Boolean {
            if (reset || current == null || current.closed) return false
            val ids = Mp4.trackTimescales(initSegment).keys
            return ids.isNotEmpty() && ids == current.trackIds
        }

        private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
        private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
    }
}
