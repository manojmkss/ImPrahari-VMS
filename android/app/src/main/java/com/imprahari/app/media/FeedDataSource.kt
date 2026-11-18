package com.imprahari.app.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.InterruptedIOException

/**
 * Media3 DataSource over a [Feed]: an endless byte stream with no known
 * length. Reads block until the websocket delivers more; Media3 cancels a load
 * by interrupting the loading thread, which surfaces here as
 * InterruptedIOException - so tearing the player down never hangs on us.
 */
@UnstableApi
class FeedDataSource(private val feed: Feed) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var chunk: ByteArray? = null
    private var pos = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var current = chunk
        while (current == null || pos >= current.size) {
            current = try {
                feed.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("live stream read cancelled")
            } ?: return C.RESULT_END_OF_INPUT
            chunk = current
            pos = 0
        }
        val n = minOf(length, current.size - pos)
        System.arraycopy(current, pos, buffer, offset, n)
        pos += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        chunk = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
