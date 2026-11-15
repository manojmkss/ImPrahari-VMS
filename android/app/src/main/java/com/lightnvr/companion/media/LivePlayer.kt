package com.lightnvr.companion.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory

@UnstableApi
object LivePlayer {
    /**
     * ExoPlayer tuned for live: start after ~0.1 s of data and keep at most a
     * few seconds buffered, so the picture stays close to real time. Decoding
     * is MediaCodec - the phone's hardware H.264/H.265 decoder.
     */
    fun create(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 500,
                        /* maxBufferMs = */ 3_000,
                        /* bufferForPlaybackMs = */ 100,
                        /* bufferForPlaybackAfterRebufferMs = */ 250,
                    )
                    .build()
            )
            .build()
            .apply { volume = 0f } // muted until the viewer turns sound on

    /**
     * ExoPlayer for recorded footage: the NVR sends up to ~20 s ahead of what
     * is on screen, so buffer that much (it keeps reading the feed instead of
     * letting it back up), and start after ~0.5 s.
     */
    fun createPlayback(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 30_000, 500, 1_000)
                    .build()
            )
            .build()

    /** A player source reading one [Feed] as an endless fragmented MP4. */
    fun source(feed: Feed, cameraId: Int, kind: String = "live"): MediaSource =
        ProgressiveMediaSource.Factory(
            DataSource.Factory { FeedDataSource(feed) },
            ExtractorsFactory { arrayOf(FragmentedMp4Extractor(DefaultSubtitleParserFactory())) },
        ).createMediaSource(MediaItem.fromUri("lightnvr://$kind/$cameraId"))
}
