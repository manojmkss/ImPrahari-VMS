package com.imprahari.app.media

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * One continuous fragmented-MP4 stream (init segment, then fragments) for one
 * player instance. The websocket thread pushes raw bytes in; the player's
 * loading thread takes whole top-level boxes out, with fragment timestamps
 * already rebased to start near zero.
 *
 * Bounded: if the player falls this far behind, [onOverflow] fires so the
 * caller can reconnect and resume at the live edge rather than buffer forever.
 */
class Feed(
    private val maxQueuedBytes: Long = 16L * 1024 * 1024,
    private val onOverflow: () -> Unit = {},
    /** Playback: the session's wall-clock origin (unix s) from the server's init. */
    val sessionOrigin: Double = 0.0,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicLong(0)

    // Only touched from the single pushing thread.
    private var pending = ByteArray(0)
    private var rebaser: TimestampRebaser? = null
    private val origin = Origin()

    // Playback: where the next init segment's file starts in the session.
    @Volatile
    private var nextOffsetSeconds = 0.0

    /** Track ids of the last init segment (to tell whether a new one fits). */
    @Volatile
    var trackIds: Set<Int> = emptySet()
        private set

    /**
     * Wall-clock time (unix s) of player position [positionMs], for a
     * playback feed - or null before its first fragment. Player 0 is the
     * first fragment, which is [sessionOrigin] + its time in the session.
     */
    fun wallAt(positionMs: Long): Double? = origin.seconds?.let { sessionOrigin + it + positionMs / 1000.0 }

    /** Playback: the next init segment belongs to a file starting [offsetSeconds]
     *  into the session (the server's `offset`). */
    fun beginPiece(offsetSeconds: Double) {
        nextOffsetSeconds = offsetSeconds
    }

    @Volatile
    var closed = false
        private set

    /** Websocket thread: a binary message. Forwards every complete box. */
    fun push(bytes: ByteArray) {
        if (closed) return
        val buf = if (pending.isEmpty()) bytes else pending + bytes
        var p = 0
        while (p + 8 <= buf.size) {
            var size = Mp4.u32(buf, p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > buf.size) break
                size = Mp4.u64(buf, p + 8)
                header = 16
            }
            if (size < header || size > MAX_BOX_BYTES) {
                // Corrupt framing: nothing after this can be trusted.
                close()
                onOverflow()
                return
            }
            if (p + size > buf.size) break // rest of this box hasn't arrived yet
            val end = (p + size).toInt()
            val box = buf.copyOfRange(p, end)
            when (Mp4.type(box, 4)) {
                "moov" -> {
                    val scales = Mp4.trackTimescales(box)
                    trackIds = scales.keys
                    rebaser = TimestampRebaser(scales, nextOffsetSeconds, origin)
                }
                "moof" -> rebaser?.rewrite(box, Mp4.Box("moof", 0, header, box.size))
            }
            if (queuedBytes.addAndGet(box.size.toLong()) > maxQueuedBytes) {
                close()
                onOverflow()
                return
            }
            queue.put(box)
            p = end
        }
        pending = if (p < buf.size) buf.copyOfRange(p, buf.size) else EMPTY
    }

    /** No more data: the player sees end-of-stream once it drains the queue. */
    fun close() {
        if (closed) return
        closed = true
        queue.put(END)
    }

    /** Player loading thread: the next box, or null at end-of-stream. Blocks
     *  until data arrives; interruptible (that's how the player cancels). */
    @Throws(InterruptedException::class)
    fun take(): ByteArray? {
        val chunk = queue.take()
        if (chunk === END) {
            queue.put(END) // every later read sees the end too
            return null
        }
        queuedBytes.addAndGet(-chunk.size.toLong())
        return chunk
    }

    private companion object {
        val END = ByteArray(0)
        val EMPTY = ByteArray(0)
        const val MAX_BOX_BYTES = 64L * 1024 * 1024
    }
}
