package com.imprahari.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.imprahari.app.ImPrahariApplication
import com.imprahari.app.R
import com.imprahari.app.net.AuthExpiredException
import com.imprahari.app.push.Alerts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Saving clips to the phone: the NVR cuts the clip, it's streamed into
 * Movies/ImPrahari (where the gallery finds it), with a notification that
 * shows progress and opens the clip when it's done. Runs in the app's scope,
 * so leaving the playback screen doesn't stop it.
 */
object ClipDownloads {
    sealed interface Status {
        val name: String

        /** [fraction] null = the NVR is still cutting it. */
        data class Working(override val name: String, val fraction: Float?) : Status
        data class Saved(override val name: String, val uri: Uri?) : Status
        data class Failed(override val name: String, val message: String) : Status
    }

    private const val CHANNEL = "downloads"
    const val FOLDER = "ImPrahari"

    private val _status = MutableStateFlow<Status?>(null)

    /** The latest clip's progress, for the screen that started it. */
    val status: StateFlow<Status?> = _status

    fun dismiss() {
        if (_status.value !is Status.Working) _status.value = null
    }

    fun start(app: ImPrahariApplication, cameraId: Int, cameraName: String, start: Double, end: Double) {
        val name = ClipMath.fileName(cameraName, start, ZoneId.systemDefault())
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        app.appScope.launch {
            report(app, id, Status.Working(name, null))
            val result = try {
                val uri = withContext(Dispatchers.IO) {
                    app.api.exportClip(cameraId, start, end).use { r ->
                        val body = r.body ?: throw IOException("The NVR sent an empty clip")
                        val total = body.contentLength().takeIf { it > 0 }
                        var shown = 0L
                        save(app, name, body.byteStream()) { done ->
                            val now = System.currentTimeMillis()
                            if (total != null && now - shown > 500) {
                                shown = now
                                report(app, id, Status.Working(name, (done.toFloat() / total).coerceIn(0f, 1f)))
                            }
                        }
                    }
                }
                Status.Saved(name, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                Status.Failed(name, "Sign in again to save clips")
            } catch (e: IOException) {
                Status.Failed(name, e.message ?: "Couldn't save the clip")
            } catch (e: SecurityException) {
                Status.Failed(name, "No permission to save to Movies")
            }
            report(app, id, result)
        }
    }

    private fun report(context: Context, id: Int, status: Status) {
        _status.value = status
        if (!Alerts.canNotify(context)) return
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(status.name)
        when (status) {
            is Status.Working -> builder
                .setContentText(if (status.fraction == null) "The NVR is preparing the clip…" else "Saving to Movies/$FOLDER")
                .setProgress(100, ((status.fraction ?: 0f) * 100).toInt(), status.fraction == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            is Status.Saved -> {
                builder.setContentText("Saved to Movies/$FOLDER - tap to play").setAutoCancel(true)
                status.uri?.let { uri ->
                    builder.setContentIntent(
                        PendingIntent.getActivity(context, id, viewIntent(uri), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
            is Status.Failed -> builder.setContentText(status.message).setAutoCancel(true)
        }
        context.getSystemService(NotificationManager::class.java).notify(CHANNEL, id, builder.build())
    }

    /** Opens a saved clip in the phone's video player. */
    fun viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android 8-9 need the storage permission to write to Movies. */
    val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Write [input] to Movies/ImPrahari/[name]; the clip's address for a player. */
    private suspend fun save(context: Context, name: String, input: InputStream, progress: (Long) -> Unit): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Couldn't create the file in Movies")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't write to Movies")
                out.use { copy(input, it, progress) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null) // no half-written clip in the gallery
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create Movies/$FOLDER")
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, name.removeSuffix(".mp4") + " (${n++}).mp4")
        try {
            file.outputStream().use { copy(input, it, progress) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("video/mp4")) { _, uri -> cont.resume(uri) }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, progress: (Long) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            progress(done)
        }
    }
}
